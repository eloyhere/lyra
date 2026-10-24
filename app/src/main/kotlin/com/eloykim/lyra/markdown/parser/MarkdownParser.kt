package com.eloykim.lyra.markdown.parser

import com.eloykim.lyra.markdown.lexer.BlockToken
import com.eloykim.lyra.markdown.lexer.InlineLexer
import com.eloykim.lyra.markdown.lexer.InlineToken
import com.eloykim.lyra.markdown.lexer.MarkdownLexer
import com.eloykim.lyra.structure.BlockNode
import com.eloykim.lyra.structure.InlineNode
import com.eloykim.lyra.structure.ListItemNode
import com.eloykim.lyra.structure.MarkdownDocument
import com.eloykim.lyra.structure.TableAlignment
import com.eloykim.lyra.structure.TableCellNode

/**
 * 语法分析器：把词法单元组合成 AST。
 *
 * 只依赖 structure 中的节点定义；AST 不包含任何渲染信息（颜色、字号等）。
 */
internal object MarkdownParser {

    fun parse(source: String): MarkdownDocument =
        MarkdownDocument(MarkdownLexer.tokenize(source).map(::toBlock))

    private fun toBlock(token: BlockToken): BlockNode = when (token) {
        is BlockToken.Paragraph -> BlockNode.Paragraph(InlineParser.parse(token.text))
        is BlockToken.Heading -> BlockNode.Heading(token.level, InlineParser.parse(token.text))
        is BlockToken.FencedCode -> when (token.language?.lowercase()) {
            "canvas", "lyra-canvas" -> BlockNode.Canvas(token.code)
            "html", "lyra-html" -> BlockNode.Html(token.code)
            else -> BlockNode.FencedCode(token.language, token.code)
        }
        is BlockToken.BlockQuote -> BlockNode.BlockQuote(parse(token.text).blocks)
        is BlockToken.BulletList -> BlockNode.BulletList(token.items.map(::toListItem))
        is BlockToken.OrderedList -> BlockNode.OrderedList(token.start, token.items.map(::toListItem))
        is BlockToken.Table -> BlockNode.Table(
            headers = token.headers.map(::toTableCell),
            alignments = token.alignments.map(::toAlignment),
            rows = token.rows.map { row -> row.map(::toTableCell) },
        )

        BlockToken.ThematicBreak -> BlockNode.ThematicBreak
    }

    private fun toTableCell(raw: String): TableCellNode =
        TableCellNode(InlineParser.parse(raw))

    private fun toAlignment(raw: String): TableAlignment = when (raw) {
        "left" -> TableAlignment.Left
        "center" -> TableAlignment.Center
        "right" -> TableAlignment.Right
        else -> TableAlignment.Default
    }

    private fun toListItem(raw: String): ListItemNode {
        val task = TASK_ITEM.find(raw)
        val checked = task?.let { match -> match.groupValues[1].equals("x", ignoreCase = true) }
        val text = task?.groupValues?.get(2) ?: raw
        return ListItemNode(
            children = InlineParser.parse(text),
            checked = checked,
        )
    }

    private val TASK_ITEM = Regex("^\\[([ xX])\\]\\s+(.*)$")
}

internal object InlineParser {

    fun parse(text: String): List<InlineNode> = fromTokens(InlineLexer.tokenize(text))

    private fun fromTokens(tokens: List<InlineToken>): List<InlineNode> = tokens.map { token ->
        when (token) {
            is InlineToken.Text -> InlineNode.Text(token.value)
            is InlineToken.CodeSpan -> InlineNode.CodeSpan(token.value)
            is InlineToken.Emphasis -> InlineNode.Emphasis(fromTokens(token.children))
            is InlineToken.Strong -> InlineNode.Strong(fromTokens(token.children))
            is InlineToken.Strikethrough -> InlineNode.Strikethrough(fromTokens(token.children))
            is InlineToken.Link -> InlineNode.Link(
                destination = token.destination,
                title = token.title,
                children = fromTokens(token.children),
            )

            is InlineToken.Image -> InlineNode.Image(
                destination = token.destination,
                title = token.title,
                alt = token.alt,
            )

            InlineToken.SoftBreak -> InlineNode.SoftBreak
            InlineToken.HardBreak -> InlineNode.HardBreak
        }
    }
}
