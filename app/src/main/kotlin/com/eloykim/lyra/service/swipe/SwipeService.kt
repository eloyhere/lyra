package com.eloykim.lyra.service.swipe

import android.content.Context
import com.eloykim.lyra.accessibility.AutomationClient
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqInt
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * 滑动服务：按方向生成稳定的滑动手势。
 *
 * 和 `swipe.from`（直接给起终点）互补——这里只需要说「向上滑」，
 * 服务会按屏幕尺寸算出起点/终点，适合信息流、短视频、轮播图。
 */
class SwipeService(private val context: Context) : BasePhoneService() {

    override val id: String = "swipe"
    override val title: String = "滑动"
    override val description: String =
        "按方向滑动屏幕（上/下/左/右），或按给定起终点滑动。"

    init {
        register(
            ServiceMethod(
                name = "swipe",
                summary = "按方向滑动；可指定距离/起点/时长",
                parameters = Schema.params(
                    "direction" to Schema.string(
                        "方向",
                        listOf("up", "down", "left", "right"),
                    ),
                    "distance" to Schema.integer("滑动距离（像素）；省略按屏幕比例"),
                    "durationMs" to Schema.integer("滑动时长", 100, 3_000),
                    "startX" to Schema.integer("起点 x；省略按方向计算"),
                    "startY" to Schema.integer("起点 y；省略按方向计算"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            swipe(
                direction = args.optStringOr("direction", "up").lowercase(),
                distance = args.optIntOr("distance", 0),
                durationMs = args.optIntOr("durationMs", 300),
                startX = args.optIntOr("startX", -1),
                startY = args.optIntOr("startY", -1),
            )
        }

        register(
            ServiceMethod(
                name = "up",
                summary = "向上滑（看下面的内容）",
                parameters = Schema.params(),
                risk = ServiceRisk.CONTROL,
            ),
        ) { swipe("up", 0, 300, -1, -1) }

        register(
            ServiceMethod(
                name = "down",
                summary = "向下滑（看上面的内容）",
                parameters = Schema.params(),
                risk = ServiceRisk.CONTROL,
            ),
        ) { swipe("down", 0, 300, -1, -1) }

        register(
            ServiceMethod(
                name = "left",
                summary = "向左滑（下一页）",
                parameters = Schema.params(),
                risk = ServiceRisk.CONTROL,
            ),
        ) { swipe("left", 0, 300, -1, -1) }

        register(
            ServiceMethod(
                name = "right",
                summary = "向右滑（上一页）",
                parameters = Schema.params(),
                risk = ServiceRisk.CONTROL,
            ),
        ) { swipe("right", 0, 300, -1, -1) }

        register(
            ServiceMethod(
                name = "from",
                summary = "按精确起终点滑动",
                parameters = Schema.params(
                    "x1" to Schema.integer("起点 x", -20_000, 20_000),
                    "y1" to Schema.integer("起点 y", -20_000, 20_000),
                    "x2" to Schema.integer("终点 x", -20_000, 20_000),
                    "y2" to Schema.integer("终点 y", -20_000, 20_000),
                    "durationMs" to Schema.integer("滑动时长", 100, 3_000),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            perform(
                args.reqInt("x1"),
                args.reqInt("y1"),
                args.reqInt("x2"),
                args.reqInt("y2"),
                args.optIntOr("durationMs", 300),
            )
        }

        register(
            ServiceMethod(
                name = "status",
                summary = "滑动服务连接状态与屏幕尺寸",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) {
            val metrics = context.resources.displayMetrics
            ServiceResult.ok(
                "ok",
                JSONObject()
                    .put("connected", AutomationClient.connected.value)
                    .put("width", metrics.widthPixels)
                    .put("height", metrics.heightPixels),
            )
        }
    }

    private suspend fun swipe(
        direction: String,
        distance: Int,
        durationMs: Int,
        startX: Int,
        startY: Int,
    ): ServiceResult {
        if (direction !in setOf("up", "down", "left", "right")) {
            return ServiceResult.fail(
                "bad_args",
                "direction 只支持 up/down/left/right",
            )
        }
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val horizontal = direction == "left" || direction == "right"
        val span = if (horizontal) width else height
        val travel = if (distance > 0) distance else (span * 0.45f).toInt()

        val x1: Int
        val y1: Int
        val x2: Int
        val y2: Int
        when (direction) {
            "up" -> {
                x1 = if (startX >= 0) startX else width / 2
                y1 = if (startY >= 0) startY else (height * 0.75f).toInt()
                x2 = x1
                y2 = y1 - travel
            }
            "down" -> {
                x1 = if (startX >= 0) startX else width / 2
                y1 = if (startY >= 0) startY else (height * 0.25f).toInt()
                x2 = x1
                y2 = y1 + travel
            }
            "left" -> {
                x1 = if (startX >= 0) startX else (width * 0.80f).toInt()
                y1 = if (startY >= 0) startY else height / 2
                x2 = x1 - travel
                y2 = y1
            }
            else -> {
                x1 = if (startX >= 0) startX else (width * 0.20f).toInt()
                y1 = if (startY >= 0) startY else height / 2
                x2 = x1 + travel
                y2 = y1
            }
        }
        return perform(
            x1.coerceIn(0, width - 1),
            y1.coerceIn(0, height - 1),
            x2.coerceIn(0, width - 1),
            y2.coerceIn(0, height - 1),
            durationMs,
        )
    }

    private suspend fun perform(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): ServiceResult {
        if (x1 == x2 && y1 == y2) {
            return ServiceResult.fail("bad_args", "起点和终点相同")
        }
        if (!AutomationClient.connected.value) {
            return ServiceResult.fail("automation_unavailable", "自动化服务未连接")
        }
        return try {
            ServiceResult.fromAutomation(
                AutomationClient.swipe(
                    x1,
                    y1,
                    x2,
                    y2,
                    durationMs.coerceIn(100, 3_000),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            ServiceResult.fail("automation_error", t.message ?: "滑动失败")
        }
    }
}
