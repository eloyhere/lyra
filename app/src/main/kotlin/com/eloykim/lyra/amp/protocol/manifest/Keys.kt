package com.eloykim.lyra.amp.protocol.manifest

import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.Base64Url
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.nowTimestamp
import java.security.PublicKey
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * AMP v0.1 key lifecycle: validity windows, rotation, retirement and
 * revocation, mirroring `keys.ts`.
 *
 * This layer stores public keys and lifecycle metadata only; it never signs or
 * verifies a revocation list. A caller must authenticate a list's `issuer`
 * before merging it or acting on its entries.
 */

/** Default tolerance for issuer/verifier clock disagreement (five minutes). */
const val DEFAULT_CLOCK_SKEW_MS: Long = 5 * 60_000L

/** Lifecycle state: active keys are current, retired keys only verify history. */
enum class KeyStatus(val wire: String) {
    ACTIVE("active"),
    RETIRED("retired"),
    REVOKED("revoked");

    companion object {
        fun fromWire(value: String): KeyStatus =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 key status: $value")
    }
}

/** A public key plus the lifecycle metadata needed to judge it at a time. */
data class KeyRecord(
    val kid: Id,
    val publicKey: PublicKey,
    val status: KeyStatus,
    val validFrom: Timestamp,
    val validTo: Timestamp? = null,
    /** Kid this key replaced, when it was introduced by a rotation. */
    val supersedes: Id? = null,
    /** Free-form audit note; this module never interprets it. */
    val note: String? = null,
)

/** One kid revoked by an issuer, at a time and for an optional reason. */
data class RevocationEntry(
    val kid: Id,
    val revokedAt: Timestamp,
    val reason: String? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "kid" to JsonValue.Str(kid),
        "revokedAt" to JsonValue.Str(revokedAt),
        "reason" to reason?.let { JsonValue.Str(it) },
    )
}

/** A signed-by-the-caller revocation snapshot; this module never verifies it. */
data class RevocationList(
    val issuer: String,
    val issued: Timestamp,
    val nextUpdate: Timestamp,
    val revoked: List<RevocationEntry>,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "issuer" to JsonValue.Str(issuer),
        "issued" to JsonValue.Str(issued),
        "nextUpdate" to JsonValue.Str(nextUpdate),
        "revoked" to JsonValue.Arr(revoked.map { it.toJson() }),
    )
}

/** Successor key supplied to [Keyring.rotate]. */
data class KeyRotation(
    val kid: Id,
    val publicKey: PublicKey,
    val validFrom: Timestamp? = null,
    val overlapMs: Long = 0,
)

/** Validity verdict: `ok` plus one human-readable reason per failed check. */
data class WindowVerdict(
    val ok: Boolean,
    val reasons: List<String>,
) {
    companion object {
        fun success(): WindowVerdict = WindowVerdict(true, emptyList())
        fun failure(vararg reasons: String): WindowVerdict = WindowVerdict(false, reasons.toList())
    }
}

/** Optional clock and skew policy for a [Keyring]. */
data class KeyringOptions(
    val now: () -> Timestamp = { nowTimestamp() },
    val clockSkewMs: Long = 0,
)

/**
 * In-memory public-key lifecycle store.
 *
 * A record is usable while its status is `active` or `retired` and `at` lies
 * inside its validity window. Revocation is permanent and wins over every
 * window. Retired records are retained so signatures made before rotation stay
 * verifiable.
 */
