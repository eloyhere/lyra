package com.eloykim.lyra.amp.protocol.execution

import com.eloykim.lyra.amp.protocol.types.HandleKind
import com.eloykim.lyra.amp.protocol.types.HandleScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `src/execution/handles.ts` 的行为锁定：内容寻址 id 与确定性分页。
 */
class HandlesTest {

    @Test
    fun `put 产出确定性 id 与计量字段`() {
        val first = HandleStore()
        val handle = first.put("abcdefghij", HandleKind.TEXT, tokens = 3)
        assertEquals("h.32ce095e90", handle.id)
        assertTrue(handle.id.startsWith("h."))
        assertEquals(12, handle.id.length)
        assertEquals(HandleKind.TEXT, handle.kind)
        assertEquals(HandleScope.SESSION, handle.scope)
        assertEquals(3, handle.tokens)
        assertEquals(900_000L, handle.ttlMs)
        assertTrue(first.has(handle.id))
        assertEquals(handle, first.get(handle.id))

        val second = HandleStore()
        assertEquals(handle.id, second.put("abcdefghij", HandleKind.TEXT, tokens = 3).id)
        val other = HandleStore().put("abcdefghij", HandleKind.TEXT, tokens = 3)
        assertEquals(handle.id, other.id)
    }

    @Test
    fun `分页窗口固定且游标确定`() {
        val store = HandleStore()
        val handle = store.put("abcdefghij", HandleKind.TEXT, tokens = 3)

        val page0 = store.read(handle.id, cursor = 0, limitTokens = 1)
        assertEquals("abcd", page0.text)
        assertEquals(4, page0.next)
        assertEquals(3, page0.totalPages)
        assertEquals(1, page0.tokens)

        val page1 = store.read(handle.id, cursor = 4, limitTokens = 1)
        assertEquals("efgh", page1.text)
        assertEquals(8, page1.next)
        assertEquals(3, page1.totalPages)

        val page2 = store.read(handle.id, cursor = 8, limitTokens = 1)
        assertEquals("ij", page2.text)
        assertNull(page2.next)
        assertEquals(3, page2.totalPages)
        assertEquals(1, page2.tokens)

        // 同一游标总是同一窗口。
        assertEquals(page1, store.read(handle.id, cursor = 4, limitTokens = 1))
    }

    @Test
    fun `游标越界被夹取且空窗口没有下一页`() {
        val store = HandleStore()
        val handle = store.put("abc", HandleKind.TEXT, tokens = 1)
        val page = store.read(handle.id, cursor = 99)
        assertEquals("", page.text)
        assertNull(page.next)
        assertEquals(1, page.totalPages)

        val zero = store.read(handle.id, cursor = 0, limitTokens = 0)
        assertEquals("a", zero.text)
        assertEquals(1, zero.next)
        assertEquals(3, zero.totalPages)
    }

    @Test
    fun `未知 handle 抛错`() {
        try {
            HandleStore().read("h.missing", 0)
            throw AssertionError("expected IllegalStateException")
        } catch (error: IllegalStateException) {
            assertEquals("unknown handle: h.missing", error.message)
        }
    }

    @Test
    fun `HandlePage JSON 往返`() {
        val page = HandlePage(text = "abcd", next = 4, totalPages = 3, tokens = 1)
        assertEquals(page, HandlePage.fromJson(page.toJson()))
        val last = HandlePage(text = "ij", next = null, totalPages = 3, tokens = 1)
        assertEquals(last, HandlePage.fromJson(last.toJson()))
        assertFalse(last.toJson().has("next"))
        assertNotNull(HandlePage.fromJson(last.toJson()).text)
    }
}
