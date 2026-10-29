package com.eloykim.lyra.amp.protocol.protocol

import com.eloykim.lyra.amp.protocol.crypto.AmpSigner
import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.semantic.Base64Url
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.asObject
import java.security.PublicKey

/**
 * A2A v1.0 binding: Agent Card discovery, card signatures and `message/send`.
 *
 * Port of `src/protocol/a2a.ts`, `src/manifest/agentCard.ts` and
 * `src/manifest/cardSign.ts`. The card is kept as its raw JSON object because
 * a JWS signature covers the canonical card minus `signatures`; dropping
 * unknown members would make verification disagree with the peer.
 */

const val A2A_VERSION: String = "1.0"
const val A2A_WELL_KNOWN_PATH: String = "/.well-known/agent-card.json"

enum class A2APartKind(val wire: String) {
    TEXT("text"),
    DATA("data");

    companion object {
        fun fromWire(value: String): A2APartKind =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 A2A part kind: $value")
    }
}

enum class A2ARole(val wire: String) {
    USER("user"),
    AGENT("agent");

    companion object {
        fun fromWire(value: String): A2ARole =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 A2A role: $value")
    }
}

/** One A2A part: text or structured data. */
data class A2APart(
    val kind: A2APartKind,
    val text: String? = null,
    val data: JsonValue.Obj? = null,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to jsonText(kind.wire),
        "text" to text?.let { jsonText(it) },
        "data" to data,
    )

    companion object {
        fun fromJson(value: JsonValue): A2APart {
            val obj = value.asObject("A2A part")
            return A2APart(
                kind = A2APartKind.fromWire(obj.requireString("kind")),
                text = obj.string("text"),
                data = obj.obj("data"),
            )
        }
    }
}

/** An A2A v1.0 Message. */
data class A2AMessage(
    val messageId: String,
    val role: A2ARole,
    val parts: List<A2APart>,
    val metadata: JsonValue.Obj? = null,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to jsonText("message"),
        "messageId" to jsonText(messageId),
        "role" to jsonText(role.wire),
        "parts" to JsonValue.Arr(parts.map { it.toJson() }),
        "metadata" to metadata,
    )

    companion object {
        fun fromJson(value: JsonValue): A2AMessage {
            val obj = value.asObject("A2A message")
            require(obj.requireString("kind") == "message") { "A2A message.kind 必须是 message" }
            return A2AMessage(
                messageId = obj.requireString("messageId"),
                role = A2ARole.fromWire(obj.requireString("role")),
                parts = obj.requireArr("parts").items.map { A2APart.fromJson(it) },
                metadata = obj.obj("metadata"),
            )
        }
    }
}

data class A2AParams(val message: A2AMessage) {
    fun toJson(): JsonValue.Obj = jsonObj("message" to message.toJson())

    companion object {
        fun fromJson(value: JsonValue): A2AParams =
            A2AParams(A2AMessage.fromJson(value.asObject("A2A params").requireObj("message")))
    }
}

/** JSON-RPC `message/send` request carrying an A2A Message. */
data class A2ARequest(
    val id: String,
    val params: A2AParams,
) {
    val jsonrpc: String get() = "2.0"
    val method: String get() = "message/send"

    fun toJson(): JsonValue.Obj = jsonObj(
        "jsonrpc" to jsonText(jsonrpc),
        "id" to jsonText(id),
        "method" to jsonText(method),
        "params" to params.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): A2ARequest {
            val obj = value.asObject("A2A request")
            require(obj.requireString("jsonrpc") == "2.0") { "A2A request.jsonrpc 必须是 2.0" }
            require(obj.requireString("method") == "message/send") { "A2A request.method 必须是 message/send" }
            return A2ARequest(
                id = obj.requireString("id"),
                params = A2AParams.fromJson(obj.requireObj("params")),
            )
        }
    }
}

/**
 * Real A2A v1.0 binding: JSON-RPC `message/send` carrying an A2A Message.
 *
 * The delegation rides in a `data` part plus metadata; a text part keeps the
 * request readable to a peer that does not know AMP yet.
 */
fun toA2ARequest(doc: DelegationDoc): A2ARequest {
    val message = A2AMessage(
        messageId = doc.id,
        role = A2ARole.USER,
        parts = listOf(
            A2APart(
                kind = A2APartKind.TEXT,
                text = "AMP delegation ${doc.id}: ${doc.granter} -> ${doc.delegatee} (depth ${doc.depth})",
            ),
            A2APart(
                kind = A2APartKind.DATA,
                data = jsonObj("io.amp/delegation" to doc.toJson()),
            ),
        ),
        metadata = jsonObj(
            "io.amp/delegation_digest" to jsonText(CanonicalJson.digest(doc.toJson())),
            "io.amp/version" to jsonText(AmpWire.VERSION),
        ),
    )
    return A2ARequest(id = "amp-${doc.id}", params = A2AParams(message))
}