class Keyring(
    private val clock: () -> Timestamp = { nowTimestamp() },
    val clockSkewMs: Long = 0,
) {

    constructor(options: KeyringOptions) : this(options.now, options.clockSkewMs)

    private val records = LinkedHashMap<Id, KeyRecord>()
    private val revocationByKid = LinkedHashMap<Id, RevocationEntry>()

    init {
        require(clockSkewMs >= 0) { "clockSkewMs must be a non-negative finite number" }
    }

    /**
     * Adds a key record. Throws on a duplicate kid, a malformed timestamp, or
     * a window whose `validTo` is not strictly after `validFrom`.
     *
     * A record supplied already `revoked` is registered as revoked at its
     * `validFrom` (using the note as the reason), so it is never usable.
     */
    fun add(record: KeyRecord) {
        if (records.containsKey(record.kid)) {
            throw IllegalStateException("duplicate kid: ${record.kid}")
        }
        validateWindow(record)
        records[record.kid] = record.copy()
        if (record.status == KeyStatus.REVOKED) {
            revocationByKid[record.kid] = RevocationEntry(record.kid, record.validFrom, record.note)
        }
    }

    /**
     * Rotates from `currentKid` to `next`.
     *
     * The current record becomes `retired` and remains available for historical
     * timestamps. It stops being usable after the overlap, which ends at
     * `now + overlapMs` (or at `now` when omitted), capped by the current key's
     * existing `validTo`. The successor starts at `next.validFrom` (default now)
     * and records `supersedes: currentKid`.
     */
    fun rotate(currentKid: Id, next: KeyRotation): KeyRecord {
        val current = records[currentKid] ?: throw IllegalStateException("unknown key: $currentKid")
        when (current.status) {
            KeyStatus.REVOKED -> throw IllegalStateException("cannot rotate a revoked key: $currentKid")
            KeyStatus.RETIRED -> throw IllegalStateException("cannot rotate a retired key: $currentKid")
            KeyStatus.ACTIVE -> Unit
        }
        if (records.containsKey(next.kid)) {
            throw IllegalStateException("duplicate kid: ${next.kid}")
        }
        if (next.overlapMs < 0) {
            throw IllegalArgumentException("overlapMs must be a non-negative finite number")
        }

        val now = clock()
        val nowMs = requireInstant(now, "now").toEpochMilli()
        val nextValidFrom = next.validFrom ?: now
        requireInstant(nextValidFrom, "validFrom")

        val currentFromMs = requireInstant(current.validFrom, "validFrom").toEpochMilli()
        val currentValidTo = current.validTo
        val currentToMs = currentValidTo?.let { requireInstant(it, "validTo").toEpochMilli() }
        val plannedEndMs = nowMs + next.overlapMs
        val endMs = if (currentToMs == null) plannedEndMs else minOf(plannedEndMs, currentToMs)
        if (endMs <= currentFromMs) {
            throw IllegalArgumentException("overlap ends before the current key becomes valid")
        }

        val end = when {
            currentValidTo != null && currentToMs != null && endMs == currentToMs -> currentValidTo
            next.overlapMs == 0L -> now
            else -> timestampOf(plannedEndMs)
        }

        records[currentKid] = current.copy(status = KeyStatus.RETIRED, validTo = end)
        val successor = KeyRecord(
            kid = next.kid,
            publicKey = next.publicKey,
            status = KeyStatus.ACTIVE,
            validFrom = nextValidFrom,
            supersedes = currentKid,
        )
        records[next.kid] = successor
        return successor.copy()
    }

    /**
     * Revokes a key permanently, returning `false` when the kid is unknown or
     * was already revoked. Revocation takes precedence over every window.
     */
    fun revoke(kid: Id, at: Timestamp? = null, reason: String? = null): Boolean {
        val record = records[kid] ?: return false
        if (record.status == KeyStatus.REVOKED) {
            return false
        }
        val revokedAt = at ?: clock()
        requireInstant(revokedAt, "at")
        records[kid] = record.copy(status = KeyStatus.REVOKED)
        revocationByKid[kid] = RevocationEntry(kid, revokedAt, reason)
        return true
    }

    /**
     * Retires an active key at `at` (default now), returning `false` when the
     * key is unknown, already retired or revoked. Retirement never extends an
     * existing `validTo`.
     */
    fun retire(kid: Id, at: Timestamp? = null): Boolean {
        val record = records[kid] ?: return false
        if (record.status != KeyStatus.ACTIVE) {
            return false
        }
        val retiredAt = at ?: clock()
        val retiredAtMs = requireInstant(retiredAt, "at").toEpochMilli()
        val fromMs = requireInstant(record.validFrom, "validFrom").toEpochMilli()
        if (retiredAtMs <= fromMs) {
            throw IllegalArgumentException("retirement must be after validFrom")
        }
        val currentToMs = record.validTo?.let { requireInstant(it, "validTo").toEpochMilli() }
        val validTo = if (currentToMs == null || retiredAtMs < currentToMs) retiredAt else record.validTo
        records[kid] = record.copy(status = KeyStatus.RETIRED, validTo = validTo)
        return true
    }

    /**
     * Returns the public key usable at `at` (default now). Revoked keys always
     * return `null`; active and retired keys return `null` outside their window.
     */
    fun get(kid: Id, at: Timestamp? = null): PublicKey? {
        val record = records[kid] ?: return null
        if (record.status == KeyStatus.REVOKED) {
            return null
        }
        val atMs = requireInstant(at ?: clock(), "at").toEpochMilli()
        return if (contains(record, atMs)) record.publicKey else null
    }

    /** Returns a copy of the lifecycle record, including retired/revoked history. */
    fun record(kid: Id): KeyRecord? = records[kid]?.copy()

    /** Lists active (not retired or revoked) records usable at `at` (default now). */
    fun active(at: Timestamp? = null): List<KeyRecord> {
        val atMs = requireInstant(at ?: clock(), "at").toEpochMilli()
        return records.values
            .filter { it.status == KeyStatus.ACTIVE && contains(it, atMs) }
            .map { it.copy() }
    }

    /** True when the kid was revoked; unknown kids are not revoked. */
    fun isRevoked(kid: Id): Boolean = records[kid]?.status == KeyStatus.REVOKED

    /** Explains whether `at` is inside the key's usable window. */
    fun verifyWindow(kid: Id, at: Timestamp): WindowVerdict {
        val record = records[kid] ?: return WindowVerdict.failure("missing key: $kid")
        val atMs = requireInstant(at, "at").toEpochMilli()
        val reasons = ArrayList<String>()
        if (record.status == KeyStatus.REVOKED) {
            reasons += "key is revoked: $kid"
        }
        val fromMs = requireInstant(record.validFrom, "validFrom").toEpochMilli()
        if (atMs < fromMs - clockSkewMs) {
            reasons += "key is not yet valid: $kid"
        }
        if (record.validTo != null) {
            val toMs = requireInstant(record.validTo, "validTo").toEpochMilli()
            if (atMs > toMs + clockSkewMs) {
                reasons += "key expired: $kid"
            }
        }
        return WindowVerdict(reasons.isEmpty(), reasons)
    }

    /** Revocations in deterministic order (earliest first, then kid). */
    fun revocations(): List<RevocationEntry> = revocationByKid.values
        .map { it.copy() }
        .sortedWith(compareBy({ requireInstant(it.revokedAt, "revokedAt").toEpochMilli() }, { it.kid }))

    private fun contains(record: KeyRecord, atMs: Long): Boolean {
        val fromMs = requireInstant(record.validFrom, "validFrom").toEpochMilli()
        if (atMs < fromMs - clockSkewMs) {
            return false
        }
        if (record.validTo == null) {
            return true
        }
        val toMs = requireInstant(record.validTo, "validTo").toEpochMilli()
        return atMs <= toMs + clockSkewMs
    }

    private fun validateWindow(record: KeyRecord) {
        val fromMs = requireInstant(record.validFrom, "validFrom").toEpochMilli()
        val validTo = record.validTo ?: return
        val toMs = requireInstant(validTo, "validTo").toEpochMilli()
        if (toMs <= fromMs) {
            throw IllegalArgumentException("validTo must be after validFrom")
        }
    }
}

