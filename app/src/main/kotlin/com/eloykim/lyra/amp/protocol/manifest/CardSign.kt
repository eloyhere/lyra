package com.eloykim.lyra.amp.protocol.manifest

import com.eloykim.lyra.amp.protocol.crypto.AmpSigner
import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.Base64Url
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import java.security.PublicKey

/**
 * JWS JSON serialization over the JCS-canonicalised card, mirroring
 * `cardSign.ts`. The protected header is `{"alg":"EdDSA","kid":...}` encoded
 * as unpadded base64url; the signing input is `<protected>.<payload>`.
 */
data class CardSignature(
    /** Base64url-encoded JWS protected header (wire field `protected`). */
    val protectedHeader: String,
    val signature: String,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "protected" to JsonValue.Str(protectedHeader),
        "signature" to JsonValue.Str(signature),
    )

    companion object {
        fun fromJson(value: JsonValue): CardSignature {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("card signature 必须是 JSON 对象")
            return CardSignature(
                protectedHeader = obj.string("protected") ?: throw IllegalArgumentException("缺少字符串字段 protected"),
                signature = obj.string("signature") ?: throw IllegalArgumentException("缺少字符串字段 signature"),
            )
        }
    }
}

/** Signs the card with the signer's Ed25519 key; never fabricates a signature. */
fun signCard(card: AgentCard, signer: AmpSigner): CardSignature {
    if (!Ed25519.isAvailable()) {
        throw SignatureUnavailableException("ed25519")
    }
    val headerJson = """{"alg":"EdDSA","kid":${CanonicalJson.encode(JsonValue.Str(signer.kid))}}"""
    val protectedHeader = Base64Url.encode(headerJson.toByteArray(Charsets.UTF_8))
    val signatureBytes = try {
        signer.sign(signingInput(protectedHeader, card))
    } catch (error: Exception) {
        throw SignatureUnavailableException("ed25519 signing failed: ${error.message}", error)
    }
    if (signatureBytes.size != CARD_SIGNATURE_BYTES) {
        throw SignatureUnavailableException("ed25519 signing returned ${signatureBytes.size} bytes")
    }
    return CardSignature(protectedHeader, Base64Url.encode(signatureBytes))
}

/** Verifies a card signature against a caller-supplied public key. */
fun verifyCardSignature(card: AgentCard, signature: CardSignature, publicKey: PublicKey): VerifyResult {
    val header = decodeProtectedHeader(signature)
        ?: return VerifyResult.failure("protected header is not base64url JSON")
    val reasons = ArrayList<String>()
    val alg = header.string("alg")
    if (alg != "EdDSA") {
        reasons += "unsupported alg ${alg ?: "null"}"
    }
    if (!Ed25519.isAvailable()) {
        reasons += "${VerifyResult.UNAVAILABLE_PREFIX}ed25519"
        return VerifyResult(false, reasons)
    }
    val signatureBytes = try {
        Base64Url.decode(signature.signature)
    } catch (error: IllegalArgumentException) {
        reasons += "card signature is not valid base64url"
        return VerifyResult(false, reasons)
    }
    if (!Ed25519.verify(publicKey, signingInput(signature.protectedHeader, card), signatureBytes)) {
        reasons += "card signature does not verify"
    }
    return VerifyResult(reasons.isEmpty(), reasons)
}

/**
 * Resolves the signing key from a trust registry (which federation bundles can
 * populate) instead of trusting a key embedded in the card itself.
 */
fun verifyCardWithRegistry(card: AgentCard, signature: CardSignature, registry: KeyRegistry): VerifyResult {
    val header = decodeProtectedHeader(signature)
        ?: return VerifyResult.failure("protected header is not base64url JSON")
    val kid = header.string("kid")
    if (kid.isNullOrEmpty()) {
        return VerifyResult.failure("signature carries no kid")
    }
    val publicKey = registry.get(kid)
        ?: return VerifyResult.failure("unknown or revoked key: $kid")
    return verifyCardSignature(card, signature, publicKey)
}

private fun decodeProtectedHeader(signature: CardSignature): JsonValue.Obj? = runCatching {
    val bytes = Base64Url.decode(signature.protectedHeader)
    JsonParser.parse(String(bytes, Charsets.UTF_8)) as? JsonValue.Obj
}.getOrNull()

private fun signingInput(protectedHeader: String, card: AgentCard): ByteArray {
    val payload = CanonicalJson.encode(card.signingPayloadJson()).toByteArray(Charsets.UTF_8)
    return "$protectedHeader.${Base64Url.encode(payload)}".toByteArray(Charsets.UTF_8)
}

private const val CARD_SIGNATURE_BYTES: Int = 64
