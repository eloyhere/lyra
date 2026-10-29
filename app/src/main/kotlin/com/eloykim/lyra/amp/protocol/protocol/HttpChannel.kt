package com.eloykim.lyra.amp.protocol.protocol

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.AmpWire
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale

/**
 * Minimal injectable HTTP boundary.
 *
 * The reference uses `fetch`; Android 29 has neither `java.net.http` nor a
 * suspend-friendly client dependency here, so the production adapter is
 * [UrlConnectionHttpTransport] over [HttpURLConnection]. Tests inject a fake
 * transport instead of binding a socket.
 */
data class HttpRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val timeoutMs: Int = 5_000,
)

data class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
)

interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}

/** Default Android-29-safe transport. One request per TCP connection. */
class UrlConnectionHttpTransport : HttpTransport {

    override fun execute(request: HttpRequest): HttpResponse {
        val connection = URL(request.url).openConnection() as? HttpURLConnection
            ?: throw IllegalArgumentException("不是 HTTP URL: ${request.url}")
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = request.timeoutMs
            connection.readTimeout = request.timeoutMs
            connection.useCaches = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

            val sendsBody = request.method !in setOf("GET", "HEAD") &&
                (request.body.isNotEmpty() || request.method in setOf("POST", "PUT", "PATCH"))
            if (sendsBody) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(request.body.size)
                connection.outputStream.use { it.write(request.body) }
            }

            val status = connection.responseCode
            val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            val headers = LinkedHashMap<String, String>()
            connection.headerFields.forEach { (name, values) ->
                if (name != null) headers[name.lowercase(Locale.ROOT)] = values.firstOrNull() ?: ""
            }
            return HttpResponse(status, headers, body)
        } finally {
            connection.disconnect()
        }
    }
}

/** Body cap used by both HTTP channels; matches the reference server's 1 MiB limit. */
const val DEFAULT_HTTP_BODY_BYTES: Int = 1_048_576

internal fun httpFailureReason(error: Throwable): String =
    if (error is SocketTimeoutException) "timeout" else (error.message ?: error.toString())

data class HttpChannelOptions(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Int = 5_000,
    /**
     * Retries are only safe because the idempotency key is the document digest.
     */
    val retries: Int = 0,
    val backoffMs: Long = 50,
    val maxRequestBytes: Int = DEFAULT_HTTP_BODY_BYTES,
    val maxResponseBytes: Int = DEFAULT_HTTP_BODY_BYTES,
    val transport: HttpTransport = UrlConnectionHttpTransport(),
)

/** One recorded `delegation-envelope` and its idempotency key. */
data class SentEnvelope(
    val envelope: JsonValue.Obj,
    val key: String,
)

/**
 * HTTP team channel: `POST` a `delegation-envelope` and surface the peer's
 * verdict. Port of `src/protocol/httpChannel.ts`.
 *
 * The envelope is canonical JSON rather than `JSON.stringify` order; every
 * reader parses JSON, and the idempotency key is the canonical document digest,
 * so the bytes are stable across senders.
 */
class HttpTeamChannel(options: HttpChannelOptions) : Channel {

    val url: String = options.url
    val extraHeaders: Map<String, String> = options.headers
    val timeoutMs: Int = options.timeoutMs
    val retries: Int = options.retries
    val backoffMs: Long = options.backoffMs
    val maxRequestBytes: Int = options.maxRequestBytes
    val maxResponseBytes: Int = options.maxResponseBytes

    private val transport: HttpTransport = options.transport
    private val sentEnvelopes = ArrayList<SentEnvelope>()

    /** Every envelope handed to the transport, including failed attempts. */
    val sent: List<SentEnvelope> get() = sentEnvelopes

    /** Number of wire attempts, not logical sends. */
    var attempts: Int = 0
        private set

    override fun send(doc: DelegationDoc): ChannelVerdict {
        val envelope = jsonObj(
            "amp" to jsonText(AmpWire.VERSION),
            "kind" to jsonText("delegation-envelope"),
            "delegation" to doc.toJson(),
        )
        val key = CanonicalJson.digest(doc.toJson())
        sentEnvelopes += SentEnvelope(envelope, key)

        val body = CanonicalJson.encode(envelope).toByteArray(Charsets.UTF_8)
        if (body.size > maxRequestBytes) {
            return ChannelVerdict(false, "request body exceeds $maxRequestBytes bytes")
        }

        var attempt = 0
        while (attempt <= retries) {
            attempts += 1
            val result = once(body, key)
            if (!result.retryable || attempt == retries) {
                return result.verdict
            }
            try {
                Thread.sleep(backoffDelay(attempt))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return ChannelVerdict(false, "interrupted")
            }
            attempt += 1
        }
        return ChannelVerdict(false, "exhausted without a verdict")
    }

    private fun once(body: ByteArray, key: String): Attempt {
        val headers = LinkedHashMap<String, String>()
        headers["content-type"] = "application/json"
        headers["idempotency-key"] = key
        headers.putAll(extraHeaders)
        return try {
            val response = transport.execute(HttpRequest(url, "POST", headers, body, timeoutMs))
            if (response.status >= 500) {
                return Attempt(ChannelVerdict(false, "HTTP ${response.status}"), retryable = true)
            }
            if (response.status !in 200..299) {
                return Attempt(ChannelVerdict(false, "HTTP ${response.status}"), retryable = false)
            }
            if (response.body.size > maxResponseBytes) {
                return Attempt(
                    ChannelVerdict(false, "response body exceeds $maxResponseBytes bytes"),
                    retryable = false,
                )
            }
            val parsed = JsonParser.parse(response.body.toString(Charsets.UTF_8))
            if (parsed !is JsonValue.Obj) {
                return Attempt(ChannelVerdict(false, null), retryable = false)
            }
            Attempt(
                ChannelVerdict(
                    accepted = parsed.bool("accepted") == true,
                    reason = parsed.string("reason"),
                ),
                retryable = false,
            )
        } catch (error: Exception) {
            Attempt(ChannelVerdict(false, httpFailureReason(error)), retryable = true)
        }
    }

    private fun backoffDelay(attempt: Int): Long =
        backoffMs.coerceAtLeast(0) * (1L shl attempt.coerceIn(0, 30))

    private data class Attempt(val verdict: ChannelVerdict, val retryable: Boolean)
}