/** Thrown by [requireFresh] when a revocation list cannot be trusted as current. */
class RevocationError(
    message: String,
    val reasons: List<String>,
) : IllegalStateException(message)

/**
 * Freshness verdict for a revocation list: `false` for malformed timestamps,
 * `nextUpdate` before `issued`, a future `issued`, or a stale list.
 */
fun isRevocationFresh(list: RevocationList, now: Timestamp, maxStalenessMs: Double): Boolean =
    revocationFreshnessProblems(list, now, maxStalenessMs).isEmpty()

/** Returns `list` when fresh; otherwise throws a [RevocationError] with reasons. */
fun requireFresh(list: RevocationList, now: Timestamp, maxStalenessMs: Double): RevocationList {
    val problems = revocationFreshnessProblems(list, now, maxStalenessMs)
    if (problems.isNotEmpty()) {
        throw RevocationError("unfresh revocation list: ${problems.joinToString("; ")}", problems)
    }
    return list
}

private fun revocationFreshnessProblems(list: RevocationList, now: Timestamp, maxStalenessMs: Double): List<String> {
    val problems = ArrayList<String>()
    if (!maxStalenessMs.isFinite() || maxStalenessMs < 0) {
        problems += "maxStalenessMs must be a non-negative finite number"
    }

    val nowMs = instantOf(now)?.toEpochMilli()
    if (nowMs == null) {
        problems += "now is not a valid timestamp"
    }
    val issuedMs = instantOf(list.issued)?.toEpochMilli()
    if (issuedMs == null) {
        problems += "issued is not a valid timestamp"
    }
    val nextUpdateMs = instantOf(list.nextUpdate)?.toEpochMilli()
    if (nextUpdateMs == null) {
        problems += "nextUpdate is not a valid timestamp"
    }

    if (issuedMs != null && nextUpdateMs != null && nextUpdateMs < issuedMs) {
        problems += "nextUpdate precedes issued"
    }
    if (issuedMs != null && nowMs != null && issuedMs > nowMs) {
        problems += "issued is in the future"
    }
    if (nextUpdateMs != null && nowMs != null && maxStalenessMs.isFinite() && maxStalenessMs >= 0 &&
        nowMs.toDouble() > nextUpdateMs + maxStalenessMs
    ) {
        problems += "revocation list is stale"
    }
    list.revoked.forEach { entry ->
        if (instantOf(entry.revokedAt) == null) {
            problems += "revokedAt for ${entry.kid} is not a valid timestamp"
        }
    }
    return problems
}

