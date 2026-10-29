package com.eloykim.lyra.amp.protocol.protocol

import com.eloykim.lyra.amp.protocol.crypto.AmpSigner
import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.semantic.Base64Url
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Timestamp
import java.security.PublicKey
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * AMP v0.1 A-surface runtime: one sealed envelope for the seven performatives.
 *
 * The signature covers the canonical envelope except `sig`. Ordering, replay,
 * dedupe and cancellation are receiver-local decisions; no peer promise is
 * trusted. FIFO is scoped to `(from, to, thread)`; cross-thread ordering is not
 * guaranteed. Port of `src/protocol/envelope.ts`.
 */

/** Inclusive `major.minor` version window. Comparison is numeric, not lexical. */
data class VersionRange(
    val min: String,
    val max: String,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "min" to jsonText(min),
        "max" to jsonText(max),
    )

    companion object {
        fun fromJson(value: JsonValue): VersionRange {
            val obj = value.asObject("version")
            return VersionRange(
                min = obj.requireString("min"),
                max = obj.requireString("max"),
            )
        }
    }
}

private const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L
private const val MAX_TIMESTAMP_MS: Long = 8_640_000_000_000_000L
private val VERSION_PATTERN = Regex("^([0-9]+)\\.([0-9]+)$")
private val Random = SecureRandom()

private fun parseVersion(value: String): LongArray? {
    val match = VERSION_PATTERN.matchEntire(value) ?: return null
    val major = match.groupValues[1].toLongOrNull() ?: return null
    val minor = match.groupValues[2].toLongOrNull() ?: return null
    if (major > MAX_SAFE_INTEGER || minor > MAX_SAFE_INTEGER) return null
    return longArrayOf(major, minor)
}

private fun compareVersions(left: LongArray, right: LongArray): Int = when {
    left[0] != right[0] -> if (left[0] < right[0]) -1 else 1
    left[1] != right[1] -> if (left[1] < right[1]) -1 else 1
    else -> 0
}

private fun formatVersion(version: LongArray): String = "${version[0]}.${version[1]}"

/** Rejects malformed, inverted and non-`major.minor` ranges. */
private fun normalizeVersionRange(value: JsonValue?): VersionRange? {
    val obj = value as? JsonValue.Obj ?: return null
    val minText = obj.string("min") ?: return null
    val maxText = obj.string("max") ?: return null
    val min = parseVersion(minText) ?: return null
    val max = parseVersion(maxText) ?: return null
    if (compareVersions(min, max) > 0) return null
    return VersionRange(formatVersion(min), formatVersion(max))
}

/**
 * Intersects two inclusive `major.minor` windows.
 *
 * Versions compare numerically (`0.10` is newer than `0.9`), with no prerelease
 * or patch semantics. Malformed or inverted ranges yield `null`, as does a
 * disjoint pair such as `0.1` with `0.2`.
 */
fun negotiateVersion(local: VersionRange, remote: VersionRange): VersionRange? {
    val left = normalizeVersionRange(local.toJson()) ?: return null
    val right = normalizeVersionRange(remote.toJson()) ?: return null
    val leftMin = parseVersion(left.min) ?: return null
    val leftMax = parseVersion(left.max) ?: return null
    val rightMin = parseVersion(right.min) ?: return null
    val rightMax = parseVersion(right.max) ?: return null
    val min = if (compareVersions(leftMin, rightMin) >= 0) leftMin else rightMin
    val max = if (compareVersions(leftMax, rightMax) <= 0) leftMax else rightMax
    if (compareVersions(min, max) > 0) return null
    return VersionRange(formatVersion(min), formatVersion(max))
}

/** JavaScript `Date#toISOString()` formatting, including milliseconds. */
fun jsIsoTimestamp(epochMillis: Long): String {
    val time = Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC)
    return String.format(
        Locale.ROOT,
        "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
        time.year,
        time.monthValue,
        time.dayOfMonth,
        time.hour,
        time.minute,
        time.second,
        time.nano / 1_000_000,
    )
}

private fun timestampMsOf(value: JsonValue?): Long? {
    val text = (value as? JsonValue.Str)?.value ?: return null
    return timestampMsOf(text)
}

private fun timestampMsOf(text: String?): Long? =
    text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

