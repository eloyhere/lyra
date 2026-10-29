package com.eloykim.lyra.network

import org.json.JSONObject

/**
 * 与厂商无关的对话模型接口。
 *
 * 只覆盖 Lyra 真正需要的：system/user 消息、工具定义、工具调用、用量。
 * 流式暂不实现（Agent 循环是「一次调用一个工具」，非流式足够）。
 */
data class LlmMessage(val role: String, val content: String)

data class LlmTool(
    val name: String,
    val description: String,
    /** JSON Schema 字符串，例如 {"type":"object","properties":{...}} */
    val parametersJson: String,
)

data class LlmToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class LlmUsage(val inputTokens: Int = 0, val outputTokens: Int = 0)

data class LlmResult(
    val text: String?,
    val toolCalls: List<LlmToolCall>,
    val usage: LlmUsage,
    /** 思维链；只有开启思考且服务商返回时才非空。 */
    val reasoning: String? = null,
)

/** 聊天页的思考等级；具体映射由各服务商客户端决定。 */
enum class ThinkingLevel(val wireValue: String) {
    Off("off"),
    Minimum("minimum"),
    Medium("medium"),
    Maximum("maximum"),
}

interface LlmClient {
    val model: String
    suspend fun complete(messages: List<LlmMessage>, tools: List<LlmTool> = emptyList()): LlmResult

    /**
     * 流式生成：每段文本通过 [onDelta] 回调（正文, 思维链）。
     * 默认实现不支持流式，退化为整段生成后一次性回调。
     */
    suspend fun stream(
        messages: List<LlmMessage>,
        tools: List<LlmTool> = emptyList(),
        onDelta: (String, String?) -> Unit,
    ): LlmResult {
        val result = complete(messages, tools)
        result.text?.let { onDelta(it, result.reasoning) }
        return result
    }
}

/** 按服务商选择协议实现；默认走 OpenAI 兼容。 */
object LlmClients {

    fun create(
        providerId: String,
        baseUrl: String?,
        apiKey: String,
        model: String,
        transport: HttpTransport = UrlConnectionTransport(),
        thinking: ThinkingLevel = ThinkingLevel.Off,
    ): LlmClient {
        val base = baseUrl?.trimEnd('/')
        return when (providerId) {
            "anthropic" -> AnthropicClient(
                base ?: "https://api.anthropic.com",
                apiKey,
                model,
                thinking,
                transport,
            )

            "gemini" -> GeminiClient(
                base ?: "https://generativelanguage.googleapis.com",
                apiKey,
                model,
                thinking,
                transport,
            )

            else -> OpenAiCompatibleClient(
                base ?: "https://api.openai.com/v1",
                apiKey,
                model,
                providerId,
                thinking,
                transport,
            )
        }
    }
}

