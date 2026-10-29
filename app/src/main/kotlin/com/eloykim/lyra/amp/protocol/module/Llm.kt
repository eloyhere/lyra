package com.eloykim.lyra.amp.protocol.module

import com.eloykim.lyra.amp.protocol.json.JsonValue

/**
 * LLM 端口（`src/module/llm/llm.ts` 的 Kotlin 移植）。
 *
 * 内核从不直接调用模型提供方；它向 [LlmClient] 请求一次补全并记录 usage。
 * 参考实现是异步的，Android 侧统一采用同步接口：调用方自己决定线程与超时，
 * 这样模块不依赖 Kotlin 协程或 Java 网络栈。
 */
enum class LlmRole(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    companion object {
        fun fromWire(value: String): LlmRole =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 LLM role: $value")
    }
}

/** 助手返回的工具调用；`arguments` 保留提供方给的原始 JSON 文本。 */
data class LlmToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/** 暴露给模型的工具定义，`inputSchema` 是 JSON Schema。 */
data class LlmToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonValue.Obj,
)

data class LlmMessage(
    val role: LlmRole,
    val content: String,
    /** 助手产生的工具调用，或工具结果对应的调用 id。 */
    val toolCalls: List<LlmToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null,
)

data class LlmRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val tools: List<LlmToolDefinition>? = null,
)

data class LlmUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    /** 提供方报告的缓存命中 prompt token。 */
    val cacheHitTokens: Int? = null,
    /** 未命中缓存的 prompt token。 */
    val cacheMissTokens: Int? = null,
    /** 提供方报告或适配器计算的美元成本。 */
    val costUsd: Double? = null,
)

enum class LlmFinishReason(val wire: String) {
    STOP("stop"),
    LENGTH("length"),
    TOOL_CALL("tool_call");

    companion object {
        fun fromWire(value: String): LlmFinishReason =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 finishReason: $value")
    }
}

data class LlmResponse(
    val text: String,
    val usage: LlmUsage,
    val finishReason: LlmFinishReason,
    val toolCalls: List<LlmToolCall>? = null,
)

fun interface LlmClient {
    fun complete(request: LlmRequest): LlmResponse
}

/**
 * 可脚本化的 LLM：让工具循环在没有网络的情况下可测试。
 *
 * 与参考实现一致：每次调用消耗队列中的下一个响应，队列耗尽时抛错而不是
 * 静默返回空补全。
 */
class ScriptedLlm(responses: List<LlmResponse>) : LlmClient {

    private val remaining: ArrayDeque<LlmResponse> = ArrayDeque(responses)

    var calls: Int = 0
        private set

    override fun complete(request: LlmRequest): LlmResponse {
        calls += 1
        return remaining.removeFirstOrNull()
            ?: throw IllegalStateException("scripted LLM has no response left")
    }
}
