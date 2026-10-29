package com.eloykim.lyra.amp.protocol.module

import com.eloykim.lyra.amp.protocol.json.JsonValue

/**
 * Streamable-HTTP 传输（`src/module/mcp/http.ts` 的移植）。
 *
 * 2026-07-28 的绑定把 JSON-RPC 信封镜像到头部：`MCP-Protocol-Version`、
 * `Mcp-Method`，以及 `tools/call` 的 `Mcp-Name`。缺这些头部正是真实服务端
 * （context7）报 -32020 的原因。
 *
 * 参考实现直接 `fetch`；Android 端口把网络调用抽成 [McpHttpPoster]，
 * 保留头部构造、SSE 解析、session id 捕获与 202 空 body 语义。
 */
data class HttpTransportOptions(
    val url: String,
    /** 2026-07-28 服务端会拒绝头部与信封不一致的请求。 */
    val protocolVersion: String = McpProtocol.VERSION,
    val sessionId: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Int = 20_000,
)

data class McpHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

data class McpHttpResponse(
    val status: Int,
    /** 头部名大小写不敏感；实现方可以保留服务端原始大小写。 */
    val headers: Map<String, String>,
    val body: String,
) {

    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * 宿主提供的 HTTP 注入点；必须遵守 `timeoutMs`，并在超时时抛异常。
 */
fun interface McpHttpPoster {
    fun post(request: McpHttpRequest, timeoutMs: Int): McpHttpResponse
}

/** 解析 SSE 响应体：把所有 `data:` 行拼起来，再按 JSON-RPC 响应解析。 */
fun parseSseResponse(text: String): JsonRpcResponse {
    val data: String = text
        .split(Regex("\\r?\\n"))
        .filter { it.startsWith("data:") }
        .map { it.substring(5).trim() }
        .joinToString("")
    require(data.isNotEmpty()) { "empty SSE response" }
    return McpWire.parseResponse(data)
}

class HttpTransport(
    options: HttpTransportOptions,
    private val poster: McpHttpPoster,
) : McpTransport, McpProtocolVersionCarrier {

    val url: String = options.url

    /** 可变：legacy 握手需要在版本协商前改成 2025-06-18。 */
    override var protocolVersion: String = options.protocolVersion

    val extraHeaders: Map<String, String> = options.headers
    val timeoutMs: Int = options.timeoutMs
    var sessionId: String? = options.sessionId

    var sent: Int = 0
        private set

    override fun send(request: JsonRpcRequest): JsonRpcResponse {
        sent += 1
        val headers: LinkedHashMap<String, String> = LinkedHashMap()
        headers["content-type"] = "application/json"
        headers["accept"] = "application/json, text/event-stream"
        headers["mcp-protocol-version"] = protocolVersion
        headers["mcp-method"] = request.method
        headers.putAll(extraHeaders)
        sessionId?.let { headers["mcp-session-id"] = it }
        val toolName: String? = request.params?.string("name")
        if (request.method == "tools/call" && toolName != null) {
            headers["mcp-name"] = toolName
        }

        val response: McpHttpResponse = poster.post(
            McpHttpRequest(url = url, headers = headers, body = McpWire.encodeRequest(request)),
            timeoutMs,
        )
        response.header("mcp-session-id")?.let { sessionId = it }

        if (response.body.trim().isEmpty()) {
            // 通知（202 Accepted）没有 body。
            return JsonRpcResponse.success(request.id, JsonValue.Null)
        }
        val contentType: String = response.header("content-type") ?: ""
        val payload: JsonRpcResponse = if (contentType.contains("text/event-stream")) {
            parseSseResponse(response.body)
        } else {
            McpWire.parseResponse(response.body)
        }
        if (response.status !in 200..299 && payload.error == null) {
            throw IllegalStateException("HTTP ${response.status}: ${response.body.take(200)}")
        }
        return payload
    }
}
