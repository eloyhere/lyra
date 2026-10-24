package com.eloykim.lyra.service.snackbar

import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optBoolOr
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.reqString
import com.eloykim.lyra.service.ui.ServiceUi
import com.eloykim.lyra.service.ui.SnackbarOutcome
import com.eloykim.lyra.service.ui.SnackbarRequest
import org.json.JSONObject

/**
 * 轻提示服务：在应用内显示一条 snackbar。
 *
 * 只在前台且 [com.eloykim.lyra.service.ui.ServiceUiOverlay] 已挂载时可用；
 * 后台调用返回 `ui_unavailable`，不会静默丢弃。
 */
class SnackbarService : BasePhoneService() {

    override val id: String = "snackbar"
    override val title: String = "轻提示"
    override val description: String = "在应用内显示短暂提示；可选一个操作按钮并等待用户点击。"

    init {
        register(
            ServiceMethod(
                name = "show",
                summary = "显示 snackbar；有 actionLabel 时等待用户点击或超时",
                parameters = Schema.params(
                    "message" to Schema.string("提示文字"),
                    "actionLabel" to Schema.string("操作按钮文字，例如「撤销」"),
                    "durationMs" to Schema.integer("停留时长", 1_000, 10_000),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args ->
            val message = args.reqString("message").take(240)
            val actionLabel = args.reqStringOrNull("actionLabel")?.take(24)
            val durationMs = args.optIntOr("durationMs", 4_000).coerceIn(1_000, 10_000)
            val result = ServiceUi.host.showSnackbar(
                SnackbarRequest(
                    message = message,
                    actionLabel = actionLabel,
                    withDismissAction = actionLabel == null,
                    durationMs = durationMs.toLong(),
                ),
            )
            when (result.outcome) {
                SnackbarOutcome.Action -> ServiceResult.ok(
                    "action",
                    JSONObject().put("acted", true).put("message", message),
                )
                SnackbarOutcome.Dismissed -> ServiceResult.ok(
                    "dismissed",
                    JSONObject().put("acted", false).put("message", message),
                )
                SnackbarOutcome.Unavailable -> ServiceResult.fail(
                    "ui_unavailable",
                    "应用不在前台，或 UI 宿主未挂载",
                )
            }
        }

        register(
            ServiceMethod(
                name = "status",
                summary = "snackbar 宿主是否可用",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) {
            ServiceResult.ok("ok", JSONObject().put("available", ServiceUi.available))
        }
    }

    private fun org.json.JSONObject.reqStringOrNull(name: String): String? =
        optString(name).takeIf { it.isNotBlank() }
}