private fun safeInteger(value: JsonValue?): Long? {
    val number = (value as? JsonValue.Num)?.value ?: return null
    if (!number.isFinite() || number != Math.floor(number)) return null
    if (Math.abs(number) > MAX_SAFE_INTEGER.toDouble()) return null
    return number.toLong()
}

private fun randomHex(bytes: Int): String {
    val data = ByteArray(bytes)
    Random.nextBytes(data)
    return buildString(bytes * 2) {
        data.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }
}

private val HEX = "0123456789abcdef".toCharArray()

private fun signingBytes(value: JsonValue): ByteArray =
    CanonicalJson.encode(value).toByteArray(Charsets.UTF_8)

/** The wire form of an authenticated AMP A-surface message. */
data class SealedEnvelope(
    val id: Id,
    val from: Ref,
    val to: Ref,
    val thread: Id,
    val seq: Long,
    val type: Performative,
    val sent: Timestamp,
    val nonce: String,
    val version: VersionRange,
    val payload: JsonValue,
    val kid: String,
    val sig: String,
    val expires: Timestamp? = null,
) {
    fun toJson(): JsonValue.Obj = envelopeJson(
        id = id,
        from = from,
        to = to,
        thread = thread,
        seq = seq,
        type = type,
        sent = sent,
        expires = expires,
        nonce = nonce,
        version = version,
        payload = payload,
        kid = kid,
        sig = sig,
    )

    /** The exact object the signature covers. */
    fun unsignedJson(): JsonValue.Obj = toJson().without("sig")

    companion object {
        fun fromJson(value: JsonValue): SealedEnvelope {
            val obj = value.asObject("envelope")
            require(obj.requireString("amp") == AmpWire.VERSION) { "envelope.amp 必须是 ${AmpWire.VERSION}" }
            require(obj.requireString("kind") == "envelope") { "envelope.kind 必须是 envelope" }
            val seq = safeInteger(obj["seq"]) ?: throw IllegalArgumentException("envelope.seq 必须是非负安全整数")
            require(seq >= 0) { "envelope.seq 必须是非负安全整数" }
            return SealedEnvelope(
                id = obj.requireString("id"),
                from = Ref.fromJson(obj.requireObj("from")),
                to = Ref.fromJson(obj.requireObj("to")),
                thread = obj.requireString("thread"),
                seq = seq,
                type = Performative.fromWire(obj.requireString("type")),
                sent = obj.requireString("sent"),
                nonce = obj.requireString("nonce"),
                version = VersionRange.fromJson(obj.requireObj("version")),
                payload = obj.fields["payload"] ?: throw IllegalArgumentException("缺少 payload 字段"),
                kid = obj.requireString("kid"),
                sig = obj.requireString("sig"),
                expires = obj.string("expires"),
            )
        }
    }
}

private fun envelopeJson(
    id: Id,
    from: Ref,
    to: Ref,
    thread: Id,
    seq: Long,
    type: Performative,
    sent: Timestamp,
    expires: Timestamp?,
    nonce: String,
    version: VersionRange,
    payload: JsonValue,
    kid: String,
    sig: String?,
): JsonValue.Obj = jsonObj(
    "amp" to jsonText(AmpWire.VERSION),
    "kind" to jsonText("envelope"),
    "id" to jsonText(id),
    "from" to from.toJson(),
    "to" to to.toJson(),
    "thread" to jsonText(thread),
    "seq" to jsonNumber(seq),
    "type" to jsonText(type.wire),
    "sent" to jsonText(sent),
    "expires" to expires?.let { jsonText(it) },
    "nonce" to jsonText(nonce),
    "version" to version.toJson(),
    "payload" to payload,
    "kid" to jsonText(kid),
    "sig" to sig?.let { jsonText(it) },
)

/** Caller-supplied fields; [sealEnvelope] fills every omitted default. */
data class EnvelopeInput(
    val from: Ref,
    val to: Ref,
    val thread: Id,
    val type: Performative,
    val payload: JsonValue,
    val id: Id? = null,
    val seq: Long? = null,
    val sent: Timestamp? = null,
    val ttlMs: Long? = null,
    val nonce: String? = null,
    val version: VersionRange? = null,
    val kid: String? = null,
)

