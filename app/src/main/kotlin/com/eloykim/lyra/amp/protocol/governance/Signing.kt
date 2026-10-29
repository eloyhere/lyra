package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.crypto.AmpSigner
import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.manifest.SignatureUnavailableException
import com.eloykim.lyra.amp.protocol.semantic.Base64Url
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import java.security.PublicKey

/**
 * Ed25519 adapter for governance objects, a faithful port of
 * `src/governance/signing.ts`.
 *
 * Governance sign-offs answer "which department approved this digest"; this module
 * answers "who can prove they hold the key for that department". The two layers are
 * deliberately separate: a deployment may run the governance layer without
 * cryptography, and it may attach cryptography only where the host can manage keys.
 *
 * A signature covers `{signer, subject, subjectId, subjectVersion, digest}`, so a
 * signature cannot be transplanted from one manifest or credential to another.
 *
 * The narrow signing boundary is [AmpSigner] from the manifest port: it exposes a
 * `kid` and `sign(bytes)`, and [Ed25519] is its default JCA implementation. On
 * platforms without Ed25519 (Android below API 33 without a bundled provider)
 * signing throws [SignatureUnavailableException] rather than fabricating a
 * signature, and verification fails closed.
 */

/** The governance objects a signature bundle can cover. */
enum class GovernanceSubject(val wire: String) {
    ATTENTION_MANIFEST("attention-manifest"),
    SWITCH_CREDENTIAL("switch-credential"),
    AUDIT_CHECKPOINT("audit-checkpoint"),
    COOPERATION_MESSAGE("cooperation-message");

    companion object {
        fun fromWire(value: String): GovernanceSubject =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 governance subject: $value")
    }
}

/**
 * A governance signer: the department name plus the key that proves it.
 *
 * `signer` is the department name (`legal`, `finance`, ...), mirroring the reference;
 * the key's `kid` is what a keyring is keyed by.
 */
class GovernanceSigner(
    val signer: String,
    val key: AmpSigner,
) {
    val kid: String get() = key.kid

    fun publicKey(): PublicKey? = key.publicKey()

    override fun toString(): String = "GovernanceSigner($signer, $kid)"
}

data class GovernanceCryptoSignature(
    val signer: String,
    val kid: Id,
    val alg: String = "ed25519",
    val sig: String,
    val digest: Digest,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "signer" to JsonValue.Str(signer),
        "kid" to JsonValue.Str(kid),
        "alg" to JsonValue.Str(alg),
        "sig" to JsonValue.Str(sig),
        "digest" to JsonValue.Str(digest),
    )

    companion object {
        fun fromJson(value: JsonValue): GovernanceCryptoSignature {
            val obj = value as? JsonValue.Obj ?: throw IllegalArgumentException("governance signature 必须是 JSON 对象")
            return GovernanceCryptoSignature(
                signer = obj.requireString("signer"),
                kid = obj.requireString("kid"),
                alg = obj.string("alg") ?: "ed25519",
                sig = obj.requireString("sig"),
                digest = obj.requireString("digest"),
            )
        }
    }
}

/**
 * A bundle of cryptographic signatures over one versioned subject digest.
 */
data class GovernanceSignatureBundle(
    val kind: String = "governance-signatures",
    val subject: GovernanceSubject,
    val subjectId: String,
    val subjectVersion: Int? = null,
    val subjectDigest: Digest,
    val signatures: List<GovernanceCryptoSignature>,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to JsonValue.Str(kind),
        "subject" to JsonValue.Str(subject.wire),
        "subjectId" to JsonValue.Str(subjectId),
        "subjectVersion" to subjectVersion?.let { jsonNumber(it) },
        "subjectDigest" to JsonValue.Str(subjectDigest),
        "signatures" to jsonArr(*signatures.map { it.toJson() }.toTypedArray()),
    )

    companion object {
        fun fromJson(value: JsonValue): GovernanceSignatureBundle {
            val obj = value as? JsonValue.Obj ?: throw IllegalArgumentException("governance bundle 必须是 JSON 对象")
            return GovernanceSignatureBundle(
                kind = obj.string("kind") ?: "governance-signatures",
                subject = GovernanceSubject.fromWire(obj.requireString("subject")),
                subjectId = obj.requireString("subjectId"),
                subjectVersion = obj.int("subjectVersion"),
                subjectDigest = obj.requireString("subjectDigest"),
                signatures = obj.requireArr("signatures").items.map(GovernanceCryptoSignature::fromJson),
            )
        }
    }
}

