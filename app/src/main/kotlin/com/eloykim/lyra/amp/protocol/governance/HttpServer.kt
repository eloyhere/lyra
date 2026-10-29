package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Local HTTP host for the cooperation bus, a faithful port of
 * `src/governance/httpServer.ts`.
 *
 * This is the smallest real server that can sit behind
 * [HttpCooperationTransport]: POST a JSON envelope, receive a [Delivery] body.
 * Authentication, clock skew and replay are handled by the transport handler;
 * the server only enforces method, path and body size.
 *
 * 参考实现基于 Node `http`；Android 29 没有等价的轻量服务器，这里用
 * [ServerSocket] 实现一个最小 HTTP/1.1 端点（单请求、`Content-Length`、连接关闭）。
 *
 * 需要的接口只有 [CooperationHandler]：`HttpServer.kt` 与 `Transport.kt` 通过
 * 它解耦，宿主也可以把 `createCooperationHandler(bus, options)` 挂到自己的
 * 服务器（NanoHTTPD、Ktor、测试桩）上。
 */

/** Default body cap, matching the reference's 1 MiB. */
const val DEFAULT_COOPERATION_BODY_BYTES: Int = 1_048_576

/** Server options: transport authentication plus the bind address. */
data class CooperationServerOptions(
    val keyring: GovernanceKeyring? = null,
    val maxSkewMs: Long = 60_000,
    val replayWindowMs: Long = 300_000,
    val now: () -> Long = { System.currentTimeMillis() },
    val host: String = "127.0.0.1",
    /** 0 binds an ephemeral port, like the reference's default. */
    val port: Int = 0,
    val path: String = "/cooperation",
    val maxBodyBytes: Int = DEFAULT_COOPERATION_BODY_BYTES,
)

/** A bound server; [close] stops the accept loop. */
class RunningCooperationServer internal constructor(
    private val server: ServerSocket,
    val port: Int,
    val url: String,
    private val acceptThread: Thread,
) {

    fun close() {
        runCatching { server.close() }
        acceptThread.interrupt()
    }
}

/**
 * Start the cooperation server. The returned URL is the exact endpoint the
 * client transport should POST to.
 */
fun startCooperationServer(
    bus: CooperationBus,
    options: CooperationServerOptions = CooperationServerOptions(),
): RunningCooperationServer {
    val handler = createCooperationHandler(
        bus,
        CooperationHandlerOptions(
            keyring = options.keyring,
            maxSkewMs = options.maxSkewMs,
            replayWindowMs = options.replayWindowMs,
            now = options.now,
        ),
    )
    val server = ServerSocket()
    server.reuseAddress = true
    server.bind(InetSocketAddress(options.host, options.port))
    val port = server.localPort
    val thread = Thread(
        {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    serveConnection(socket, handler, options)
                } catch (error: Exception) {
                    runCatching {
                        writeResponse(
                            socket.getOutputStream(),
                            400,
                            Delivery.denied(
                                DeliveryCode.TRANSPORT_ERROR,
                                error.message ?: error.toString(),
                            ),
                        )
                    }
                } finally {
                    runCatching { socket.close() }
                }
            }
        },
        "amp-cooperation-server",
    )
    thread.isDaemon = true
    thread.start()
    return RunningCooperationServer(
        server = server,
        port = port,
        url = "http://${options.host}:$port${options.path}",
        acceptThread = thread,
    )
}

private fun serveConnection(socket: Socket, handler: CooperationHandler, options: CooperationServerOptions) {
    socket.soTimeout = 10_000
    val input = socket.getInputStream()
    val requestLine = readLine(input)
    if (requestLine.isNullOrBlank()) {
        writeResponse(
            socket.getOutputStream(),
            400,
            Delivery.denied(DeliveryCode.TRANSPORT_ERROR, "empty HTTP request"),
        )
        return
    }
    val parts = requestLine.trim().split(" ").filter { it.isNotEmpty() }
    val method = parts.getOrNull(0) ?: ""
    val path = parts.getOrNull(1) ?: ""
    val headers = LinkedHashMap<String, String>()
    while (true) {
        val line = readLine(input) ?: break
        if (line.isEmpty()) {
            break
        }
        val colon = line.indexOf(':')
        if (colon > 0) {
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }
    if (method != "POST" || path != options.path) {
        writeResponse(
            socket.getOutputStream(),
            404,
            Delivery.denied(DeliveryCode.TRANSPORT_ERROR, "expected POST ${options.path}"),
        )
        return
    }
    val declared = headers["content-length"]?.toIntOrNull() ?: 0
    if (declared < 0 || declared > options.maxBodyBytes) {
        writeResponse(
            socket.getOutputStream(),
            400,
            Delivery.denied(
                DeliveryCode.TRANSPORT_ERROR,
                "request body exceeds ${options.maxBodyBytes} bytes",
            ),
        )
        return
    }
    val body = readExactly(input, declared)
    if (body == null) {
        writeResponse(
            socket.getOutputStream(),
            400,
            Delivery.denied(DeliveryCode.TRANSPORT_ERROR, "truncated request body"),
        )
        return
    }
    val parsed = runCatching {
        JsonParser.parse(body.toString(StandardCharsets.UTF_8))
    }.getOrElse { error ->
        writeResponse(
            socket.getOutputStream(),
            400,
            Delivery.denied(DeliveryCode.TRANSPORT_ERROR, error.message ?: "invalid JSON body"),
        )
        return
    }
    val result = handler.handle(parsed)
    writeResponse(socket.getOutputStream(), result.status, result.body)
}

private const val MAX_HEADER_LINE_BYTES: Int = 8_192

/** Reads one CRLF line; returns null at end of stream. */
private fun readLine(input: InputStream): String? {
    val buffer = ByteArrayOutputStream()
    while (true) {
        val byte = input.read()
        if (byte < 0) {
            return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
        }
        if (byte == '\n'.code) {
            return buffer.toString("ISO-8859-1").removeSuffix("\r")
        }
        buffer.write(byte)
        if (buffer.size() > MAX_HEADER_LINE_BYTES) {
            throw IllegalArgumentException("HTTP header line exceeds $MAX_HEADER_LINE_BYTES bytes")
        }
    }
}

/** Reads exactly [length] bytes; a short read is a malformed request. */
private fun readExactly(input: InputStream, length: Int): ByteArray? {
    if (length == 0) {
        return ByteArray(0)
    }
    val buffer = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = input.read(buffer, offset, length - offset)
        if (read < 0) {
            return null
        }
        offset += read
    }
    return buffer
}

private fun writeResponse(output: OutputStream, status: Int, delivery: Delivery) {
    val body = CanonicalJson.encode(delivery.toJson()).toByteArray(StandardCharsets.UTF_8)
    val head = buildString {
        append("HTTP/1.1 ").append(status).append(' ').append(reasonFor(status)).append("\r\n")
        append("Content-Type: application/json\r\n")
        append("Content-Length: ").append(body.size).append("\r\n")
        append("Connection: close\r\n\r\n")
    }
    output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
    output.write(body)
    output.flush()
}

private fun reasonFor(status: Int): String = when (status) {
    200 -> "OK"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    404 -> "Not Found"
    409 -> "Conflict"
    422 -> "Unprocessable Entity"
    500 -> "Internal Server Error"
    else -> "Status"
}
