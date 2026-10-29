package com.eloykim.lyra.amp.protocol.json

/**
 * Strict JSON parser for the protocol surface.
 *
 * It accepts the JSON grammar (RFC 8259), keeps object member order, and rejects
 * trailing content. Numbers are stored as IEEE-754 doubles, matching JavaScript's
 * JSON.parse and therefore the reference implementation's digest behaviour.
 */
class JsonParseException(message: String, val offset: Int) :
    IllegalArgumentException("$message (offset $offset)")

object JsonParser {

    fun parse(text: String): JsonValue = Parser(text).parseDocument()

    private class Parser(private val text: String) {

        private var index: Int = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = readValue(0)
            skipWhitespace()
            if (index != text.length) {
                fail("JSON 结束后仍有内容")
            }
            return value
        }

        private fun readValue(depth: Int): JsonValue {
            if (depth > 256) fail("JSON 嵌套过深")
            skipWhitespace()
            if (index >= text.length) fail("意外的结尾")
            return when (val ch = text[index]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> JsonValue.Str(readString())
                't' -> readLiteral("true", JsonValue.Bool(true))
                'f' -> readLiteral("false", JsonValue.Bool(false))
                'n' -> readLiteral("null", JsonValue.Null)
                else -> {
                    if (ch == '-' || ch.isDigit()) readNumber() else fail("非法字符 '$ch'")
                }
            }
        }

        private fun readObject(depth: Int): JsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index += 1
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("对象的键必须是字符串")
                val key = readString()
                skipWhitespace()
                expect(':')
                val value = readValue(depth)
                fields[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index += 1
                        skipWhitespace()
                        if (peek() == '}') fail("对象不允许尾随逗号")
                    }

                    '}' -> {
                        index += 1
                        return JsonValue.Obj(fields)
                    }

                    else -> fail("对象成员之间缺少 ',' 或 '}'")
                }
            }
        }

        private fun readArray(depth: Int): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index += 1
                return JsonValue.Arr(items)
            }
            while (true) {
                val value = readValue(depth)
                items += value
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index += 1
                        skipWhitespace()
                        if (peek() == ']') fail("数组不允许尾随逗号")
                    }

                    ']' -> {
                        index += 1
                        return JsonValue.Arr(items)
                    }

                    else -> fail("数组元素之间缺少 ',' 或 ']'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (index < text.length) {
                val ch = text[index]
                index += 1
                when {
                    ch == '"' -> return builder.toString()
                    ch == '\\' -> {
                        if (index >= text.length) fail("字符串转义未完成")
                        val escape = text[index]
                        index += 1
                        when (escape) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000c')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> builder.append(readUnicodeEscape())
                            else -> fail("非法字符串转义 \\$escape")
                        }
                    }

                    ch < ' ' -> fail("字符串中不允许未转义的控制字符")
                    else -> builder.append(ch)
                }
            }
            fail("字符串未闭合")
        }

        private fun readUnicodeEscape(): Char {
            if (index + 4 > text.length) fail("\\u 转义不完整")
            var value = 0
            repeat(4) {
                val digit = Character.digit(text[index], 16)
                if (digit < 0) fail("\\u 转义包含非十六进制字符")
                value = value * 16 + digit
                index += 1
            }
            return value.toChar()
        }

        private fun readNumber(): JsonValue.Num {
            val start = index
            if (peek() == '-') index += 1
            if (index >= text.length || !text[index].isDigit()) fail("数字缺少整数部分")
            if (text[index] == '0') {
                index += 1
            } else {
                while (index < text.length && text[index].isDigit()) index += 1
            }
            if (index < text.length && text[index] == '.') {
                index += 1
                if (index >= text.length || !text[index].isDigit()) fail("数字缺少小数部分")
                while (index < text.length && text[index].isDigit()) index += 1
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index += 1
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index += 1
                if (index >= text.length || !text[index].isDigit()) fail("指数缺少数字")
                while (index < text.length && text[index].isDigit()) index += 1
            }
            val raw = text.substring(start, index)
            val number = raw.toDoubleOrNull() ?: fail("非法数字 $raw")
            if (!number.isFinite()) fail("数字必须是有限值")
            return JsonValue.Num(number)
        }

        private fun readLiteral(literal: String, value: JsonValue): JsonValue {
            if (!text.startsWith(literal, index)) fail("非法字面量")
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length) {
                when (text[index]) {
                    ' ', '\t', '\n', '\r' -> index += 1
                    else -> return
                }
            }
        }

        private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

        private fun expect(ch: Char) {
            if (index >= text.length || text[index] != ch) fail("期望 '$ch'")
            index += 1
        }

        private fun fail(message: String): Nothing = throw JsonParseException(message, index)
    }
}
