package com.eloykim.lyra.service.application

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.eloykim.lyra.accessibility.AccessibilityState
import com.eloykim.lyra.accessibility.AutomationClient
import com.eloykim.lyra.data.HistoryRepository
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optBoolOr
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用服务：查询已安装应用、搜索、详情、启动、当前前台应用和常用应用。
 *
 * 启动仍走 AutomationService → PolicyGuard，因此 Lyra 自身、系统设置、
 * 支付类应用不会被这里绕过。
 */
class ApplicationService(
    private val context: Context,
    private val history: HistoryRepository? = null,
) : BasePhoneService() {

    override val id: String = "application"
    override val title: String = "应用"
    override val description: String =
        "列出已安装应用（含系统应用）、搜索、查详情、启动应用与常用应用。"

    private val packageManager: PackageManager get() = context.packageManager

    private data class AppEntry(
        val packageName: String,
        val label: String,
        val system: Boolean,
        val launchable: Boolean,
        val self: Boolean,
    )

    @Volatile
    private var cachedApps: Pair<Long, List<AppEntry>>? = null

    init {
        register(
            ServiceMethod(
                name = "list",
                summary = "列出已安装应用；可按名称/包名过滤",
                parameters = Schema.params(
                    "query" to Schema.string("名称或包名片段"),
                    "limit" to Schema.integer("数量上限", 1, 2000),
                    "includeSystem" to Schema.boolean("是否包含系统应用；默认 true"),
                    "includeSelf" to Schema.boolean("是否包含 Lyra；默认 false"),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args ->
            list(
                query = args.optStringOr("query"),
                limit = args.optIntOr("limit", 50),
                includeSystem = args.optBoolOr("includeSystem", true),
                includeSelf = args.optBoolOr("includeSelf", false),
            )
        }

        register(
            ServiceMethod(
                name = "search",
                summary = "按名称/包名搜索应用",
                parameters = Schema.params(
                    "query" to Schema.string("名称或包名片段"),
                    "limit" to Schema.integer("数量上限", 1, 2000),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args ->
            list(
                query = args.reqString("query"),
                limit = args.optIntOr("limit", 2000),
                includeSystem = true,
                includeSelf = false,
            )
        }

        register(
            ServiceMethod(
                name = "info",
                summary = "读取应用详情（版本、安装时间、是否系统/可启动）",
                parameters = Schema.params(
                    "package" to Schema.string("包名"),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args -> info(args.reqString("package")) }

        register(
            ServiceMethod(
                name = "launch",
                summary = "启动应用（经过 PolicyGuard）",
                parameters = Schema.params(
                    "package" to Schema.string("包名"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args -> launch(args.reqString("package")) }

        register(
            ServiceMethod(
                name = "recent",
                summary = "最常用的应用（来自本地使用记录）",
                parameters = Schema.params(
                    "limit" to Schema.integer("数量", 1, 50),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args -> recent(args.optIntOr("limit", 8)) }

        register(
            ServiceMethod(
                name = "status",
                summary = "应用目录可用性",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { status() }
    }

    private suspend fun list(
        query: String,
        limit: Int,
        includeSystem: Boolean,
        includeSelf: Boolean,
    ): ServiceResult = withContext(Dispatchers.IO) {
        val needle = query.trim().lowercase()
        val entries = installedApps().asSequence()
            .filter { includeSystem || !it.system }
            .filter { includeSelf || !it.self }
            .filter {
                needle.isEmpty() ||
                    it.label.lowercase().contains(needle) ||
                    it.packageName.lowercase().contains(needle)
            }
            .take(limit.coerceIn(1, 2000))
            .toList()

        val array = JSONArray()
        entries.forEach { array.put(appJson(it)) }
        ServiceResult.ok(
            "${entries.size} apps",
            JSONObject().put("apps", array).put("count", entries.size),
        )
    }

    private suspend fun info(packageName: String): ServiceResult = withContext(Dispatchers.IO) {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        }.getOrNull() ?: return@withContext ServiceResult.fail(
            "app_not_found",
            "设备上没有安装 $packageName",
        )
        val app = info.applicationInfo ?: return@withContext ServiceResult.fail(
            "app_not_found",
            "无法读取 $packageName 的应用信息",
        )
        ServiceResult.ok(
            "ok",
            JSONObject()
                .put("package", packageName)
                .put("label", labelOf(app))
                .put("versionName", info.versionName)
                .put("versionCode", info.longVersionCode)
                .put("firstInstallTime", info.firstInstallTime)
                .put("lastUpdateTime", info.lastUpdateTime)
                .put("system", isSystem(app))
                .put("self", packageName == context.packageName)
                .put("launchable", packageManager.getLaunchIntentForPackage(packageName) != null),
        )
    }

    private suspend fun launch(packageName: String): ServiceResult {
        if (packageManager.getLaunchIntentForPackage(packageName) == null) {
            return ServiceResult.fail("app_not_found", "设备上没有可启动的 $packageName")
        }
        if (!AutomationClient.connected.value) {
            return ServiceResult.fail(
                "automation_unavailable",
                "自动化服务未连接：启动应用需要 Lyra 的无障碍服务",
            )
        }
        return try {
            val result = ServiceResult.fromAutomation(AutomationClient.launch(packageName))
            if (result.ok) result.data.put("package", packageName)
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            ServiceResult.fail("automation_error", t.message ?: "启动失败")
        }
    }

    private suspend fun recent(limit: Int): ServiceResult {
        val repository = history ?: return ServiceResult.fail(
            "history_unavailable",
            "没有注入 HistoryRepository",
        )
        val rows = repository.topPackages(limit.coerceIn(1, 50))
        val array = JSONArray()
        rows.forEach { row ->
            array.put(
                JSONObject()
                    .put("package", row.packageName)
                    .put("label", row.label)
                    .put("launchCount", row.launchCount)
                    .put("stepCount", row.stepCount)
                    .put("lastSeenAt", row.lastSeenAt),
            )
        }
        return ServiceResult.ok(
            "${rows.size} recent apps",
            JSONObject().put("apps", array).put("count", rows.size),
        )
    }

    private fun status(): ServiceResult = ServiceResult.ok(
        "ok",
        JSONObject()
            .put("automationConnected", AutomationClient.connected.value)
            .put("accessibilityConnected", AccessibilityState.connected.value)
            .put("self", context.packageName)
            .put("installedKnown", runCatching { installedApps().size }.getOrDefault(-1)),
    )

    private fun installedApps(): List<AppEntry> {
        val now = System.currentTimeMillis()
        cachedApps?.let { (at, apps) ->
            if (now - at < 60_000L) return apps
        }
        val launchable = launchablePackages()
        val apps = runCatching {
            val infos = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }
            infos.map { app ->
                AppEntry(
                    packageName = app.packageName,
                    label = labelOf(app),
                    system = isSystem(app),
                    launchable = app.packageName in launchable,
                    self = app.packageName == context.packageName,
                )
            }.sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
        cachedApps = now to apps
        return apps
    }

    private fun launchablePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        return resolved.mapNotNull { it.activityInfo?.packageName }.toSet()
    }

    private fun labelOf(app: ApplicationInfo): String =
        runCatching { packageManager.getApplicationLabel(app).toString() }
            .getOrDefault(app.packageName)

    private fun isSystem(app: ApplicationInfo): Boolean =
        (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    private fun appJson(app: AppEntry): JSONObject = JSONObject()
        .put("package", app.packageName)
        .put("label", app.label)
        .put("system", app.system)
        .put("launchable", app.launchable)
        .put("self", app.self)
}
