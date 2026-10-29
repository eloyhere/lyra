package com.eloykim.lyra.amp.protocol.semantic

import com.eloykim.lyra.amp.protocol.json.JsonValue
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Canonical JSON used for digests and signatures.
 *
 * Semantics mirror the reference implementation (`src/semantic/canonical.ts`):
 * keys sorted lexicographically by UTF-16 code unit, no insignificant whitespace,
 * and JavaScript `JSON.stringify` number formatting. Sorting recursively is what
 * makes an object's digest independent of construction order.
 */
object CanonicalJson {

    fun encode(value: JsonValue): String = buildString { appendValue(this, value) }

    fun digest(value: JsonValue): String = "sha256:" + Sha256.hex(encode(value))

    fun digestHex(value: JsonValue): String = Sha256.hex(encode(value))

    private fun appendValue(out: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Str -> appendString(out, value.value)
            is JsonValue.Num -> out.append(formatNumber(value.value))
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    appendValue(out, item)
                }
                out.append(']')
            }

            is JsonValue.Obj -> {
                out.append('{')
                val keys = value.fields.keys.sorted()
                keys.forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    appendString(out, key)
                    out.append(':')
                    appendValue(out, value.fields.getValue(key))
                }
                out.append('}')
            }
        }
    }

    /**
     * ECMAScript `Number::toString` formatting for the canonical form.
     *
     * `BigDecimal.valueOf` starts from the shortest round-tripping decimal
     * (`Double.toString`), and this method rearranges its digits using the
     * ECMAScript plain-vs-exponential thresholds: plain for decimal exponents in
     * (-6, 21], exponential outside of that window.
     */
    fun formatNumber(value: Double): String {
        require(value.isFinite()) { "canonical JSON 不允许 NaN/Infinity" }
        if (value == 0.0) return "0"

        // Java's Double.toString is already a shortest round-tripping decimal
        // for normal values, but not always for subnormals (5e-324 prints as
        // 4.9E-324). Try progressively shorter significands and keep the first
        // one that parses back to the same double; this matches ECMAScript's
        // shortest-representation rule.
        val base = BigDecimal.valueOf(value).stripTrailingZeros()
        var chosen = base
        if (base.precision() > 1) {
            for (precision in 1 until base.precision()) {
                val candidate = base.round(MathContext(precision, RoundingMode.HALF_EVEN))
                if (candidate.toDouble() == value) {
                    chosen = candidate.stripTrailingZeros()
                    break
                }
            }
        }

        val negative = chosen.signum() < 0
        val digits = chosen.unscaledValue().abs().toString()
        val exponent = digits.length - chosen.scale()
        val body = buildString {
            when {
                digits.length <= exponent && exponent <= 21 -> {
                    append(digits)
                    repeat(exponent - digits.length) { append('0') }
                }

                0 < exponent && exponent <= 21 -> {
                    append(digits, 0, exponent)
                    append('.')
                    append(digits, exponent, digits.length)
                }

                -6 < exponent && exponent <= 0 -> {
                    append("0.")
                    repeat(-exponent) { append('0') }
                    append(digits)
                }

                else -> {
                    append(digits[0])
                    if (digits.length > 1) {
                        append('.')
                        append(digits, 1, digits.length)
                    }
                    append('e')
                    val power = exponent - 1
                    if (power >= 0) append('+')
                    append(power)
                }
            }
        }
        return if (negative) "-$body" else body
    }

    private fun appendString(out: StringBuilder, value: String) {
        out.append('"')
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\b' -> out.append("\\b")
                ch == '\u000c' -> out.append("\\f")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch < ' ' -> appendUnicodeEscape(out, ch)
                ch.isHighSurrogate() -> {
                    if (index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                        out.append(ch)
                        out.append(value[index + 1])
                        index += 1
                    } else {
                        appendUnicodeEscape(out, ch)
                    }
                }

                ch.isLowSurrogate() -> appendUnicodeEscape(out, ch)
                else -> out.append(ch)
            }
            index += 1
        }
        out.append('"')
    }

    private fun appendUnicodeEscape(out: StringBuilder, ch: Char) {
        out.append("\\u")
        val value = ch.code
        out.append(HEX[(value ushr 12) and 0x0f])
        out.append(HEX[(value ushr 8) and 0x0f])
        out.append(HEX[(value ushr 4) and 0x0f])
        out.append(HEX[value and 0x0f])
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
