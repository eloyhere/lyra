package com.eloykim.lyra.service.grant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.eloykim.lyra.permission.PermissionBroker
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optBoolOr
import com.eloykim.lyra.service.optStringOr
import org.json.JSONArray
import org.json.JSONObject

/**
 * Grant 服务：Agent 在运行中向用户申请 App 权限。
 *
 * 只申请 Manifest 已声明的权限；真正授权仍由系统弹窗/设置页完成，
 * Agent 不能代替用户点“允许”。
 */
class GrantService(private val context: Context) : BasePhoneService() {

    override val id: String = "grant"
    override val title: String = "权限申请"
    override val description: String =
        "查询/申请已声明的 App 权限；会弹出系统授权窗口，由用户决定。"

    init {
        register(
            ServiceMethod(
                name = "list",
                summary = "列出已声明且可申请的运行时权限及当前状态",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { listPermissions() }

        register(
            ServiceMethod(
                name = "status",
                summary = "查询指定权限是否已授予",
                parameters = Schema.params(
                    "permissions" to Schema.array(
                        "权限名列表，例如 android.permission.CAMERA",
                        Schema.string("permission"),
                    ),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args -> status(args.optPermissions()) }

        register(
            ServiceMethod(
                name = "request",
                summary = "向用户申请权限；会弹出系统授权窗口",
                parameters = Schema.params(
                    "permissions" to Schema.array(
                        "需要申请的权限名列表",
                        Schema.string("permission"),
                    ),
                    "allFiles" to Schema.boolean("是否申请所有文件访问权限"),
                    "reason" to Schema.string("向用户说明用途"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            request(
                permissions = args.optPermissions(),
                allFiles = args.optBoolOr("allFiles", false),
                reason = args.optStringOr("reason"),
            )
        }
    }

    private fun JSONObject.optPermissions(): List<String> {
        val raw = opt("permissions")
        if (raw !is JSONArray) return emptyList()
        return buildList {
            for (index in 0 until raw.length()) {
                val value = raw.optString(index).trim()
                if (value.isNotEmpty() && value !in this) add(value)
            }
        }
    }

    private suspend fun listPermissions(): ServiceResult {
        val declared = declaredRuntimePermissions()
        val array = JSONArray()
        declared.forEach { permission ->
            array.put(
                JSONObject()
                    .put("permission", permission)
                    .put("granted", isGranted(permission)),
            )
        }
        array.put(
            JSONObject()
                .put("permission", Manifest.permission.MANAGE_EXTERNAL_STORAGE)
                .put("granted", hasAllFilesAccess())
                .put("special", true),
        )
        return ServiceResult.ok("${array.length()} 个可申请权限", JSONObject().put("permissions", array))
    }

    private fun status(permissions: List<String>): ServiceResult {
        if (permissions.isEmpty()) {
            return ServiceResult.fail("bad_args", "permissions 不能为空")
        }
        val array = JSONArray()
        permissions.forEach { permission ->
            array.put(
                JSONObject()
                    .put("permission", permission)
                    .put("declared", isDeclared(permission))
                    .put("granted", isGranted(permission)),
            )
        }
        return ServiceResult.ok("${array.length()} 个权限", JSONObject().put("permissions", array))
    }

    private suspend fun request(
        permissions: List<String>,
        allFiles: Boolean,
        reason: String,
    ): ServiceResult {
        if (permissions.isEmpty() && !allFiles) {
            return ServiceResult.fail("bad_args", "没有要申请的权限")
        }
        val unknown = permissions.filterNot { isDeclared(it) }
        if (unknown.isNotEmpty()) {
            return ServiceResult.fail(
                "not_declared",
                "Manifest 没有声明：${unknown.joinToString()}",
            )
        }
        val requested = permissions.filterNot { it == Manifest.permission.MANAGE_EXTERNAL_STORAGE }
        val result = JSONObject()
        if (reason.isNotBlank()) result.put("reason", reason)

        var runtimeGranted = true
        if (requested.isNotEmpty()) {
            PermissionBroker.ensure(context, requested)
            val granted = JSONArray()
            val denied = JSONArray()
            requested.forEach { permission ->
                if (isGranted(permission)) {
                    granted.put(permission)
                } else {
                    denied.put(permission)
                    runtimeGranted = false
                }
            }
            result.put("granted", granted).put("denied", denied)
        }

        var filesGranted = true
        if (allFiles || permissions.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE)) {
            filesGranted = PermissionBroker.ensureExternalStorage(context) || hasAllFilesAccess()
            result.put("allFilesGranted", filesGranted)
        }

        val ok = runtimeGranted && filesGranted
        result.put("ok", ok).put("needsUserAction", !ok)
        return if (ok) {
            ServiceResult.ok("权限已授予", result)
        } else {
            ServiceResult.fail(
                "permission_denied",
                "用户未授予全部权限；可以在系统设置中重新授权",
                result,
            )
        }
    }

    private fun declaredRuntimePermissions(): List<String> {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        return info.requestedPermissions.orEmpty().filter { permission ->
            val level = runCatching {
                context.packageManager.getPermissionInfo(permission, 0).protection
            }.getOrDefault(0)
            level and android.content.pm.PermissionInfo.PROTECTION_DANGEROUS != 0
        }
    }

    private fun isDeclared(permission: String): Boolean {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        return info.requestedPermissions.orEmpty().contains(permission)
    }

    private fun isGranted(permission: String): Boolean {
        if (permission == Manifest.permission.MANAGE_EXTERNAL_STORAGE) {
            return hasAllFilesAccess()
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
}