/** OpenAI / DeepSeek / Qwen / Moonshot / 智谱 / OpenRouter / 本地 都走这个协议。 */
class OpenAiCompatibleClient(
    private val baseUrl: String,
    private val apiKey: String,
    override val model: String,
    private val providerId: String,
    private val thinking: ThinkingLevel,
    private val transport: HttpTransport,
) : LlmClient {

    override suspend fun stream(
        messages: List<LlmMessage>,
        tools: List<LlmTool>,
        onDelta: (String, String?) -> Unit,
    ): LlmResult {
        val payload = JSONObject().apply {
            put("model", model)
            if (thinking == ThinkingLevel.Off) put("temperature", 0.2)
            put(
                "messages",
                org.json.JSONArray(messages.map {
                    JSONObject().put("role", it.role).put("content", it.content)
                }),
            )
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    org.json.JSONArray(tools.map { tool ->
                        JSONObject()
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", tool.name)
                                    .put("description", tool.description)
                                    .put("parameters", JSONObject(tool.parametersJson)),
                            )
                    }),
                )
                put("tool_choice", "auto")
            }
            applyThinking(this)
            put("stream", true)
        }
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = LinkedHashMap<Int, StringBuilder>()
        val callIds = LinkedHashMap<Int, String>()
        val callNames = LinkedHashMap<Int, String>()
        transport.postStream(
            url = "$baseUrl/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json",
            ),
            body = payload.toString(),
        ) { line ->
            if (!line.startsWith("data:")) return@postStream
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") return@postStream
            runCatching {
                val json = JSONObject(data)
                val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                fun textOf(key: String): String {
                    val d = delta ?: return ""
                    if (!d.has(key) || d.isNull(key)) return ""
                    return d.optString(key)
                }
                val piece = textOf("content")
                val think = textOf("reasoning_content")
                if (think.isNotEmpty()) {
                    reasoning.append(think)
                    onDelta("", think)
                }
                if (piece.isNotEmpty()) {
                    text.append(piece)
                    onDelta(piece, null)
                }
                delta?.optJSONArray("tool_calls")?.let { array ->
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val slot = item.optInt("index", index)
                        item.optString("id").takeIf { it.isNotBlank() }?.let { callIds[slot] = it }
                        item.optJSONObject("function")?.let { function ->
                            function.optString("name").takeIf { it.isNotBlank() }?.let { callNames[slot] = it }
                            function.optString("arguments").takeIf { it.isNotEmpty() }?.let {
                                calls.getOrPut(slot) { StringBuilder() }.append(it)
                            }
                        }
                    }
                }
            }
        }
        return LlmResult(
            text = text.toString().takeIf { it.isNotBlank() },
            toolCalls = calls.map { (slot, args) ->
                LlmToolCall(
                    id = callIds[slot] ?: "call_$slot",
                    name = callNames[slot] ?: "lyra_service",
                    argumentsJson = args.toString().ifBlank { "{}" },
                )
            },
            usage = LlmUsage(),
            reasoning = reasoning.toString().takeIf { it.isNotBlank() },
        )
    }
    override suspend fun complete(messages: List<LlmMessage>, tools: List<LlmTool>): LlmResult {
        val payload = JSONObject().apply {
            put("model", model)
            // 思考模式通常不接受 temperature；关闭思考时才保留原来的 0.2。
            if (thinking == ThinkingLevel.Off) {
                put("temperature", 0.2)
            }
            put(
                "messages",
                org.json.JSONArray(messages.map {
                    JSONObject().put("role", it.role).put("content", it.content)
                }),
            )
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    org.json.JSONArray(tools.map { tool ->
                        JSONObject()
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", tool.name)
                                    .put("description", tool.description)
                                    .put("parameters", JSONObject(tool.parametersJson)),
                            )
                    }),
                )
                put("tool_choice", "auto")
            }
            applyThinking(this)
        }

        val raw = transport.post(
            url = "$baseUrl/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json",
            ),
            body = payload.toString(),
        )

        val json = JSONObject(raw)
        val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val text = message?.optString("content")?.takeIf { it.isNotBlank() && it != "null" }
        val reasoning = extractReasoning(message)

        val calls = ArrayList<LlmToolCall>()
        message?.optJSONArray("tool_calls")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val function = item.optJSONObject("function") ?: continue
                calls += LlmToolCall(
                    id = item.optString("id").ifBlank { "call_$index" },
                    name = function.optString("name"),
                    argumentsJson = function.optString("arguments").ifBlank { "{}" },
                )
            }
        }

        val usage = json.optJSONObject("usage")
        return LlmResult(
            text = text,
            toolCalls = calls,
            usage = LlmUsage(
                inputTokens = usage?.optInt("prompt_tokens") ?: 0,
                outputTokens = usage?.optInt("completion_tokens") ?: 0,
            ),
            reasoning = reasoning,
        )
    }

    /**
     * 各品牌的思考参数映射。
     *
     * - DeepSeek: 顶层 thinking + reasoning_effort
     * - Qwen: enable_thinking + thinking_budget
     * - Kimi: k3 用 reasoning_effort；k2.6 用 thinking.type
     * - 智谱: thinking.type；GLM-5 / 4.7 再加 reasoning_effort
     * - OpenRouter: 统一 reasoning.effort
     * - OpenAI / 本地: 仅对已知推理模型加 reasoning_effort
     */
    private fun applyThinking(payload: JSONObject) {
        when (providerId) {
            "deepseek" -> {
                if (thinking == ThinkingLevel.Off) {
                    payload.put("thinking", JSONObject().put("type", "disabled"))
                } else {
                    payload.put("thinking", JSONObject().put("type", "enabled"))
                    payload.put(
                        "reasoning_effort",
                        when (thinking) {
                            ThinkingLevel.Minimum -> "low"
                            ThinkingLevel.Medium -> "medium"
                            ThinkingLevel.Maximum -> "max"
                            ThinkingLevel.Off -> "low"
                        },
                    )
                }
            }

            "qwen" -> {
                payload.put("enable_thinking", thinking != ThinkingLevel.Off)
                if (thinking != ThinkingLevel.Off) {
                    payload.put(
                        "thinking_budget",
                        when (thinking) {
                            ThinkingLevel.Minimum -> 1024
                            ThinkingLevel.Medium -> 8192
                            ThinkingLevel.Maximum -> 24576
                            ThinkingLevel.Off -> 0
                        },
                    )
                }
            }

            "moonshot" -> {
                when {
                    model.startsWith("kimi-k3") -> {
                        if (thinking != ThinkingLevel.Off) {
                            payload.put(
                                "reasoning_effort",
                                when (thinking) {
                                    ThinkingLevel.Minimum -> "low"
                                    ThinkingLevel.Medium -> "high"
                                    ThinkingLevel.Maximum -> "max"
                                    ThinkingLevel.Off -> "low"
                                },
                            )
                        }
                    }

                    model.startsWith("kimi-k2.7-code") -> Unit // 始终思考，不传参数

                    model.startsWith("kimi-k2") -> {
                        payload.put(
                            "thinking",
                            JSONObject().put(
                                "type",
                                if (thinking == ThinkingLevel.Off) "disabled" else "enabled",
                            ),
                        )
                    }
                }
            }

            "zhipu" -> {
                payload.put(
                    "thinking",
                    JSONObject().put(
                        "type",
                        if (thinking == ThinkingLevel.Off) "disabled" else "enabled",
                    ),
                )
                if (thinking != ThinkingLevel.Off &&
                    (model.startsWith("glm-5") || model.startsWith("glm-4.7"))
                ) {
                    payload.put(
                        "reasoning_effort",
                        when (thinking) {
                            ThinkingLevel.Minimum -> "low"
                            ThinkingLevel.Medium -> "high"
                            ThinkingLevel.Maximum -> "max"
                            ThinkingLevel.Off -> "low"
                        },
                    )
                }
            }

            "openrouter" -> {
                payload.put(
                    "reasoning",
                    JSONObject().put(
                        "effort",
                        when (thinking) {
                            ThinkingLevel.Off -> "none"
                            ThinkingLevel.Minimum -> "low"
                            ThinkingLevel.Medium -> "medium"
                            ThinkingLevel.Maximum -> "high"
                        },
                    ),
                )
            }

            "openai", "local" -> {
                if (thinking != ThinkingLevel.Off && isReasoningModel(model)) {
                    payload.put(
                        "reasoning_effort",
                        when (thinking) {
                            ThinkingLevel.Minimum -> "low"
                            ThinkingLevel.Medium -> "medium"
                            ThinkingLevel.Maximum -> "high"
                            ThinkingLevel.Off -> "low"
                        },
                    )
                }
            }

            else -> {
                if (thinking != ThinkingLevel.Off && isReasoningModel(model)) {
                    payload.put(
                        "reasoning_effort",
                        when (thinking) {
                            ThinkingLevel.Minimum -> "low"
                            ThinkingLevel.Medium -> "medium"
                            ThinkingLevel.Maximum -> "high"
                            ThinkingLevel.Off -> "low"
                        },
                    )
                }
            }
        }
    }

    private fun isReasoningModel(name: String): Boolean {
        val modelName = name.lowercase()
        return modelName.startsWith("o1") ||
            modelName.startsWith("o3") ||
            modelName.startsWith("o4") ||
            modelName.contains("gpt-5") ||
            modelName.contains("reason")
    }

    /**
     * 思考内容在不同品牌下的字段不同：
     * reasoning_content（DeepSeek/Qwen/Kimi/GLM）、reasoning（OpenRouter）、
     * reasoning_details（OpenRouter 结构化）。
     */
    private fun extractReasoning(message: JSONObject?): String? {
        if (message == null) return null
        message.optString("reasoning_content").takeIf { it.isNotBlank() && it != "null" }?.let {
            return it
        }
        when (val raw = message.opt("reasoning")) {
            is String -> if (raw.isNotBlank() && raw != "null") return raw
            is JSONObject -> raw.optString("content").ifBlank { raw.optString("text") }
                .takeIf { it.isNotBlank() }
                ?.let { return it }

            is org.json.JSONArray -> joinReasoningArray(raw)?.let { return it }
        }
        return joinReasoningDetails(message.optJSONArray("reasoning_details"))
    }

    private fun joinReasoningArray(array: org.json.JSONArray): String? {
        val parts = ArrayList<String>()
        for (index in 0 until array.length()) {
            val item = array.opt(index)
            when (item) {
                is String -> item.takeIf { it.isNotBlank() }?.let { parts += it }
                is JSONObject -> item.optString("text").ifBlank { item.optString("summary") }
                    .takeIf { it.isNotBlank() }
                    ?.let { parts += it }
            }
        }
        return parts.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun joinReasoningDetails(array: org.json.JSONArray?): String? {
        if (array == null) return null
        val parts = ArrayList<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val text = when (item.optString("type")) {
                "reasoning.text" -> item.optString("text")
                "reasoning.summary" -> item.optString("summary")
                else -> ""
            }
            if (text.isNotBlank()) parts += text
        }
        return parts.joinToString("\n").takeIf { it.isNotBlank() }
    }
}

