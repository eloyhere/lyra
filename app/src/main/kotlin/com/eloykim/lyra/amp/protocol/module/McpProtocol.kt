package com.eloykim.lyra.amp.protocol.module

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson

/**
 * Exec profile 使用的最小 MCP 2026-07-28 表面（`src/module/mcp/protocol.ts` 的移植）。
 *
 * 这里只有 JSON-RPC 信封与 discovery / tools 结果类型；传输、客户端、服务端
 * 分别在 McpTransport / McpClient / McpServer。所有对象都提供 `toJson()`，
 * 伴随对象提供 `fromJson()`。
 */
object McpProtocol {

    /** 2026-07-28 无状态修订。 */
    const val VERSION: String = "2026-07-28"

    /** 客户端 legacy 握手使用的版本。 */
    const val LEGACY_VERSION: String = "2025-06-18"

    /** 服务端 `initialize` 明确支持的 legacy 版本。 */
    val LEGACY_VERSIONS: List<String> = listOf("2025-11-25", "2025-06-18")

    const val JSONRPC_VERSION: String = "2.0"
    const val META_PROTOCOL_VERSION: String = "io.modelcontextprotocol/protocolVersion"
    const val META_CLIENT_CAPABILITIES: String = "io.modelcontextprotocol/clientCapabilities"
    const val META_CLIENT_INFO: String = "io.modelcontextprotocol/clientInfo"
    const val META_WORKSPACE_EXTENSION: String = "io.amp/workspace"
}

object JsonRpcErrorCode {
    const val PARSE_ERROR: Int = -32700
    const val INVALID_REQUEST: Int = -32600
    const val METHOD_NOT_FOUND: Int = -32601
    const val INVALID_PARAMS: Int = -32602
    const val INTERNAL_ERROR: Int = -32603

    /** 服务端要求先 initialize，或提供无状态协议元数据。 */
    const val NOT_INITIALIZED: Int = -32002

    /** 真实 HTTP 服务端对信封/头部不一致报告的私有错误码。 */
    const val HEADER_MISMATCH: Int = -32020
}

/**
 * MCP 消息大小限制（README 的 workspace stdio 适配器约定）：
 * 单条消息 1 MiB，宿主配置 64 MiB。安卓侧不读 stdin，但限制在适配器与服务端
 * seam 上仍然生效。
 */
object McpLimits {

    const val MAX_MESSAGE_BYTES: Int = 1024 * 1024
    const val MAX_CONFIG_BYTES: Long = 64L * 1024 * 1024

    fun messageBytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    fun requireMessageSize(text: String) {
        require(messageBytes(text) <= MAX_MESSAGE_BYTES) { "request exceeds 1 MiB" }
    }

    fun requireConfigSize(bytes: Long) {
        require(bytes <= MAX_CONFIG_BYTES) { "configuration exceeds 64 MiB" }
    }
}

/** JSON-RPC id：安全整数或字符串；通知没有 id。 */
sealed interface JsonRpcId {

    fun toJson(): JsonValue

    data class Num(val value: Long) : JsonRpcId {
        override fun toJson(): JsonValue = jsonNumber(value)
    }

    data class Str(val value: String) : JsonRpcId {
        override fun toJson(): JsonValue = JsonValue.Str(value)
    }

    companion object {

        private const val MAX_SAFE_INTEGER: Double = 9_007_199_254_740_991.0

        fun fromJson(value: JsonValue): JsonRpcId = when (value) {
            is JsonValue.Str -> Str(value.value)
            is JsonValue.Num -> {
                val number: Double = value.value
                require(number.isFinite() && number == Math.floor(number) && Math.abs(number) <= MAX_SAFE_INTEGER) {
                    "JSON-RPC id 必须是安全整数或字符串"
                }
                Num(number.toLong())
            }

            else -> throw IllegalArgumentException("JSON-RPC id 必须是安全整数或字符串")
        }

        fun fromJsonOrNull(value: JsonValue?): JsonRpcId? =
            if (value == null || value is JsonValue.Null) null else fromJson(value)
    }
}

