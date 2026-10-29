package com.eloykim.lyra.amp.protocol.module

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson

/**
 * DeepSeek 版 [LlmClient]（`src/module/llm/deepseek.ts` 的移植）。
 *
 * API key 由调用方注入，绝不硬编码。思考模式默认关闭，让运行时得到确定性的
 * 最终答案并且只为输出 token 付费；usage 映射回 LLM 端口。
 *
 * 参考实现直接使用 `fetch`；Android 端口把 HTTP 抽成 [DeepSeekHttpClient]，
 * 保留请求/响应映射、工具调用解析、usage 字段与重试语义，不在此引入网络栈。
 */
data class DeepSeekOptions(
    val apiKey: String,
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val timeoutMs: Int = 60_000,
    val retries: Int = 2,
    /** 每百万 token 的价格；默认是 deepseek-flash 的 off-peak 价格。 */
    val inputMissPricePerMTok: Double = 0.15,
    val inputHitPricePerMTok: Double = 0.003,
    val outputPricePerMTok: Double = 0.6,
)

class DeepSeekError(
    val status: Int,
    message: String,
    val retryable: Boolean,
) : RuntimeException(message)

data class DeepSeekHttpResponse(
    val status: Int,
    val body: String,
)

/**
 * 宿主提供的 HTTP 注入点。
 *
 * [post] 必须遵守 `timeoutMs`：超时时抛 [DeepSeekTimeoutException]，其他网络
 * 错误抛任意异常即可——适配器会按参考实现把它们映射成可重试的
 * [DeepSeekError]（status 0）。
 */
fun interface DeepSeekHttpClient {
    fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): DeepSeekHttpResponse
}

class DeepSeekTimeoutException(message: String) : RuntimeException(message)