/**
 * Seals one A-surface envelope with Ed25519.
 *
 * Defaults: a fresh random `id`, a 16-byte hex `nonce`, `seq` 0, an `amp` 0.1
 * version window, `kid` from the key unless explicitly supplied, `sent` from
 * the real clock and no expiry. A supplied `ttlMs` is added to `sent` to
 * produce `expires`; negative TTLs are rejected. The signature covers the
 * canonical envelope excluding only `sig`.
 */
fun sealEnvelope(input: EnvelopeInput, key: AmpSigner): SealedEnvelope {
    val ttl = input.ttlMs
    if (ttl != null && ttl < 0) {
        throw IllegalArgumentException("ttlMs must be a non-negative finite number")
    }

    val sent = input.sent ?: jsIsoTimestamp(System.currentTimeMillis())
    val sentMs = timestampMsOf(sent) ?: throw IllegalArgumentException("sent must be a valid timestamp")

    val seq = input.seq ?: 0L
    if (seq < 0 || seq > MAX_SAFE_INTEGER) {
        throw IllegalArgumentException("seq must be a non-negative safe integer")
    }

    val id = input.id ?: "m.${randomHex(16)}"
    val nonce = input.nonce ?: randomHex(16)
    val version = input.version?.let { VersionRange(it.min, it.max) }
        ?: VersionRange(AmpWire.VERSION, AmpWire.VERSION)

    val expires = ttl?.let {
        val expiresMs = try {
            Math.addExact(sentMs, it)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("expires is outside the supported timestamp range")
        }
        if (expiresMs < -MAX_TIMESTAMP_MS || expiresMs > MAX_TIMESTAMP_MS) {
            throw IllegalArgumentException("expires is outside the supported timestamp range")
        }
        jsIsoTimestamp(expiresMs)
    }

    val kid = input.kid ?: key.kid
    val unsigned = envelopeJson(
        id = id,
        from = input.from,
        to = input.to,
        thread = input.thread,
        seq = seq,
        type = input.type,
        sent = sent,
        expires = expires,
        nonce = nonce,
        version = version,
        payload = input.payload,
        kid = kid,
        sig = null,
    )
    val signature = Base64Url.encode(key.sign(signingBytes(unsigned)))
    return SealedEnvelope(
        id = id,
        from = input.from,
        to = input.to,
        thread = input.thread,
        seq = seq,
        type = input.type,
        sent = sent,
        nonce = nonce,
        version = version,
        payload = input.payload,
        kid = kid,
        sig = signature,
        expires = expires,
    )
}

/** Content digest of the signed envelope body (everything except `sig`). */
fun envelopeDigest(envelope: SealedEnvelope): Digest = envelopeDigest(envelope.toJson())

/** Content digest of a raw signed envelope body (everything except `sig`). */
fun envelopeDigest(envelope: JsonValue.Obj): Digest = CanonicalJson.digest(envelope.without("sig"))

/** Receiver outcome: `ok` is true only for [EnvelopeStatus.ACCEPTED]. */
data class EnvelopeVerification(
    val ok: Boolean,
    val reasons: List<String>,
)

/** Optional receiver settings for [verifyEnvelope]. */
data class EnvelopeVerifyOptions(
    val now: Timestamp? = null,
    val expectedVersion: VersionRange? = null,
    val clockSkewMs: Long? = null,
    val seenNonces: MutableSet<String>? = null,
)

private fun isRef(value: JsonValue?): Boolean {
    val obj = value as? JsonValue.Obj ?: return false
    if (obj["kind"] !is JsonValue.Str) return false
    return (obj["id"] as? JsonValue.Str)?.value?.isNotEmpty() == true
}

private fun normalizedSkew(value: Long?): Long = if (value != null && value > 0) value else 0L

/**
 * Verifies an envelope's structure, amp/kind, expiry (with skew), version
 * intersection, optional nonce uniqueness and Ed25519 signature.
 *
 * A nonce is added to `seenNonces` only after every check passes, so a forged
 * or expired copy cannot burn a legitimate nonce. Expiry is accepted up to and
 * including `expires + clockSkewMs`.
 */