class AnthropicClient(
    private val baseUrl: String,
    private val apiKey: String,
    override val model: String,
    private val thinking: ThinkingLevel,
    private val transport: HttpTransport,
) : LlmClient {

    override suspend fun complete(messages: List<LlmMessage>, tools: List<LlmTool>): LlmResult {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val dialogue = messages.filter { it.role != "system" }
        val thinkingBudget = when (thinking) {
            ThinkingLevel.Off -> null
            ThinkingLevel.Minimum -> 1024
            ThinkingLevel.Medium -> 8192
            ThinkingLevel.Maximum -> 24_576
        }

        val payload = JSONObject().apply {
            put("model", model)
            // Anthropic 要求 max_tokens 严格大于 thinking budget。
            put(
                "max_tokens",
                if (thinkingBudget != null) maxOf(2048, thinkingBudget + 2048) else 2048,
            )
            if (system.isNotBlank()) put("system", system)
            put(
                "messages",
                org.json.JSONArray(dialogue.map { message ->
                    JSONObject()
                        .put("role", if (message.role == "assistant") "assistant" else "user")
                        .put("content", message.content)
                }),
            )
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    org.json.JSONArray(tools.map { tool ->
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("input_schema", JSONObject(tool.parametersJson))
                    }),
                )
            }
            thinkingBudget?.let {
                put(
                    "thinking",
                    JSONObject()
                        .put("type", "enabled")
                        .put("budget_tokens", it),
                )
            }
        }

        val raw = transport.post(
            url = "$baseUrl/v1/messages",
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                "Content-Type" to "application/json",
            ),
            body = payload.toString(),
        )

        val json = JSONObject(raw)
        val blocks = json.optJSONArray("content")
        val textParts = StringBuilder()
        val reasoningParts = StringBuilder()
        val calls = ArrayList<LlmToolCall>()
        if (blocks != null) {
            for (index in 0 until blocks.length()) {
                val block = blocks.optJSONObject(index) ?: continue
                when (block.optString("type")) {
                    "text" -> textParts.append(block.optString("text"))
                    "thinking" -> reasoningParts.append(block.optString("thinking"))
                    "tool_use" -> calls += LlmToolCall(
                        id = block.optString("id").ifBlank { "call_$index" },
                        name = block.optString("name"),
                        argumentsJson = block.optJSONObject("input")?.toString() ?: "{}",
                    )
                }
            }
        }

        val usage = json.optJSONObject("usage")
        return LlmResult(
            text = textParts.toString().takeIf { it.isNotBlank() },
            toolCalls = calls,
            usage = LlmUsage(
                inputTokens = usage?.optInt("input_tokens") ?: 0,
                outputTokens = usage?.optInt("output_tokens") ?: 0,
            ),
            reasoning = reasoningParts.toString().trim().takeIf { it.isNotBlank() },
        )
    }
}

