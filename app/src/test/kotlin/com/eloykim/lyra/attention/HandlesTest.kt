package com.eloykim.lyra.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HandlesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val longText = (1..120).joinToString("\n") { "第 $it 行：这是一段用于分页测试的中文内容" }

    @Test
    fun `同一内容得到同一句柄`() {
        val store = InMemoryHandleStore()
        val a = store.put("tree", "确认订单")
        val b = store.put("tree", "确认订单")

        assertEquals(a.id, b.id)
        assertEquals(a.digest, b.digest)
    }

    @Test
    fun `不同内容得到不同句柄`() {
        val store = InMemoryHandleStore()
        val a = store.put("tree", "确认订单")
        val b = store.put("tree", "取消订单")

        assertTrue(a.id != b.id)
    }

    @Test
    fun `分页可以重建全文`() {
        val store = InMemoryHandleStore()
        val ref = store.put("tree", longText, chunkChars = 200)
        assertTrue(ref.totalChunks > 2)

        val builder = StringBuilder()
        var cursor: String? = null
        do {
            val page = store.read(ref.id, cursor, limit = 3)
            assertNotNull(page)
            builder.append(page!!.content)
            cursor = page.nextCursor
        } while (cursor != null)

        assertEquals(longText, builder.toString())
    }

    @Test
    fun `同一 cursor 返回同一页`() {
        val store = InMemoryHandleStore()
        val ref = store.put("ocr", longText, chunkChars = 100)

        val first = store.read(ref.id, "2", limit = 1)
        val second = store.read(ref.id, "2", limit = 1)

        assertEquals(first?.content, second?.content)
    }

    @Test
    fun `未知句柄返回空`() {
        val store = InMemoryHandleStore()
        assertNull(store.read("h.000000000000", null, 2))
    }

    @Test
    fun `文件版重启后仍可读`() {
        val dir = temp.newFolder("handles")
        val writer = FileHandleStore(dir)
        val ref = writer.put("ocr", longText, chunkChars = 200)

        val reader = FileHandleStore(dir)
        val page = reader.read(ref.id, null, 1)

        assertNotNull(page)
        assertTrue(page!!.content.isNotEmpty())
    }

    @Test
    fun `中文 token 估算高于同等字符数的英文`() {
        val chinese = estimateTokens("确认订单并提交")
        val english = estimateTokens("abcdefgh")

        assertTrue(chinese > english)
    }

    @Test
    fun `空文本估算为零`() {
        assertEquals(0, estimateTokens(""))
    }
}
