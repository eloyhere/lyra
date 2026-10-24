package com.eloykim.lyra.markdown

import com.eloykim.lyra.structure.BlockNode
import com.eloykim.lyra.structure.InlineNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `builds block and inline ast`() {
        val document = Markdown.parse(
            """
            # Title

            - **one**
            - two

            ```kotlin
            val x = 1
            ```
            """.trimIndent(),
        )
        assertEquals(3, document.blocks.size)

        val heading = document.blocks[0] as BlockNode.Heading
        assertEquals(1, heading.level)
        assertTrue(heading.children.single() is InlineNode.Text)

        val list = document.blocks[1] as BlockNode.BulletList
        assertEquals(2, list.items.size)
        assertTrue(list.items[0].children.single() is InlineNode.Strong)

        val code = document.blocks[2] as BlockNode.FencedCode
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
    }

    @Test
    fun `paragraph and inline formatting`() {
        assertEquals(
            "<p>hello <b>bold</b> and <i>italic</i> and <code>code</code></p>",
            Markdown.toHtml("hello **bold** and *italic* and `code`"),
        )
    }

    @Test
    fun `strong and emphasis nesting`() {
        assertEquals("<p><b>bold</b> <i>italic</i></p>", Markdown.toHtml("**bold** _italic_"))
        assertEquals("<p><b><i>both</i></b></p>", Markdown.toHtml("***both***"))
    }

    @Test
    fun `escapes raw html`() {
        assertEquals(
            "<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>",
            Markdown.toHtml("<script>alert(1)</script>"),
        )
    }

    @Test
    fun `heading and rule`() {
        assertEquals("<h1>Title</h1>", Markdown.toHtml("# Title"))
        assertEquals("<h2>Sub</h2>", Markdown.toHtml("Sub\n---"))
        assertEquals("<p>────────</p>", Markdown.toHtml("---"))
    }

    @Test
    fun `bullet and ordered lists`() {
        assertEquals("<ul><li>one</li><li>two</li></ul>", Markdown.toHtml("- one\n- two"))
        assertEquals(
            "<ol start=\"2\"><li>two</li><li>three</li></ol>",
            Markdown.toHtml("2. two\n3. three"),
        )
    }

    @Test
    fun `task list`() {
        assertEquals(
            "<ul><li>☐ todo</li><li>☑ done</li></ul>",
            Markdown.toHtml("- [ ] todo\n- [x] done"),
        )
    }

    @Test
    fun `table`() {
        val html = Markdown.toHtml(
            """
            | Name | Age |
            |:-----|----:|
            | A | 1 |
            """.trimIndent(),
        )
        assertEquals(
            "<div class=\"table-wrap\"><table><thead><tr>" +
                "<th align=\"left\">Name</th><th align=\"right\">Age</th>" +
                "</tr></thead><tbody><tr>" +
                "<td align=\"left\">A</td><td align=\"right\">1</td>" +
                "</tr></tbody></table></div>",
            html,
        )
    }

    @Test
    fun `quote`() {
        assertEquals(
            "<blockquote><p>quoted <b>text</b></p></blockquote>",
            Markdown.toHtml("> quoted **text**"),
        )
    }

    @Test
    fun `fenced code`() {
        assertEquals(
            "<pre><code>val x = 1 &lt; 2</code></pre>",
            Markdown.toHtml("```kotlin\nval x = 1 < 2\n```"),
        )
    }

    @Test
    fun `links`() {
        assertEquals(
            "<p><a href=\"https://example.com\">example</a></p>",
            Markdown.toHtml("[example](https://example.com)"),
        )
        assertEquals(
            "<p><a href=\"https://example.com\">https://example.com</a></p>",
            Markdown.toHtml("<https://example.com>"),
        )
        assertEquals(
            "<p><a href=\"https://x.com\"><b>bold</b></a></p>",
            Markdown.toHtml("[**bold**](https://x.com)"),
        )
    }

    @Test
    fun `strikethrough`() {
        assertEquals("<p><strike>gone</strike></p>", Markdown.toHtml("~~gone~~"))
    }

    @Test
    fun `line breaks`() {
        assertEquals("<p>a<br>b</p>", Markdown.toHtml("a\nb"))
        assertEquals("<p>a<br>b</p>", Markdown.toHtml("a  \nb"))
    }

    @Test
    fun `snake case is not emphasis`() {
        assertEquals("<p>snake_case_name</p>", Markdown.toHtml("snake_case_name"))
        assertEquals("<p><i>snake_case</i></p>", Markdown.toHtml("_snake_case_"))
    }

    @Test
    fun `unclosed fence does not crash`() {
        assertEquals(
            "<pre><code>code\nmore</code></pre>",
            Markdown.toHtml("```\ncode\nmore"),
        )
    }
}
