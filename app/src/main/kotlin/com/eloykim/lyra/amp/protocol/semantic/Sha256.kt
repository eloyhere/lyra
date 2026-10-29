package com.eloykim.lyra.amp.protocol.semantic

import java.security.MessageDigest

/** SHA-256 helpers shared by digests, chain hashes and signatures. */
object Sha256 {

    fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun digest(text: String): ByteArray = digest(text.toByteArray(Charsets.UTF_8))

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    fun hex(text: String): String = hex(digest(text))

    /** Legacy text digest shape: `sha256:<hex>` over raw UTF-8 text. */
    fun digestString(text: String): String = "sha256:" + hex(text)

    fun isValidDigest(digest: String): Boolean =
        DIGEST_PATTERN.matches(digest)

    private val HEX = "0123456789abcdef".toCharArray()
    private val DIGEST_PATTERN = Regex("^sha256:[0-9a-f]{64}$")
}
