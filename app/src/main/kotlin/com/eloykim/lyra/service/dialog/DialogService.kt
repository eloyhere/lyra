package com.eloykim.lyra.service.dialog

import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optBoolOr
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqString
import com.eloykim.lyra.service.reqStringList
import com.eloykim.lyra.service.ui.DialogChoice
import com.eloykim.lyra.service.ui.DialogKind
import com.eloykim.lyra.service.ui.DialogRequest
import com.eloykim.lyra.service.ui.DialogResult
import com.eloykim.lyra.service.ui.ServiceUi
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * 对话框服务：让模型把选择交给用户，并等待用户做出选择。
 *
 * 注意：这是产品交互，不是安全授权。真正的授权（发消息/文件/账号）
 * 仍必须走 ConsentManager，不能由模型自己弹框、自己点确认。
 */
class DialogService : BasePhoneService() {

    override val id: String = "dialog"
    override val title: String = "对话框"
    override val description: String =
        "向用户显示确认框、多选项或文本输入，并等待用户选择后返回结果。"

    private val counter = AtomicInteger(1)

    init {
        register(
            ServiceMethod(
                name = "confirm",
                summary = "确认框，返回 confirmed=true/false",
                parameters = Schema.params(
                    "title" to Schema.string("标题"),
                    "message" to Schema.string("正文"),
                    "confirmLabel" to Schema.string("确认按钮文字"),
                    "cancelLabel" to Schema.string("取消按钮文字"),
                    "dismissible" to Schema.boolean("是否允许返回键/点外部取消"),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args ->
            val result = ServiceUi.host.showDialog(
                DialogRequest(
                    id = "dialog-${counter.getAndIncrement()}",
                    kind = DialogKind.Confirm,
                    title = args.reqString("title").take(120),
                    message = args.optStringOr("message").take(2_000).ifBlank { null },
                    confirmLabel = args.optStringOr("confirmLabel", "确定").take(12),
                    dismissLabel = args.optStringOr("cancelLabel", "取消").take(12),
                    dismissible = args.optBoolOr("dismissible", true),
                ),
            )
            when (result.choice) {
                DialogChoice.Confirmed -> ServiceResult.ok(
                    "confirmed",
                    JSONObject().put("confirmed", true),
                )
                DialogChoice.Dismissed -> ServiceResult.ok(
                    "dismissed",
                    JSONObject().put("confirmed", false),
                )
                DialogChoice.Unavailable -> ServiceResult.fail("ui_unavailable", result.message)
                DialogChoice.Busy -> ServiceResult.fail("ui_busy", result.message)
                else -> ServiceResult.fail("unexpected", "未预期的对话框结果")
            }
        }

        register(
            ServiceMethod(
                name = "choose",
                summary = "多选一，返回选中的下标和文字",
                parameters = Schema.params(
                    "title" to Schema.string("标题"),
                    "message" to Schema.string("正文"),
                    "options" to Schema.array(
                        "选项列表（2–6 个）",
                        Schema.string("选项"),
                    ),
                    "dismissible" to Schema.boolean("是否允许取消"),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args ->
            val options = args.reqStringList("options")
            if (options.size !in 2..6) {
                throw IllegalArgumentException("options 需要 2–6 个")
            }
            val result = ServiceUi.host.showDialog(
                DialogRequest(
                    id = "dialog-${counter.getAndIncrement()}",
                    kind = DialogKind.Choose,
                    title = args.reqString("title").take(120),
                    message = args.optStringOr("message").take(2_000).ifBlank { null },
                    options = options.map { it.take(60) },
                    dismissible = args.optBoolOr("dismissible", true),
                ),
            )
            dialogResult(result)
        }

        register(
            ServiceMethod(
                name = "select",
                summary = "下拉框，返回用户选中的下标和文字",
                parameters = Schema.params(
                    "title" to Schema.string("标题"),
                    "message" to Schema.string("正文"),
                    "options" to Schema.array(
                        "下拉选项（1–20 个）",
                        Schema.string("选项"),
                    ),
                    "defaultIndex" to Schema.integer("默认选中下标", 0, 19),
                    "dismissible" to Schema.boolean("是否允许取消"),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args ->
            val options = args.reqStringList("options")
            if (options.size !in 1..20) {
                throw IllegalArgumentException("options 需要 1–20 个")
            }
            val selected = args.optIntOr("defaultIndex", 0)
                .coerceIn(0, options.lastIndex)
            val result = ServiceUi.host.showDialog(
                DialogRequest(
                    id = "dialog-${counter.getAndIncrement()}",
                    kind = DialogKind.Select,
                    title = args.reqString("title").take(120),
                    message = args.optStringOr("message").take(2_000).ifBlank { null },
                    options = options.map { it.take(60) },
                    selectedIndex = selected,
                    dismissible = args.optBoolOr("dismissible", true),
                ),
            )
            dialogResult(result)
        }

        register(
            ServiceMethod(
                name = "input",
                summary = "文本输入框，返回用户输入的文本",
                parameters = Schema.params(
                    "title" to Schema.string("标题"),
                    "message" to Schema.string("正文"),
                    "placeholder" to Schema.string("占位提示"),
                    "initialValue" to Schema.string("初始文本"),
                    "secret" to Schema.boolean("是否密码输入"),
                    "dismissible" to Schema.boolean("是否允许取消"),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args ->
            val result = ServiceUi.host.showDialog(
                DialogRequest(
                    id = "dialog-${counter.getAndIncrement()}",
                    kind = DialogKind.Input,
                    title = args.reqString("title").take(120),
                    message = args.optStringOr("message").take(2_000).ifBlank { null },
                    placeholder = args.optStringOr("placeholder").take(120).ifBlank { null },
                    initialValue = args.optStringOr("initialValue").take(4_000),
                    secret = args.optBoolOr("secret", false),
                    dismissible = args.optBoolOr("dismissible", true),
                ),
            )
            dialogResult(result)
        }

        register(
            ServiceMethod(
                name = "info",
                summary = "提示框，只有一个按钮",
                parameters = Schema.params(
                    "title" to Schema.string("标题"),
                    "message" to Schema.string("正文"),
                    "buttonLabel" to Schema.string("按钮文字"),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args ->
            val result = ServiceUi.host.showDialog(
                DialogRequest(
                    id = "dialog-${counter.getAndIncrement()}",
                    kind = DialogKind.Info,
                    title = args.reqString("title").take(120),
                    message = args.optStringOr("message").take(2_000).ifBlank { null },
                    confirmLabel = args.optStringOr("buttonLabel", "知道了").take(12),
                ),
            )
            when (result.choice) {
                DialogChoice.Confirmed, DialogChoice.Dismissed ->
                    ServiceResult.ok("info_closed", JSONObject().put("closed", true))
                DialogChoice.Unavailable -> ServiceResult.fail("ui_unavailable", result.message)
                DialogChoice.Busy -> ServiceResult.fail("ui_busy", result.message)
                else -> ServiceResult.fail("unexpected", "未预期的对话框结果")
            }
        }

        register(
            ServiceMethod(
                name = "status",
                summary = "对话框宿主是否可用",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) {
            ServiceResult.ok("ok", JSONObject().put("available", ServiceUi.available))
        }
    }

    private fun dialogResult(result: DialogResult): ServiceResult = when (result.choice) {
        DialogChoice.Option -> ServiceResult.ok(
            "chosen",
            JSONObject()
                .put("index", result.optionIndex ?: -1)
                .put("option", result.optionLabel)
                .put("text", result.text),
        )
        DialogChoice.Selected -> ServiceResult.ok(
            "selected",
            JSONObject()
                .put("index", result.optionIndex ?: -1)
                .put("option", result.optionLabel)
                .put("text", result.text),
        )
        DialogChoice.Input -> ServiceResult.ok(
            "submitted",
            JSONObject().put("text", result.text.orEmpty()),
        )
        DialogChoice.Dismissed -> ServiceResult.ok(
            "dismissed",
            JSONObject().put("dismissed", true),
        )
        DialogChoice.Unavailable -> ServiceResult.fail("ui_unavailable", result.message)
        DialogChoice.Busy -> ServiceResult.fail("ui_busy", result.message)
        else -> ServiceResult.fail("unexpected", "未预期的对话框结果")
    }
}