/* ----------------------------- Agent Card ------------------------------- */

/** One advertised A2A interface. */
data class AgentInterface(
    val url: String,
    val protocolBinding: String? = null,
    val protocolVersion: String? = null,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "url" to jsonText(url),
        "protocolBinding" to protocolBinding?.let { jsonText(it) },
        "protocolVersion" to protocolVersion?.let { jsonText(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): AgentInterface {
            val obj = value.asObject("A2A interface")
            return AgentInterface(
                url = obj.requireString("url"),
                protocolBinding = obj.string("protocolBinding"),
                protocolVersion = obj.string("protocolVersion"),
            )
        }

        internal fun lenient(obj: JsonValue.Obj): AgentInterface = AgentInterface(
            url = obj.string("url") ?: "",
            protocolBinding = obj.string("protocolBinding"),
            protocolVersion = obj.string("protocolVersion"),
        )
    }
}

data class AgentSkill(
    val id: String,
    val name: String? = null,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to jsonText(id),
        "name" to name?.let { jsonText(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): AgentSkill {
            val obj = value.asObject("A2A skill")
            return AgentSkill(id = obj.requireString("id"), name = obj.string("name"))
        }
    }
}

/** JWS JSON signature (`protected` header + signature), EdDSA over the JCS card. */
data class CardSignature(
    val protectedHeader: String,
    val signature: String,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "protected" to jsonText(protectedHeader),
        "signature" to jsonText(signature),
    )

    companion object {
        fun fromJson(value: JsonValue): CardSignature {
            val obj = value.asObject("card signature")
            return CardSignature(
                protectedHeader = obj.requireString("protected"),
                signature = obj.requireString("signature"),
            )
        }
    }
}

/** A2A v1.0 Agent Card; unmodelled members stay in [raw] for signature coverage. */
class AgentCard(val raw: JsonValue.Obj) {

    val name: String? get() = raw.string("name")
    val description: String? get() = raw.string("description")
    val version: String? get() = raw.string("version")
    val url: String? get() = raw.string("url")

    val supportedInterfaces: List<AgentInterface>
        get() = raw.arr("supportedInterfaces")?.items.orEmpty()
            .mapNotNull { item ->
                val obj = item as? JsonValue.Obj ?: return@mapNotNull null
                AgentInterface.lenient(obj)
            }

    val capabilities: JsonValue.Obj? get() = raw.obj("capabilities")

    val skills: List<AgentSkill>
        get() = raw.arr("skills")?.items.orEmpty()
            .mapNotNull { item ->
                val skill = item as? JsonValue.Obj ?: return@mapNotNull null
                val id = skill.string("id") ?: return@mapNotNull null
                AgentSkill(id, skill.string("name"))
            }

    val signatures: List<CardSignature>
        get() = raw.arr("signatures")?.items.orEmpty()
            .mapNotNull { item ->
                val signature = item as? JsonValue.Obj ?: return@mapNotNull null
                val header = signature.string("protected") ?: return@mapNotNull null
                val value = signature.string("signature") ?: return@mapNotNull null
                CardSignature(header, value)
            }

    /** The endpoint the card advertises: explicit `url`, then the first interface. */
    val endpoint: String?
        get() = url ?: supportedInterfaces.firstOrNull()?.url

    fun toJson(): JsonValue.Obj = raw

    fun withSignatures(signatures: List<CardSignature>): AgentCard = AgentCard(
        raw.copyWith("signatures", JsonValue.Arr(signatures.map { it.toJson() })),
    )

    companion object {
        fun fromJson(value: JsonValue): AgentCard =
            AgentCard(value as? JsonValue.Obj ?: throw IllegalArgumentException("agent card 必须是 JSON 对象"))

        /**
         * Builds a card for local publication or tests. Omitted fields stay absent,
         * so the canonical signing payload never contains invented defaults.
         */
        fun of(
            name: String,
            description: String? = null,
            version: String? = null,
            url: String? = null,
            supportedInterfaces: List<AgentInterface> = emptyList(),
            capabilities: JsonValue.Obj? = null,
            skills: List<AgentSkill> = emptyList(),
            extra: Map<String, JsonValue> = emptyMap(),
        ): AgentCard {
            val fields = LinkedHashMap<String, JsonValue>()
            fields["name"] = jsonText(name)
            description?.let { fields["description"] = jsonText(it) }
            version?.let { fields["version"] = jsonText(it) }
            url?.let { fields["url"] = jsonText(it) }
            if (supportedInterfaces.isNotEmpty()) {
                fields["supportedInterfaces"] = JsonValue.Arr(supportedInterfaces.map { it.toJson() })
            }
            capabilities?.let { fields["capabilities"] = it }
            if (skills.isNotEmpty()) fields["skills"] = JsonValue.Arr(skills.map { it.toJson() })
            fields.putAll(extra)
            return AgentCard(JsonValue.Obj(fields))
        }
    }
}

