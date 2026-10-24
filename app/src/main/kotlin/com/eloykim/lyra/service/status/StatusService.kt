package com.eloykim.lyra.service.status

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.eloykim.lyra.accessibility.AccessibilityState
import com.eloykim.lyra.accessibility.AutomationClient
import com.eloykim.lyra.data.HistoryRepository
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 状态服务：只读汇总 Lyra / 设备 / 权限 / 连接 / 存储状态。
 *
 * 不修改任何系统设置；排查「为什么命令不可用」时先看这里。
 */
class StatusService(
    private val context: Context,
    private val history: HistoryRepository,
) : BasePhoneService() {

    override val id: String = "status"
    override val title: String = "状态"
    override val description: String =
        "只读查看应用/设备版本、连接状态、权限、存储与账本概况。"

    init {
        register(
            ServiceMethod(
                name = "overview",
                summary = "Lyra 当前状态总览",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { overview() }

        register(
            ServiceMethod(
                name = "permissions",
                summary = "关键权限与无障碍/通知使用权状态",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { permissions() }

        register(
            ServiceMethod(
                name = "connections",
                summary = "无障碍/自动化/通知监听/数据库连接状态",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { connections() }

        register(
            ServiceMethod(
                name = "storage",
                summary = "数据库、WAL、缓存目录大小",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { storage() }

        register(
            ServiceMethod(
                name = "version",
                summary = "应用版本 / Android / 设备型号",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { version() }
    }

    private suspend fun overview(): ServiceResult {
        val snapshot = AccessibilityState.snapshot.value
        val settings = SettingsStore.state.value
        val counts = withContext(Dispatchers.IO) {
            runCatching { history.counts() }.getOrDefault(0 to 0)
        }
        val events = withContext(Dispatchers.IO) {
            runCatching { history.ledger.size() }.getOrDefault(0)
        }
        val problems = withContext(Dispatchers.IO) {
            runCatching { history.ledger.verify() }.getOrDefault(emptyList())
        }
        return ServiceResult.ok(
            "ok",
            JSONObject()
                .put("app", appJson())
                .put("device", deviceJson())
                .put("connections", connectionsJson())
                .put(
                    "screen",
                    JSONObject()
                        .put("package", snapshot?.packageName)
                        .put("obsId", snapshot?.id ?: 0L)
                        .put("nodes", snapshot?.nodes?.size ?: 0)
                        .put("ageMs", snapshot?.let {
                            System.currentTimeMillis() - it.capturedAt
                        } ?: 0L),
                )
                .put(
                    "model",
                    JSONObject()
                        .put("provider", settings.brandId)
                        .put("model", settings.model)
                        .put("language", settings.languageId),
                )
                .put(
                    "data",
                    JSONObject()
                        .put("runs", counts.first)
                        .put("steps", counts.second)
                        .put("events", events),
                )
                .put(
                    "ledger",
                    JSONObject()
                        .put("events", events)
                        .put("intact", problems.isEmpty())
                        .put("problems", JSONArray(problems)),
                ),
        )
    }

    private fun permissions(): ServiceResult {
        val array = JSONArray()
        permissionRows().forEach { (name, granted) ->
            array.put(
                JSONObject()
                    .put("permission", name)
                    .put("granted", granted),
            )
        }
        return ServiceResult.ok(
            "ok",
            JSONObject()
                .put("permissions", array)
                .put("accessibility", accessibilityEnabled())
                .put("notificationListener", notificationListenerEnabled())
                .put("notificationPost", canPostNotifications()),
        )
    }

    private fun connections(): ServiceResult = ServiceResult.ok(
        "ok",
        connectionsJson(),
    )

    private suspend fun storage(): ServiceResult = withContext(Dispatchers.IO) {
        val db = context.getDatabasePath(DB_NAME)
        val wal = File(db.parentFile, "$DB_NAME-wal")
        val shm = File(db.parentFile, "$DB_NAME-shm")
        ServiceResult.ok(
            "ok",
            JSONObject()
                .put("database", db.absolutePath)
                .put("databaseBytes", if (db.exists()) db.length() else 0L)
                .put("walBytes", if (wal.exists()) wal.length() else 0L)
                .put("shmBytes", if (shm.exists()) shm.length() else 0L)
                .put("cacheBytes", sizeOf(context.cacheDir))
                .put("shotsBytes", sizeOf(File(context.cacheDir, "shots")))
                .put("filesBytes", sizeOf(context.filesDir)),
        )
    }

    private fun version(): ServiceResult = ServiceResult.ok(
        "ok",
        JSONObject()
            .put("app", appJson())
            .put("device", deviceJson()),
    )

    private fun connectionsJson(): JSONObject = JSONObject()
        .put("accessibility", AccessibilityState.connected.value)
        .put("automation", AutomationClient.connected.value)
        .put("notificationListener", notificationListenerEnabled())
        .put("database", context.getDatabasePath(DB_NAME).exists())

    private fun appJson(): JSONObject {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        }.getOrNull()
        return JSONObject()
            .put("package", context.packageName)
            .put("versionName", info?.versionName)
            .put("versionCode", info?.longVersionCode ?: 0L)
            .put("debug", (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
    }

    private fun deviceJson(): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("android", Build.VERSION.RELEASE)

    private fun permissionRows(): List<Pair<String, Boolean>> = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALENDAR,
    ).map { permission ->
        permission to (
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
            )
    }

    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.contains(context.packageName)
    }

    private fun notificationListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun sizeOf(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length()
        return runCatching {
            file.walkTopDown()
                .filter { it.isFile }
                .take(2_000)
                .sumOf { it.length() }
        }.getOrDefault(0L)
    }

    private companion object {
        const val DB_NAME = "lyra_history.db"
    }
}