/**
 * Checks a manifest's own validity window. `issued` may be up to `skewMs`
 * ahead of `now`, and `expires` up to `skewMs` behind it; the default tolerance
 * is five minutes, matching federation import.
 */
fun checkManifestWindow(
    manifest: Manifest,
    now: Timestamp,
    skewMs: Long = DEFAULT_CLOCK_SKEW_MS,
): WindowVerdict = checkManifestWindow(manifest.issued, manifest.expires, now, skewMs)

fun checkManifestWindow(
    manifest: UnsignedManifest,
    now: Timestamp,
    skewMs: Long = DEFAULT_CLOCK_SKEW_MS,
): WindowVerdict = checkManifestWindow(manifest.issued, manifest.expires, now, skewMs)

fun checkManifestWindow(
    issued: Timestamp,
    expires: Timestamp,
    now: Timestamp,
    skewMs: Long = DEFAULT_CLOCK_SKEW_MS,
): WindowVerdict {
    val reasons = ArrayList<String>()
    if (skewMs < 0) {
        reasons += "skewMs must be a non-negative finite number"
    }

    val nowMs = instantOf(now)?.toEpochMilli()
    if (nowMs == null) {
        reasons += "now is not a valid timestamp"
    }
    val issuedMs = instantOf(issued)?.toEpochMilli()
    if (issuedMs == null) {
        reasons += "manifest issued is not a valid timestamp"
    }
    val expiresMs = instantOf(expires)?.toEpochMilli()
    if (expiresMs == null) {
        reasons += "manifest expires is not a valid timestamp"
    }

    if (issuedMs != null && expiresMs != null && expiresMs <= issuedMs) {
        reasons += "manifest expires must be after issued"
    }
    if (skewMs >= 0) {
        if (nowMs != null && expiresMs != null && nowMs > expiresMs + skewMs) {
            reasons += "manifest expired"
        }
        if (nowMs != null && issuedMs != null && issuedMs - skewMs > nowMs) {
            reasons += "manifest issued in the future"
        }
    }
    return WindowVerdict(reasons.isEmpty(), reasons)
}

/**
 * Merges two authenticated revocation lists from the same issuer: earliest
 * `revokedAt` per kid, latest `issued`/`nextUpdate`, sorted output.
 *
 * This function performs no signature verification.
 */
