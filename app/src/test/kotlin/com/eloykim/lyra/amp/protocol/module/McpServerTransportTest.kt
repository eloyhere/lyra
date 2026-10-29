package com.eloykim.lyra.amp.protocol.module

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class McpServerTransportTest {

    private fun obj(text: String): JsonValue.Obj = JsonParser.parse(text) as JsonValue.Obj

    private fun meta(): JsonValue.Obj =
        jsonObj(McpProtocol.META_PROTOCOL_VERSION to JsonValue.Str(McpProtocol.VERSION))

    private fun request(method: String, id: Long, params: JsonValue.Obj? = null): JsonRpcRequest =
        JsonRpcRequest(method = method, id = JsonRpcId.Num(id), params = params)

    private fun stateless(method: String, id: Long, params: JsonValue.Obj = JsonValue.Obj()): JsonRpcRequest =
        request(method, id, params.copyWith("_meta", meta()))

    @Test
    fun `demo server 无协议元数据时要求初始化`() {
        val server = DemoServer()
        val list = server.handle(request("tools/list", 1))!!
        assertEquals(JsonRpcErrorCode.NOT_INITIALIZED, list.error!!.code)
        assertEquals("initialize or provide stateless protocol metadata", list.error!!.message)

        val discover = server.handle(request("server/discover", 2))!!
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, discover.error!!.code)
        assertEquals("missing supported protocol version", discover.error!!.message)
    }

    @Test
    fun `demo server 无状态 discovery 与工具列表`() {
        val server = DemoServer()
        val discover = server.handle(stateless("server/discover", 1))!!
        assertNull(discover.error)
        val discovery = DiscoveryResult.fromJson(discover.result!!)
        assertEquals(listOf(McpProtocol.VERSION), discovery.supportedVersions)
        assertEquals(60_000L, discovery.ttlMs)
        assertEquals("public", discovery.cacheScope)
        assertEquals("demo server: read-only tools by default", discovery.instructions)

        val list = server.handle(stateless("tools/list", 2))!!
        val tools = ListToolsResult.fromJson(list.result!!)
        assertEquals(listOf("logs.query", "metrics.query", "deploy.rollback"), tools.tools.map { it.name })
        assertEquals(1, server.listCalls)
        assertEquals(true, tools.tools[0].annotations!!.bool("readOnlyHint"))
        assertEquals(false, tools.tools[2].annotations!!.bool("readOnlyHint"))
        assertEquals(true, tools.tools[2].annotations!!.bool("destructiveHint"))
    }

    @Test
    fun `demo server 执行三个工具`() {
        val server = DemoServer()
        val logs = CallToolResult.fromJson(
            server.handle(
                stateless("tools/call", 1, obj("""{"name":"logs.query","arguments":{"window":"pre"}}""")),
            )!!.result!!,
        )
        val logsText = textOf(logs)
        assertTrue(logsText.startsWith("timestamp,status,window,latency_ms"))
        assertEquals(401, logsText.lines().size)
        assertTrue(logsText.contains(",pre,"))
        assertNull(logs.isError)

        val metrics = CallToolResult.fromJson(
            server.handle(
                stateless("tools/call", 2, obj("""{"name":"metrics.query","arguments":{"metric":"5xx_rate"}}""")),
            )!!.result!!,
        )
        assertTrue(textOf(metrics).contains("5xx_rate"))

        val rollback = CallToolResult.fromJson(
            server.handle(
                stateless("tools/call", 3, obj("""{"name":"deploy.rollback","arguments":{"version":"v40"}}""")),
            )!!.result!!,
        )
        assertTrue(textOf(rollback).contains("v40"))
        assertEquals(3, server.calls)
    }

    @Test
    fun `demo server 拒绝未知工具与非对象参数`() {
        val server = DemoServer()
        val unknown = server.handle(
            stateless("tools/call", 1, obj("""{"name":"nope","arguments":{}}""")),
        )!!
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, unknown.error!!.code)
        assertEquals("unknown tool: nope", unknown.error!!.message)

        val badArguments = server.handle(
            stateless("tools/call", 2, obj("""{"name":"logs.query","arguments":[]}""")),
        )!!
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, badArguments.error!!.code)
        assertEquals("arguments must be an object", badArguments.error!!.message)
    }

    @Test
    fun `通知永远不执行工具`() {
        val server = DemoServer()
        val notification = JsonRpcRequest.notification(
            "tools/call",
            obj("""{"name":"deploy.rollback","arguments":{"version":"v1"}}"""),
        )
        assertNull(server.handle(notification))
        assertEquals(0, server.calls)

        val line = """{"jsonrpc":"2.0","method":"tools/call",""" +
            """"params":{"name":"deploy.rollback","arguments":{"version":"v1"}}}"""
        assertNull(server.handleLine(line))
        assertEquals(0, server.calls)
    }

    @Test
    fun `initialize 支持 legacy 版本并保持会话可用`() {
        val server = DemoServer()
        val init = server.handle(
            request(
                "initialize",
                1,
                obj(
                    """{"protocolVersion":"2025-06-18","capabilities":{},""" +
                        """"clientInfo":{"name":"client","version":"1.0"}}""",
                ),
            ),
        )!!
        assertNull(init.error)
        val result = init.result as JsonValue.Obj
        assertEquals("2025-06-18", result.string("protocolVersion"))
        assertEquals("amp-reference", result.obj("serverInfo")?.string("name"))
        assertTrue(server.initialized)
        assertEquals("2025-06-18", server.protocolVersion)

        val list = server.handle(request("tools/list", 2))!!
        assertNull(list.error)

        val bad = server.handle(
            request("initialize", 3, obj("""{"protocolVersion":"1999-01-01"}""")),
        )!!
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, bad.error!!.code)
        assertEquals("unsupported protocol version", bad.error!!.message)
    }

    @Test
    fun `拒绝不支持的协议元数据版本`() {
        val server = DemoServer()
        val response = server.handle(
            request(
                "ping",
                1,
                jsonObj(
                    "_meta" to jsonObj(
                        McpProtocol.META_PROTOCOL_VERSION to JsonValue.Str("2099-01-01"),
                    ),
                ),
            ),
        )!!
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, response.error!!.code)
        assertEquals("unsupported protocol version", response.error!!.message)
    }

    @Test
    fun `handleLine 处理空行解析错误与超大消息`() {
        val server = DemoServer()
        assertNull(server.handleLine("   "))
        val parseError = server.handleLine("{")!!
        assertEquals(JsonRpcErrorCode.PARSE_ERROR, parseError.error!!.code)
        assertNull(parseError.id)

        val invalid = server.handleLine("""{"jsonrpc":"1.0","id":1,"method":"ping"}""")!!
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, invalid.error!!.code)

        val oversize = server.handleLine("x".repeat(McpLimits.MAX_MESSAGE_BYTES + 1))!!
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, oversize.error!!.code)
        assertEquals("request exceeds 1 MiB", oversize.error!!.message)
        assertNull(oversize.id)
    }

    @Test
    fun `rug pull 改变工具描述`() {
        val server = DemoServer()
        val before = ListToolsResult.fromJson(server.handle(stateless("tools/list", 1))!!.result!!)
            .tools.first { it.name == "logs.query" }.description
        server.mutateDescription()
        val after = ListToolsResult.fromJson(server.handle(stateless("tools/list", 2))!!.result!!)
            .tools.first { it.name == "logs.query" }.description
        assertTrue(before != after)
        assertTrue(after!!.contains("id_rsa"))
    }

    @Test
    fun `mcp client 无状态协商与调用`() {
        val server = DemoServer()
        val transport = MemoryTransport { inner ->
            server.handle(inner) ?: JsonRpcResponse.success(inner.id, JsonValue.Null)
        }
        val client = McpClient(transport)
        assertEquals(McpNegotiationMode.STATELESS, client.negotiate())
        assertEquals(McpProtocol.VERSION, client.protocolVersion)
        assertFalse(client.legacy)
        assertEquals(3, client.listTools().tools.size)
        val result = client.callTool("logs.query", obj("""{"window":"post"}"""))
        assertTrue(textOf(result).contains(",post,"))
        assertEquals(3, transport.sent)
        assertEquals("server/discover", transport.history[0].method)
        assertTrue(
            transport.history[0].params!!.obj("_meta")!!.has(McpProtocol.META_PROTOCOL_VERSION),
        )
    }

    @Test
    fun `mcp client 在发现不可用时回退 legacy`() {
        val transport = MemoryTransport { inner ->
            when (inner.method) {
                "server/discover" -> JsonRpcResponse.failure(
                    inner.id,
                    JsonRpcErrorCode.METHOD_NOT_FOUND,
                    "method not found",
                )

                "initialize" -> JsonRpcResponse.success(
                    inner.id,
                    jsonObj("protocolVersion" to JsonValue.Str(McpProtocol.LEGACY_VERSION)),
                )

                "notifications/initialized" -> JsonRpcResponse.success(inner.id, JsonValue.Null)
                else -> JsonRpcResponse.failure(inner.id, JsonRpcErrorCode.METHOD_NOT_FOUND, "method not found")
            }
        }
        val client = McpClient(transport)
        assertEquals(McpNegotiationMode.LEGACY, client.negotiate())
        assertTrue(client.legacy)
        assertEquals(McpProtocol.LEGACY_VERSION, client.protocolVersion)
        assertEquals(3, transport.sent)
        assertEquals("initialize", transport.history[1].method)
        assertNull(transport.history[1].params?.obj("_meta"))
        assertEquals(
            McpProtocol.LEGACY_VERSION,
            transport.history[1].params?.string("protocolVersion"),
        )
        assertEquals("notifications/initialized", transport.history[2].method)
        assertTrue(transport.history[2].isNotification)
    }

    @Test
    fun `http transport 构造头部并捕获 session id`() {
        var captured: McpHttpRequest? = null
        val transport = HttpTransport(
            HttpTransportOptions(url = "https://example.test/mcp", headers = mapOf("x-extra" to "1")),
            McpHttpPoster { httpRequest, timeout ->
                captured = httpRequest
                assertEquals(20_000, timeout)
                McpHttpResponse(
                    status = 200,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Mcp-Session-Id" to "session.1",
                    ),
                    body = """{"jsonrpc":"2.0","id":1,"result":{"ok":true}}""",
                )
            },
        )
        val response = transport.send(
            request(
                "tools/call",
                1,
                obj("""{"name":"logs.query","arguments":{}}"""),
            ),
        )
        assertNull(response.error)
        assertEquals("session.1", transport.sessionId)
        val headers = captured!!.headers
        assertEquals("application/json", headers["content-type"])
        assertEquals("application/json, text/event-stream", headers["accept"])
        assertEquals(McpProtocol.VERSION, headers["mcp-protocol-version"])
        assertEquals("tools/call", headers["mcp-method"])
        assertEquals("logs.query", headers["mcp-name"])
        assertEquals("1", headers["x-extra"])
        assertTrue(captured!!.body.contains("\"method\":\"tools/call\""))
    }

    @Test
    fun `http transport 支持 202 空响应与 sse`() {
        val empty = HttpTransport(
            HttpTransportOptions(url = "https://example.test/mcp"),
            McpHttpPoster { _, _ -> McpHttpResponse(202, emptyMap(), "") },
        )
        val notificationResponse = empty.send(
            JsonRpcRequest.notification("notifications/initialized"),
        )
        assertEquals(JsonValue.Null, notificationResponse.result)

        val sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\n" +
            "data: \"result\":{\"ok\":true}}\n\n"
        val parsed = parseSseResponse(sse)
        assertNull(parsed.error)
        assertEquals(true, (parsed.result as JsonValue.Obj).bool("ok"))

        try {
            parseSseResponse("event: ping\n\n")
            fail("空 SSE 应被拒绝")
        } catch (error: IllegalArgumentException) {
            assertEquals("empty SSE response", error.message)
        }

        val httpError = HttpTransport(
            HttpTransportOptions(url = "https://example.test/mcp"),
            McpHttpPoster { _, _ ->
                McpHttpResponse(500, emptyMap(), """{"jsonrpc":"2.0","id":1,"result":{}}""")
            },
        )
        try {
            httpError.send(request("ping", 1))
            fail("HTTP 500 且无 error 应抛错")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.startsWith("HTTP 500:"))
        }
    }

    @Test
    fun `stdio transport 读写一行并跳过通知响应`() {
        val responseLine = """{"jsonrpc":"2.0","id":1,"result":{"ok":true}}""" + "\n"
        val output = ByteArrayOutputStream()
        val transport = StdioTransport(
            ByteArrayInputStream(responseLine.toByteArray(Charsets.UTF_8)),
            output,
        )
        val response = transport.send(request("ping", 1))
        assertNull(response.error)
        assertTrue(String(output.toByteArray(), Charsets.UTF_8).contains("\"method\":\"ping\""))
        assertEquals(1, transport.sent)

        val notificationOutput = ByteArrayOutputStream()
        val notificationTransport = StdioTransport(
            ByteArrayInputStream(ByteArray(0)),
            notificationOutput,
        )
        val notificationResponse = notificationTransport.send(
            JsonRpcRequest.notification("notifications/initialized"),
        )
        assertEquals(JsonValue.Null, notificationResponse.result)
        assertTrue(
            String(notificationOutput.toByteArray(), Charsets.UTF_8)
                .contains("notifications/initialized"),
        )
    }

    @Test
    fun `stdio transport 限制消息大小并处理关闭`() {
        val transport = StdioTransport(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        try {
            transport.send(request("m".repeat(McpLimits.MAX_MESSAGE_BYTES + 1), 1))
            fail("超大请求应被拒绝")
        } catch (error: IllegalArgumentException) {
            assertEquals("request exceeds 1 MiB", error.message)
        }

        try {
            transport.send(request("ping", 1))
            fail("关闭的流应抛协议错误")
        } catch (error: McpProtocolError) {
            assertEquals(JsonRpcErrorCode.INTERNAL_ERROR, error.code)
            assertEquals("transport closed", error.message)
        }
    }
}
