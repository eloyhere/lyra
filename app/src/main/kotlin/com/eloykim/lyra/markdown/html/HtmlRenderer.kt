package com.eloykim.lyra.markdown.html

import com.eloykim.lyra.structure.BlockNode
import com.eloykim.lyra.structure.InlineNode
import com.eloykim.lyra.structure.ListItemNode
import com.eloykim.lyra.structure.MarkdownDocument
import com.eloykim.lyra.structure.TableAlignment
import com.eloykim.lyra.structure.TableCellNode
import android.net.Uri
import android.util.Base64

/**
 * AST -> HTML。
 *
 * 只输出 [android.text.Html] 能识别的语义标签（p/h1..h6/b/i/strike/code/pre/
 * blockquote/ul/ol/li/a/br）。所有文本和属性都经过转义，Markdown 里的原始
 * HTML 不会被执行。
 */
internal object HtmlRenderer {

    fun render(document: MarkdownDocument): String =
        document.blocks.joinToString("\n") { renderBlock(it) }

    private fun renderBlock(block: BlockNode): String = when (block) {
        is BlockNode.Paragraph -> "<p>${renderInlines(block.children)}</p>"
        is BlockNode.Heading -> "<h${block.level}>${renderInlines(block.children)}</h${block.level}>"
        is BlockNode.FencedCode -> {
            val language = block.language
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.uppercase()
                ?: "CODE"
            "<div class=\"code-block\"><div class=\"code-bar\">" +
                "<span>${escapeText(language)}</span>" +
                "<button type=\"button\" class=\"lyra-copy\">复制</button></div>" +
                "<pre><code>${escapeText(block.code)}</code></pre></div>"
        }

        is BlockNode.Canvas -> {
            val encoded = Base64.encodeToString(
                block.spec.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            "<div class=\"lyra-canvas\" data-spec=\"$encoded\">" +
                "<canvas></canvas><span class=\"lyra-canvas-hint\">双指缩放 · 拖动</span></div>"
        }

        is BlockNode.Html -> {
            // 只允许脚本，不给 same-origin；脚本拿不到聊天页 DOM/Cookie/存储。
            val doc = """
                <!doctype html><html><head><meta charset="utf-8">
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data: https:; connect-src 'none'; form-action 'none';">
                <style>
                  html,body{margin:0;padding:0;overflow:auto;background:#fff;color:#111;font-family:sans-serif}
                  ::-webkit-scrollbar{width:0;height:0}
                </style>
                </head><body>${block.html}
                <script>
                  (function () {
                    function pictureOnly() {
                      var canvas = document.querySelector("canvas");
                      if (!canvas) return;
                      try {
                        var nodes = Array.prototype.slice.call(document.body.children);
                        for (var i = 0; i < nodes.length; i++) {
                          if (nodes[i] !== canvas) nodes[i].style.display = "none";
                        }
                        if (canvas.parentNode !== document.body) document.body.appendChild(canvas);
                        canvas.style.display = "block";
                        canvas.style.maxWidth = "100%";
                        canvas.style.height = "auto";
                        canvas.style.margin = "0 auto";
                        document.body.style.margin = "0";
                        document.body.style.overflow = "auto";
                        document.body.style.height = "100vh";
                        document.body.style.display = "flex";
                        document.body.style.alignItems = "center";
                        document.body.style.justifyContent = "center";
                        fit(canvas);
                      } catch (e) {}
                    }
                    function fit(canvas) {
                      var w = canvas.width || canvas.clientWidth || 1;
                      var h = canvas.height || canvas.clientHeight || 1;
                      var vw = window.innerWidth || 360;
                      var vh = window.innerHeight || 360;
                      var scale = Math.min(vw / w, vh / h);
                      canvas.style.maxWidth = "none";
                      canvas.style.maxHeight = "none";
                      canvas.style.width = Math.max(1, Math.floor(w * scale)) + "px";
                      canvas.style.height = Math.max(1, Math.floor(h * scale)) + "px";
                      canvas.style.margin = "0";
                    }
                    window.addEventListener("load", function () { setTimeout(pictureOnly, 300); });
                    window.addEventListener("resize", function () {
                      var canvas = document.querySelector("canvas");
                      if (canvas) fit(canvas);
                    });
                    function relay(active) {
                      try { parent.postMessage({ type: "lyra-scroll", active: active }, "*"); } catch (e) {}
                    }
                    document.addEventListener("pointerdown", function () { relay(true); }, true);
                    document.addEventListener("pointerup", function () { relay(false); }, true);
                    document.addEventListener("pointercancel", function () { relay(false); }, true);
                  })();
                </script>
                </body></html>
            """.trimIndent()
            "<div class=\"lyra-html-block\">" +
                "<div class=\"lyra-html-bar\">" +
                "<span class=\"lyra-html-title\">PICTURE</span>" +
                "<div class=\"lyra-html-actions\">" +
                "<button type=\"button\" class=\"lyra-copy\">复制</button>" +
                "<label class=\"lyra-html-switch\">" +
                "<span>代码</span>" +
                "<input type=\"checkbox\" checked>" +
                "<span class=\"lyra-html-track\"><span class=\"lyra-html-thumb\"></span></span>" +
                "<span>预览</span>" +
                "</label></div></div>" +
                "<div class=\"lyra-html-body\">" +
                "<pre class=\"lyra-html-code\"><code>${escapeText(block.html)}</code></pre>" +
                "<iframe class=\"lyra-html\" sandbox=\"allow-scripts\" scrolling=\"no\" srcdoc=\"" +
                escapeAttribute(doc) + "\"></iframe></div></div>"
        }
        is BlockNode.BlockQuote ->
            "<blockquote>${block.blocks.joinToString("") { renderBlock(it) }}</blockquote>"

        is BlockNode.BulletList ->
            "<ul>${block.items.joinToString("") { renderItem(it) }}</ul>"

        is BlockNode.OrderedList -> {
            val start = if (block.start != 1) " start=\"${block.start}\"" else ""
            "<ol$start>${block.items.joinToString("") { renderItem(it) }}</ol>"
        }

        is BlockNode.Table -> renderTable(
            headers = block.headers,
            alignments = block.alignments,
            rows = block.rows,
        )

        BlockNode.ThematicBreak -> "<p>────────</p>"
    }

    private fun renderTable(
        headers: List<TableCellNode>,
        alignments: List<TableAlignment>,
        rows: List<List<TableCellNode>>,
    ): String = buildString {
        append("<div class=\"table-wrap\"><table><thead><tr>")
        headers.forEachIndexed { index, cell ->
            append("<th")
            appendAlignment(alignments.getOrNull(index))
            append(">").append(renderInlines(cell.children)).append("</th>")
        }
        append("</tr></thead><tbody>")
        rows.forEach { row ->
            append("<tr>")
            row.forEachIndexed { index, cell ->
                append("<td")
                appendAlignment(alignments.getOrNull(index))
                append(">").append(renderInlines(cell.children)).append("</td>")
            }
            append("</tr>")
        }
        append("</tbody></table></div>")
    }

    private fun StringBuilder.appendAlignment(alignment: TableAlignment?) {
        when (alignment) {
            TableAlignment.Left -> append(" align=\"left\"")
            TableAlignment.Center -> append(" align=\"center\"")
            TableAlignment.Right -> append(" align=\"right\"")
            TableAlignment.Default, null -> Unit
        }
    }

    private fun renderItem(item: ListItemNode): String {
        val marker = when (item.checked) {
            true -> "☑ "
            false -> "☐ "
            null -> ""
        }
        return "<li>$marker${renderInlines(item.children)}</li>"
    }

    private fun renderInlines(nodes: List<InlineNode>): String = buildString {
        nodes.forEach { node ->
            when (node) {
                is InlineNode.Text -> append(linkifyMentions(node.value))

                is InlineNode.Emphasis ->
                    append("<i>").append(renderInlines(node.children)).append("</i>")

                is InlineNode.Strong ->
                    append("<b>").append(renderInlines(node.children)).append("</b>")

                is InlineNode.Strikethrough ->
                    append("<strike>").append(renderInlines(node.children)).append("</strike>")

                is InlineNode.CodeSpan ->
                    append("<code>").append(escapeText(node.value)).append("</code>")

                is InlineNode.Link -> {
                    append("<a href=\"").append(escapeAttribute(node.destination)).append("\"")
                    node.title?.let {
                        append(" title=\"").append(escapeAttribute(it)).append("\"")
                    }
                    append(">").append(renderInlines(node.children)).append("</a>")
                }

                is InlineNode.Image -> {
                    val alt = node.alt.ifBlank { "图片" }
                    append("<picture><img src=\"")
                        .append(escapeAttribute(node.destination))
                        .append("\" alt=\"")
                        .append(escapeAttribute(alt))
                        .append("\"")
                    node.title?.let {
                        append(" title=\"").append(escapeAttribute(it)).append("\"")
                    }
                    append("></picture>")
                }

                InlineNode.SoftBreak, InlineNode.HardBreak -> append("<br>")
            }
        }
    }

    private fun escapeText(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(char)
            }
        }
    }

    private fun linkifyMentions(text: String): String {
        val escaped = escapeText(text)
        return Regex("@[\\p{L}\\p{N}_-]+").replace(escaped) { match ->
            val name = match.value.removePrefix("@")
            "<a href=\"lyra-mention://${Uri.encode(name)}\">${match.value}</a>"
        }
    }

    private fun escapeAttribute(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }
}
