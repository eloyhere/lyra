package com.eloykim.lyra.amp.protocol.manifest

import com.eloykim.lyra.amp.protocol.json.JsonParseException
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * A2A v1.0 Agent Card discovery, mirroring `agentCard.ts`.
 *
 * Cards are external A2A objects: unknown fields are preserved in [extras] so
 * re-serialising a discovered card does not silently change the bytes a
 * signature covers.
 */

data class AgentInterface(
    val url: String,
    val protocolBinding: String? = null,
    val protocolVersion: String? = null,
    val extras: Map<String, JsonValue> = emptyMap(),
) {

    fun toJson(): JsonValue.Obj {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["url"] = JsonValue.Str(url)
        protocolBinding?.let { fields["protocolBinding"] = JsonValue.Str(it) }
        protocolVersion?.let { fields["protocolVersion"] = JsonValue.Str(it) }
        extras.forEach { (key, value) -> if (key !in KNOWN_FIELDS) fields[key] = value }
        return JsonValue.Obj(fields)
    }

    companion object {

        private val KNOWN_FIELDS = setOf("url", "protocolBinding", "protocolVersion")

        fun fromJson(value: JsonValue): AgentInterface {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("agentInterface 必须是 JSON 对象")
            return AgentInterface(
                url = obj.string("url") ?: throw IllegalArgumentException("缺少字符串字段 url"),
                protocolBinding = obj.string("protocolBinding"),
                protocolVersion = obj.string("protocolVersion"),
                extras = extrasOf(obj, KNOWN_FIELDS),
            )
        }
    }
}

data class AgentSkill(
    val id: String,
    val name: String? = null,
    val extras: Map<String, JsonValue> = emptyMap(),
) {

    fun toJson(): JsonValue.Obj {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["id"] = JsonValue.Str(id)
        name?.let { fields["name"] = JsonValue.Str(it) }
        extras.forEach { (key, value) -> if (key !in KNOWN_FIELDS) fields[key] = value }
        return JsonValue.Obj(fields)
    }

    companion object {

        private val KNOWN_FIELDS = setOf("id", "name")

        fun fromJson(value: JsonValue): AgentSkill {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("agent skill 必须是 JSON 对象")
            return AgentSkill(
                id = obj.string("id") ?: throw IllegalArgumentException("缺少字符串字段 id"),
                name = obj.string("name"),
                extras = extrasOf(obj, KNOWN_FIELDS),
            )
        }
    }
}

data class AgentCard(
    val name: String,
    val description: String? = null,
    val version: String? = null,
    val url: String? = null,
    val supportedInterfaces: List<AgentInterface>? = null,
    val capabilities: JsonValue.Obj? = null,
    val skills: List<AgentSkill>? = null,
    /** Wire fields this model does not interpret, preserved for signatures. */
    val extras: Map<String, JsonValue> = emptyMap(),
) {

    /** Top-level signatures attached to a discovered card, if any. */
    fun signatures(): List<CardSignature> =
        (extras["signatures"] as? JsonValue.Arr)?.items?.map(CardSignature::fromJson).orEmpty()

    /** Discovery payload; `signatures` is excluded because it is not signed. */
    internal fun signingPayloadJson(): JsonValue.Obj = render(dropSignatures = true)

    fun toJson(): JsonValue.Obj = render(dropSignatures = false)

    /** Adds `signatures` for the discovery document; not part of the payload. */
    fun toSignedJson(signatures: List<CardSignature>): JsonValue.Obj = render(dropSignatures = false)
        .copyWith("signatures", jsonArr(*signatures.map { it.toJson() }.toTypedArray()))

    private fun render(dropSignatures: Boolean): JsonValue.Obj {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["name"] = JsonValue.Str(name)
        description?.let { fields["description"] = JsonValue.Str(it) }
        version?.let { fields["version"] = JsonValue.Str(it) }
        url?.let { fields["url"] = JsonValue.Str(it) }
        supportedInterfaces?.let { interfaces -> fields["supportedInterfaces"] = jsonArr(*interfaces.map { it.toJson() }.toTypedArray()) }
        capabilities?.let { fields["capabilities"] = it }
        skills?.let { list -> fields["skills"] = jsonArr(*list.map { it.toJson() }.toTypedArray()) }
        extras.forEach { (key, value) ->
            if (key in KNOWN_FIELDS) return@forEach
            if (dropSignatures && key == "signatures") return@forEach
            fields[key] = value
        }
        return JsonValue.Obj(fields)
    }

    companion object {

        private val KNOWN_FIELDS =
            setOf("name", "description", "version", "url", "supportedInterfaces", "capabilities", "skills")

        fun fromJson(value: JsonValue): AgentCard {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("agent card 必须是 JSON 对象")
            return AgentCard(
                name = obj.string("name") ?: "",
                description = obj.string("description"),
                version = obj.string("version"),
                url = obj.string("url"),
                supportedInterfaces = obj.arr("supportedInterfaces")?.items?.map(AgentInterface::fromJson),
                capabilities = obj.obj("capabilities"),
                skills = obj.arr("skills")?.items?.map(AgentSkill::fromJson),
                extras = extrasOf(obj, KNOWN_FIELDS),
            )
        }
    }
}

