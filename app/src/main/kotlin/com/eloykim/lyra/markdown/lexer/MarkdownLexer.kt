package com.eloykim.lyra.markdown.lexer

/**
 * 块级词法分析器。
 *
 * 逐行扫描，产出「块级词法单元」：
 * 围栏代码、标题、引用、列表、分隔线、段落。
 *
 * 这一层不消费行内 Markdown，也不生成 AST；它只回答「这一块是什么」。
 */
internal object MarkdownLexer {

    fun tokenize(source: String): List<BlockToken> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val tokens = ArrayList<BlockToken>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                index++
                continue
            }

            val fence = parseFenceStart(line)
            if (fence != null) {
                val code = ArrayList<String>()
                index++
                while (index < lines.size && !isFenceEnd(lines[index], fence)) {
                    code += lines[index]
                    index++
                }
                if (index < lines.size) index++ // 吃掉闭合围栏
                tokens += BlockToken.FencedCode(
                    language = fence.info.ifBlank { null },
                    code = code.joinToString("\n"),
                )
                continue
            }

            val heading = parseHeading(line)
            if (heading != null) {
                tokens += heading
                index++
                continue
            }

            val setext = parseSetext(lines, index)
            if (setext != null) {
                tokens += setext.heading
                index = setext.nextIndex
                continue
            }

            if (isThematicBreak(line)) {
                tokens += BlockToken.ThematicBreak
                index++
                continue
            }

            if (isQuoteLine(line)) {
                val quoted = ArrayList<String>()
                while (index < lines.size && isQuoteLine(lines[index])) {
                    quoted += stripQuote(lines[index])
                    index++
                }
                tokens += BlockToken.BlockQuote(quoted.joinToString("\n"))
                continue
            }

            val bullet = parseBulletItem(line)
            if (bullet != null) {
                val items = ArrayList<String>()
                while (index < lines.size) {
                    val current = lines[index]
                    if (current.isBlank()) break
                    val item = parseBulletItem(current)
                    if (item != null) {
                        items += item
                        index++
                    } else if (isListContinuation(current)) {
                        if (items.isNotEmpty()) {
                            items[items.lastIndex] = items.last() + "\n" + current.trim()
                        }
                        index++
                    } else {
                        break
                    }
                }
                tokens += BlockToken.BulletList(items)
                continue
            }

            val ordered = parseOrderedItem(line)
            if (ordered != null) {
                val items = ArrayList<String>()
                val start = ordered.start
                while (index < lines.size) {
                    val current = lines[index]
                    if (current.isBlank()) break
                    val item = parseOrderedItem(current)
                    if (item != null) {
                        items += item.text
                        index++
                    } else if (isListContinuation(current)) {
                        if (items.isNotEmpty()) {
                            items[items.lastIndex] = items.last() + "\n" + current.trim()
                        }
                        index++
                    } else {
                        break
                    }
                }
                tokens += BlockToken.OrderedList(start, items)
                continue
            }

            val table = parseTable(lines, index)
            if (table != null) {
                tokens += table.token
                index = table.nextIndex
                continue
            }

            val paragraph = ArrayList<String>()
            while (index < lines.size) {
                val current = lines[index]
                if (current.isBlank()) break
                if (paragraph.isNotEmpty() && isBlockStart(current)) break
                if (paragraph.isNotEmpty() && isTableStart(lines, index)) break
                paragraph += current.trim()
                index++
            }
            if (paragraph.isEmpty()) {
                index++ // 防御：不认识的块也必须前进
            } else {
                tokens += BlockToken.Paragraph(paragraph.joinToString("\n"))
            }
        }

        return tokens
    }

    // ---------------------------------------------------------------- 围栏代码

    private data class Fence(
        val char: Char,
        val length: Int,
        val info: String,
    )

    private fun parseFenceStart(line: String): Fence? {
        val trimmed = line.trimStart()
        if (trimmed.length < 3) return null
        val char = trimmed[0]
        if (char != '`' && char != '~') return null
        val length = trimmed.takeWhile { it == char }.length
        if (length < 3) return null
        val info = trimmed.substring(length).trim()
        if (char == '`' && info.contains('`')) return null
        return Fence(char, length, info)
    }

    private fun isFenceEnd(line: String, fence: Fence): Boolean {
        val trimmed = line.trim()
        return trimmed.length >= fence.length && trimmed.all { it == fence.char }
    }

    // ---------------------------------------------------------------- 标题

    private fun parseHeading(line: String): BlockToken.Heading? {
        val trimmed = line.trimStart()
        val hashes = trimmed.takeWhile { it == '#' }.length
        if (hashes !in 1..6) return null
        if (trimmed.length == hashes) return BlockToken.Heading(hashes, "")
        if (!trimmed[hashes].isWhitespace()) return null
        var text = trimmed.substring(hashes).trim()
        val withoutClosing = text.trimEnd('#').trimEnd()
        if (withoutClosing.length < text.length &&
            (withoutClosing.isEmpty() || text[withoutClosing.length].isWhitespace())
        ) {
            text = withoutClosing
        }
        return BlockToken.Heading(hashes, text)
    }

    private data class Setext(
        val heading: BlockToken.Heading,
        val nextIndex: Int,
    )

    private fun parseSetext(lines: List<String>, index: Int): Setext? {
        val line = lines[index]
        if (line.isBlank() || isBlockStart(line)) return null
        val underline = lines.getOrNull(index + 1) ?: return null
        val level = when {
            SETEXT_H1.matches(underline) -> 1
            SETEXT_H2.matches(underline) -> 2
            else -> return null
        }
        return Setext(
            heading = BlockToken.Heading(level, line.trim()),
            nextIndex = index + 2,
        )
    }

    // ---------------------------------------------------------------- 引用

    private fun isQuoteLine(line: String): Boolean = line.trimStart().startsWith(">")

    private fun stripQuote(line: String): String {
        val trimmed = line.trimStart()
        val without = trimmed.removePrefix(">")
        return if (without.startsWith(" ")) without.substring(1) else without
    }

    // ---------------------------------------------------------------- 列表

    private fun parseBulletItem(line: String): String? {
        val trimmed = line.trimStart()
        if (trimmed.length < 2) return null
        if (trimmed[0] != '-' && trimmed[0] != '+' && trimmed[0] != '*') return null
        if (!trimmed[1].isWhitespace()) return null
        return trimmed.substring(1).trim()
    }

    private data class OrderedItem(
        val start: Int,
        val text: String,
    )

    private fun parseOrderedItem(line: String): OrderedItem? {
        val match = ORDERED_ITEM.matchEntire(line) ?: return null
        return OrderedItem(
            start = match.groupValues[1].toIntOrNull() ?: 1,
            text = match.groupValues[2].trim(),
        )
    }

    private fun isListContinuation(line: String): Boolean =
        line.startsWith("  ") || line.startsWith("\t")

    // ---------------------------------------------------------------- 其他块

    private fun isThematicBreak(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3) return false
        val marker = trimmed[0]
        if (marker != '-' && marker != '*' && marker != '_') return false
        var count = 0
        for (char in trimmed) {
            when {
                char == marker -> count++
                char.isWhitespace() -> Unit
                else -> return false
            }
        }
        return count >= 3
    }

    private fun isBlockStart(line: String): Boolean =
        parseFenceStart(line) != null ||
            parseHeading(line) != null ||
            isThematicBreak(line) ||
            isQuoteLine(line) ||
            parseBulletItem(line) != null ||
            parseOrderedItem(line) != null

    // ---------------------------------------------------------------- 表格

    private data class TableResult(
        val token: BlockToken.Table,
        val nextIndex: Int,
    )

    private fun parseTable(lines: List<String>, index: Int): TableResult? {
        if (!isTableStart(lines, index)) return null
        val headers = splitTableRow(lines[index])
        val delimiters = splitTableRow(lines[index + 1])
        val alignments = delimiters.map { cell ->
            val left = cell.startsWith(":")
            val right = cell.endsWith(":")
            when {
                left && right -> "center"
                left -> "left"
                right -> "right"
                else -> "default"
            }
        }

        val rows = ArrayList<List<String>>()
        var next = index + 2
        while (next < lines.size) {
            val line = lines[next]
            if (line.isBlank() || !line.contains('|')) break
            val cells = splitTableRow(line)
            if (cells.isEmpty()) break
            rows += List(headers.size) { cellIndex -> cells.getOrElse(cellIndex) { "" } }
            next++
        }

        return TableResult(
            token = BlockToken.Table(headers, alignments, rows),
            nextIndex = next,
        )
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        val header = lines.getOrNull(index) ?: return false
        val delimiter = lines.getOrNull(index + 1) ?: return false
        if (!header.contains('|') || !delimiter.contains('|')) return false
        val headers = splitTableRow(header)
        val delimiters = splitTableRow(delimiter)
        return headers.isNotEmpty() &&
            headers.size == delimiters.size &&
            delimiters.all { DELIMITER_CELL.matches(it) }
    }

    private fun splitTableRow(line: String): List<String> {
        val trimmed = line.trim()
        if (!trimmed.contains('|') || trimmed == "|") return emptyList()
        val start = if (trimmed.startsWith("|")) 1 else 0
        val end = if (trimmed.endsWith("|")) trimmed.length - 1 else trimmed.length
        if (end < start) return emptyList()
        val content = trimmed.substring(start, end)

        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var index = 0
        while (index < content.length) {
            val char = content[index]
            if (char == '\\' && index + 1 < content.length && content[index + 1] == '|') {
                cell.append('|')
                index += 2
            } else if (char == '|') {
                cells += cell.toString().trim()
                cell.clear()
                index++
            } else {
                cell.append(char)
                index++
            }
        }
        cells += cell.toString().trim()
        return cells
    }

    private val SETEXT_H1 = Regex("^\\s{0,3}=+\\s*$")
    private val SETEXT_H2 = Regex("^\\s{0,3}-+\\s*$")
    private val ORDERED_ITEM = Regex("^\\s{0,3}(\\d{1,9})[.)]\\s+(.*)$")
    private val DELIMITER_CELL = Regex("^:?-{1,}:?$")
}
