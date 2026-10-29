package com.eloykim.lyra.amp.protocol.semantic

import java.util.Base64

/** Unpadded base64url, the encoding used by AMP signatures and keys. */
object Base64Url {

    fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(text: String): ByteArray {
        val padded = when (text.length % 4) {
            0 -> text
            2 -> "$text=="
            3 -> "$text="
            else -> throw IllegalArgumentException("base64url 长度非法: ${text.length}")
        }
        return Base64.getUrlDecoder().decode(padded)
    }

    fun isValid(text: String): Boolean =
        text.isNotEmpty() && text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
}
