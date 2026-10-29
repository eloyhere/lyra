package com.eloykim.lyra.amp.protocol.manifest

import com.eloykim.lyra.amp.protocol.crypto.AmpSigner
import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.semantic.Base64Url
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Signature
import com.eloykim.lyra.amp.protocol.types.SignatureRole
import com.eloykim.lyra.amp.protocol.types.Timestamp
import java.security.PublicKey

/**
 * Federation bundles and third-party attestations, mirroring `federation.ts`.
 *
 * Import fails closed: every check must pass before the manifest is usable.
 * The only permitted degradation is "not endorsed", never "less verified".
 */

data class BundleOptions(
    val issued: Timestamp,
    val expires: Timestamp,
    val revoked: List<String>? = null,
    val attestations: List<Attestation>? = null,
)

data class ImportOptions(
    val now: Timestamp,
    val revoked: List<String> = emptyList(),
    /** The caller's trust root for third-party attestations. */
    val issuerKeys: KeyRegistry? = null,
)

data class ImportResult(
    val ok: Boolean,
    val reasons: List<String>,
    val manifest: Manifest? = null,
    /** True only for an attestation bound to the digest and signed by a trusted issuer key. */
    val attested: Boolean,
    val attestations: List<Id>,
)

/** Digest covers manifest + keys + revocation + validity window. */
fun bundleDigest(bundle: ManifestBundle): Digest =
    bundleDigest(bundle.manifest, bundle.keys, bundle.revoked ?: emptyList(), bundle.issued, bundle.expires)

fun bundleDigest(
    manifest: Manifest,
    keys: List<KeyMaterial>,
    revoked: List<String>,
    issued: Timestamp,
    expires: Timestamp,
): Digest = CanonicalJson.digest(
    jsonObj(
        "manifest" to manifest.toJson(),
        "keys" to jsonArr(*keys.map { it.toJson() }.toTypedArray()),
        "revoked" to jsonStrings(revoked),
        "issued" to JsonValue.Str(issued),
        "expires" to JsonValue.Str(expires),
    ),
)

fun exportBundle(manifest: Manifest, signer: AmpSigner, options: BundleOptions): ManifestBundle {
    val publicKey = signer.publicKey() ?: throw SignatureUnavailableException("ed25519 public key unavailable")
    val keys = listOf(KeyMaterial(kid = signer.kid, publicKey = Ed25519Keys.encodePublicKey(publicKey)))
    val revoked = options.revoked ?: emptyList()
    val digest = bundleDigest(manifest, keys, revoked, options.issued, options.expires)
    return ManifestBundle(
        manifest = manifest,
        keys = keys,
        issued = options.issued,
        expires = options.expires,
        digest = digest,
        revoked = revoked,
        attestations = options.attestations,
    )
}

/**
 * Verifies and imports a bundle. `issuerKeys` is the caller's trust root for
 * attestations; the bundle carries the manifest signer's keys, not the
 * issuer's, so an attestation is never trusted just because it is inside it.
 */
fun importBundle(bundle: ManifestBundle, options: ImportOptions): ImportResult {
    val reasons = ArrayList<String>()

    if (bundleDigest(bundle) != bundle.digest) {
        reasons += "bundle digest does not cover its content"
    }

    val nowMs = instantOf(options.now)?.toEpochMilli()
    if (nowMs == null) {
        reasons += "now is not a valid timestamp"
    }
    val expiresMs = instantOf(bundle.expires)?.toEpochMilli()
    if (nowMs != null && expiresMs != null && expiresMs + DEFAULT_CLOCK_SKEW_MS < nowMs) {
        reasons += "bundle expired"
    }
    val issuedMs = instantOf(bundle.issued)?.toEpochMilli()
    if (nowMs != null && issuedMs != null && issuedMs - DEFAULT_CLOCK_SKEW_MS > nowMs) {
        reasons += "bundle issued in the future"
    }

    checkManifestWindow(bundle.manifest, options.now, DEFAULT_CLOCK_SKEW_MS).reasons.forEach { reason ->
        reasons += "manifest $reason"
    }

    val signature = bundle.manifest.signatures.firstOrNull { it.role == SignatureRole.SELF }
    if (signature == null) {
        reasons += "manifest has no self signature"
    }

    val key = signature?.let { current -> bundle.keys.firstOrNull { it.kid == current.kid } }
    if (signature != null && key == null) {
        reasons += "missing key for kid ${signature.kid}"
    }

    val revoked = LinkedHashSet<String>()
    revoked += bundle.revoked.orEmpty()
    revoked += options.revoked
    if (signature != null && revoked.contains(signature.kid)) {
        reasons += "key revoked: ${signature.kid}"
    }

    if (signature != null && key != null) {
        try {
            val publicKey = Ed25519Keys.decodePublicKey(key.publicKey)
            verifyManifest(bundle.manifest, publicKey).reasons.forEach { reason -> reasons += reason }
        } catch (error: IllegalArgumentException) {
            reasons += "public key cannot be imported"
        }
    }

    val endorsed = ArrayList<Id>()
    bundle.attestations.orEmpty().forEach { attestation ->
        if (attestation.subject.digest != bundle.manifest.digest) {
            return@forEach
        }
        val attestationExpires = instantOf(attestation.expires)?.toEpochMilli() ?: return@forEach
        if (nowMs != null && attestationExpires + DEFAULT_CLOCK_SKEW_MS < nowMs) {
            return@forEach
        }
        val attestationIssued = instantOf(attestation.issued)?.toEpochMilli()
        if (nowMs != null && attestationIssued != null && attestationIssued - DEFAULT_CLOCK_SKEW_MS > nowMs) {
            return@forEach
        }
        val issuerKid = attestation.signatures.firstOrNull()?.kid ?: return@forEach
        val issuerKey = options.issuerKeys?.get(issuerKid) ?: return@forEach
        if (!verifyAttestationWithKey(attestation, issuerKey).ok) {
            return@forEach
        }
        endorsed += attestation.id
    }

    return ImportResult(
        ok = reasons.isEmpty(),
        reasons = reasons,
        manifest = if (reasons.isEmpty()) bundle.manifest else null,
        attested = endorsed.isNotEmpty(),
        attestations = endorsed,
    )
}