/** Minimal validation result plus the endpoint derived from the card. */
data class CardResult(
    val ok: Boolean,
    val card: AgentCard? = null,
    val endpoint: String? = null,
    val reasons: List<String> = emptyList(),
)

data class AgentCardOptions(
    val timeoutMs: Int = 5_000,
    val maxResponseBytes: Int = DEFAULT_HTTP_BODY_BYTES,
)

/**
 * Fetches and minimally validates an Agent Card; the endpoint is derived from it.
 */
fun fetchAgentCard(
    baseUrl: String,
    options: AgentCardOptions = AgentCardOptions(),
    transport: HttpTransport = UrlConnectionHttpTransport(),
): CardResult {
    val url = baseUrl.removeSuffix("/") + A2A_WELL_KNOWN_PATH
    return try {
        val response = transport.execute(
            HttpRequest(
                url = url,
                method = "GET",
                headers = linkedMapOf("accept" to "application/json", "a2a-version" to A2A_VERSION),
                timeoutMs = options.timeoutMs,
            ),
        )
        if (response.status !in 200..299) {
            return CardResult(false, reasons = listOf("HTTP ${response.status} from $url"))
        }
        if (response.body.size > options.maxResponseBytes) {
            return CardResult(
                false,
                reasons = listOf("response body exceeds ${options.maxResponseBytes} bytes"),
            )
        }

        val parsed = JsonParser.parse(response.body.toString(Charsets.UTF_8))
        if (parsed !is JsonValue.Obj) {
            return CardResult(false, reasons = listOf("card has no name", "card has no interface url"))
        }

        val card = AgentCard(parsed)
        val reasons = ArrayList<String>()
        if (card.name.isNullOrEmpty()) reasons += "card has no name"
        if (card.url.isNullOrEmpty() && card.supportedInterfaces.isEmpty()) {
            reasons += "card has no interface url"
        }
        val endpoint = card.endpoint
        if (reasons.isEmpty() && !endpoint.isNullOrEmpty()) {
            CardResult(true, card, endpoint, reasons)
        } else {
            CardResult(false, card, null, reasons)
        }
    } catch (error: Exception) {
        CardResult(false, reasons = listOf(httpFailureReason(error)))
    }
}

/** Key lookup for card signatures; a revoked key must be absent from the map. */
fun interface CardKeyRegistry {
    fun get(kid: String): PublicKey?
}

private fun cardPayload(card: AgentCard): ByteArray =
    CanonicalJson.encode(card.raw.without("signatures")).toByteArray(Charsets.UTF_8)

private fun cardSigningInput(protectedHeader: String, payload: ByteArray): ByteArray =
    "$protectedHeader.${Base64Url.encode(payload)}".toByteArray(Charsets.UTF_8)

private fun decodingHeader(protectedHeader: String): JsonValue.Obj? = runCatching {
    JsonParser.parse(String(Base64Url.decode(protectedHeader), Charsets.UTF_8)) as? JsonValue.Obj
}.getOrNull()

/** Signs the JCS-canonicalised card with EdDSA, excluding `signatures`. */
fun signCard(card: AgentCard, key: AmpSigner): CardSignature {
    val headerJson = CanonicalJson.encode(
        jsonObj(
            "alg" to jsonText("EdDSA"),
            "kid" to jsonText(key.kid),
        ),
    )
    val protectedHeader = Base64Url.encode(headerJson.toByteArray(Charsets.UTF_8))
    val signature = key.sign(cardSigningInput(protectedHeader, cardPayload(card)))
    return CardSignature(protectedHeader, Base64Url.encode(signature))
}

/** Verifies a card signature; the protected header must say `EdDSA`. */
fun verifyCardSignature(
    card: AgentCard,
    signature: CardSignature,
    publicKey: PublicKey,
): CardVerification {
    val header = decodingHeader(signature.protectedHeader)
        ?: return CardVerification(false, listOf("protected header is not base64url JSON"))

    val reasons = ArrayList<String>()
    val alg = header.string("alg")
    if (alg != "EdDSA") reasons += "unsupported alg ${alg ?: "undefined"}"

    var malformed = false
    val valid = try {
        Ed25519.verify(
            publicKey,
            cardSigningInput(signature.protectedHeader, cardPayload(card)),
            Base64Url.decode(signature.signature),
        )
    } catch (_: Exception) {
        malformed = true
        false
    }
    if (malformed) {
        reasons += "card signature is malformed"
    } else if (!valid) {
        reasons += "card signature does not verify"
    }
    return CardVerification(reasons.isEmpty(), reasons)
}