data class CardResult(
    val ok: Boolean,
    val reasons: List<String>,
    val card: AgentCard? = null,
    val endpoint: String? = null,
)

data class CardOptions(
    val timeoutMs: Long = 5_000L,
)

/** Minimal HTTP response shape; transport is injectable so discovery is testable. */
data class CardHttpResponse(
    val status: Int,
    val body: String,
)

/** Transport boundary for agent-card discovery. */
fun interface CardFetcher {
    fun fetch(url: String, headers: Map<String, String>, timeoutMs: Long): CardHttpResponse
}

/** Default transport: `HttpURLConnection`, available on Android 29. */
object HttpCardFetcher : CardFetcher {

    override fun fetch(url: String, headers: Map<String, String>, timeoutMs: Long): CardHttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            val timeout = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return CardHttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }
}

const val WELL_KNOWN_AGENT_CARD: String = "/.well-known/agent-card.json"

/**
 * Fetches and minimally validates an Agent Card; the endpoint is derived from
 * it. Network failures become reasons, never exceptions.
 */
fun fetchAgentCard(
    baseUrl: String,
    options: CardOptions = CardOptions(),
    fetcher: CardFetcher = HttpCardFetcher,
): CardResult {
    val url = "${baseUrl.removeSuffix("/")}$WELL_KNOWN_AGENT_CARD"
    val response = try {
        fetcher.fetch(
            url,
            mapOf("accept" to "application/json", "a2a-version" to "1.0"),
            options.timeoutMs,
        )
    } catch (error: SocketTimeoutException) {
        return CardResult(ok = false, reasons = listOf("timeout"))
    } catch (error: IOException) {
        return CardResult(ok = false, reasons = listOf(error.message ?: "discovery failed"))
    } catch (error: Exception) {
        return CardResult(ok = false, reasons = listOf(error.message ?: error.toString()))
    }

    if (response.status !in 200..299) {
        return CardResult(ok = false, reasons = listOf("HTTP ${response.status} from $url"))
    }

    val card = try {
        AgentCard.fromJson(JsonParser.parse(response.body))
    } catch (error: JsonParseException) {
        return CardResult(ok = false, reasons = listOf(error.message ?: "card is not valid JSON"))
    } catch (error: IllegalArgumentException) {
        return CardResult(ok = false, reasons = listOf(error.message ?: "card is not a JSON object"))
    }

    val reasons = ArrayList<String>()
    if (card.name.isEmpty()) {
        reasons += "card has no name"
    }
    if (card.url.isNullOrEmpty() && card.supportedInterfaces.isNullOrEmpty()) {
        reasons += "card has no interface url"
    }
    val endpoint = card.url ?: card.supportedInterfaces?.firstOrNull()?.url
    return if (reasons.isEmpty() && !endpoint.isNullOrEmpty()) {
        CardResult(ok = true, reasons = emptyList(), card = card, endpoint = endpoint)
    } else {
        CardResult(ok = false, reasons = reasons, card = card)
    }
}

private fun extrasOf(obj: JsonValue.Obj, known: Set<String>): Map<String, JsonValue> =
    obj.fields.filterKeys { it !in known }
