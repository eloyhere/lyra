package com.eloykim.lyra.service.screen

import com.eloykim.lyra.accessibility.AccessibilityState
import com.eloykim.lyra.accessibility.AutomationClient
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optLongOr
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 屏幕观察服务：抓屏、等待屏幕变化、查看观察状态。
 *
 * 返回的 `observation` 与 AIDL 的 observe 一致：
 * `{obsId, package, capturedAt, counts, elements[]}`，编号只在本次观察内有效。
 */
class ScreenService : BasePhoneService() {

    override val id: String = "screen"
    override val title: String = "屏幕"
    override val description: String = "观察当前屏幕（节点树），或等待屏幕发生变化。"

    init {
        register(
            ServiceMethod(
                name = "observe",
                summary = "抓取当前屏幕，返回 obsId 与元素列表",
                parameters = Schema.params(),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { observe() }

        register(
            ServiceMethod(
                name = "wait",
                summary = "等待 obsId 变化（界面更新）",
                parameters = Schema.params(
                    "obsId" to Schema.integer("上一次观察的 obsId"),
                    "timeoutMs" to Schema.integer("最长等待", 200, 30_000),
                    "intervalMs" to Schema.integer("轮询间隔", 100, 2_000),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args ->
            waitForChange(
                previous = args.optLongOr("obsId", 0L),
                timeoutMs = args.optIntOr("timeoutMs", 5_000),
                intervalMs = args.optIntOr("intervalMs", 300),
            )
        }

        register(
            ServiceMethod(
                name = "status",
                summary = "无障碍/自动化连接状态与最近快照",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { status() }

        register(
            ServiceMethod(
                name = "snapshot",
                summary = "最近一次无障碍快照的完整节点（不重新抓屏；调试浮窗/弹窗用）",
                parameters = Schema.params(),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { snapshot() }
    }

    private suspend fun observe(): ServiceResult {
        if (!AutomationClient.connected.value) {
            return ServiceResult.fail(
                "automation_unavailable",
                "自动化服务未连接：请先开启 Lyra 的无障碍服务",
            )
        }
        return runCatching { ServiceResult.fromAutomation(AutomationClient.observe()) }
            .getOrElse { ServiceResult.fail("automation_error", it.message ?: "观察失败") }
    }

    private suspend fun waitForChange(
        previous: Long,
        timeoutMs: Int,
        intervalMs: Int,
    ): ServiceResult {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(200, 30_000)
        var last: ServiceResult? = null
        do {
            val current = observe()
            last = current
            if (!current.ok) return current
            val obsId = current.data.optJSONObject("observation")?.optLong("obsId")
                ?: current.data.optLong("obsId")
            if (previous <= 0L || obsId != previous) {
                current.data.put("changed", previous > 0L && obsId != previous)
                return current
            }
            delay(intervalMs.coerceIn(100, 2_000).toLong())
        } while (System.currentTimeMillis() < deadline)
        return last ?: ServiceResult.fail("timeout", "等待屏幕变化超时")
    }

    private fun status(): ServiceResult {
        val snapshot = AccessibilityState.snapshot.value
        return ServiceResult.ok(
            "ok",
            JSONObject()
                .put("accessibilityConnected", AccessibilityState.connected.value)
                .put("automationConnected", AutomationClient.connected.value)
                .put("package", snapshot?.packageName)
                .put("obsId", snapshot?.id ?: 0L)
                .put("capturedAt", snapshot?.capturedAt ?: 0L)
                .put("nodes", snapshot?.nodes?.size ?: 0)
                .put("ageMs", snapshot?.let {
                    System.currentTimeMillis() - it.capturedAt
                } ?: 0L),
        )
    }

    /**
     * 最近一次无障碍快照的完整节点，不重新抓屏。
     *
     * 用途：adb 调试浮窗/弹窗。Lyra 在前台时无障碍服务会忽略自身事件，
     * 所以这里读到的仍是切回 Lyra 之前那个 App 的快照。
     */
    private fun snapshot(): ServiceResult {
        val snapshot = AccessibilityState.snapshot.value
            ?: return ServiceResult.fail("no_snapshot", "还没有屏幕快照")
        val text = buildString {
            appendLine(
                "package=${snapshot.packageName} obsId=${snapshot.id} " +
                    "nodes=${snapshot.nodes.size}",
            )
            snapshot.nodes.forEach { node ->
                append("#${node.id} ")
                append(node.className.substringAfterLast('.').ifBlank { "?" })
                node.viewId?.takeIf { it.isNotBlank() }?.let { append(" viewId=").append(it) }
                node.text?.takeIf { it.isNotBlank() }?.let { append(" text=").append(it) }
                node.contentDescription?.takeIf { it.isNotBlank() }?.let {
                    append(" desc=").append(it)
                }
                append(" click=").append(node.clickable)
                append(" editable=").append(node.editable)
                append(" visible=").append(node.visible)
                append(" bounds=")
                append(node.bounds.left).append(',')
                append(node.bounds.top).append(',')
                append(node.bounds.right).append(',')
                append(node.bounds.bottom)
                appendLine()
            }
        }
        return ServiceResult.ok(
            text,
            JSONObject()
                .put("package", snapshot.packageName)
                .put("obsId", snapshot.id)
                .put("nodes", snapshot.nodes.size),
        )
    }
}