data class GovernanceVerification(
    val ok: Boolean,
    val reasons: List<String>,
) {
    companion object {
        fun success(): GovernanceVerification = GovernanceVerification(true, emptyList())
        fun failure(vararg reasons: String): GovernanceVerification = GovernanceVerification(false, reasons.toList())
        fun failure(reasons: List<String>): GovernanceVerification = GovernanceVerification(false, reasons)
    }
}

/** The expected binding of a bundle: subject, its id and version, and the digest. */
data class GovernanceExpectation(
    val subject: GovernanceSubject,
    val subjectId: String,
    val subjectVersion: Int? = null,
    val digest: Digest,
)

/** Public keys keyed by the governance signer name, plus lifecycle metadata. */
data class GovernanceKeyRecord(
    val publicKey: PublicKey,
    val kid: Id? = null,
    val addedAt: Long? = null,
    val retiresAt: Long? = null,
)

enum class KeyringAction(val wire: String) {
    ADD("add"),
    ROTATE("rotate"),
    REVOKE("revoke"),
    REVOKE_KID("revoke-kid"),
    PRUNE("prune"),
}

data class KeyringEvent(
    val action: KeyringAction,
    val signer: String,
    val kid: Id? = null,
    val at: Long,
)

typealias KeyringEventSink = (KeyringEvent) -> Unit

/**
 * Governance keyring: public keys per signer, with rotation overlap and revocation.
 *
 * Revocation is separate from deletion, so a revoked signer fails closed even if its
 * key is still present. Rotation keeps the old key as a candidate until `retiresAt`,
 * so signatures made before the rotation still verify during the overlap window.
 */
class GovernanceKeyring(
    private val onEvent: KeyringEventSink? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val keys = LinkedHashMap<String, MutableList<GovernanceKeyRecord>>()
    private val revokedSigners = LinkedHashSet<String>()
    private val revokedKids = LinkedHashSet<String>()

    private fun emit(action: KeyringAction, signer: String, at: Long, kid: Id? = null) {
        onEvent?.invoke(KeyringEvent(action = action, signer = signer, kid = kid, at = at))
    }

    fun add(
        signer: String,
        publicKey: PublicKey,
        kid: Id? = null,
        at: Long? = null,
        retiresAt: Long? = null,
    ) {
        keys.getOrPut(signer) { ArrayList() } += GovernanceKeyRecord(
            publicKey = publicKey,
            kid = kid,
            addedAt = at,
            retiresAt = retiresAt,
        )
        emit(KeyringAction.ADD, signer, at ?: clock(), kid)
    }

    /**
     * Add a new key and retire the previous ones after the overlap window.
     */
    fun rotate(signer: String, publicKey: PublicKey, kid: Id? = null, at: Long, overlapMs: Long = 0) {
        val overlap = maxOf(0L, overlapMs)
        val existing = keys.getOrPut(signer) { ArrayList() }
        existing.forEachIndexed { index, record ->
            if (record.retiresAt == null) {
                existing[index] = record.copy(retiresAt = at + overlap)
            }
        }
        existing += GovernanceKeyRecord(publicKey = publicKey, kid = kid, addedAt = at)
        emit(KeyringAction.ROTATE, signer, at, kid)
    }

    fun revoke(signer: String) {
        revokedSigners += signer
        emit(KeyringAction.REVOKE, signer, clock())
    }

    fun revokeKid(signer: String, kid: Id) {
        revokedKids += "$signer#$kid"
        emit(KeyringAction.REVOKE_KID, signer, clock(), kid)
    }

    fun prune(at: Long) {
        val entries = keys.entries.toList()
        for ((signer, records) in entries) {
            keys[signer] = records.filter { it.retiresAt == null || it.retiresAt!! > at }.toMutableList()
        }
        emit(KeyringAction.PRUNE, "", at)
    }

    fun candidates(signer: String, kid: Id? = null, at: Long = clock()): List<PublicKey> {
        if (revokedSigners.contains(signer)) {
            return emptyList()
        }
        val records = (keys[signer] ?: emptyList()).filter { record ->
            (record.retiresAt == null || record.retiresAt!! > at) &&
                !revokedKids.contains("$signer#${record.kid ?: ""}")
        }
        if (kid != null) {
            val matching = records.filter { it.kid == kid }
            if (matching.isNotEmpty()) {
                return matching.map { it.publicKey }
            }
        }
        return records.map { it.publicKey }
    }

    fun get(signer: String, kid: Id? = null, at: Long = clock()): PublicKey? = candidates(signer, kid, at).firstOrNull()
}