fun verifyEnvelope(
    envelope: JsonValue,
    publicKey: PublicKey,
    options: EnvelopeVerifyOptions = EnvelopeVerifyOptions(),
): EnvelopeVerification {
    val obj = envelope as? JsonValue.Obj
        ?: return EnvelopeVerification(false, listOf("envelope is not an object"))

    val reasons = ArrayList<String>()

    if (obj.string("amp") != AmpWire.VERSION) reasons += "amp must be \"${AmpWire.VERSION}\""
    if (obj.string("kind") != "envelope") reasons += "kind must be \"envelope\""
    if (obj.string("id")?.isNotEmpty() != true) reasons += "id must be a non-empty string"
    if (!isRef(obj["from"])) reasons += "from must be a Ref with a non-empty id"
    if (!isRef(obj["to"])) reasons += "to must be a Ref with a non-empty id"
    if (obj.string("thread")?.isNotEmpty() != true) reasons += "thread must be a non-empty string"
    val seq = safeInteger(obj["seq"])
    if (seq == null || seq < 0) reasons += "seq must be a non-negative safe integer"
    if (obj.string("type")?.let { Performative.isKnown(it) } != true) reasons += "type must be one of the seven performatives"
    if (timestampMsOf(obj["sent"]) == null) reasons += "sent must be a valid timestamp"
    if (obj.has("expires") && timestampMsOf(obj["expires"]) == null) reasons += "expires must be a valid timestamp"
    if (obj.string("nonce")?.isNotEmpty() != true) reasons += "nonce must be a non-empty string"
    if (!obj.has("payload")) reasons += "payload is required"
    if (obj.string("kid")?.isNotEmpty() != true) reasons += "kid must be a non-empty string"

    val version = normalizeVersionRange(obj["version"])
    if (version == null) reasons += "version must be an inclusive major.minor range"

    val expectedRange = options.expectedVersion
    if (expectedRange != null) {
        val expected = normalizeVersionRange(expectedRange.toJson())
        if (expected == null) {
            reasons += "expectedVersion must be an inclusive major.minor range"
        } else if (version != null && negotiateVersion(version, expected) == null) {
            reasons += "version ranges do not intersect"
        }
    }

    if (obj.has("expires")) {
        val expiresMs = timestampMsOf(obj["expires"])
        if (expiresMs != null) {
            val nowMs = timestampMsOf(options.now ?: jsIsoTimestamp(System.currentTimeMillis()))
            if (nowMs == null) {
                reasons += "now must be a valid timestamp"
            } else {
                val skew = normalizedSkew(options.clockSkewMs)
                val limit = if (expiresMs > Long.MAX_VALUE - skew) Long.MAX_VALUE else expiresMs + skew
                if (nowMs > limit) reasons += "envelope has expired"
            }
        }
    }

    val nonce = obj.string("nonce")?.takeIf { it.isNotEmpty() }
    if (nonce != null && options.seenNonces?.contains(nonce) == true) {
        reasons += "nonce has already been seen"
    }

    val signature = obj.string("sig")
    if (signature == null || signature.isEmpty()) {
        reasons += "signature is missing"
    } else {
        try {
            val signatureBytes = Base64Url.decode(signature)
            if (signatureBytes.size != ED25519_SIGNATURE_BYTES) {
                reasons += "signature is malformed"
            } else if (!Ed25519.verify(publicKey, signingBytes(obj.without("sig")), signatureBytes)) {
                reasons += "signature does not verify"
            }
        } catch (_: Exception) {
            reasons += "signature is malformed"
        }
    }

    if (reasons.isEmpty() && nonce != null) {
        options.seenNonces?.add(nonce)
    }
    return EnvelopeVerification(reasons.isEmpty(), reasons)
}

private const val ED25519_SIGNATURE_BYTES: Int = 64

/** Convenience overload: verifies a typed envelope as its own wire object. */
fun verifyEnvelope(
    envelope: SealedEnvelope,
    publicKey: PublicKey,
    options: EnvelopeVerifyOptions = EnvelopeVerifyOptions(),
): EnvelopeVerification = verifyEnvelope(envelope.toJson(), publicKey, options)

/** Receiver status; `ok` is true only for `accepted`. */
enum class EnvelopeStatus(val wire: String) {
    ACCEPTED("accepted"),
    DUPLICATE("duplicate"),
    OUT_OF_ORDER("out_of_order"),
    CANCELLED("cancelled"),
    INVALID("invalid"),
}

data class EnvelopeVerdict(
    val status: EnvelopeStatus,
    val reasons: List<String> = emptyList(),
    val envelope: SealedEnvelope? = null,
) {
    val ok: Boolean get() = status == EnvelopeStatus.ACCEPTED
}

