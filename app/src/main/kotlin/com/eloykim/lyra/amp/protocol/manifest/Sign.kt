package com.eloykim.lyra.amp.protocol.manifest

import com.eloykim.lyra.amp.protocol.crypto.AmpSigner
import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.crypto.JcaEd25519Signer
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.semantic.Base64Url
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Signature
import com.eloykim.lyra.amp.protocol.types.SignatureRole
import java.security.PublicKey

/**
 * Manifest signing and verification, mirroring `sign.ts`.
 *
 * The digest covers the unsigned body; the signature covers the canonical body
 * plus that digest, with `signatures` excluded. Verification always recomputes
 * the digest first, so a tampered body cannot ride on an old signature.
 */

data class VerifyResult(
    val ok: Boolean,
    val reasons: List<String>,
) {

    /** True when the failure is a missing platform crypto provider, not a bad signature. */
    val unavailable: Boolean
        get() = reasons.any { it.startsWith(UNAVAILABLE_PREFIX) }

    companion object {

        const val UNAVAILABLE_PREFIX: String = "unavailable: "

        fun success(): VerifyResult = VerifyResult(true, emptyList())

        fun failure(vararg reasons: String): VerifyResult = VerifyResult(false, reasons.toList())

        fun failure(reasons: List<String>): VerifyResult = VerifyResult(false, reasons)

        /** Typed failure used when Ed25519 is absent; never a fabricated signature. */
        fun unavailable(what: String): VerifyResult = VerifyResult(false, listOf("$UNAVAILABLE_PREFIX$what"))
    }
}

/**
 * Thrown instead of inventing a signature when the platform cannot perform
 * Ed25519 (Android before API 33 without a bundled provider).
 */
class SignatureUnavailableException(
    val what: String,
    cause: Throwable? = null,
) : IllegalStateException("unavailable: $what", cause)

/** Digest of the unsigned manifest body (no `digest`, no `signatures`). */
fun contentDigest(manifest: UnsignedManifest): Digest = CanonicalJson.digest(manifest.toJson())

/** Generates an Ed25519 signing key, mirroring `generateSigningKey`. */
fun generateSigningKey(kid: Id): JcaEd25519Signer =
    Ed25519.generate(kid) ?: throw SignatureUnavailableException("ed25519")

internal fun contentDigestOf(manifest: UnsignedManifest): Digest = contentDigest(manifest)

/** Sign the canonical manifest. The digest is recomputed here, never trusted. */
fun signManifest(unsigned: UnsignedManifest, signer: AmpSigner): Manifest {
    requireEd25519()
    val digest = contentDigest(unsigned)
    val body = unsigned.toJson().copyWith("digest", JsonValue.Str(digest))
    val signature = try {
        signer.sign(signingBytes(body))
    } catch (error: Exception) {
        throw SignatureUnavailableException("ed25519 signing failed: ${error.message}", error)
    }
    if (signature.size != ED25519_SIGNATURE_BYTES) {
        throw SignatureUnavailableException("ed25519 signing returned ${signature.size} bytes")
    }
    return unsigned.toManifest(
        digest = digest,
        signatures = listOf(
            Signature(
                role = SignatureRole.SELF,
                alg = ALGORITHM,
                kid = signer.kid,
                sig = Base64Url.encode(signature),
            ),
        ),
    )
}

/** Recomputes the digest, then verifies the `self` signature. */
fun verifyManifest(manifest: Manifest, publicKey: PublicKey): VerifyResult {
    if (!Ed25519.isAvailable()) {
        return VerifyResult.unavailable("ed25519")
    }
    val reasons = ArrayList<String>()

    val unsigned = manifest.unsignedJson()
    if (CanonicalJson.digest(unsigned) != manifest.digest) {
        reasons += "digest does not cover the manifest content"
    }

    val signature = manifest.signatures.firstOrNull { it.role == SignatureRole.SELF }
    if (signature == null) {
        reasons += "missing self signature"
        return VerifyResult(false, reasons)
    }

    val signatureBytes = try {
        Base64Url.decode(signature.sig)
    } catch (error: IllegalArgumentException) {
        reasons += "signature is not valid base64url"
        return VerifyResult(false, reasons)
    }

    if (signatureBytes.size != ED25519_SIGNATURE_BYTES) {
        reasons += "signature is not a 64-byte ed25519 signature"
        return VerifyResult(false, reasons)
    }

    val body = unsigned.copyWith("digest", JsonValue.Str(manifest.digest))
    if (!Ed25519.verify(publicKey, signingBytes(body), signatureBytes)) {
        reasons += "signature does not verify"
    }

    return VerifyResult(reasons.isEmpty(), reasons)
}

/** Distribution and permanent revocation for manifest verification keys. */
class KeyRegistry {

    private val keys = LinkedHashMap<Id, PublicKey>()
    private val revoked = LinkedHashSet<Id>()

    fun add(kid: Id, publicKey: PublicKey) {
        keys[kid] = publicKey
    }

    /** Revocation is permanent: a revoked kid can never resolve again. */
    fun revoke(kid: Id) {
        revoked += kid
    }

    fun get(kid: Id): PublicKey? = if (revoked.contains(kid)) null else keys[kid]

    fun isRevoked(kid: Id): Boolean = revoked.contains(kid)

    fun kids(): Set<Id> = keys.keys.toSet()
}

/** Verifies the `self` signature with the key the registry resolves for its kid. */
fun verifyWithRegistry(manifest: Manifest, registry: KeyRegistry): VerifyResult {
    val signature = manifest.signatures.firstOrNull { it.role == SignatureRole.SELF }
        ?: return VerifyResult.failure("missing self signature")
    val publicKey = registry.get(signature.kid)
        ?: return VerifyResult.failure("unknown or revoked key: ${signature.kid}")
    return verifyManifest(manifest, publicKey)
}

internal fun requireEd25519() {
    if (!Ed25519.isAvailable()) {
        throw SignatureUnavailableException("ed25519")
    }
}

private fun signingBytes(body: JsonValue.Obj): ByteArray =
    CanonicalJson.encode(body).toByteArray(Charsets.UTF_8)

internal const val ALGORITHM: String = "ed25519"
private const val ED25519_SIGNATURE_BYTES: Int = 64
