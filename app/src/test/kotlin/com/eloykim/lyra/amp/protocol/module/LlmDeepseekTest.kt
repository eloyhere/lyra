package com.eloykim.lyra.amp.protocol.module

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LlmDeepseekTest {

    private val noHttp: DeepSeekHttpClient = DeepSeekHttpClient { _, _, _, _ ->
        throw IllegalStateException("HTTP 不应被调用")
    }

    private val simpleRequest: LlmRequest =
        LlmRequest("m", listOf(LlmMessage(LlmRole.USER, "hi")))

    private fun responseOf(text: String): LlmResponse =
        LlmResponse(text, LlmUsage(1, 2), LlmFinishReason.STOP)

    @Test
    fun `scripted llm 顺序返回并统计调用`() {
        val llm = ScriptedLlm(listOf(responseOf("a"), responseOf("b")))
        assertEquals("a", llm.complete(simpleRequest).text)
        assertEquals("b", llm.complete(simpleRequest).text)
        assertEquals(2, llm.calls)
        try {
            llm.complete(simpleRequest)
            fail("队列耗尽后应抛错")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("no response left"))
        }
    }

    @Test
    fun `deepseek 请求体映射消息工具与参数`() {
        val llm = DeepSeekLlm(DeepSeekOptions(apiKey = "key"), noHttp)
        val request = LlmRequest(
            model = "",
            messages = listOf(
                LlmMessage(LlmRole.USER, "hello"),
                LlmMessage(
                    LlmRole.ASSISTANT,
                    "",
                    toolCalls = listOf(LlmToolCall("call.1", "logs.query", """{"window":"pre"}""")),
                ),
                LlmMessage(LlmRole.TOOL, "rows", toolCallId = "call.1", name = "logs_query"),
            ),
            maxTokens = 64,
            temperature = 0.2,
            tools = listOf(
                LlmToolDefinition(
                    "logs.query",
                    "查询日志",
                    JsonParser.parse("""{"type":"object"}""") as JsonValue.Obj,
                ),
            ),
        )
        val body = llm.requestBody(request)
        assertEquals("deepseek-flash", body.string("model"))
        assertEquals(0.2, body.double("temperature")!!, 1e-9)
        assertEquals(64, body.int("max_tokens"))
        assertEquals("disabled", body.obj("thinking")?.string("type"))
        assertEquals(false, body.bool("stream"))

        val messages = body.requireArr("messages")
        assertEquals(3, messages.size)
        assertEquals("hello", messages.obj(0)?.string("content"))
        val assistant = messages.obj(1)!!
        assertEquals(JsonValue.Null, assistant["content"])
        val call = assistant.requireArr("tool_calls").obj(0)!!
        assertEquals("call.1", call.string("id"))
        assertEquals("function", call.string("type"))
        assertEquals("logs.query", call.obj("function")?.string("name"))
        assertEquals("""{"window":"pre"}""", call.obj("function")?.string("arguments"))
        val tool = messages.obj(2)!!
        assertEquals("call.1", tool.string("tool_call_id"))
        assertEquals("logs_query", tool.string("name"))
        val definition = body.requireArr("tools").obj(0)!!
        assertEquals("function", definition.string("type"))
        assertEquals("logs.query", definition.obj("function")?.string("name"))
    }

    @Test
    fun `deepseek 解析工具调用与 usage`() {
        val llm = DeepSeekLlm(DeepSeekOptions(apiKey = "key"), noHttp)
        val response = llm.parseResponse(
            """{"choices":[{"message":{"content":null,"tool_calls":[""" +
                """{"id":"c.1","function":{"name":"logs.query","arguments":"{\"window\":\"post\"}"}},""" +
                """{"id":"c.2"}]},"finish_reason":"tool_calls"}],""" +
                """"usage":{"prompt_tokens":1000,"completion_tokens":40,"prompt_cache_hit_tokens":200}}""",
        )
        assertEquals("", response.text)
        assertEquals(LlmFinishReason.TOOL_CALL, response.finishReason)
        val calls = response.toolCalls!!
        assertEquals(2, calls.size)
        assertEquals("logs.query", calls[0].name)
        assertEquals("""{"window":"post"}""", calls[0].arguments)
        assertEquals("", calls[1].name)
        assertEquals("{}", calls[1].arguments)
        assertEquals(1000, response.usage.inputTokens)
        assertEquals(40, response.usage.outputTokens)
        assertEquals(200, response.usage.cacheHitTokens)
        assertEquals(800, response.usage.cacheMissTokens)
        assertEquals(
            (200 * 0.003 + 800 * 0.15 + 40 * 0.6) / 1_000_000.0,
            response.usage.costUsd!!,
            1e-15,
        )
    }

    @Test
    fun `deepseek 识别 length 与空 usage`() {
        val llm = DeepSeekLlm(DeepSeekOptions(apiKey = "key"), noHttp)
        val response = llm.parseResponse(
            """{"choices":[{"message":{"content":"partial"},"finish_reason":"length"}],"usage":{}}""",
        )
        assertEquals(LlmFinishReason.LENGTH, response.finishReason)
        assertEquals("partial", response.text)
        assertEquals(0, response.usage.inputTokens)
        assertEquals(0, response.usage.cacheHitTokens)
        assertNull(response.toolCalls)
    }

    @Test
    fun `deepseek 重试 5xx 后成功`() {
        var attempts = 0
        val http = DeepSeekHttpClient { _, _, _, _ ->
            attempts += 1
            if (attempts == 1) {
                DeepSeekHttpResponse(503, """{"error":{"message":"busy"}}""")
            } else {
                DeepSeekHttpResponse(
                    200,
                    """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],""" +
                        """"usage":{"prompt_tokens":10,"completion_tokens":2}}""",
                )
            }
        }
        val llm = DeepSeekLlm(DeepSeekOptions(apiKey = "key", retries = 2), http)
        val response = llm.complete(simpleRequest)
        assertEquals("ok", response.text)
        assertEquals(2, attempts)
    }

    @Test
    fun `deepseek 不重试 400`() {
        var attempts = 0
        val http = DeepSeekHttpClient { _, _, _, _ ->
            attempts += 1
            DeepSeekHttpResponse(400, """{"error":{"message":"bad request"}}""")
        }
        val llm = DeepSeekLlm(DeepSeekOptions(apiKey = "key", retries = 2), http)
        try {
            llm.complete(simpleRequest)
            fail("400 应直接抛错")
        } catch (error: DeepSeekError) {
            assertEquals(400, error.status)
            assertFalse(error.retryable)
            assertTrue(error.message!!.contains("DeepSeek HTTP 400: bad request"))
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `deepseek 超时映射为可重试错误`() {
        val http = DeepSeekHttpClient { _, _, _, _ -> throw DeepSeekTimeoutException("aborted") }
        val llm = DeepSeekLlm(DeepSeekOptions(apiKey = "key", timeoutMs = 1234, retries = 0), http)
        try {
            llm.complete(simpleRequest)
            fail("超时应抛错")
        } catch (error: DeepSeekError) {
            assertEquals(0, error.status)
            assertTrue(error.retryable)
            assertEquals("DeepSeek timeout after 1234ms", error.message)
        }
    }

    @Test
    fun `deepseek 要求非空密钥并从显式环境读取`() {
        try {
            DeepSeekLlm(DeepSeekOptions(apiKey = "  "), noHttp)
            fail("空密钥应被拒绝")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("apiKey"))
        }
        try {
            DeepSeekLlm.fromEnv(emptyMap(), noHttp)
            fail("缺少 DEEPSEEK_API_KEY 应被拒绝")
        } catch (error: IllegalArgumentException) {
            assertEquals("DEEPSEEK_API_KEY is not set", error.message)
        }
        val llm = DeepSeekLlm.fromEnv(
            mapOf("DEEPSEEK_API_KEY" to "env-key", "DEEPSEEK_MODEL" to "deepseek-reasoner"),
            noHttp,
        )
        val body = llm.requestBody(
            LlmRequest("", listOf(LlmMessage(LlmRole.USER, "hi"))),
        )
        assertEquals("deepseek-reasoner", body.string("model"))
    }
}