/** Runtime clock, local version window and tolerated clock skew. */
data class EnvelopeRuntimeOptions(
    val now: () -> Timestamp,
    val version: VersionRange,
    val clockSkewMs: Long? = null,
)

private data class IdentityKey(val from: String, val id: String)
private data class SequenceKey(val from: String, val to: String, val thread: String)
private data class TombstoneKey(val from: String, val target: String)

/**
 * Authenticated receiver for one A-surface participant.
 *
 * `receive` verifies signature, TTL, version intersection and nonce replay
 * before touching sequence state. It enforces `(from, to, thread)` FIFO,
 * global `(from, id)` dedupe within this runtime, and sticky `(from, targetId)`
 * cancel tombstones. A cancel is subject to the same sequence ordering as every
 * other performative. An envelope whose `seq` is ahead of the next expected
 * value is queued as `out_of_order` and becomes deliverable when the gap closes.
 * Threads and recipient pairs advance independently.
 *
 * A receiver is not configured with its own `to` identity, so `to` participates
 * in the ordering key but audience rejection is the caller's responsibility.
 */
class EnvelopeRuntime(options: EnvelopeRuntimeOptions) {

    private val now: () -> Timestamp = options.now
    private val version = VersionRange(options.version.min, options.version.max)
    private val clockSkewMs: Long = normalizedSkew(options.clockSkewMs)

    private val nextByThread = HashMap<SequenceKey, Long>()
    private val queueByThread = HashMap<SequenceKey, MutableMap<Long, SealedEnvelope>>()
    private val seenIds = HashSet<IdentityKey>()
    private val seenNonces = HashSet<String>()
    private val tombstones = HashSet<TombstoneKey>()
    private val deliveredEnvelopes = ArrayList<SealedEnvelope>()
    private val cancelledEnvelopes = ArrayList<SealedEnvelope>()

    /** Delivered envelopes in sequence order, including cancel performatives. */
    val delivered: List<SealedEnvelope> get() = deliveredEnvelopes.toList()

    /** Convenience alias for [delivered]; both exclude cancelled targets. */
    val accepted: List<SealedEnvelope> get() = deliveredEnvelopes.toList()

    /** Verified envelopes suppressed by a previously delivered cancel tombstone. */
    val cancelled: List<SealedEnvelope> get() = cancelledEnvelopes.toList()

    /** Verified envelopes still waiting behind an earlier sequence gap. */
    val pending: List<SealedEnvelope>
        get() = queueByThread.values.flatMap { it.values }.sortedBy { it.seq }

