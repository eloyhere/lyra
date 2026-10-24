package com.eloykim.lyra.service.touch

import com.eloykim.lyra.accessibility.AutomationClient
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optBoolOr
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optLongOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqInt
import com.eloykim.lyra.service.reqString
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * 触摸服务：按节点、按文字、按 View Id、按坐标点击，以及长按/滚动。
 *
 * 所有动作仍走 AutomationService → AutomationEngine 的策略闸门，
 * 因此支付、凭证、自身界面等硬边界不会被这里绕开。
 */
class TouchService : BasePhoneService() {

    override val id: String = "touch"
    override val title: String = "触摸"
    override val description: String =
        "节点点击、坐标点击/长按、滚动，以及按文字定位元素并点击。"

    init {
        register(
            ServiceMethod(
                name = "tap",
                summary = "点击当前观察里的节点（nodeId 来自 screen.observe）",
                parameters = Schema.params(
                    "nodeId" to Schema.integer("节点编号"),
                    "obsId" to Schema.integer("观察版本号；0 表示不校验"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            automation {
                AutomationClient.tap(
                    args.reqInt("nodeId"),
                    args.optLongOr("obsId", 0L),
                )
            }
        }

        register(
            ServiceMethod(
                name = "tapById",
                summary = "按节点编号点击（tap 的别名）",
                parameters = Schema.params(
                    "nodeId" to Schema.integer("节点编号"),
                    "obsId" to Schema.integer("观察版本号；0 表示不校验"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            automation {
                AutomationClient.tap(
                    args.reqInt("nodeId"),
                    args.optLongOr("obsId", 0L),
                )
            }
        }

        register(
            ServiceMethod(
                name = "tapViewId",
                summary = "按 View Id 点击；支持完整 pkg:id/name 或只给 name",
                parameters = Schema.params(
                    "viewId" to Schema.string("View Id，例如 login_button 或 pkg:id/login_button"),
                    "obsId" to Schema.integer("观察版本号；0 表示不校验"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            automation {
                AutomationClient.tapByViewId(
                    args.reqString("viewId"),
                    args.optLongOr("obsId", 0L),
                )
            }
        }

        register(
            ServiceMethod(
                name = "tapAt",
                summary = "按屏幕像素坐标点击",
                parameters = Schema.params(
                    "x" to Schema.integer("屏幕 x", 0, 20_000),
                    "y" to Schema.integer("屏幕 y", 0, 20_000),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            automation { AutomationClient.tapAt(args.reqInt("x"), args.reqInt("y")) }
        }

        register(
            ServiceMethod(
                name = "longPress",
                summary = "按屏幕坐标长按",
                parameters = Schema.params(
                    "x" to Schema.integer("屏幕 x", 0, 20_000),
                    "y" to Schema.integer("屏幕 y", 0, 20_000),
                    "durationMs" to Schema.integer("按住时长", 100, 5_000),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            automation {
                AutomationClient.longPress(
                    args.reqInt("x"),
                    args.reqInt("y"),
                    args.optIntOr("durationMs", 600),
                )
            }
        }

        register(
            ServiceMethod(
                name = "scroll",
                summary = "滚动当前可滚动区域",
                parameters = Schema.params(
                    "direction" to Schema.string(
                        "方向",
                        listOf("down", "up", "left", "right"),
                    ),
                    "obsId" to Schema.integer("观察版本号；0 表示不校验"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            val direction = when (args.optStringOr("direction", "down").lowercase()) {
                "down", "0" -> 0
                "up", "1" -> 1
                "left", "2" -> 2
                "right", "3" -> 3
                else -> throw IllegalArgumentException("direction 只支持 down/up/left/right")
            }
            automation {
                AutomationClient.scroll(direction, args.optLongOr("obsId", 0L))
            }
        }

        register(
            ServiceMethod(
                name = "tapText",
                summary = "在当前屏幕里找包含指定文字的元素并点击",
                parameters = Schema.params(
                    "text" to Schema.string("要查找的文字"),
                    "exact" to Schema.boolean("是否完全匹配；默认包含"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args -> tapText(args.reqString("text"), args.optBoolOr("exact", false)) }

        register(
            ServiceMethod(
                name = "tapByText",
                summary = "按文字找元素并点击（tapText 的别名）",
                parameters = Schema.params(
                    "text" to Schema.string("要查找的文字"),
                    "exact" to Schema.boolean("是否完全匹配；默认包含"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args -> tapText(args.reqString("text"), args.optBoolOr("exact", false)) }

        register(
            ServiceMethod(
                name = "status",
                summary = "自动化服务连接状态",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) {
            ServiceResult.ok(
                "ok",
                JSONObject().put("connected", AutomationClient.connected.value),
            )
        }
    }

    private suspend fun automation(block: suspend () -> String): ServiceResult {
        if (!AutomationClient.connected.value) {
            return ServiceResult.fail(
                "automation_unavailable",
                "自动化服务未连接：请先开启 Lyra 的无障碍服务",
            )
        }
        return try {
            ServiceResult.fromAutomation(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            ServiceResult.fail("automation_error", t.message ?: "自动化调用失败")
        }
    }

    private suspend fun tapText(text: String, exact: Boolean): ServiceResult {
        if (!AutomationClient.connected.value) {
            return ServiceResult.fail("automation_unavailable", "自动化服务未连接")
        }
        val observed = runCatching { ServiceResult.fromAutomation(AutomationClient.observe()) }
            .getOrElse { ServiceResult.fail("automation_error", it.message ?: "观察失败") }
        if (!observed.ok) return observed
        val observation = observed.data.optJSONObject("observation")
            ?: return ServiceResult.fail("bad_response", "观察结果缺少 observation")
        val elements = observation.optJSONArray("elements")
            ?: return ServiceResult.fail("bad_response", "观察结果缺少 elements")

        var match: JSONObject? = null
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val label = element.optString("text").ifBlank { element.optString("desc") }
            if (label.isBlank()) continue
            val hit = if (exact) label.equals(text, ignoreCase = true)
            else label.contains(text, ignoreCase = true)
            if (hit) {
                match = element
                break
            }
        }
        val target = match ?: return ServiceResult.fail(
            "text_not_found",
            "当前屏幕没有找到「$text」",
            JSONObject().put("package", observation.optString("package")),
        )
        val nodeId = target.optInt("id")
        val obsId = observation.optLong("obsId")
        val result = automation { AutomationClient.tap(nodeId, obsId) }
        if (result.ok) {
            result.data.put("matchedId", nodeId)
                .put("matchedText", target.optString("text").ifBlank { target.optString("desc") })
                .put("obsId", obsId)
        }
        return result
    }
}