/** Imports a bundle's keys into a registry; revoked kids stay revoked. */
fun registryFromBundle(bundle: ManifestBundle): KeyRegistry {
    val registry = KeyRegistry()
    bundle.keys.forEach { material ->
        registry.add(material.kid, Ed25519Keys.decodePublicKey(material.publicKey))
    }
    bundle.revoked.orEmpty().forEach { kid -> registry.revoke(kid) }
    return registry
}

/** Signs a third-party endorsement of a specific manifest digest. */
fun attest(
    id: Id,
    issuer: Ref,
    manifest: Manifest,
    scope: List<String>,
    issued: Timestamp,
    expires: Timestamp,
    signer: AmpSigner,
): Attestation {
    val body = jsonObj(
        "amp" to JsonValue.Str(AmpWire.VERSION),
        "kind" to JsonValue.Str("attestation"),
        "id" to JsonValue.Str(id),
        "issuer" to issuer.toJson(),
        "subject" to jsonObj(
            "kind" to JsonValue.Str("manifest"),
            "id" to JsonValue.Str(manifest.agent.id),
            "digest" to JsonValue.Str(manifest.digest),
        ),
        "scope" to jsonStrings(scope),
        "issued" to JsonValue.Str(issued),
        "expires" to JsonValue.Str(expires),
    )
    requireEd25519()
    val signatureBytes = try {
        signer.sign(CanonicalJson.encode(body).toByteArray(Charsets.UTF_8))
    } catch (error: Exception) {
        throw SignatureUnavailableException("ed25519 signing failed: ${error.message}", error)
    }
    if (signatureBytes.size != ATTESTATION_SIGNATURE_BYTES) {
        throw SignatureUnavailableException("ed25519 signing returned ${signatureBytes.size} bytes")
    }
    return Attestation(
        id = id,
        issuer = issuer,
        subject = AttestationSubject(id = manifest.agent.id, digest = manifest.digest),
        scope = scope,
        issued = issued,
        expires = expires,
        signatures = listOf(
            Signature(
                role = SignatureRole.ORG,
                alg = ALGORITHM,
                kid = signer.kid,
                sig = Base64Url.encode(signatureBytes),
            ),
        ),
    )
}

/** Public-key variant so a verifier does not need the issuer's private key. */
fun verifyAttestationWithKey(attestation: Attestation, publicKey: PublicKey): VerifyResult {
    val signature = attestation.signatures.firstOrNull()
        ?: return VerifyResult.failure("attestation has no signature")
    if (!Ed25519.isAvailable()) {
        return VerifyResult.unavailable("ed25519")
    }
    val signatureBytes = try {
        Base64Url.decode(signature.sig)
    } catch (error: IllegalArgumentException) {
        return VerifyResult.failure("attestation signature is not valid base64url")
    }
    val body = attestation.toJson().without("signatures")
    val valid = Ed25519.verify(
        publicKey,
        CanonicalJson.encode(body).toByteArray(Charsets.UTF_8),
        signatureBytes,
    )
    return if (valid) VerifyResult.success() else VerifyResult.failure("attestation signature does not verify")
}

fun verifyAttestation(attestation: Attestation, publicKey: PublicKey): VerifyResult =
    verifyAttestationWithKey(attestation, publicKey)

/** Key-pair-ish variant that reads the issuer's public key from the signer. */
fun verifyAttestation(attestation: Attestation, signer: AmpSigner): VerifyResult {
    val publicKey = signer.publicKey() ?: return VerifyResult.unavailable("ed25519 public key unavailable")
    return verifyAttestationWithKey(attestation, publicKey)
}

private const val ATTESTATION_SIGNATURE_BYTES = 64