fun mergeRevocations(local: RevocationList, remote: RevocationList): RevocationList {
    if (local.issuer != remote.issuer) {
        throw IllegalArgumentException(
            "cannot merge revocation lists from different issuers: ${local.issuer} and ${remote.issuer}",
        )
    }

    val merged = LinkedHashMap<Id, RevocationEntry>()
    (local.revoked + remote.revoked).forEach { entry ->
        requireInstant(entry.revokedAt, "revokedAt")
        val existing = merged[entry.kid]
        merged[entry.kid] = if (existing == null) entry.copy() else earlierRevocation(existing, entry)
    }

    val revoked = merged.values
        .map { it.copy() }
        .sortedWith(compareBy({ requireInstant(it.revokedAt, "revokedAt").toEpochMilli() }, { it.kid }))

    return RevocationList(
        issuer = local.issuer,
        issued = latestTimestamp(local.issued, remote.issued, "issued"),
        nextUpdate = latestTimestamp(local.nextUpdate, remote.nextUpdate, "nextUpdate"),
        revoked = revoked,
    )
}

private fun earlierRevocation(left: RevocationEntry, right: RevocationEntry): RevocationEntry {
    val leftMs = requireInstant(left.revokedAt, "revokedAt").toEpochMilli()
    val rightMs = requireInstant(right.revokedAt, "revokedAt").toEpochMilli()
    if (leftMs < rightMs) return left
    if (rightMs < leftMs) return right
    if (left.revokedAt != right.revokedAt) {
        return if (left.revokedAt < right.revokedAt) left else right
    }
    if (left.reason == null && right.reason != null) return right
    if (right.reason == null && left.reason != null) return left
    val leftReason = left.reason ?: ""
    val rightReason = right.reason ?: ""
    return if (leftReason != rightReason && rightReason < leftReason) right else left
}

/** Latest timestamp; ties normalize to the lexicographically smaller spelling. */
private fun latestTimestamp(left: Timestamp, right: Timestamp, field: String): Timestamp {
    val leftMs = requireInstant(left, field).toEpochMilli()
    val rightMs = requireInstant(right, field).toEpochMilli()
    if (leftMs > rightMs) return left
    if (rightMs > leftMs) return right
    return if (left <= right) left else right
}

/** Epoch milliseconds for a finite timestamp, or `null` when malformed. */
fun instantOf(value: Timestamp): Instant? = runCatching { Instant.parse(value) }.getOrNull()

/** Epoch milliseconds, or an error naming the invalid field. */
fun requireInstant(value: Timestamp, field: String): Instant =
    instantOf(value) ?: throw IllegalArgumentException("$field is not a valid timestamp: $value")

/** JavaScript `Date#toISOString()` spelling, used by rotation deadlines. */
private fun timestampOf(ms: Long): Timestamp = ISO_MILLIS.format(Instant.ofEpochMilli(ms))

private val ISO_MILLIS: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
    .withZone(ZoneOffset.UTC)

/**
 * Raw Ed25519 key material for the federation wire format (`publicKey` is the
 * 32-byte raw key, not the JCA X.509 wrapper).
 */
object Ed25519Keys {

    private val SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    fun rawPublicKey(publicKey: PublicKey): ByteArray {
        val encoded = publicKey.encoded ?: throw IllegalArgumentException("ed25519 公钥没有编码形式")
        if (encoded.size == RAW_KEY_BYTES) {
            return encoded
        }
        if (encoded.size == SPKI_PREFIX.size + RAW_KEY_BYTES &&
            encoded.copyOfRange(0, SPKI_PREFIX.size).contentEquals(SPKI_PREFIX)
        ) {
            return encoded.copyOfRange(SPKI_PREFIX.size, encoded.size)
        }
        throw IllegalArgumentException("不是 ed25519 公钥")
    }

    fun encodePublicKey(publicKey: PublicKey): String = Base64Url.encode(rawPublicKey(publicKey))

    fun decodePublicKey(text: String): PublicKey = decodePublicKeyBytes(Base64Url.decode(text))

    fun decodePublicKeyBytes(raw: ByteArray): PublicKey {
        require(raw.size == RAW_KEY_BYTES) { "ed25519 公钥必须是 32 字节" }
        return try {
            Ed25519.decodePublicKey(SPKI_PREFIX + raw)
        } catch (error: Exception) {
            throw IllegalArgumentException("ed25519 公钥无法导入", error)
        }
    }

    private const val RAW_KEY_BYTES = 32
}
