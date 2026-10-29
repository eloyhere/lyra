package com.eloykim.lyra.amp.protocol.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Signing boundary: the protocol never needs the private key type to leak. */
interface AmpSigner {
    val kid: String
    fun sign(data: ByteArray): ByteArray
    fun publicKey(): PublicKey?
}

class JcaEd25519Signer(
    private val keyPair: KeyPair,
    override val kid: String,
) : AmpSigner {

    override fun sign(data: ByteArray): ByteArray {
        val signature = Signature.getInstance(ALGORITHM)
        signature.initSign(keyPair.private)
        signature.update(data)
        return signature.sign()
    }

    override fun publicKey(): PublicKey = keyPair.public

    companion object {
        const val ALGORITHM: String = "Ed25519"
    }
}

/**
 * Ed25519 adapter over the JCA provider.
 *
 * Android added Ed25519 in API 33; on older devices [isAvailable] is false and
 * callers must treat a manifest as unsigned rather than fabricating a signature.
 * A bundled fallback can be added behind the same interface without changing
 * protocol objects.
 */
object Ed25519 {

    fun isAvailable(): Boolean = runCatching { KeyPairGenerator.getInstance(JcaEd25519Signer.ALGORITHM) }.isSuccess

    fun generate(kid: String): JcaEd25519Signer? = runCatching {
        val generator = KeyPairGenerator.getInstance(JcaEd25519Signer.ALGORITHM)
        JcaEd25519Signer(generator.generateKeyPair(), kid)
    }.getOrNull()

    fun verify(publicKey: PublicKey, data: ByteArray, signatureBytes: ByteArray): Boolean = runCatching {
        val signature = Signature.getInstance(JcaEd25519Signer.ALGORITHM)
        signature.initVerify(publicKey)
        signature.update(data)
        signature.verify(signatureBytes)
    }.getOrDefault(false)

    fun encodePublicKey(publicKey: PublicKey): ByteArray = publicKey.encoded

    fun decodePublicKey(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance(JcaEd25519Signer.ALGORITHM).generatePublic(X509EncodedKeySpec(encoded))

    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val signature = Signature.getInstance(JcaEd25519Signer.ALGORITHM)
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }
}
