package com.eloykim.lyra.agent

/**
 * Agent 可调用的宿主服务。
 *
 * 和 [AutomationGateway] 的区别：这里不是「操作屏幕」，而是查询宿主数据
 * （应用目录）或驱动宿主能力（TTS）。结果以纯文本回给模型，写进对话历史。
 */

/** 一次服务调用的结果。message 会回传给模型；过长由 backend 截断。 */
data class ServiceResult(
    val ok: Boolean,
    val code: String? = null,
    val message: String = "",
)

/** 本机应用目录。目前支持 list。 */
interface AppCatalogService {
    suspend fun handle(action: String, query: String? = null): ServiceResult
}

/** 语音播报。支持 speak / stop。 */
interface VoiceService {
    suspend fun handle(action: String, text: String? = null): ServiceResult
}
