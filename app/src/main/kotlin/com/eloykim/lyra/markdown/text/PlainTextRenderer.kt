package com.eloykim.lyra.markdown.text

import com.eloykim.lyra.structure.BlockNode
import com.eloykim.lyra.structure.InlineNode
import com.eloykim.lyra.structure.ListItemNode
import com.eloykim.lyra.structure.MarkdownDocument
import com.eloykim.lyra.structure.TableCellNode

/**
 * AST -> 纯文本，用于「复制文本」。
 *
 * 表格用 ` | ` 分列，列表保留前缀，代码块保留原文；不输出 Markdown 分隔符。
 */
internal object PlainTextRenderer {

    fun render(document: MarkdownDocument): String =
        document.blocks.joinToString("\n") { renderBlock(it) }.trim()

    private fun renderBlock(block: BlockNode): String = when (block) {
        is BlockNode.Paragraph -> renderInlines(block.children)
        is BlockNode.Heading -> renderInlines(block.children)

        is BlockNode.FencedCode -> block.code

        is BlockNode.Canvas -> "[canvas]"

        is BlockNode.Html -> "[html]"

        is BlockNode.BlockQuote -> block.blocks.joinToString("\n") { child ->
            renderBlock(child).lineSequence().joinToString("\n") { "> $it" }
        }

        is BlockNode.BulletList -> block.items.joinToString("\n") { item ->
            "- ${renderItem(item)}"
        }

        is BlockNode.OrderedList -> block.items.mapIndexed { index, item ->
            "${block.start + index}. ${renderItem(item)}"
        }.joinToString("\n")

        is BlockNode.Table -> renderTable(block.headers, block.rows)
        BlockNode.ThematicBreak -> "────────"
    }

    private fun renderItem(item: ListItemNode): String {
        val marker = when (item.checked) {
            true -> "☑ "
            false -> "☐ "
            null -> ""
        }
        return marker + renderInlines(item.children)
    }

    private fun renderTable(
        headers: List<TableCellNode>,
        rows: List<List<TableCellNode>>,
    ): String = buildString {
        append(headers.joinToString(" | ") { renderInlines(it.children) })
        append('\n')
        append(headers.joinToString("-+-") { "---" })
        rows.forEach { row ->
            append('\n')
            append(row.joinToString(" | ") { renderInlines(it.children) })
        }
    }

    private fun renderInlines(nodes: List<InlineNode>): String = buildString {
        nodes.forEach { node ->
            when (node) {
                is InlineNode.Text -> append(node.value)
                is InlineNode.Emphasis -> append(renderInlines(node.children))
                is InlineNode.Strong -> append(renderInlines(node.children))
                is InlineNode.Strikethrough -> append(renderInlines(node.children))
                is InlineNode.CodeSpan -> append(node.value)
                is InlineNode.Link -> append(
                    renderInlines(node.children).ifBlank { node.destination },
                )
                is InlineNode.Image -> append(node.alt.ifBlank { node.destination })
                InlineNode.SoftBreak, InlineNode.HardBreak -> append('\n')
            }
        }
    }
}