class DeepSeekLlm(
    options: DeepSeekOptions,
    private val http: DeepSeekHttpClient,
) : LlmClient {

    private val apiKey: String
    private val baseUrl: String
    private val model: String
    private val timeoutMs: Int
    private val retries: Int
    private val inputMissPrice: Double
    private val inputHitPrice: Double
    private val outputPrice: Double

    init {
        require(options.apiKey.trim().isNotEmpty()) { "DeepSeekLlm requires an apiKey" }
        apiKey = options.apiKey
        baseUrl = options.baseUrl.trimEnd('/')
        model = options.model
        timeoutMs = options.timeoutMs
        retries = options.retries.coerceAtLeast(0)
        inputMissPrice = options.inputMissPricePerMTok
        inputHitPrice = options.inputHitPricePerMTok
        outputPrice = options.outputPricePerMTok
    }

    override fun complete(request: LlmRequest): LlmResponse {
        var lastError: DeepSeekError? = null
        for (attempt in 0..retries) {
            try {
                return once(request)
            } catch (error: DeepSeekError) {
                lastError = error
                if (!error.retryable || attempt >= retries) {
                    throw error
                }
                try {
                    Thread.sleep(250L * (1L shl attempt))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw DeepSeekError(0, "DeepSeek request interrupted", false)
                }
            }
        }
        throw lastError ?: DeepSeekError(0, "DeepSeek request failed", true)
    }

    /**
     * 把 LLM 端口请求映射成 DeepSeek chat-completions 请求体。
     *
     * 与参考实现一致：有 `toolCalls` 的助手消息在 content 为空时显式写 null；
     * `thinking.type` 固定 `disabled`，`stream` 固定 false。
     */
    fun requestBody(request: LlmRequest): JsonValue.Obj = jsonObj(
        "model" to JsonValue.Str(if (request.model.isEmpty()) model else request.model),
        "messages" to JsonValue.Arr(request.messages.map { messagePayload(it) }),
        "thinking" to jsonObj("type" to JsonValue.Str("disabled")),
        "temperature" to jsonNumber(request.temperature ?: 0.0),
        "max_tokens" to request.maxTokens?.let { jsonNumber(it) },
        "tools" to request.tools?.let { definitions -> JsonValue.Arr(definitions.map { toolPayload(it) }) },
        "stream" to JsonValue.Bool(false),
    )

    /** 解析响应体；工具调用、finishReason 与 usage/cost 按参考实现映射。 */
    fun parseResponse(text: String): LlmResponse {
        val body: JsonValue.Obj = JsonParser.parse(text) as? JsonValue.Obj
            ?: throw IllegalArgumentException("DeepSeek 响应不是 JSON 对象")
        val choice: JsonValue.Obj? = body.arr("choices")?.obj(0)
        val message: JsonValue.Obj? = choice?.obj("message")
        val textOut: String = message?.string("content") ?: ""
        val rawCalls: List<JsonValue> = message?.arr("tool_calls")?.items ?: emptyList()
        val toolCalls: List<LlmToolCall> = rawCalls.mapNotNull { item ->
            val call: JsonValue.Obj = item as? JsonValue.Obj ?: return@mapNotNull null
            LlmToolCall(
                id = call.string("id") ?: "",
                name = call.obj("function")?.string("name") ?: "",
                arguments = call.obj("function")?.string("arguments") ?: "{}",
            )
        }
        val finish: String? = choice?.string("finish_reason")
        val finishReason: LlmFinishReason = when {
            finish == "length" -> LlmFinishReason.LENGTH
            finish == "tool_calls" || toolCalls.isNotEmpty() -> LlmFinishReason.TOOL_CALL
            else -> LlmFinishReason.STOP
        }
        val usage: JsonValue.Obj? = body.obj("usage")
        val inputTokens: Int = usage?.int("prompt_tokens") ?: 0
        val hit: Int = usage?.int("prompt_cache_hit_tokens") ?: 0
        val miss: Int = usage?.int("prompt_cache_miss_tokens") ?: maxOf(0, inputTokens - hit)
        val outputTokens: Int = usage?.int("completion_tokens") ?: 0
        val costUsd: Double = (hit * inputHitPrice + miss * inputMissPrice + outputTokens * outputPrice) / 1_000_000.0
        return LlmResponse(
            text = textOut,
            usage = LlmUsage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cacheHitTokens = hit,
                cacheMissTokens = miss,
                costUsd = costUsd,
            ),
            finishReason = finishReason,
            toolCalls = if (toolCalls.isEmpty()) null else toolCalls,
        )
    }

    private fun once(request: LlmRequest): LlmResponse {
        try {
            val body: String = CanonicalJson.encode(requestBody(request))
            val response: DeepSeekHttpResponse = http.post(
                "$baseUrl/chat/completions",
                mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Content-Type" to "application/json",
                ),
                body,
                timeoutMs,
            )
            if (response.status !in 200..299) {
                val detail: String = errorDetail(response.body)
                throw DeepSeekError(
                    response.status,
                    "DeepSeek HTTP ${response.status}: ${detail.take(400)}",
                    retryableStatus(response.status),
                )
            }
            return parseResponse(response.body)
        } catch (error: DeepSeekError) {
            throw error
        } catch (error: DeepSeekTimeoutException) {
            throw DeepSeekError(0, "DeepSeek timeout after ${timeoutMs}ms", true)
        } catch (error: Exception) {
            throw DeepSeekError(0, error.message ?: error.toString(), true)
        }
    }

    private fun messagePayload(message: LlmMessage): JsonValue.Obj {
        val calls: List<LlmToolCall>? = message.toolCalls
        val content: JsonValue = when {
            calls == null -> JsonValue.Str(message.content)
            message.content.isEmpty() -> JsonValue.Null
            else -> JsonValue.Str(message.content)
        }
        return jsonObj(
            "role" to JsonValue.Str(message.role.wire),
            "content" to content,
            "tool_calls" to calls?.let { list ->
                JsonValue.Arr(list.map { call ->
                    jsonObj(
                        "id" to JsonValue.Str(call.id),
                        "type" to JsonValue.Str("function"),
                        "function" to jsonObj(
                            "name" to JsonValue.Str(call.name),
                            "arguments" to JsonValue.Str(call.arguments),
                        ),
                    )
                })
            },
            "tool_call_id" to message.toolCallId?.let { JsonValue.Str(it) },
            "name" to message.name?.let { JsonValue.Str(it) },
        )
    }

    private fun toolPayload(tool: LlmToolDefinition): JsonValue.Obj = jsonObj(
        "type" to JsonValue.Str("function"),
        "function" to jsonObj(
            "name" to JsonValue.Str(tool.name),
            "description" to JsonValue.Str(tool.description),
            "parameters" to tool.inputSchema,
        ),
    )

    private fun errorDetail(body: String): String {
        val parsed: JsonValue = runCatching { JsonParser.parse(body) }.getOrNull() ?: return body
        val obj: JsonValue.Obj = parsed as? JsonValue.Obj ?: return ""
        return obj.obj("error")?.string("message") ?: ""
    }

    companion object {

        private fun retryableStatus(status: Int): Boolean =
            status == 429 || status == 500 || status == 502 || status == 503 || status == 504

        /**
         * Node 的 `fromEnv` 在 Android 上没有对应的进程环境契约，因此改为显式
         * 传入环境映射；密钥仍只来自注入值。
         */
        fun fromEnv(env: Map<String, String>, http: DeepSeekHttpClient): DeepSeekLlm {
            val apiKey: String? = env["DEEPSEEK_API_KEY"]
            if (apiKey == null || apiKey.trim().isEmpty()) {
                throw IllegalArgumentException("DEEPSEEK_API_KEY is not set")
            }
            return DeepSeekLlm(
                DeepSeekOptions(
                    apiKey = apiKey,
                    baseUrl = env["DEEPSEEK_BASE_URL"] ?: "https://api.deepseek.com",
                    model = env["DEEPSEEK_MODEL"] ?: "deepseek-flash",
                ),
                http,
            )
        }
    }
}