    /**
     * Verifies and admits one envelope.
     *
     * Invalid envelopes leave the message id, nonce and sequence state
     * untouched. A duplicate id short-circuits to `duplicate`; a stale sequence
     * is also reported as `duplicate`; a future sequence is queued without
     * delivery. A cancelled target consumes its sequence slot so the FIFO can
     * continue.
     */
    fun receive(envelope: JsonValue, publicKey: PublicKey): EnvelopeVerdict {
        val obj = envelope as? JsonValue.Obj
            ?: return verdict(EnvelopeStatus.INVALID, listOf("envelope is not an object"))

        val initialIdentity = identityKey(obj)
        if (initialIdentity != null && seenIds.contains(initialIdentity)) {
            return verdict(
                EnvelopeStatus.DUPLICATE,
                listOf("envelope id has already been received"),
                sealedOrNull(obj),
            )
        }

        val nonces = HashSet(seenNonces)
        val verification = verifyEnvelope(
            obj,
            publicKey,
            EnvelopeVerifyOptions(
                now = now(),
                expectedVersion = version,
                clockSkewMs = clockSkewMs,
                seenNonces = nonces,
            ),
        )
        if (!verification.ok) {
            return verdict(EnvelopeStatus.INVALID, verification.reasons, sealedOrNull(obj))
        }

        val identity = identityKey(obj)
            ?: return verdict(
                EnvelopeStatus.INVALID,
                listOf("envelope has no usable (from, id) identity"),
                sealedOrNull(obj),
            )
        if (seenIds.contains(identity)) {
            return verdict(
                EnvelopeStatus.DUPLICATE,
                listOf("envelope id has already been received"),
                sealedOrNull(obj),
            )
        }

        val sealed = sealedOrNull(obj)
            ?: return verdict(
                EnvelopeStatus.INVALID,
                listOf("envelope is not a valid sealed envelope"),
                null,
            )

        val cancelTarget = if (sealed.type == Performative.CANCEL) cancelTargetId(sealed.payload) else null
        if (sealed.type == Performative.CANCEL && cancelTarget == null) {
            return verdict(
                EnvelopeStatus.INVALID,
                listOf("cancel payload must include a non-empty targetId or request"),
                sealed,
            )
        }

        if (seenNonces.contains(sealed.nonce)) {
            return verdict(EnvelopeStatus.INVALID, listOf("nonce has already been seen"), sealed)
        }

        seenNonces += sealed.nonce
        seenIds += identity

        val key = sequenceKey(sealed)
        val next = nextByThread[key] ?: 0L

        if (sealed.seq > next) {
            val queue = queueByThread.getOrPut(key) { HashMap() }
            if (queue.containsKey(sealed.seq)) {
                return verdict(
                    EnvelopeStatus.DUPLICATE,
                    listOf("sequence ${sealed.seq} is already queued"),
                    sealed,
                )
            }
            queue[sealed.seq] = sealed
            return verdict(
                EnvelopeStatus.OUT_OF_ORDER,
                listOf("sequence ${sealed.seq} arrived before expected $next"),
                sealed,
            )
        }

        if (sealed.seq < next) {
            return verdict(
                EnvelopeStatus.DUPLICATE,
                listOf("sequence ${sealed.seq} is stale; expected $next"),
                sealed,
            )
        }

        val wasCancelled = settle(sealed)
        nextByThread[key] = sealed.seq + 1
        drain(key)

        if (wasCancelled) {
            return verdict(EnvelopeStatus.CANCELLED, listOf("envelope ${sealed.id} was cancelled"), sealed)
        }
        return verdict(EnvelopeStatus.ACCEPTED, emptyList(), sealed)
    }

    fun receive(envelope: SealedEnvelope, publicKey: PublicKey): EnvelopeVerdict =
        receive(envelope.toJson(), publicKey)

    private fun settle(envelope: SealedEnvelope): Boolean {
        if (envelope.type == Performative.CANCEL) {
            cancelTargetId(envelope.payload)?.let { target ->
                tombstones += TombstoneKey(envelope.from.id, target)
            }
            deliveredEnvelopes += envelope
            return false
        }

        if (tombstones.contains(TombstoneKey(envelope.from.id, envelope.id))) {
            cancelledEnvelopes += envelope
            return true
        }

        deliveredEnvelopes += envelope
        return false
    }

    /** Consumes every contiguous queued sequence after an accepted envelope. */
    private fun drain(key: SequenceKey) {
        val queue = queueByThread[key] ?: return
        var next = nextByThread[key] ?: 0L
        while (true) {
            val queued = queue[next] ?: break
            queue.remove(next)
            settle(queued)
            next += 1
        }
        nextByThread[key] = next
        if (queue.isEmpty()) queueByThread.remove(key)
    }

    private fun verdict(
        status: EnvelopeStatus,
        reasons: List<String>,
        envelope: SealedEnvelope? = null,
    ): EnvelopeVerdict = EnvelopeVerdict(status, reasons, envelope)

    private fun identityKey(envelope: JsonValue.Obj): IdentityKey? {
        val id = envelope.string("id")?.takeIf { it.isNotEmpty() } ?: return null
        if (!isRef(envelope["from"])) return null
        val fromId = envelope.obj("from")?.string("id") ?: return null
        return IdentityKey(fromId, id)
    }

    private fun sequenceKey(envelope: SealedEnvelope): SequenceKey =
        SequenceKey(envelope.from.id, envelope.to.id, envelope.thread)

    private fun sealedOrNull(envelope: JsonValue.Obj): SealedEnvelope? =
        runCatching { SealedEnvelope.fromJson(envelope) }.getOrNull()
}

private fun cancelTargetId(payload: JsonValue): String? {
    val obj = payload as? JsonValue.Obj ?: return null
    obj.string("targetId")?.takeIf { it.isNotEmpty() }?.let { return it }
    obj.string("request")?.takeIf { it.isNotEmpty() }?.let { return it }
    return null
}
