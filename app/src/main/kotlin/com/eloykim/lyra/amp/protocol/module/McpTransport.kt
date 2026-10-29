package com.eloykim.lyra.amp.protocol.module

import com.eloykim.lyra.amp.protocol.json.JsonValue
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * MCP 传输端口（`src/module/mcp/transport.ts` 的移植）。
 *
 * 宿主只需要替换这一层就能从进程内切到 HTTP/stdio。参考实现的请求是无状态的：
 * 2026-07-28 不要求 initialize，也不要求 session id（legacy 握手除外）。
 */
interface McpTransport {
    fun send(request: JsonRpcRequest): JsonRpcResponse
}

/**
 * legacy 握手需要把传输层版本改成 2025-06-18（HTTP 的
 * `MCP-Protocol-Version` 头）。内存传输不实现它。
 */
interface McpProtocolVersionCarrier {
    var protocolVersion: String
}

fun interface McpHandler {
    fun handle(request: JsonRpcRequest): JsonRpcResponse
}

/**
 * 进程内传输，演示与测试使用。
 */
class MemoryTransport(private val handler: McpHandler) : McpTransport {

    val history: MutableList<JsonRpcRequest> = ArrayList()

    var sent: Int = 0
        private set

    override fun send(request: JsonRpcRequest): JsonRpcResponse {
        sent += 1
        history += request
        return handler.handle(request)
    }
}

/**
 * stdio 风格的传输：换行分隔的 JSON-RPC，跑在宿主提供的流上。
 *
 * 安卓没有 stdin/stdout 进程契约，所以这里不启动进程循环、不触碰
 * `System.in`；宿主要么把 socket 流接进来，要么直接用 [McpServer] 的
 * `handleLine` seam。单条消息限制为 1 MiB（[McpLimits.MAX_MESSAGE_BYTES]）。
 *
 * 通知（没有 id）只写出、不等待响应；否则 legacy 的
 * `notifications/initialized` 会一直阻塞。
 */
class StdioTransport(
    input: InputStream,
    output: OutputStream,
    private val maxMessageBytes: Int = McpLimits.MAX_MESSAGE_BYTES,
) : McpTransport, McpProtocolVersionCarrier {

    private val reader: BufferedReader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
    private val writer: BufferedWriter = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))

    override var protocolVersion: String = McpProtocol.VERSION

    var sent: Int = 0
        private set

    override fun send(request: JsonRpcRequest): JsonRpcResponse {
        val line: String = McpWire.encodeRequest(request)
        require(McpLimits.messageBytes(line) <= maxMessageBytes) { "request exceeds 1 MiB" }
        writer.write(line)
        writer.write("\n")
        writer.flush()
        sent += 1
        if (request.isNotification) {
            return JsonRpcResponse.success(request.id, JsonValue.Null)
        }
        val responseLine: String = readLine() ?: throw McpProtocolError(
            JsonRpcErrorCode.INTERNAL_ERROR,
            "transport closed",
        )
        require(McpLimits.messageBytes(responseLine) <= maxMessageBytes) { "response exceeds 1 MiB" }
        return McpWire.parseResponse(responseLine)
    }

    /**
     * 读取下一条非空行。超限时丢弃到行尾并抛错，避免把一条超大消息切成
     * 多条合法请求。
     */
    fun readLine(): String? {
        while (true) {
            val builder: StringBuilder = StringBuilder()
            var bytes: Int = 0
            var overflow: Boolean = false
            var any: Boolean = false
            while (true) {
                val code: Int = reader.read()
                if (code < 0) {
                    if (!any && !overflow) return null
                    break
                }
                any = true
                if (code == '\n'.code) break
                val ch: Char = code.toChar()
                if (overflow) continue
                bytes += utf8Length(ch)
                if (bytes > maxMessageBytes) {
                    overflow = true
                    builder.setLength(0)
                } else {
                    builder.append(ch)
                }
            }
            if (overflow) {
                throw IllegalArgumentException("response exceeds 1 MiB")
            }
            val line: String = builder.toString().trimEnd('\r')
            if (line.isBlank()) continue
            return line
        }
    }

    fun close() {
        runCatching { writer.flush() }
        runCatching { reader.close() }
        runCatching { writer.close() }
    }

    private fun utf8Length(ch: Char): Int = when {
        ch.code < 0x80 -> 1
        ch.code < 0x800 -> 2
        ch.code < 0x10000 -> 3
        else -> 4
    }
}

/**
 * 宿主配置读取 seam（README：workspace stdio 的配置文件上限 64 MiB）。
 *
 * 安卓没有 `--config` 命令行；宿主把自己的配置流接进来即可。这里流式读取并
 * 在超限时立即失败，不会先把超大文件整个读进内存。
 */
object McpConfig {

    fun read(stream: InputStream, maxBytes: Long = McpLimits.MAX_CONFIG_BYTES): String {
        val output: ByteArrayOutputStream = ByteArrayOutputStream()
        val buffer: ByteArray = ByteArray(8192)
        var total: Long = 0
        while (true) {
            val read: Int = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException("configuration exceeds $maxBytes bytes")
            }
            output.write(buffer, 0, read)
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }
}
