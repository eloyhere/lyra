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

/** 本机应用目录。支持 list / search / info / launch。 */
interface AppCatalogService {

    suspend fun handle(action: String, query: String? = null): ServiceResult

    /**
     * 最近一次 `application.list` 的结构化结果。
     *
     * 默认返回空列表，兼容只实现 [handle] 的旧调用方；首页启动阶段要求
     * 实现方在 list 成功后把结果缓存下来。
     */
    suspend fun listApps(): List<AppInfo> = emptyList()

    /** 通过 application 服务启动应用。默认退化为 handle("launch")。 */
    suspend fun launch(packageName: String): ServiceResult = handle("launch", packageName)
}

/** 语音播报。支持 speak / stop。 */
interface VoiceService {
    suspend fun handle(action: String, text: String? = null): ServiceResult
}
