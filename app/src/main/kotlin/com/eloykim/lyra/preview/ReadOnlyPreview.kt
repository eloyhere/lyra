package com.eloykim.lyra.preview

import com.eloykim.lyra.markdown.Markdown
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

enum class PreviewKind { MARKDOWN, WORD, EXCEL, POWERPOINT, TEX, PROGRAM, TEXT, UNKNOWN }

sealed interface PreviewBlock {
    data class Paragraph(val text: String) : PreviewBlock
    data class Heading(val level: Int, val text: String) : PreviewBlock
    data class Code(val language: String?, val text: String) : PreviewBlock
    data class Table(val rows: List<List<String>>) : PreviewBlock
    data class Slide(val number: Int, val text: String) : PreviewBlock
}

data class PreviewDocument(
    val title: String,
    val kind: PreviewKind,
    val blocks: List<PreviewBlock>,
    val readOnly: Boolean = true,
) {
    val plainText: String get() = blocks.joinToString("\n") {
        when (it) {
            is PreviewBlock.Paragraph -> it.text
            is PreviewBlock.Heading -> it.text
            is PreviewBlock.Code -> it.text
            is PreviewBlock.Table -> it.rows.joinToString("\n") { row -> row.joinToString(" | ") }
            is PreviewBlock.Slide -> it.text
        }
    }
}

interface ReadOnlyPreview {
    fun parse(source: ByteArray, title: String = "Untitled"): PreviewDocument
}

object MarkdownPreview : ReadOnlyPreview {
    override fun parse(source: ByteArray, title: String) = parse(source.toString(StandardCharsets.UTF_8), title)
    fun parse(source: String, title: String = "Untitled"): PreviewDocument {
        val ast = Markdown.parse(source)
        val blocks = ast.blocks.map { block ->
            when (block) {
                is com.eloykim.lyra.structure.BlockNode.Heading -> PreviewBlock.Heading(block.level, inlineText(block.children))
                is com.eloykim.lyra.structure.BlockNode.Paragraph -> PreviewBlock.Paragraph(inlineText(block.children))
                is com.eloykim.lyra.structure.BlockNode.FencedCode -> PreviewBlock.Code(block.language, block.code)
                is com.eloykim.lyra.structure.BlockNode.Table -> PreviewBlock.Table(
                    listOf(block.headers.map { inlineText(it.children) }) + block.rows.map { row -> row.map { inlineText(it.children) } },
                )
                is com.eloykim.lyra.structure.BlockNode.BlockQuote -> PreviewBlock.Paragraph(
                    block.blocks.joinToString("\n") { plainBlock(it) },
                )
                is com.eloykim.lyra.structure.BlockNode.BulletList -> PreviewBlock.Paragraph(
                    block.items.joinToString("\n") { "• ${inlineText(it.children)}" },
                )
                is com.eloykim.lyra.structure.BlockNode.OrderedList -> PreviewBlock.Paragraph(
                    block.items.mapIndexed { index, item -> "${block.start + index}. ${inlineText(item.children)}" }.joinToString("\n"),
                )
                is com.eloykim.lyra.structure.BlockNode.Canvas -> PreviewBlock.Paragraph("[canvas preview]")
                is com.eloykim.lyra.structure.BlockNode.Html -> PreviewBlock.Code("html", block.html)
                com.eloykim.lyra.structure.BlockNode.ThematicBreak -> PreviewBlock.Paragraph("────────")
            }
        }
        return PreviewDocument(title, PreviewKind.MARKDOWN, blocks)
    }
    private fun inlineText(nodes: List<com.eloykim.lyra.structure.InlineNode>): String = buildString {
        nodes.forEach { n -> when (n) {
            is com.eloykim.lyra.structure.InlineNode.Text -> append(n.value)
            is com.eloykim.lyra.structure.InlineNode.CodeSpan -> append(n.value)
            is com.eloykim.lyra.structure.InlineNode.Emphasis -> append(inlineText(n.children))
            is com.eloykim.lyra.structure.InlineNode.Strong -> append(inlineText(n.children))
            is com.eloykim.lyra.structure.InlineNode.Strikethrough -> append(inlineText(n.children))
            is com.eloykim.lyra.structure.InlineNode.Link -> append(inlineText(n.children))
            is com.eloykim.lyra.structure.InlineNode.Image -> append(n.alt)
            com.eloykim.lyra.structure.InlineNode.SoftBreak, com.eloykim.lyra.structure.InlineNode.HardBreak -> append('\n')
        } }
    }
    private fun plainBlock(block: com.eloykim.lyra.structure.BlockNode): String = when (block) {
        is com.eloykim.lyra.structure.BlockNode.Paragraph -> inlineText(block.children)
        is com.eloykim.lyra.structure.BlockNode.Heading -> inlineText(block.children)
        is com.eloykim.lyra.structure.BlockNode.FencedCode -> block.code
        is com.eloykim.lyra.structure.BlockNode.BlockQuote -> block.blocks.joinToString("\n", transform = ::plainBlock)
        else -> ""
    }
}

object WordPreview : ReadOnlyPreview {
    override fun parse(source: ByteArray, title: String): PreviewDocument = PreviewDocument(title, PreviewKind.WORD, listOf(PreviewBlock.Paragraph(xmlText(source, "word/document.xml"))))
}

