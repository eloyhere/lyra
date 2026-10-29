package com.eloykim.lyra.amp.protocol.module

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj

/**
 * 无状态 MCP 客户端（`src/module/mcp/client.ts` 的移植）。
 *
 * 2026-07-28 没有 initialize 握手、没有 session id：版本与能力在每次请求的
 * `_meta` 里携带。只有 `server/discover` 返回协议错误时才回退到 2025-06-18
 * 的 legacy 握手。
 */
enum class McpNegotiationMode(val wire: String) {
    STATELESS("stateless"),
    LEGACY("legacy"),
}

class McpClient(
    val transport: McpTransport,
    val clientName: String = "amp-reference",
) {

    var protocolVersion: String = McpProtocol.VERSION
        private set

    var legacy: Boolean = false
        private set

    private var seq: Long = 0

    private fun meta(): JsonValue.Obj = jsonObj(
        McpProtocol.META_PROTOCOL_VERSION to JsonValue.Str(protocolVersion),
        McpProtocol.META_CLIENT_CAPABILITIES to JsonValue.Obj(),
        McpProtocol.META_CLIENT_INFO to jsonObj(
            "name" to JsonValue.Str(clientName),
            "version" to JsonValue.Str("0.1.0"),
        ),
    )

    private fun request(
        method: String,
        params: JsonValue.Obj = JsonValue.Obj(),
        withMeta: Boolean = true,
    ): JsonValue {
        seq += 1
        val extraMeta: JsonValue.Obj? = params.obj("_meta")
        val merged: JsonValue.Obj = if (withMeta) {
            val mergedMeta: LinkedHashMap<String, JsonValue> = LinkedHashMap(meta().fields)
            extraMeta?.let { mergedMeta.putAll(it.fields) }
            params.copyWith("_meta", JsonValue.Obj(mergedMeta))
        } else {
            params
        }
        val response: JsonRpcResponse = transport.send(
            JsonRpcRequest(method = method, id = JsonRpcId.Num(seq), params = merged),
        )
        response.error?.let { error ->
            throw McpProtocolError(error.code, error.message, error.data)
        }
        return response.result ?: JsonValue.Null
    }

    /**
     * legacy 2025-06-18 握手；只在 `server/discover` 不可用时使用。
     */
    fun initializeLegacy() {
        protocolVersion = McpProtocol.LEGACY_VERSION
        (transport as? McpProtocolVersionCarrier)?.protocolVersion = McpProtocol.LEGACY_VERSION
        request(
            "initialize",
            jsonObj(
                "protocolVersion" to JsonValue.Str(McpProtocol.LEGACY_VERSION),
                "capabilities" to JsonValue.Obj(),
                "clientInfo" to jsonObj(
                    "name" to JsonValue.Str(clientName),
                    "version" to JsonValue.Str("0.1.0"),
                ),
            ),
            withMeta = false,
        )
        transport.send(JsonRpcRequest.notification("notifications/initialized"))
        legacy = true
    }

    /** 先尝试无状态 discovery；只有协议错误才回退到 legacy。 */
    fun negotiate(): McpNegotiationMode {
        return try {
            val discovery: DiscoveryResult = discover()
            protocolVersion = discovery.supportedVersions.firstOrNull() ?: McpProtocol.VERSION
            McpNegotiationMode.STATELESS
        } catch (error: McpProtocolError) {
            initializeLegacy()
            McpNegotiationMode.LEGACY
        }
    }

    fun discover(): DiscoveryResult = DiscoveryResult.fromJson(request("server/discover"))

    fun listTools(): ListToolsResult = ListToolsResult.fromJson(request("tools/list"))

    fun callTool(name: String, args: JsonValue.Obj): CallToolResult =
        CallToolResult.fromJson(
            request(
                "tools/call",
                jsonObj(
                    "name" to JsonValue.Str(name),
                    "arguments" to args,
                ),
            ),
        )
}
