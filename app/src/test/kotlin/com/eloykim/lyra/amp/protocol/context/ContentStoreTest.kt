package com.eloykim.lyra.amp.protocol.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ContentStoreTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "amp-ctx-" + UUID.randomUUID()).apply { mkdirs() }

    @Test
    fun memoryStoreDedupesEqualContent() {
        val store = MemoryContentStore()
        val first = store.put("hello")
        val second = store.put("hello")
        assertEquals(first, second)
        assertEquals("hello", store.get(first))
        assertEquals(1, store.size())
        assertNull(store.get("sha256:" + "0".repeat(64)))
        assertNull(store.get("../outside"))
    }

    @Test
    fun fileStoreRoundTripsAndVerifiesHash() {
        val dir = tempDir()
        val store = FileContentStore(dir)
        val ref = store.put("payload")
        assertTrue(ref.matches(Regex("^sha256:[0-9a-f]{64}$")))
        assertEquals("payload", store.get(ref))

        // 同名文件被改写后，读取必须校验失败，而不是返回不可信的字节。
        File(dir, ref.removePrefix("sha256:") + ".txt").writeText("tampered")
        val error = assertThrows(IllegalStateException::class.java) { store.get(ref) }
        assertTrue(error.message!!.contains("integrity mismatch"))
    }

    @Test
    fun fileStoreRejectsTraversalAndNestedPaths() {
        val dir = tempDir()
        val store = FileContentStore(dir)
        val ref = store.put("x")
        val path = File(dir, ref.removePrefix("sha256:") + ".txt")

        val outside = File(dir.parentFile, "outside-" + UUID.randomUUID() + ".txt")
        outside.writeText("outside")
        assertNull(storedFile(dir, outside.path))

        val nested = File(dir, "sub").apply { mkdirs() }
        File(nested, "nested.txt").writeText("nested")
        assertNull(storedFile(dir, File(nested, "nested.txt").path))

        val stored = storedFile(dir, path.path)
        assertNotNull(stored)
        assertEquals(path.canonicalFile, stored!!.canonicalFile)
    }

    @Test
    fun detailStoresKeepSummariesAndBodiesSeparate() {
        val dir = tempDir()
        val store = FileDetailStore(dir)
        val ref = store.put("node.one", "detail body")
        assertEquals("detail body", store.get(ref))
        assertNull(store.get(File(dir, "missing.txt").path))

        val memory = MemoryDetailStore()
        assertEquals("alias", memory.put("alias", "content"))
        assertEquals("content", memory.get("alias"))
    }
}
