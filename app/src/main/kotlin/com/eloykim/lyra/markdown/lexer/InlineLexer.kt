package com.eloykim.lyra.markdown.lexer

/**
 * 行内词法分析器。
 *
 * 处理反引号代码、强调、删除线、链接/图片、自动链接、反斜杠转义与换行。
 * 强调类节点会递归解析内部文本，产出带 children 的词法单元。
 *
 * 设计上只做「识别」，不生成 HTML：HTML 由 AST 渲染器统一转义和输出。
 */
internal object InlineLexer {

    fun tokenize(text: String): List<InlineToken> = lex(text)

    private fun lex(text: String): List<InlineToken> {
        val tokens = ArrayList<InlineToken>()
        val buffer = StringBuilder()
        var index = 0

        fun flush() {
            if (buffer.isNotEmpty()) {
                tokens += InlineToken.Text(buffer.toString())
                buffer.clear()
            }
        }

        while (index < text.length) {
            val char = text[index]
            when {
                char == '\\' && index + 1 < text.length -> {
                    val next = text[index + 1]
                    if (next == '\n') {
                        flush()
                        tokens += InlineToken.HardBreak
                        index += 2
                    } else if (isEscapable(next)) {
                        buffer.append(next)
                        index += 2
                    } else {
                        buffer.append(char)
                        index++
                    }
                }

                char == '`' -> {
                    val run = runLength(text, index, '`')
                    val close = findBacktickClose(text, index + run, run)
                    if (close >= 0) {
                        flush()
                        tokens += InlineToken.CodeSpan(normalizeCode(text.substring(index + run, close)))
                        index = close + run
                    } else {
                        buffer.append(char)
                        index++
                    }
                }

                char == '!' && index + 1 < text.length && text[index + 1] == '[' -> {
                    val image = parseImage(text, index)
                    if (image != null) {
                        flush()
                        tokens += image.token
                        index = image.end
                    } else {
                        buffer.append(char)
                        index++
                    }
                }

                char == '[' -> {
                    val link = parseLink(text, index)
                    if (link != null) {
                        flush()
                        tokens += link.token
                        index = link.end
                    } else {
                        buffer.append(char)
                        index++
                    }
                }

                char == '<' -> {
                    val auto = parseAutolink(text, index)
                    if (auto != null) {
                        flush()
                        tokens += auto.token
                        index = auto.end
                    } else {
                        buffer.append(char)
                        index++
                    }
                }

                char == '*' || char == '_' -> {
                    val emphasis = parseEmphasis(text, index, char)
                    if (emphasis != null) {
                        flush()
                        tokens += emphasis.token
                        index = emphasis.end
                    } else {
                        buffer.append(char)
                        index++
                    }
                }

                char == '~' && index + 1 < text.length && text[index + 1] == '~' -> {
                    val strike = parseStrikethrough(text, index)
                    if (strike != null) {
                        flush()
                        tokens += strike.token
                        index = strike.end
                    } else {
                        buffer.append(char)
                        index++
                    }
                }

                char == '\n' -> {
                    val hard = buffer.length >= 2 &&
                        buffer[buffer.length - 1] == ' ' &&
                        buffer[buffer.length - 2] == ' '
                    while (buffer.endsWith(" ")) {
                        buffer.deleteCharAt(buffer.length - 1)
                    }
                    flush()
                    tokens += if (hard) InlineToken.HardBreak else InlineToken.SoftBreak
                    index++
                }

                else -> {
                    buffer.append(char)
                    index++
                }
            }
        }
        flush()
        return tokens
    }

    // ---------------------------------------------------------------- 代码

    private fun findBacktickClose(text: String, start: Int, length: Int): Int {
        var index = start
        while (index < text.length) {
            if (text[index] == '`') {
                val run = runLength(text, index, '`')
                if (run == length) return index
                index += run
            } else {
                index++
            }
        }
        return -1
    }