/**
 * Resolves the signing key from a trust registry (which Federation bundles can
 * populate) instead of trusting a key embedded in the card itself.
 */
fun verifyCardWithRegistry(
    card: AgentCard,
    signature: CardSignature,
    registry: CardKeyRegistry,
): CardVerification {
    val header = decodingHeader(signature.protectedHeader)
        ?: return CardVerification(false, listOf("protected header is not base64url JSON"))
    val kid = header.string("kid")
    if (kid.isNullOrEmpty()) {
        return CardVerification(false, listOf("signature carries no kid"))
    }
    val key = registry.get(kid)
        ?: return CardVerification(false, listOf("unknown or revoked key: $kid"))
    return verifyCardSignature(card, signature, key)
}

data class CardVerification(
    val ok: Boolean,
    val reasons: List<String>,
)

/* ---------------------------- A2A channel -------------------------------- */

data class A2AChannelOptions(
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Int = 5_000,
    val maxRequestBytes: Int = DEFAULT_HTTP_BODY_BYTES,
    val maxResponseBytes: Int = DEFAULT_HTTP_BODY_BYTES,
    val transport: HttpTransport = UrlConnectionHttpTransport(),
)

/**
 * Closes the loop: use the endpoint the Agent Card advertised, not a hardcoded URL.
 */
fun channelFromCard(card: CardResult, options: A2AChannelOptions = A2AChannelOptions()): A2AChannel {
    val endpoint = card.endpoint
    if (!card.ok || endpoint.isNullOrEmpty()) {
        throw IllegalStateException("agent card has no usable endpoint")
    }
    return A2AChannel(options.copy(url = endpoint))
}

/** A2A JSON-RPC channel; records every request for audit and tests. */
class A2AChannel(options: A2AChannelOptions) : Channel {

    val url: String = options.url
    val extraHeaders: Map<String, String> = options.headers
    val timeoutMs: Int = options.timeoutMs
    val maxRequestBytes: Int = options.maxRequestBytes
    val maxResponseBytes: Int = options.maxResponseBytes

    private val transport: HttpTransport = options.transport
    private val sentRequests = ArrayList<A2ARequest>()

    /** Every A2A request handed to the transport. */
    val requests: List<A2ARequest> get() = sentRequests

    override fun send(doc: DelegationDoc): ChannelVerdict {
        val request = toA2ARequest(doc)
        sentRequests += request

        val body = CanonicalJson.encode(request.toJson()).toByteArray(Charsets.UTF_8)
        if (body.size > maxRequestBytes) {
            return ChannelVerdict(false, "request body exceeds $maxRequestBytes bytes")
        }

        val headers = LinkedHashMap<String, String>()
        headers["content-type"] = "application/json"
        headers["a2a-version"] = A2A_VERSION
        headers.putAll(extraHeaders)

        return try {
            val response = transport.execute(HttpRequest(url, "POST", headers, body, timeoutMs))
            when {
                response.status !in 200..299 -> ChannelVerdict(false, "HTTP ${response.status}")
                response.body.size > maxResponseBytes ->
                    ChannelVerdict(false, "response body exceeds $maxResponseBytes bytes")

                else -> {
                    val parsed = JsonParser.parse(response.body.toString(Charsets.UTF_8))
                    if (parsed !is JsonValue.Obj) {
                        ChannelVerdict(false, "unrecognised A2A result")
                    } else {
                        val error = parsed.obj("error")
                        val result = parsed.obj("result")
                        when {
                            error != null -> {
                                val code = error.int("code")?.toString() ?: "undefined"
                                val message = error.string("message") ?: ""
                                ChannelVerdict(false, "A2A $code: $message")
                            }

                            result?.string("kind") == "task" -> {
                                val taskId = result?.string("id") ?: ""
                                val state = result?.obj("status")?.string("state") ?: "submitted"
                                ChannelVerdict(true, "task $taskId $state")
                            }

                            result?.string("kind") == "message" ->
                                ChannelVerdict(true, "agent replied with a message")

                            else -> ChannelVerdict(false, "unrecognised A2A result")
                        }
                    }
                }
            }
        } catch (error: Exception) {
            ChannelVerdict(false, httpFailureReason(error))
        }
    }
}
