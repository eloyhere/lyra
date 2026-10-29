package com.eloykim.lyra.amp.protocol.manifest

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Signature
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.isTimestamp

/**
 * AMP v0.1 federation wire objects: bundles and third-party attestations.
 * Mirrors `federationTypes.ts` and `docs/schemas/federation.schema.json`.
 */

data class KeyMaterial(
    val kid: String,
    /** base64url raw Ed25519 public key, 32 bytes when well formed. */
    val publicKey: String,
    val alg: String = "ed25519",
) {

    init {
        require(kid.isNotBlank()) { "key.kid 不能为空" }
        require(alg == "ed25519") { "AMP 只支持 ed25519 公钥" }
        require(publicKey.isNotBlank()) { "key.publicKey 不能为空" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "kid" to JsonValue.Str(kid),
        "alg" to JsonValue.Str(alg),
        "publicKey" to JsonValue.Str(publicKey),
    )

    companion object {
        fun fromJson(value: JsonValue): KeyMaterial {
            val obj = value.asFederationObject("key")
            return KeyMaterial(
                kid = obj.requireString("kid"),
                publicKey = obj.requireString("publicKey"),
                alg = obj.string("alg") ?: "ed25519",
            )
        }
    }
}

/**
 * A transport shape, not a new trust root: the manifest inside is already
 * signed, and the keys are the minimum needed to check it.
 */
data class ManifestBundle(
    val manifest: Manifest,
    val keys: List<KeyMaterial>,
    val issued: Timestamp,
    val expires: Timestamp,
    val digest: Digest,
    val revoked: List<String>? = null,
    val attestations: List<Attestation>? = null,
    val amp: String = AmpWire.VERSION,
    val kind: String = "bundle",
) {

    init {
        require(amp == AmpWire.VERSION) { "bundle.amp 必须是 ${AmpWire.VERSION}" }
        require(kind == "bundle") { "bundle.kind 必须是 bundle" }
        require(isTimestamp(issued)) { "bundle.issued 不是合法时间" }
        require(isTimestamp(expires)) { "bundle.expires 不是合法时间" }
        require(Sha256.isValidDigest(digest)) { "bundle.digest 格式非法" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(amp),
        "kind" to JsonValue.Str(kind),
        "manifest" to manifest.toJson(),
        "keys" to jsonArr(*keys.map { it.toJson() }.toTypedArray()),
        "revoked" to revoked?.let(::jsonStrings),
        "issued" to JsonValue.Str(issued),
        "expires" to JsonValue.Str(expires),
        "digest" to JsonValue.Str(digest),
        "attestations" to attestations?.let { list -> jsonArr(*list.map { it.toJson() }.toTypedArray()) },
    )

    companion object {
        fun fromJson(value: JsonValue): ManifestBundle {
            val obj = value.asFederationObject("bundle")
            return ManifestBundle(
                manifest = Manifest.fromJson(obj.requireObj("manifest")),
                keys = obj.requireArr("keys").items.map(KeyMaterial::fromJson),
                issued = obj.requireString("issued"),
                expires = obj.requireString("expires"),
                digest = obj.requireString("digest"),
                revoked = obj.arr("revoked")?.items?.map { item ->
                    (item as? JsonValue.Str)?.value
                        ?: throw IllegalArgumentException("bundle.revoked 的元素必须是字符串")
                },
                attestations = obj.arr("attestations")?.items?.map(Attestation::fromJson),
                amp = obj.string("amp") ?: AmpWire.VERSION,
                kind = obj.string("kind") ?: "bundle",
            )
        }
    }
}

data class AttestationSubject(
    val id: Id,
    val digest: Digest,
    val kind: String = "manifest",
) {

    init {
        ProtocolIds.require(id, "subject.id")
        require(kind == "manifest") { "attestation.subject.kind 必须是 manifest" }
        require(Sha256.isValidDigest(digest)) { "attestation.subject.digest 格式非法" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to JsonValue.Str(kind),
        "id" to JsonValue.Str(id),
        "digest" to JsonValue.Str(digest),
    )

    companion object {
        fun fromJson(value: JsonValue): AttestationSubject {
            val obj = value.asFederationObject("attestation subject")
            return AttestationSubject(
                id = obj.requireString("id"),
                digest = obj.requireString("digest"),
                kind = obj.string("kind") ?: "manifest",
            )
        }
    }
}

data class Attestation(
    val id: Id,
    val issuer: Ref,
    val subject: AttestationSubject,
    val scope: List<String>,
    val issued: Timestamp,
    val expires: Timestamp,
    val signatures: List<Signature>,
    val amp: String = AmpWire.VERSION,
    val kind: String = "attestation",
) {

    init {
        ProtocolIds.require(id, "attestation.id")
        require(amp == AmpWire.VERSION) { "attestation.amp 必须是 ${AmpWire.VERSION}" }
        require(kind == "attestation") { "attestation.kind 必须是 attestation" }
        require(isTimestamp(issued)) { "attestation.issued 不是合法时间" }
        require(isTimestamp(expires)) { "attestation.expires 不是合法时间" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(amp),
        "kind" to JsonValue.Str(kind),
        "id" to JsonValue.Str(id),
        "issuer" to issuer.toJson(),
        "subject" to subject.toJson(),
        "scope" to jsonStrings(scope),
        "issued" to JsonValue.Str(issued),
        "expires" to JsonValue.Str(expires),
        "signatures" to jsonArr(*signatures.map { it.toJson() }.toTypedArray()),
    )

    companion object {
        fun fromJson(value: JsonValue): Attestation {
            val obj = value.asFederationObject("attestation")
            return Attestation(
                id = obj.requireString("id"),
                issuer = Ref.fromJson(obj.requireObj("issuer")),
                subject = AttestationSubject.fromJson(obj.requireObj("subject")),
                scope = obj.requireArr("scope").items.map { item ->
                    (item as? JsonValue.Str)?.value
                        ?: throw IllegalArgumentException("attestation.scope 的元素必须是字符串")
                },
                issued = obj.requireString("issued"),
                expires = obj.requireString("expires"),
                signatures = obj.requireArr("signatures").items.map(Signature::fromJson),
                amp = obj.string("amp") ?: AmpWire.VERSION,
                kind = obj.string("kind") ?: "attestation",
            )
        }
    }
}

private fun JsonValue.asFederationObject(what: String): JsonValue.Obj =
    this as? JsonValue.Obj ?: throw IllegalArgumentException("$what 必须是 JSON 对象")
