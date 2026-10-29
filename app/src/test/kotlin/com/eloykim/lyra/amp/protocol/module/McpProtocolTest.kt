package com.eloykim.lyra.amp.protocol.module

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class McpProtocolTest {

    private fun obj(text: String): JsonValue.Obj = JsonParser.parse(text) as JsonValue.Obj

    @Test
    fun `jsonrpc 请求与响应往返`() {
        val request = JsonRpcRequest(
            method = "tools/call",
            id = JsonRpcId.Num(7),
            params = obj("""{"name":"logs.query","arguments":{"window":"pre"}}"""),
        )
        val round = McpWire.parseRequest(McpWire.encodeRequest(request))
        assertEquals(request, round)
        assertFalse(round.isNotification)

        val notification = JsonRpcRequest.notification("notifications/initialized")
        val parsedNotification = McpWire.parseRequest(McpWire.encodeRequest(notification))
        assertNull(parsedNotification.id)
        assertTrue(parsedNotification.isNotification)

        val stringId = JsonRpcRequest("ping", JsonRpcId.Str("req.1"))
        assertEquals(stringId, McpWire.parseRequest(McpWire.encodeRequest(stringId)))

        val response = JsonRpcResponse.failure(
            JsonRpcId.Num(7),
            JsonRpcErrorCode.INVALID_PARAMS,
            "bad",
            JsonValue.Str("detail"),
        )
        val roundResponse = McpWire.parseResponse(McpWire.encodeResponse(response))
        assertEquals(JsonRpcId.Num(7), roundResponse.id)
        assertEquals(-32602, roundResponse.error!!.code)
        assertEquals("bad", roundResponse.error!!.message)
        assertEquals(JsonValue.Str("detail"), roundResponse.error!!.data)
        assertNull(roundResponse.result)
    }

    @Test
    fun `jsonrpc 信封拒绝非法版本与 id`() {
        val invalid = listOf(
            """{"jsonrpc":"1.0","method":"ping"}""",
            """{"jsonrpc":"2.0","id":null,"method":"ping"}""",
            """{"jsonrpc":"2.0","id":1.5,"method":"ping"}""",
            """{"jsonrpc":"2.0","id":9007199254740992,"method":"ping"}""",
            """{"jsonrpc":"2.0","id":1,"method":"ping","params":[]}""",
            """{"jsonrpc":"2.0","id":1}""",
            """{"jsonrpc":"2.0","id":1,"method":""}""",
        )
        invalid.forEach { text ->
            try {
                McpWire.parseRequest(text)
                fail("应拒绝 $text")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message!!.isNotEmpty())
            }
        }
    }

    @Test
    fun `mcp 工具与文本结果解析`() {
        val tool = McpTool.fromJson(
            obj(
                """{"name":"logs.query","title":"查询日志","description":"d",""" +
                    """"inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}}""",
            ),
        )
        assertEquals("logs.query", tool.name)
        assertEquals("查询日志", tool.title)
        assertEquals(true, tool.annotations!!.bool("readOnlyHint"))

        try {
            McpTool.fromJson(obj("""{"name":"x"}"""))
            fail("缺少 inputSchema 应被拒绝")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("inputSchema"))
        }

        val result = CallToolResult.fromJson(
            obj(
                """{"resultType":"complete","content":[{"type":"text","text":"a"},""" +
                    """{"type":"image","data":"x"},{"type":"text","text":"b"}],"isError":false}""",
            ),
        )
        assertEquals("a\nb", textOf(result))
        assertEquals(false, result.isError)
        assertEquals("complete", result.toJson().string("resultType"))
    }

    @Test
    fun `mcp 消息与配置大小限制`() {
        McpLimits.requireMessageSize("x".repeat(McpLimits.MAX_MESSAGE_BYTES))
        try {
            McpLimits.requireMessageSize("x".repeat(McpLimits.MAX_MESSAGE_BYTES + 1))
            fail("超过 1 MiB 应被拒绝")
        } catch (error: IllegalArgumentException) {
            assertEquals("request exceeds 1 MiB", error.message)
        }
        McpLimits.requireConfigSize(McpLimits.MAX_CONFIG_BYTES)
        try {
            McpLimits.requireConfigSize(McpLimits.MAX_CONFIG_BYTES + 1)
            fail("超过 64 MiB 应被拒绝")
        } catch (error: IllegalArgumentException) {
            assertEquals("configuration exceeds 64 MiB", error.message)
        }
        assertEquals(3, McpLimits.messageBytes("中"))
        assertEquals(64L * 1024 * 1024, McpLimits.MAX_CONFIG_BYTES)
    }

    @Test
    fun `配置读取流式执行 64 MiB 上限`() {
        val text = """{"version":1,"agentId":"a.1"}"""
        assertEquals(text, McpConfig.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
        try {
            McpConfig.read(ByteArrayInputStream(ByteArray(100)), 50)
            fail("超限配置应被拒绝")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("configuration exceeds"))
        }
    }

    @Test
    fun `legacy 结果缺少仅 2026 字段时仍可解析`() {
        val tools = ListToolsResult.fromJson(
            obj("""{"tools":[{"name":"t","inputSchema":{}}]}"""),
        )
        assertEquals(1, tools.tools.size)
        assertEquals(0L, tools.ttlMs)
        assertEquals("private", tools.cacheScope)

        val discovery = DiscoveryResult.fromJson(
            obj(
                """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}},""" +
                    """"ttlMs":60000,"cacheScope":"public"}""",
            ),
        )
        assertEquals(listOf("2026-07-28"), discovery.supportedVersions)
        assertEquals(60_000L, discovery.ttlMs)
        assertEquals("public", discovery.cacheScope)
    }
}
