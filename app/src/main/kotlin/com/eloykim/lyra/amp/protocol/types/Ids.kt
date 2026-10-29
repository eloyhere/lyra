package com.eloykim.lyra.amp.protocol.types

/** AMP id grammar shared by every object: `^[a-z][a-z0-9._:-]{0,63}$`. */
object ProtocolIds {

    private val PATTERN = Regex("^[a-z][a-z0-9._:-]{0,63}$")

    fun isValid(id: String): Boolean = PATTERN.matches(id)

    fun require(id: String, what: String): String {
        require(isValid(id)) { "$what 不是合法的 AMP id: $id" }
        return id
    }

    /**
     * Coerces arbitrary text into the AMP id grammar without silently producing
     * an invalid id. The prefix is normalised first; the body is truncated so the
     * result never exceeds 64 code units.
     */
    fun slug(raw: String, prefix: String): String {
        val safePrefix = ascii(prefix.lowercase()).trim('.', ':', '-').ifEmpty { "x" }
        val base = when {
            safePrefix.first() in 'a'..'z' -> safePrefix
            else -> "x$safePrefix"
        }
        val body = ascii(raw.lowercase()).trim('.', ':', '-').take(48)
        val candidate = if (body.isEmpty()) "$base.0" else "$base.$body"
        val trimmed = candidate.take(64)
        return if (isValid(trimmed)) trimmed else "x.${Math.abs(trimmed.hashCode())}"
    }

    private fun ascii(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> append(ch)
                ch == '.' || ch == ':' || ch == '_' || ch == '-' -> append(ch)
                else -> append('-')
            }
        }
    }
}

object AmpWire {
    const val VERSION: String = "0.1"
    const val KERNEL_VERSION: String = "0.4.1"
}