data class JsonRpcRequest(
    val method: String,
    /** 通知（例如 `notifications/initialized`）没有 id。 */
    val id: JsonRpcId? = null,
    val params: JsonValue.Obj? = null,
) {

    val isNotification: Boolean
        get() = id == null

    fun toJson(): JsonValue.Obj = jsonObj(
        "jsonrpc" to JsonValue.Str(McpProtocol.JSONRPC_VERSION),
        "id" to id?.toJson(),
        "method" to JsonValue.Str(method),
        "params" to params,
    )

    companion object {

        fun notification(method: String, params: JsonValue.Obj? = null): JsonRpcRequest =
            JsonRpcRequest(method = method, id = null, params = params)

        fun fromJson(value: JsonValue): JsonRpcRequest {
            val obj: JsonValue.Obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("JSON-RPC 请求必须是对象")
            require(obj.string("jsonrpc") == McpProtocol.JSONRPC_VERSION) { "jsonrpc 必须是 2.0" }
            val method: String = obj.requireString("method")
            require(method.isNotEmpty()) { "method 不能为空" }
            val params: JsonValue.Obj? = obj["params"]?.let {
                it as? JsonValue.Obj ?: throw IllegalArgumentException("params 必须是对象")
            }
            val id: JsonRpcId? = if (obj.has("id")) {
                JsonRpcId.fromJsonOrNull(obj["id"]) ?: throw IllegalArgumentException("id 不能为 null")
            } else {
                null
            }
            return JsonRpcRequest(method = method, id = id, params = params)
        }
    }
}

data class JsonRpcErrorBody(
    val code: Int,
    val message: String,
    val data: JsonValue? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "code" to jsonNumber(code),
        "message" to JsonValue.Str(message),
        "data" to data,
    )

    companion object {
        fun fromJson(value: JsonValue): JsonRpcErrorBody {
            val obj: JsonValue.Obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("JSON-RPC error 必须是对象")
            return JsonRpcErrorBody(
                code = obj.requireInt("code"),
                message = obj.requireString("message"),
                data = obj["data"],
            )
        }
    }
}

data class JsonRpcResponse(
    val id: JsonRpcId? = null,
    val result: JsonValue? = null,
    val error: JsonRpcErrorBody? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "jsonrpc" to JsonValue.Str(McpProtocol.JSONRPC_VERSION),
        "id" to (id?.toJson() ?: JsonValue.Null),
        "result" to result,
        "error" to error?.toJson(),
    )

    companion object {

        fun success(id: JsonRpcId?, result: JsonValue): JsonRpcResponse =
            JsonRpcResponse(id = id, result = result, error = null)

        fun failure(id: JsonRpcId?, code: Int, message: String, data: JsonValue? = null): JsonRpcResponse =
            JsonRpcResponse(id = id, result = null, error = JsonRpcErrorBody(code, message, data))

        fun fromJson(value: JsonValue): JsonRpcResponse {
            val obj: JsonValue.Obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("JSON-RPC 响应必须是对象")
            require(obj.string("jsonrpc") == McpProtocol.JSONRPC_VERSION) { "jsonrpc 必须是 2.0" }
            val id: JsonRpcId? = if (obj.has("id")) JsonRpcId.fromJsonOrNull(obj["id"]) else null
            val error: JsonRpcErrorBody? = obj.obj("error")?.let { JsonRpcErrorBody.fromJson(it) }
            return JsonRpcResponse(id = id, result = obj["result"], error = error)
        }
    }
}

/** MCP 协议级错误；客户端把 `error` 分支转换成这个异常。 */
class McpProtocolError(
    val code: Int,
    message: String,
    val data: JsonValue? = null,
) : RuntimeException(message)

data class McpTool(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val inputSchema: JsonValue.Obj,
    val annotations: JsonValue.Obj? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "name" to JsonValue.Str(name),
        "title" to title?.let { JsonValue.Str(it) },
        "description" to description?.let { JsonValue.Str(it) },
        "inputSchema" to inputSchema,
        "annotations" to annotations,
    )

    companion object {
        fun fromJson(value: JsonValue): McpTool {
            val obj: JsonValue.Obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("MCP tool 必须是对象")
            return McpTool(
                name = obj.requireString("name"),
                title = obj.string("title"),
                description = obj.string("description"),
                inputSchema = obj.requireObj("inputSchema"),
                annotations = obj.obj("annotations"),
            )
        }
    }
}

