package com.eloykim.lyra.service.window

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.eloykim.lyra.accessibility.AccessibilityState
import com.eloykim.lyra.accessibility.AutomationClient
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqString
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * 窗口/导航服务：home、back、recent、notifications、go。
 *
 * 只负责「窗口级」动作；任务记录仍归 TaskService，应用内任务归 MissionService。
 */
class WindowService(private val context: Context) : BasePhoneService() {

    override val id: String = "window"
    override val title: String = "窗口"
    override val description: String =
        "系统窗口导航：home/back/recent/notifications，或 go 到指定应用；查看当前前台窗口。"

    init {
        register(
            ServiceMethod(
                name = "home",
                summary = "回到桌面",
                parameters = Schema.params(),
                risk = ServiceRisk.CONTROL,
            ),
        ) { global("home") }

        register(
            ServiceMethod(
                name = "back",
                summary = "返回上一级",
                parameters = Schema.params(),
                risk = ServiceRisk.CONTROL,
            ),
        ) { global("back") }

        register(
            ServiceMethod(
                name = "recent",
                summary = "打开系统最近任务",
                parameters = Schema.params(),
                risk = ServiceRisk.CONTROL,
            ),
        ) { global("recents") }

        register(
            ServiceMethod(
                name = "recents",
                summary = "打开系统最近任务（recent 的别名）",
                parameters = Schema.params(),
                risk = ServiceRisk.CONTROL,
            ),
        ) { global("recents") }

        register(
            ServiceMethod(
                name = "notifications",
                summary = "拉下通知栏",
                parameters = Schema.params(),
                risk = ServiceRisk.CONTROL,
            ),
        ) { global("notifications") }

        register(
            ServiceMethod(
                name = "go",
                summary = "打开指定应用（包名或应用名）",
                parameters = Schema.params(
                    "package" to Schema.string("应用包名"),
                    "app" to Schema.string("应用名称，例如「微信」"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            val packageName = args.optStringOr("package")
                .ifBlank { resolveApp(args.optStringOr("app")) }
                .ifBlank { throw IllegalArgumentException("需要 package 或 app") }
            go(packageName)
        }

        register(
            ServiceMethod(
                name = "current",
                summary = "当前前台窗口",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { current() }

        register(
            ServiceMethod(
                name = "status",
                summary = "窗口服务与无障碍连接状态",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { status() }
    }

    private suspend fun global(action: String): ServiceResult {
        if (!AutomationClient.connected.value) {
            return ServiceResult.fail("automation_unavailable", "自动化服务未连接")
        }
        return try {
            ServiceResult.fromAutomation(AutomationClient.globalAction(action))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            ServiceResult.fail("automation_error", t.message ?: "$action 失败")
        }
    }

    private suspend fun go(packageName: String): ServiceResult {
        if (!AutomationClient.connected.value) {
            return ServiceResult.fail("automation_unavailable", "自动化服务未连接")
        }
        return try {
            val result = ServiceResult.fromAutomation(AutomationClient.launch(packageName))
            if (result.ok) result.data.put("package", packageName)
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            ServiceResult.fail("automation_error", t.message ?: "打开应用失败")
        }
    }

    private fun current(): ServiceResult {
        val snapshot = AccessibilityState.snapshot.value
            ?: return ServiceResult.fail("no_observation", "还没有屏幕快照")
        val packageName = snapshot.packageName
        val label = runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString()
        }.getOrNull()
        return ServiceResult.ok(
            "ok",
            JSONObject()
                .put("package", packageName)
                .put("label", label)
                .put("obsId", snapshot.id)
                .put("nodes", snapshot.nodes.size)
                .put("capturedAt", snapshot.capturedAt),
        )
    }

    private fun status(): ServiceResult {
        val snapshot = AccessibilityState.snapshot.value
        return ServiceResult.ok(
            "ok",
            JSONObject()
                .put("accessibilityConnected", AccessibilityState.connected.value)
                .put("automationConnected", AutomationClient.connected.value)
                .put("package", snapshot?.packageName),
        )
    }

    private fun resolveApp(query: String): String {
        if (query.isBlank()) return ""
        val pm = context.packageManager
        val apps = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        }.getOrDefault(emptyList())
        val needle = query.trim()
        val exact = apps.firstOrNull { pm.getApplicationLabel(it).toString().equals(needle, true) }
        val match = exact ?: apps.firstOrNull {
            pm.getApplicationLabel(it).toString().contains(needle, true)
        }
        return match?.packageName.orEmpty()
    }
}
