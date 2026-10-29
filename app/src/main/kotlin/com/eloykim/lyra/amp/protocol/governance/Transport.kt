package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.crypto.AmpSigner
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.protocol.HttpRequest
import com.eloykim.lyra.amp.protocol.protocol.HttpTransport
import com.eloykim.lyra.amp.protocol.protocol.UrlConnectionHttpTransport
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import java.time.Instant
import java.util.UUID

/**
 * Transport seam for multi-agent governance messages, a faithful port of
 * `src/governance/transport.ts`.
 *
 * The bus owns validation; the transport only moves a sealed [AgentMessage].
 * Two implementations ship here:
 *   - [LoopbackTransport]: a direct call, for tests and single-process teams;
 *   - [HttpCooperationTransport]: POST a JSON envelope to an endpoint and
 *     require a [Delivery] back.
 *
 * HTTP can be authenticated with Ed25519. The sender signs
 * `{message, signer, timestamp, nonce}`; the handler checks the signature, the
 * clock skew and a replay window before it calls [CooperationBus.receive]. The
 * message's own credential still authorises the task; transport authentication
 * is a separate layer.
 *
 * 签名与公钥解析复用同包的 `Signing.kt`（`signCanonicalValue` /
 * `verifyCanonicalValue` / `GovernanceKeyring`），因此传输认证与治理签章使用同一
 * 份 Ed25519 适配层。
 *
 * Android 29：默认 HTTP 客户端是 [UrlConnectionHttpTransport]（`HttpURLConnection`），
 * 不使用 `java.net.http`。
 */

/** Moves one sealed message; the bus decides whether to admit it. */
interface CooperationTransport {
    fun deliver(message: AgentMessage): Delivery
}

/** Direct in-process delivery. */
class LoopbackTransport(private val bus: CooperationBus) : CooperationTransport {

    override fun deliver(message: AgentMessage): Delivery = bus.receive(message)
}

/** The sender's transport identity. */
data class HttpAuth(
    val signer: String,
    val key: AmpSigner,
)

/** Client options for the HTTP transport. */
data class HttpCooperationOptions(
    val endpoint: String,
    val timeoutMs: Int = 15_000,
    val auth: HttpAuth? = null,
    /** Injectable for tests; defaults to the Android-29-safe URLConnection client. */
    val transport: HttpTransport = UrlConnectionHttpTransport(),
    val nowMs: () -> Long = { System.currentTimeMillis() },
)

/** The transport authentication block on the wire. */
data class WireAuth(
    val signer: String,
    val kid: String,
    val timestamp: String,
    val nonce: String,
    val signature: String,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "signer" to jsonText(signer),
        "kid" to jsonText(kid),
        "timestamp" to jsonText(timestamp),
        "nonce" to jsonText(nonce),
        "signature" to jsonText(signature),
    )

    companion object {
        fun fromJsonOrNull(value: JsonValue): WireAuth? {
            if (value !is JsonValue.Obj) {
                return null
            }
            return WireAuth(
                signer = value.string("signer") ?: return null,
                kid = value.string("kid") ?: "",
                timestamp = value.string("timestamp") ?: return null,
                nonce = value.string("nonce") ?: return null,
                signature = value.string("signature") ?: return null,
            )
        }
    }
}

/** The handler's HTTP result. */
data class CooperationHttpResponse(
    val status: Int,
    val body: Delivery,
)

/** Server-side authentication options. */
data class CooperationHandlerOptions(
    val keyring: GovernanceKeyring? = null,
    val maxSkewMs: Long = 60_000,
    val replayWindowMs: Long = 300_000,
    val now: () -> Long = { System.currentTimeMillis() },
)

/**
 * Nonce replay window. Entries expire after the window, so the guard does not
 * grow without bound.
 */
class ReplayGuard {

    private val seen = LinkedHashMap<String, Long>()

    fun accept(nonce: String, now: Long, windowMs: Long): Boolean {
        val expired = seen.entries.filter { it.value <= now }.map { it.key }
        expired.forEach { seen.remove(it) }
        if (seen.containsKey(nonce)) {
            return false
        }
        seen[nonce] = now + windowMs
        return true
    }
}

/** One handler function: parse an envelope, admit or reject it. */
fun interface CooperationHandler {
    fun handle(body: JsonValue): CooperationHttpResponse
}

/**
 * The HTTP client transport.
 *
 * `deliver` never throws: a network failure becomes a `transport-error`
 * delivery, exactly like the reference's catch block.
 */
class HttpCooperationTransport(private val options: HttpCooperationOptions) : CooperationTransport {

    init {
        require(options.endpoint.isNotEmpty()) { "HttpCooperationTransport requires an endpoint" }
    }