/**
 * Generate one signing key per governance signer name; `kid` becomes
 * `<kidPrefix>.<signer>`.
 */
fun generateGovernanceSigners(signers: List<String>, kidPrefix: String = "gov"): List<GovernanceSigner> {
    if (!Ed25519.isAvailable()) {
        throw SignatureUnavailableException("ed25519")
    }
    return signers.map { name ->
        val key = Ed25519.generate("$kidPrefix.$name")
            ?: throw SignatureUnavailableException("ed25519")
        GovernanceSigner(name, key)
    }
}

/**
 * Generic canonical-value signatures for transport envelopes and audit checkpoints.
 */
fun signCanonicalValue(value: JsonValue, signer: AmpSigner): String {
    if (!Ed25519.isAvailable()) {
        throw SignatureUnavailableException("ed25519")
    }
    val signature = try {
        signer.sign(CanonicalJson.encode(value).toByteArray(Charsets.UTF_8))
    } catch (error: Exception) {
        throw SignatureUnavailableException("ed25519 signing failed: ${error.message}", error)
    }
    return Base64Url.encode(signature)
}

fun verifyCanonicalValue(value: JsonValue, signature: String, publicKey: PublicKey): Boolean {
    if (!Ed25519.isAvailable()) {
        return false
    }
    val bytes = runCatching { Base64Url.decode(signature) }.getOrNull() ?: return false
    return Ed25519.verify(
        publicKey,
        CanonicalJson.encode(value).toByteArray(Charsets.UTF_8),
        bytes,
    )
}

/**
 * Sign one subject digest for one governance signer.
 */
fun signGovernanceDigest(
    signer: GovernanceSigner,
    subject: GovernanceSubject,
    subjectId: String,
    subjectVersion: Int? = null,
    digest: Digest,
): GovernanceCryptoSignature {
    if (!Ed25519.isAvailable()) {
        throw SignatureUnavailableException("ed25519")
    }
    val bytes = signingBytes(signer.signer, subject, subjectId, subjectVersion, digest)
    val signature = try {
        signer.key.sign(bytes)
    } catch (error: Exception) {
        throw SignatureUnavailableException("ed25519 signing failed: ${error.message}", error)
    }
    if (signature.size != ED25519_SIGNATURE_BYTES) {
        throw SignatureUnavailableException("ed25519 signing returned ${signature.size} bytes")
    }
    return GovernanceCryptoSignature(
        signer = signer.signer,
        kid = signer.kid,
        alg = ALGORITHM,
        sig = Base64Url.encode(signature),
        digest = digest,
    )
}

/**
 * Verify one signature against an expected binding and a keyring.
 */
fun verifyGovernanceSignature(
    signature: GovernanceCryptoSignature,
    subject: GovernanceSubject,
    subjectId: String,
    subjectVersion: Int? = null,
    digest: Digest,
    keyring: GovernanceKeyring,
): GovernanceVerification {
    if (signature.alg != ALGORITHM) {
        return GovernanceVerification.failure("unsupported algorithm ${signature.alg}")
    }
    if (signature.digest != digest) {
        return GovernanceVerification.failure("signature covers a different digest")
    }
    val candidates = keyring.candidates(signature.signer, signature.kid)
    if (candidates.isEmpty()) {
        return GovernanceVerification.failure("unknown or revoked signer ${signature.signer}")
    }
    if (!Ed25519.isAvailable()) {
        return GovernanceVerification.failure("unavailable: ed25519")
    }
    val signatureBytes = runCatching { Base64Url.decode(signature.sig) }.getOrNull()
        ?: return GovernanceVerification.failure("signature is not valid base64url")
    val bytes = signingBytes(signature.signer, subject, subjectId, subjectVersion, digest)
    for (candidate in candidates) {
        if (Ed25519.verify(candidate, bytes, signatureBytes)) {
            return GovernanceVerification.success()
        }
    }
    return GovernanceVerification.failure("signature does not verify")
}

