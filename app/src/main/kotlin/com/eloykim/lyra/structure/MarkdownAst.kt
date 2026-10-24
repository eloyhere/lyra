package com.eloykim.lyra.structure

/**
 * Markdown 抽象语法树（AST）。
 *
 * 结构：
 *
 * ```
 * MarkdownDocument
 *   └── BlockNode (Paragraph / Heading / FencedCode / BlockQuote / List / ThematicBreak)
 *         └── InlineNode (Text / Emphasis / Strong / CodeSpan / Link / …)
 * ```
 *
 * 所有节点都是不可变数据类：词法分析只负责切分，语法分析产出这棵树，
 * HTML 渲染器再遍历这棵树。渲染层不直接接触原始 Markdown 文本。
 */
data class MarkdownDocument(
    val blocks: List<BlockNode>,
)

sealed interface BlockNode {

    /** 普通段落。children 是行内节点的有序序列。 */
    data class Paragraph(
        val children: List<InlineNode>,
    ) : BlockNode

    /** ATX / Setext 标题；level 为 1..6。 */
    data class Heading(
        val level: Int,
        val children: List<InlineNode>,
    ) : BlockNode

    /** ``` / ~~~ 围栏代码块；language 可以为空。 */
    data class FencedCode(
        val language: String?,
        val code: String,
    ) : BlockNode

    /**
     * ```canvas 围栏：spec 是受控 JSON，只允许绘制指令。
     *
     * HTML 渲染为 `<canvas>`；文本、颜色和数字都只进入 Canvas API，
     * 不拼 HTML，也不执行用户 JS。
     */
    data class Canvas(
        val spec: String,
    ) : BlockNode

    /**
     * ```html 围栏：在 sandbox iframe 里运行，只允许脚本，不给 same-origin。
     * 用途是 WebGL/3D、复杂交互；与聊天页 DOM 完全隔离。
     */
    data class Html(
        val html: String,
    ) : BlockNode

    /** 引用块；内部可以继续嵌套块节点。 */
    data class BlockQuote(
        val blocks: List<BlockNode>,
    ) : BlockNode

    /** 无序列表。 */
    data class BulletList(
        val items: List<ListItemNode>,
    ) : BlockNode

    /** 有序列表。start 是第一个序号。 */
    data class OrderedList(
        val start: Int,
        val items: List<ListItemNode>,
    ) : BlockNode

    /** 分隔线（--- / *** / ___）。 */
    data object ThematicBreak : BlockNode

    /** GFM 表格。 */
    data class Table(
        val headers: List<TableCellNode>,
        val alignments: List<TableAlignment>,
        val rows: List<List<TableCellNode>>,
    ) : BlockNode
}

/** 表格单元格；单元格内仍支持行内 Markdown。 */
data class TableCellNode(
    val children: List<InlineNode>,
)

enum class TableAlignment { Default, Left, Center, Right }

/**
 * 列表项。
 *
 * checked 为 null 表示普通列表项；true / false 表示 GFM 任务列表。
 */
data class ListItemNode(
    val children: List<InlineNode>,
    val checked: Boolean? = null,
)

sealed interface InlineNode {

    data class Text(
        val value: String,
    ) : InlineNode

    data class Emphasis(
        val children: List<InlineNode>,
    ) : InlineNode

    data class Strong(
        val children: List<InlineNode>,
    ) : InlineNode

    data class Strikethrough(
        val children: List<InlineNode>,
    ) : InlineNode

    /** 行内代码：`code`，不参与其他行内解析。 */
    data class CodeSpan(
        val value: String,
    ) : InlineNode

    data class Link(
        val destination: String,
        val title: String? = null,
        val children: List<InlineNode>,
    ) : InlineNode

    /** 图片在聊天里不做网络加载，降级为可点击的链接文本。 */
    data class Image(
        val destination: String,
        val title: String? = null,
        val alt: String,
    ) : InlineNode

    /** 段落内的普通换行。 */
    data object SoftBreak : InlineNode

    /** 两个以上空格或行尾反斜杠产生的硬换行。 */
    data object HardBreak : InlineNode
}
