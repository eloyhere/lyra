package com.eloykim.lyra.markdown

import com.eloykim.lyra.markdown.html.HtmlRenderer
import com.eloykim.lyra.markdown.parser.MarkdownParser
import com.eloykim.lyra.markdown.text.PlainTextRenderer
import com.eloykim.lyra.structure.MarkdownDocument

/**
 * Markdown 入口。
 *
 * 流程：词法分析（lexer） -> 抽象语法树（structure） -> HTML（html）。
 * 聊天组件只调用 [toHtml]，再把 HTML 交给 Android TextView 渲染。
 */
object Markdown {

    fun parse(source: String): MarkdownDocument = MarkdownParser.parse(source)

    fun toHtml(source: String): String = HtmlRenderer.render(parse(source))

    fun toPlainText(source: String): String = PlainTextRenderer.render(parse(source))
}