data class DiscoveryResult(
    val supportedVersions: List<String>,
    val capabilities: JsonValue.Obj = JsonValue.Obj(),
    val ttlMs: Long = 0L,
    val cacheScope: String = "private",
    val instructions: String? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "resultType" to JsonValue.Str("complete"),
        "supportedVersions" to jsonStrings(supportedVersions),
        "capabilities" to capabilities,
        "instructions" to instructions?.let { JsonValue.Str(it) },
        "ttlMs" to jsonNumber(ttlMs),
        "cacheScope" to JsonValue.Str(cacheScope),
    )

    companion object {

        /**
         * 2026-07-28 的 discovery 结果。`ttlMs` / `cacheScope` 在 legacy
         * 服务端不存在，按参考实现的运行时行为保持可选（不默认必需字段）。
         */
        fun fromJson(value: JsonValue): DiscoveryResult {
            val obj: JsonValue.Obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("server/discover 结果必须是对象")
            val versions: List<String> = obj.arr("supportedVersions")?.items?.map { item ->
                (item as? JsonValue.Str)?.value
                    ?: throw IllegalArgumentException("supportedVersions 只能是字符串")
            } ?: throw IllegalArgumentException("缺少数组字段 supportedVersions")
            return DiscoveryResult(
                supportedVersions = versions,
                capabilities = obj.obj("capabilities") ?: JsonValue.Obj(),
                ttlMs = obj.long("ttlMs") ?: 0L,
                cacheScope = obj.string("cacheScope") ?: "private",
                instructions = obj.string("instructions"),
            )
        }
    }
}

data class ListToolsResult(
    val tools: List<McpTool>,
    val ttlMs: Long = 0L,
    val cacheScope: String = "private",
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "resultType" to JsonValue.Str("complete"),
        "tools" to JsonValue.Arr(tools.map { it.toJson() }),
        "ttlMs" to jsonNumber(ttlMs),
        "cacheScope" to JsonValue.Str(cacheScope),
    )

    companion object {
        fun fromJson(value: JsonValue): ListToolsResult {
            val obj: JsonValue.Obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("tools/list 结果必须是对象")
            val tools: List<McpTool> = obj.requireArr("tools").items.map { McpTool.fromJson(it) }
            return ListToolsResult(
                tools = tools,
                ttlMs = obj.long("ttlMs") ?: 0L,
                cacheScope = obj.string("cacheScope") ?: "private",
            )
        }
    }
}

data class ContentBlock(
    val type: String,
    val text: String? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "type" to JsonValue.Str(type),
        "text" to text?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): ContentBlock {
            val obj: JsonValue.Obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("MCP content block 必须是对象")
            return ContentBlock(type = obj.requireString("type"), text = obj.string("text"))
        }
    }
}

data class CallToolResult(
    val content: List<ContentBlock>,
    val isError: Boolean? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "resultType" to JsonValue.Str("complete"),
        "content" to JsonValue.Arr(content.map { it.toJson() }),
        "isError" to isError?.let { JsonValue.Bool(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): CallToolResult {
            val obj: JsonValue.Obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("tools/call 结果必须是对象")
            val content: List<ContentBlock> = obj.arr("content")?.items?.map { ContentBlock.fromJson(it) } ?: emptyList()
            return CallToolResult(content = content, isError = obj.bool("isError"))
        }
    }
}

/** 拼接工具结果里的文本块，保持参考实现的换行规则。 */
fun textOf(result: CallToolResult): String =
    result.content
        .filter { it.type == "text" && it.text != null }
        .joinToString("\n") { it.text ?: "" }

/**
 * MCP wire 编解码。
 *
 * 复用共享的 canonical JSON：输出没有多余空白、键按字典序排列，仍是合法 JSON；
 * 解析失败抛 [IllegalArgumentException]（服务端 seam 会把它转成 -32700）。
 */
object McpWire {

    fun encode(value: JsonValue): String = CanonicalJson.encode(value)

    fun encodeRequest(request: JsonRpcRequest): String = encode(request.toJson())

    fun encodeResponse(response: JsonRpcResponse): String = encode(response.toJson())

    fun parseRequest(text: String): JsonRpcRequest = JsonRpcRequest.fromJson(JsonParser.parse(text))

    fun parseResponse(text: String): JsonRpcResponse = JsonRpcResponse.fromJson(JsonParser.parse(text))
}