class GeminiClient(
    private val baseUrl: String,
    private val apiKey: String,
    override val model: String,
    private val thinking: ThinkingLevel,
    private val transport: HttpTransport,
) : LlmClient {

    override suspend fun complete(messages: List<LlmMessage>, tools: List<LlmTool>): LlmResult {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val dialogue = messages.filter { it.role != "system" }
        val geminiThinking = geminiThinkingConfig()

        val payload = JSONObject().apply {
            if (system.isNotBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", system))),
                )
            }
            put(
                "contents",
                org.json.JSONArray(dialogue.map { message ->
                    JSONObject()
                        .put("role", if (message.role == "assistant") "model" else "user")
                        .put(
                            "parts",
                            org.json.JSONArray().put(JSONObject().put("text", message.content)),
                        )
                }),
            )
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    org.json.JSONArray().put(
                        JSONObject().put(
                            "functionDeclarations",
                            org.json.JSONArray(tools.map { tool ->
                                JSONObject()
                                    .put("name", tool.name)
                                    .put("description", tool.description)
                                    .put("parameters", JSONObject(tool.parametersJson))
                            }),
                        ),
                    ),
                )
            }
            geminiThinking?.let {
                put("generationConfig", JSONObject().put("thinkingConfig", it))
            }
        }

        val raw = transport.post(
            url = "$baseUrl/v1beta/models/$model:generateContent?key=$apiKey",
            headers = mapOf("Content-Type" to "application/json"),
            body = payload.toString(),
        )

        val json = JSONObject(raw)
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")

        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = ArrayList<LlmToolCall>()
        if (parts != null) {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                part.optString("text").takeIf { it.isNotBlank() }?.let {
                    if (part.optBoolean("thought")) reasoning.append(it) else text.append(it)
                }
                part.optJSONObject("functionCall")?.let { call ->
                    calls += LlmToolCall(
                        id = "call_$index",
                        name = call.optString("name"),
                        argumentsJson = call.optJSONObject("args")?.toString() ?: "{}",
                    )
                }
            }
        }

        val usage = json.optJSONObject("usageMetadata")
        return LlmResult(
            text = text.toString().takeIf { it.isNotBlank() },
            toolCalls = calls,
            usage = LlmUsage(
                inputTokens = usage?.optInt("promptTokenCount") ?: 0,
                outputTokens = usage?.optInt("candidatesTokenCount") ?: 0,
            ),
            reasoning = reasoning.toString().trim().takeIf { it.isNotBlank() },
        )
    }

    /**
     * Gemini 2.5 用 thinkingBudget，Gemini 3 用 thinkingLevel。
     * Pro 不能把预算降到 0，这里给一个 128 的最低映射；其它模型 off 用 0。
     */
    private fun geminiThinkingConfig(): JSONObject? {
        val isGemini3 = model.contains("gemini-3")
        val isGemini25 = model.contains("2.5")
        if (!isGemini3 && !isGemini25) return null

        return JSONObject().apply {
            if (isGemini3) {
                put(
                    "thinkingLevel",
                    when (thinking) {
                        ThinkingLevel.Off -> "minimal"
                        ThinkingLevel.Minimum -> "low"
                        ThinkingLevel.Medium -> "medium"
                        ThinkingLevel.Maximum -> "high"
                    },
                )
            } else {
                put(
                    "thinkingBudget",
                    when (thinking) {
                        ThinkingLevel.Off -> if (model.contains("pro")) 128 else 0
                        ThinkingLevel.Minimum -> 1024
                        ThinkingLevel.Medium -> 8192
                        ThinkingLevel.Maximum -> 24_576
                    },
                )
            }
            if (thinking != ThinkingLevel.Off) {
                put("includeThoughts", true)
            }
        }
    }
}