/**
 * Sign one versioned subject digest with every supplied signer.
 */
fun signGovernanceObject(
    subject: GovernanceSubject,
    subjectId: String,
    subjectVersion: Int? = null,
    digest: Digest,
    signers: List<GovernanceSigner>,
): GovernanceSignatureBundle {
    if (signers.isEmpty()) {
        throw GovernanceError(GovernanceCode.MANIFEST_UNSIGNED, "at least one cryptographic signer is required")
    }
    val seen = LinkedHashSet<String>()
    val signatures = ArrayList<GovernanceCryptoSignature>()
    for (signer in signers) {
        if (!seen.add(signer.signer)) {
            throw GovernanceError(GovernanceCode.MANIFEST_UNSIGNED, "${signer.signer} signed twice")
        }
        signatures += signGovernanceDigest(signer, subject, subjectId, subjectVersion, digest)
    }
    return GovernanceSignatureBundle(
        kind = "governance-signatures",
        subject = subject,
        subjectId = subjectId,
        subjectVersion = subjectVersion,
        subjectDigest = digest,
        signatures = signatures,
    )
}

/**
 * Verify a bundle against the expected binding, the keyring and an optional required
 * signer list. Every mismatch is reported; the bundle is accepted only when no reason
 * remains.
 */
fun verifyGovernanceBundle(
    bundle: GovernanceSignatureBundle,
    expected: GovernanceExpectation,
    keyring: GovernanceKeyring,
    requiredSigners: List<String>? = null,
): GovernanceVerification {
    val reasons = ArrayList<String>()
    if (bundle.kind != "governance-signatures") {
        reasons += "not a governance signature bundle"
    }
    if (bundle.subject != expected.subject) {
        reasons += "subject mismatch: ${bundle.subject.wire}"
    }
    if (bundle.subjectId != expected.subjectId) {
        reasons += "subject id mismatch: ${bundle.subjectId}"
    }
    if (bundle.subjectVersion != expected.subjectVersion) {
        reasons += "subject version mismatch"
    }
    if (bundle.subjectDigest != expected.digest) {
        reasons += "bundle digest does not match the subject"
    }
    if (bundle.signatures.isEmpty()) {
        reasons += "bundle has no signatures"
    }
    val seen = LinkedHashSet<String>()
    for (signature in bundle.signatures) {
        if (!seen.add(signature.signer)) {
            reasons += "${signature.signer} signed twice"
            continue
        }
        val verified = verifyGovernanceSignature(
            signature = signature,
            subject = expected.subject,
            subjectId = expected.subjectId,
            subjectVersion = expected.subjectVersion,
            digest = expected.digest,
            keyring = keyring,
        )
        reasons += verified.reasons
    }
    if (requiredSigners != null) {
        for (required in requiredSigners) {
            if (!seen.contains(required)) {
                reasons += "missing required signer $required"
            }
        }
    }
    return GovernanceVerification(reasons.isEmpty(), reasons.toList())
}

/**
 * The signed bytes: canonical JSON of the binding, with `subjectVersion` present and
 * `null` when the subject has no version, exactly as the reference builds it.
 */
private fun signingBytes(
    signer: String,
    subject: GovernanceSubject,
    subjectId: String,
    subjectVersion: Int?,
    digest: Digest,
): ByteArray {
    val payload = jsonObj(
        "signer" to JsonValue.Str(signer),
        "subject" to JsonValue.Str(subject.wire),
        "subjectId" to JsonValue.Str(subjectId),
        "subjectVersion" to (subjectVersion?.let { jsonNumber(it) } ?: JsonValue.Null),
        "digest" to JsonValue.Str(digest),
    )
    return CanonicalJson.encode(payload).toByteArray(Charsets.UTF_8)
}

private const val ALGORITHM: String = "ed25519"
private const val ED25519_SIGNATURE_BYTES: Int = 64
