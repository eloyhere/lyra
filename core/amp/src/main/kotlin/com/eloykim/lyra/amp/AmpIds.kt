package com.eloykim.lyra.amp

import java.security.MessageDigest

/**
 * AMP 的 id 必须匹配 `^[a-z][a-z0-9._:-]{0,63}$`。
 * 这里在构造期强制，避免把节点编号、包名直接塞进 id 导致静默不合法。
 */
object AmpIds {

    private val PATTERN = Regex("^[a-z][a-z0-9._:-]{0,63}$")

    fun isValid(id: String): Boolean = PATTERN.matches(id)

    fun require(id: String, what: String): String {
        require(isValid(id)) { "$what 不是合法的 AMP id: $id" }
        return id
    }

    /** 把任意字符串收敛成合法 id（英文/数字/点/冒号/下划线，其余转 '-'）。 */
    fun slug(raw: String, prefix: String): String {
        val body = raw.lowercase()
            .map { if (it.isLetterOrDigit() || it == '.' || it == ':' || it == '_' || it == '-') it else '-' }
            .joinToString("")
            .trim('-', '.', ':')
            .take(48)
        val head = prefix.lowercase().firstOrNull()?.takeIf { it in 'a'..'z' } ?: 'x'
        val normalized = if (body.isEmpty()) "$head.0" else "$prefix.${body.removePrefix("$head.")}"
        return require(normalized.take(64), "slug")
    }
}

object Digests {

    fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return "sha256:" + bytes.joinToString("") { "%02x".format(it) }
    }

    /** 校验 `sha256:<64 hex>`。 */
    fun isValid(digest: String): Boolean =
        Regex("^sha256:[a-f0-9]{64}$").matches(digest)
}
