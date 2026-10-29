package com.eloykim.lyra.amp.protocol.module

/**
 * 在工具结果进入模型上下文、Receipt 或 outbox 之前遮蔽常见密钥形状
 * （`src/module/tool/redact.ts` 的移植）。这是安全网，不是 DLP 系统。
 */
data class Redaction(
    val text: String,
    val redacted: Boolean,
)

private val SECRET_PATTERNS: List<Regex> = listOf(
    Regex("""\bsk-[A-Za-z0-9_-]{16,}\b"""),
    Regex("""\bBearer\s+[A-Za-z0-9._~+/=-]{12,}\b""", RegexOption.IGNORE_CASE),
    Regex("""\bAKIA[0-9A-Z]{16}\b"""),
    Regex("""\b(?:password|passwd|secret|token)\s*[:=]\s*["']?[^\s"',;]{6,}""", RegexOption.IGNORE_CASE),
    Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
)

fun redactSecrets(text: String): Redaction {
    var redacted: Boolean = false
    var output: String = text
    SECRET_PATTERNS.forEach { pattern ->
        output = pattern.replace(output) {
            redacted = true
            "[REDACTED]"
        }
    }
    return Redaction(output, redacted)
}