object ExcelPreview : ReadOnlyPreview {
    override fun parse(source: ByteArray, title: String): PreviewDocument {
        val shared = zipEntries(source)["xl/sharedStrings.xml"].orEmpty()
        val strings = Regex("<si[^>]*>(.*?)</si>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(shared).map {
            Regex("<t[^>]*>(.*?)</t>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(it.groupValues[1]).joinToString("") { t -> decodeXml(t.groupValues[1]) }
        }.toList()
        val sheets = zipEntries(source).filter { it.key.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }.toSortedMap()
        val rows = sheets.values.flatMap { sheet ->
            Regex("<row[^>]*>(.*?)</row>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(sheet).map { row ->
                val cells = Regex("<c\\b([^>]*)>(.*?)</c>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(row.groupValues[1])
                    .mapNotNull { cell ->
                        val attrs = cell.groupValues[1]
                        val column = Regex("\\br=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1)?.let(::columnIndex) ?: return@mapNotNull null
                        val type = Regex("\\bt=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                        val content = cell.groupValues[2]
                        val value = Regex("<v[^>]*>(.*?)</v>", setOf(RegexOption.DOT_MATCHES_ALL)).find(content)?.groupValues?.get(1)
                        val text = when (type) {
                            "s" -> strings.getOrNull(value?.trim()?.toIntOrNull() ?: -1).orEmpty()
                            "inlineStr" -> Regex("<t[^>]*>(.*?)</t>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(content).joinToString("") { decodeXml(it.groupValues[1]) }
                            "b" -> if (value?.trim() == "1") "TRUE" else "FALSE"
                            else -> value?.let(::decodeXml).orEmpty()
                        }
                        column to text
                    }.toList()
                if (cells.isEmpty()) emptyList() else List(cells.maxOf { it.first } + 1) { column -> cells.firstOrNull { it.first == column }?.second.orEmpty() }
            }.toList()
        }
        return PreviewDocument(title, PreviewKind.EXCEL, listOf(PreviewBlock.Table(rows)))
    }
}

object PowerPointPreview : ReadOnlyPreview {
    override fun parse(source: ByteArray, title: String): PreviewDocument {
        val slides = zipEntries(source).filter { it.key.matches(Regex("ppt/slides/slide\\d+\\.xml")) }.toSortedMap()
        val blocks = slides.entries.mapIndexed { index, entry -> PreviewBlock.Slide(index + 1, xmlText(entry.value)) }
        return PreviewDocument(title, PreviewKind.POWERPOINT, blocks)
    }
}

object TexPreview : ReadOnlyPreview {
    override fun parse(source: ByteArray, title: String) = PreviewDocument(title, PreviewKind.TEX, listOf(PreviewBlock.Code("tex", source.toString(StandardCharsets.UTF_8))))
}

object ProgramPreview : ReadOnlyPreview {
    override fun parse(source: ByteArray, title: String): PreviewDocument {
        val language = title.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() }
        return PreviewDocument(title, PreviewKind.PROGRAM, listOf(PreviewBlock.Code(language, source.toString(StandardCharsets.UTF_8))))
    }
}

object PreviewFactory {
    fun forName(name: String): ReadOnlyPreview = when (name.substringAfterLast('.', "").lowercase()) {
        "md", "markdown", "mdown" -> MarkdownPreview
        "docx" -> WordPreview
        "xlsx", "xlsm" -> ExcelPreview
        "pptx", "pptm" -> PowerPointPreview
        "tex", "latex" -> TexPreview
        "kt", "kts", "java", "py", "js", "ts", "c", "cpp", "h", "rs", "go", "swift", "xml", "json", "yaml", "yml", "sh", "gradle" -> ProgramPreview
        else -> object : ReadOnlyPreview { override fun parse(source: ByteArray, title: String) = PreviewDocument(title, PreviewKind.UNKNOWN, listOf(PreviewBlock.Paragraph(source.toString(StandardCharsets.UTF_8)))) }
    }
    fun parse(name: String, source: ByteArray): PreviewDocument = forName(name).parse(source, name)
}

private fun zipEntries(source: ByteArray): Map<String, String> {
    val out = linkedMapOf<String, String>()
    ZipInputStream(ByteArrayInputStream(source)).use { zip ->
        var totalBytes = 0L
        while (true) {
            val entry = zip.nextEntry ?: break
            require(out.size < 500) { "Document contains too many entries" }
            if (!entry.isDirectory) {
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var entryBytes = 0
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    entryBytes += read
                    totalBytes += read
                    require(entryBytes <= 8 * 1024 * 1024 && totalBytes <= 32L * 1024 * 1024) { "Document expands beyond the preview limit" }
                    bytes.write(buffer, 0, read)
                }
                out[entry.name] = bytes.toByteArray().toString(StandardCharsets.UTF_8)
            }
        }
    }
    return out
}

private fun xmlText(source: ByteArray, entry: String): String = xmlText(zipEntries(source)[entry] ?: "")
private fun xmlText(xml: String): String = runCatching {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isExpandEntityReferences = false
        isXIncludeAware = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
    }
    val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
    val paragraphs = doc.getElementsByTagNameNS("*", "p")
    if (paragraphs.length == 0) doc.documentElement.textContent.trim() else
        (0 until paragraphs.length).map { paragraphs.item(it).textContent.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}.getOrElse { Regex("<[^>]+>").replace(xml, " ").replace(Regex("\\s+"), " ").trim() }

private fun decodeXml(value: String): String = value
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&")

private fun columnIndex(label: String): Int = label.fold(0) { index, char -> index * 26 + (char - 'A' + 1) } - 1
