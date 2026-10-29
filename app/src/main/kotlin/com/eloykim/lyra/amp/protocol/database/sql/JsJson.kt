package com.eloykim.lyra.amp.protocol.database.sql

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson

/**
 * `JSON.stringify` for values stored in TEXT columns.
 *
 * The reference columns `messages.body`, `outbox.payload`,
 * `runtime_turns.data` and `*_json` are written with `JSON.stringify`, not with
 * the canonical encoder: object keys keep insertion order and no key is sorted.
 * The persisted text is observable, so this port keeps that distinction.
 *
 * Number formatting is delegated to [CanonicalJson.formatNumber], which already
 * implements ECMAScript `Number::toString`; the only additional rewrites are the
 * ones `JSON.stringify` performs on non-finite numbers (`null`) and `-0` (`0`).
 * This is a string encoder only: values enter the layer already parsed as
 * [JsonValue], so `JSON.stringify(undefined)` (a dropped property) cannot occur.
 */
object JsJson {

    fun encode(value: JsonValue): String = buildString { append(this, value) }

    private fun append(out: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Str -> appendString(out, value.value)
            is JsonValue.Num -> {
                val number = value.value
                out.append(if (number.isFinite()) CanonicalJson.formatNumber(number) else "null")
            }

            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    append(out, item)
                }
                out.append(']')
            }

            is JsonValue.Obj -> {
                out.append('{')
                var index = 0
                for ((key, child) in value.fields) {
                    if (index > 0) out.append(',')
                    appendString(out, key)
                    out.append(':')
                    append(out, child)
                    index += 1
                }
                out.append('}')
            }
        }
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
