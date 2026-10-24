package com.eloykim.lyra.service

import org.json.JSONObject

/**
 * 一个面向 Agent 的手机能力服务。
 *
 * 设计约定：
 * - 每个服务由若干 [methods] 组成；`service.method` 就是 Agent 可调用的稳定命令 id；
 * - [call] 是唯一入口：未知方法、参数错误都返回 `ok=false`，不向调用方抛异常；
 * - 服务只持有 application context，不持有 Activity，避免泄漏；
 * - 需要用户在场的服务（dialog / snackbar）在没有 UI 宿主时返回 `ui_unavailable`；
 * - [ServiceMethod.risk] 只是元数据，真正的安全闸门仍在 AutomationEngine / PolicyGuard。
 */
interface PhoneService {

    /** 稳定 id，例如 `touch`、`short_message`。 */
    val id: String

    /** 给终端和工具目录看的名字。 */
    val title: String

    /** 一句话说明这个服务能做什么。 */
    val description: String

    /** 可调用命令声明。 */
    val methods: List<ServiceMethod>

    /** 调用前需要确认/申请的 Android 运行时权限。 */
    fun requiredPermissions(): List<String> = emptyList()

    /** 按具体方法声明权限；默认沿用服务级声明。 */
    fun requiredPermissions(method: String): List<String> = requiredPermissions()

    /** 统一调用入口。 */
    suspend fun call(method: String, args: JSONObject = JSONObject()): ServiceResult

    /** 释放底层资源（TTS、OCR、监听等）；默认无操作。 */
    fun close() = Unit
}

/**
 * 命令风险级别。Agent 接入时可按级别决定是否需要用户授权。
 */
enum class ServiceRisk {
    /** 只读：屏幕文本、通知、聊天记录、应用列表、设备状态。 */
    READ,

    /** 局部写入：输入框、剪贴板。 */
    INPUT,

    /** 控制设备 / 应用：触摸、启动应用、全局动作、音量、闹钟。 */
    CONTROL,

    /** 与用户或外部通信：TTS、snackbar、dialog、短信、打电话、邮件。 */
    COMMUNICATION,

    /** 高敏内容：截图、通知正文、短信正文、联系人、日程。 */
    SENSITIVE,
}

/**
 * 一个命令声明。
 *
 * [parameters] 是 JSON Schema（object），与 OpenAI / Anthropic 的工具参数格式一致；
 * 终端会把它渲染成参数提示，Agent 侧可以直接转成 tool definition。
 */
data class ServiceMethod(
    val name: String,
    val summary: String,
    val parameters: JSONObject = JSONObject(),
    val risk: ServiceRisk = ServiceRisk.READ,
)

/**
 * 命令执行结果。
 *
 * [data] 里放结构化结果（路径、元素、列表等）；[toJson] 会把 data 展平到顶层，
 * 保持与现有 AIDL JSON（`{ok, code, message, ...}`）兼容。
 */
data class ServiceResult(
    val ok: Boolean,
    val code: String = if (ok) "ok" else "failed",
    val message: String = "",
    val data: JSONObject = JSONObject(),
) {

    fun toJson(): JSONObject {
        val json = JSONObject()
            .put("ok", ok)
            .put("code", code)
        if (message.isNotEmpty()) json.put("message", message)
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "ok" || key == "code" || key == "message") continue
            json.put(key, data.get(key))
        }
        return json
    }

    companion object {

        private val ENVELOPE_KEYS = setOf("ok", "code", "message")

        fun ok(message: String = "ok"): ServiceResult =
            ServiceResult(true, "ok", message, JSONObject())

        fun ok(message: String, data: JSONObject): ServiceResult =
            ServiceResult(true, "ok", message, data)

        fun fail(code: String, message: String, data: JSONObject = JSONObject()): ServiceResult =
            ServiceResult(false, code, message, data)

        /**
         * 解析 AIDL / AutomationService 返回的 JSON。
         * 失败时也尽量把 `observation` 等附加字段保留在 data 里。
         */
        fun fromAutomation(raw: String): ServiceResult {
            val json = runCatching { JSONObject(raw) }.getOrElse {
                return fail("bad_response", "服务返回了非 JSON：${raw.take(160)}")
            }
            val ok = json.optBoolean("ok", false)
            val code = json.optString("code").ifBlank { if (ok) "ok" else "failed" }
            val message = json.optString("message")
            val data = JSONObject()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key in ENVELOPE_KEYS) continue
                data.put(key, json.get(key))
            }
            return ServiceResult(ok, code, message, data)
        }
    }
}