    override fun deliver(message: AgentMessage): Delivery {
        return try {
            val messageJson = message.toJson()
            val auth = options.auth?.let { auth ->
                val timestamp = Instant.ofEpochMilli(options.nowMs()).toString()
                val nonce = UUID.randomUUID().toString()
                val signed = jsonObj(
                    "message" to messageJson,
                    "signer" to jsonText(auth.signer),
                    "timestamp" to jsonText(timestamp),
                    "nonce" to jsonText(nonce),
                )
                WireAuth(
                    signer = auth.signer,
                    kid = auth.key.kid,
                    timestamp = timestamp,
                    nonce = nonce,
                    signature = signCanonicalValue(signed, auth.key),
                )
            }
            val envelope = jsonObj(
                "message" to messageJson,
                "auth" to auth?.toJson(),
            )
            val response = options.transport.execute(
                HttpRequest(
                    url = options.endpoint,
                    method = "POST",
                    headers = mapOf("content-type" to "application/json"),
                    body = CanonicalJson.encode(envelope).toByteArray(Charsets.UTF_8),
                    timeoutMs = options.timeoutMs,
                ),
            )
            val parsed = runCatching {
                JsonParser.parse(response.body.toString(Charsets.UTF_8))
            }.getOrNull()
            Delivery.fromWireOrNull(parsed ?: JsonValue.Null)
                ?: Delivery.denied(
                    DeliveryCode.TRANSPORT_ERROR,
                    "endpoint returned HTTP ${response.status} without a delivery",
                )
        } catch (error: Exception) {
            Delivery.denied(DeliveryCode.TRANSPORT_ERROR, error.message ?: error.toString())
        }
    }
}

/**
 * Server-side handler. Malformed envelopes are 400, failed authentication 401,
 * replay or clock skew 409, policy rejections 422, success 200.
 */
fun createCooperationHandler(
    bus: CooperationBus,
    options: CooperationHandlerOptions = CooperationHandlerOptions(),
): CooperationHandler {
    val replay = ReplayGuard()
    val now = options.now
    val maxSkewMs = options.maxSkewMs.coerceAtLeast(1_000)
    val replayWindowMs = options.replayWindowMs.coerceAtLeast(maxSkewMs)
    return CooperationHandler { body ->
        if (body !is JsonValue.Obj || body["message"] !is JsonValue.Obj) {
            return@CooperationHandler CooperationHttpResponse(
                400,
                Delivery.denied(DeliveryCode.MANIFEST_INVALID, "expected { message: AgentMessage }"),
            )
        }
        val keyring = options.keyring
        if (keyring != null) {
            val auth = WireAuth.fromJsonOrNull(body["auth"] ?: JsonValue.Null)
                ?: return@CooperationHandler CooperationHttpResponse(
                    401,
                    Delivery.denied(DeliveryCode.TRANSPORT_ERROR, "missing transport authentication"),
                )
            val current = now()
            val candidates = keyring.candidates(auth.signer, auth.kid.ifEmpty { null }, current)
            if (candidates.isEmpty()) {
                return@CooperationHandler CooperationHttpResponse(
                    401,
                    Delivery.denied(
                        DeliveryCode.TRANSPORT_ERROR,
                        "unknown or revoked transport signer ${auth.signer}",
                    ),
                )
            }
            val timestamp = runCatching { Instant.parse(auth.timestamp).toEpochMilli() }.getOrNull()
            if (timestamp == null || Math.abs(current - timestamp) > maxSkewMs) {
                return@CooperationHandler CooperationHttpResponse(
                    409,
                    Delivery.denied(
                        DeliveryCode.TRANSPORT_ERROR,
                        "transport timestamp is outside the allowed skew",
                    ),
                )
            }
            if (!replay.accept(auth.nonce, current, replayWindowMs)) {
                return@CooperationHandler CooperationHttpResponse(
                    409,
                    Delivery.denied(DeliveryCode.TRANSPORT_ERROR, "transport nonce was already used"),
                )
            }
            val signed = jsonObj(
                "message" to body.requireObj("message"),
                "signer" to jsonText(auth.signer),
                "timestamp" to jsonText(auth.timestamp),
                "nonce" to jsonText(auth.nonce),
            )
            val valid = candidates.any { key ->
                verifyCanonicalValue(signed, auth.signature, key)
            }
            if (!valid) {
                return@CooperationHandler CooperationHttpResponse(
                    401,
                    Delivery.denied(DeliveryCode.TRANSPORT_ERROR, "transport signature does not verify"),
                )
            }
        }
        val message = runCatching {
            AgentMessage.fromJson(body.requireObj("message"))
        }.getOrElse { error ->
            return@CooperationHandler CooperationHttpResponse(
                422,
                Delivery.denied(
                    DeliveryCode.MANIFEST_INVALID,
                    "malformed agent message: ${error.message}",
                ),
            )
        }
        val delivery = bus.receive(message)
        CooperationHttpResponse(if (delivery.ok) 200 else 422, delivery)
    }
}
