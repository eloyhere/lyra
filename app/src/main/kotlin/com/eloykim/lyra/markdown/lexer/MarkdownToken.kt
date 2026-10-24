package com.eloykim.lyra.markdown.lexer

/**
 * 块级词法单元。
 *
 * 这一层只做「切分」：保留原始行文本，不判断斜体、链接等行内语法。
 * 语法分析器再把它们组装成 [com.eloykim.lyra.structure.BlockNode]。
 */
internal sealed interface BlockToken {

    data class Paragraph(
        val text: String,
    ) : BlockToken

    data class Heading(
        val level: Int,
        val text: String,
    ) : BlockToken

    data class FencedCode(
        val language: String?,
        val code: String,
    ) : BlockToken

    /** 引用块内部仍是 Markdown，由语法分析器递归解析。 */
    data class BlockQuote(
        val text: String,
    ) : BlockToken

    data class BulletList(
        val items: List<String>,
    ) : BlockToken

    data class OrderedList(
        val start: Int,
        val items: List<String>,
    ) : BlockToken

    data object ThematicBreak : BlockToken

    /** 表格；单元格先保留原始文本，交给语法分析器解析行内节点。 */
    data class Table(
        val headers: List<String>,
        val alignments: List<String>,
        val rows: List<List<String>>,
    ) : BlockToken
}

/**
 * 行内词法单元。
 *
 * 强调/链接等节点保留 children，交给语法分析器递归转换成 AST；
 * 渲染器只认 AST，不再重新解析字符串。
 */
internal sealed interface InlineToken {

    data class Text(
        val value: String,
    ) : InlineToken

    data class CodeSpan(
        val value: String,
    ) : InlineToken

    data class Emphasis(
        val children: List<InlineToken>,
    ) : InlineToken

    data class Strong(
        val children: List<InlineToken>,
    ) : InlineToken

    data class Strikethrough(
        val children: List<InlineToken>,
    ) : InlineToken

    data class Link(
        val destination: String,
        val title: String?,
        val children: List<InlineToken>,
    ) : InlineToken

    data class Image(
        val destination: String,
        val title: String?,
        val alt: String,
    ) : InlineToken

    data object SoftBreak : InlineToken

    data object HardBreak : InlineToken
}