    private fun normalizeCode(raw: String): String {
        val value = raw.replace('\n', ' ')
        return if (value.length >= 2 && value.startsWith(" ") && value.endsWith(" ")) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    // ---------------------------------------------------------------- 强调

    private fun parseEmphasis(text: String, start: Int, marker: Char): Parsed? {
        val run = runLength(text, start, marker)
        val size = when {
            run >= 3 -> 3
            run == 2 -> 2
            else -> 1
        }
        val contentStart = start + size
        if (contentStart >= text.length || text[contentStart].isWhitespace()) return null
        if (marker == '_' && start > 0 && text[start - 1].isLetterOrDigit()) return null

        val close = findClosing(text, contentStart, marker, size)
        if (close < 0) return null
        val inner = text.substring(contentStart, close)
        if (inner.isEmpty()) return null

        val children = lex(inner)
        val token = when (size) {
            3 -> InlineToken.Strong(listOf(InlineToken.Emphasis(children)))
            2 -> InlineToken.Strong(children)
            else -> InlineToken.Emphasis(children)
        }
        return Parsed(token, close + size)
    }

    private fun findClosing(text: String, from: Int, marker: Char, size: Int): Int {
        var index = from
        while (index < text.length) {
            if (text[index] == '\\') {
                index += 2
                continue
            }
            if (text[index] == marker) {
                val run = runLength(text, index, marker)
                if (run >= size) {
                    val before = if (index > 0) text[index - 1] else ' '
                    val after = if (index + run < text.length) text[index + run] else ' '
                    if (!before.isWhitespace() && (marker != '_' || !after.isLetterOrDigit())) {
                        return index
                    }
                }
                index += run
                continue
            }
            index++
        }
        return -1
    }

    // ---------------------------------------------------------------- 删除线

    private fun parseStrikethrough(text: String, start: Int): Parsed? {
        if (start + 1 >= text.length || text[start + 1] != '~') return null
        val contentStart = start + 2
        if (contentStart >= text.length || text[contentStart].isWhitespace()) return null
        val close = findClosing(text, contentStart, '~', 2)
        if (close < 0) return null
        return Parsed(InlineToken.Strikethrough(lex(text.substring(contentStart, close))), close + 2)
    }

    // ---------------------------------------------------------------- 链接/图片

    private fun parseLink(text: String, start: Int): Parsed? {
        val labelEnd = findClosingBracket(text, start + 1)
        if (labelEnd < 0) return null
        if (labelEnd + 1 >= text.length || text[labelEnd + 1] != '(') return null
        val destination = parseDestination(text, labelEnd + 2) ?: return null
        val label = text.substring(start + 1, labelEnd)
        val token = InlineToken.Link(
            destination = destination.url,
            title = destination.title,
            children = lex(label),
        )
        return Parsed(token, destination.end)
    }

    private fun parseImage(text: String, start: Int): Parsed? {
        // start 指向 '!'
        if (start + 1 >= text.length || text[start + 1] != '[') return null
        val labelEnd = findClosingBracket(text, start + 2)
        if (labelEnd < 0) return null
        if (labelEnd + 1 >= text.length || text[labelEnd + 1] != '(') return null
        val destination = parseDestination(text, labelEnd + 2) ?: return null
        val alt = text.substring(start + 2, labelEnd)
        val token = InlineToken.Image(
            destination = destination.url,
            title = destination.title,
            alt = alt,
        )
        return Parsed(token, destination.end)
    }

    private fun findClosingBracket(text: String, start: Int): Int {
        var depth = 1
        var index = start
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index += 2
                '[' -> {
                    depth++
                    index++
                }

                ']' -> {
                    depth--
                    if (depth == 0) return index
                    index++
                }

                else -> index++
            }
        }
        return -1
    }

    private data class Destination(
        val url: String,
        val title: String?,
        val end: Int,
    )

    private fun parseDestination(text: String, start: Int): Destination? {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        if (index >= text.length) return null

        val url = StringBuilder()
        if (text[index] == '<') {
            index++
            while (index < text.length && text[index] != '>') {
                if (text[index] == '\\' && index + 1 < text.length) {
                    url.append(text[index + 1])
                    index += 2
                } else {
                    url.append(text[index])
                    index++
                }
            }
            if (index >= text.length) return null
            index++ // 吃掉 '>'
        } else {
            var depth = 0
            while (index < text.length) {
                val char = text[index]
                when {
                    char == '\\' && index + 1 < text.length -> {
                        url.append(text[index + 1])
                        index += 2
                    }

                    char == '(' -> {
                        depth++
                        url.append(char)
                        index++
                    }

                    char == ')' -> {
                        if (depth == 0) break
                        depth--
                        url.append(char)
                        index++
                    }

                    char.isWhitespace() -> break

                    else -> {
                        url.append(char)
                        index++
                    }
                }
            }
        }

        val destination = url.toString()
        if (destination.isBlank()) return null

        while (index < text.length && text[index].isWhitespace()) index++
        var title: String? = null
        if (index < text.length && (text[index] == '"' || text[index] == '\'' || text[index] == '(')) {
            val quote = text[index]
            val close = if (quote == '(') ')' else quote
            index++
            val titleText = StringBuilder()
            while (index < text.length && text[index] != close) {
                if (text[index] == '\\' && index + 1 < text.length) {
                    titleText.append(text[index + 1])
                    index += 2
                } else {
                    titleText.append(text[index])
                    index++
                }
            }
            if (index >= text.length) return null
            title = titleText.toString()
            index++
            while (index < text.length && text[index].isWhitespace()) index++
        }

        if (index >= text.length || text[index] != ')') return null
        return Destination(destination, title, index + 1)
    }

    // ---------------------------------------------------------------- 自动链接

    private fun parseAutolink(text: String, start: Int): Parsed? {
        val end = text.indexOf('>', start + 1)
        if (end < 0) return null
        val inner = text.substring(start + 1, end)
        val lower = inner.lowercase()
        if (!lower.startsWith("http://") &&
            !lower.startsWith("https://") &&
            !lower.startsWith("mailto:")
        ) {
            return null
        }
        return Parsed(
            token = InlineToken.Link(inner, null, listOf(InlineToken.Text(inner))),
            end = end + 1,
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun runLength(text: String, start: Int, char: Char): Int {
        var index = start
        while (index < text.length && text[index] == char) index++
        return index - start
    }

    private data class Parsed(
        val token: InlineToken,
        val end: Int,
    )

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!>|~"
    private fun isEscapable(char: Char): Boolean = ESCAPABLE.indexOf(char) >= 0
}
