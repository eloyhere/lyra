package com.eloykim.lyra.amp.protocol.window

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowTest {

    private fun window(
        capacityTokens: Int = 100,
        maxPages: Int = 128,
        now: () -> Long = { 0L },
        objects: ObjectRepository? = null,
    ): ContextWindow = ContextWindow(
        ContextWindowOptions(
            capacityTokens = capacityTokens,
            maxPages = maxPages,
            now = now,
            objects = objects,
        ),
    )

    @Test
    fun `page key and id follow the reference shape`() {
        assertEquals("claim:cl.1", pageKey("claim", "cl.1"))
        assertEquals("pg.claim.cl.1", pageIdFor("claim", "cl.1"))
    }

    @Test
    fun `fault evicts the least recently used unpinned page`() {
        val context = window(capacityTokens = 100, maxPages = 2, now = { 1L })
        val first = context.fault("claim", "cl.1", 60)
        assertEquals("pg.claim.cl.1", first.id)
        assertEquals(0, first.pinned)

        context.fault("claim", "cl.2", 50)

        assertFalse(context.has("claim", "cl.1"))
        assertTrue(context.has("claim", "cl.2"))
        assertEquals(50, context.usedTokens)
        assertEquals(1, context.stats().evictions)
        assertEquals(2, context.stats().faults)
    }

    @Test
    fun `a repeated fault is a hit and does not charge tokens twice`() {
        val context = window()
        val first = context.fault("claim", "cl.1", 40)
        val second = context.fault("claim", "cl.1", 40)

        assertSame(first, second)
        assertEquals(40, context.usedTokens)
        assertEquals(1, context.stats().faults)
        assertEquals(1, context.stats().hits)
        assertEquals(1, context.stats().pages)
    }

    @Test
    fun `pinned pages refuse eviction and report window_full`() {
        val context = window(capacityTokens = 100, maxPages = 2, now = { 1L })
        context.fault("claim", "cl.1", 60)
        context.fault("claim", "cl.2", 50)
        context.pin("claim", "cl.2")

        val error = assertThrows(WindowError::class.java) {
            context.fault("claim", "cl.3", 60)
        }
        assertEquals(WindowCode.WINDOW_FULL, error.code)
        assertTrue(error.problems.single().startsWith("no unpinned page can make room"))

        context.unpin("claim", "cl.2")
        context.fault("claim", "cl.3", 40)
        assertEquals(90, context.usedTokens)
        assertEquals(2, context.stats().pages)
    }

    @Test
    fun `pin, unpin and release maintain the pin count`() {
        val context = window()
        context.fault("claim", "cl.1", 10)
        assertEquals(1, context.pin("claim", "cl.1"))
        assertEquals(2, context.pin("claim", "cl.1"))
        assertEquals(1, context.unpin("claim", "cl.1"))
        assertEquals(0, context.unpin("claim", "cl.1"))
        assertEquals(0, context.unpin("claim", "cl.1"))

        val unknownPin = assertThrows(WindowError::class.java) { context.pin("claim", "missing") }
        assertEquals(WindowCode.UNKNOWN_PAGE, unknownPin.code)
        assertEquals(listOf("claim:missing"), unknownPin.problems)

        assertTrue(context.release("claim", "cl.1"))
        assertFalse(context.release("claim", "cl.1"))
        assertEquals(0, context.usedTokens)
        assertNull(context.pageOf("claim", "cl.1"))
    }

    @Test
    fun `snapshot and restore round-trip the page table`() {
        val context = window()
        val early = context.fault("claim", "cl.1", 10)
        early.lastUsed = 100L
        val late = context.fault("claim", "cl.2", 20)
        late.lastUsed = 200L
        context.pin("claim", "cl.2")

        val snapshot = context.snapshot()
        assertEquals(listOf("cl.1", "cl.2"), snapshot.map { it.objectId })
        assertEquals(listOf(false, true), snapshot.map { it.pinned })

        val restored = window(maxPages = 4)
        restored.restore(snapshot)
        assertEquals(30, restored.usedTokens)
        assertEquals(2, restored.entries().size)
        assertEquals(1, restored.pageOf("claim", "cl.2")?.pinned)
        assertEquals(100L, restored.pageOf("claim", "cl.1")?.lastUsed)
        assertEquals(snapshot.toSet(), restored.snapshot().toSet())
    }

    @Test
    fun `the page ceiling evicts even when tokens still fit`() {
        val context = window(capacityTokens = 1000, maxPages = 2, now = { 5L })
        context.fault("claim", "cl.1", 10)
        context.fault("claim", "cl.2", 10)
        context.fault("claim", "cl.3", 10)

        assertFalse(context.has("claim", "cl.1"))
        assertEquals(20, context.usedTokens)
        assertEquals(1, context.stats().evictions)
    }

    @Test
    fun `a page larger than the window is rejected before eviction`() {
        val context = window(capacityTokens = 100)
        val error = assertThrows(WindowError::class.java) {
            context.fault("claim", "big", 101)
        }
        assertEquals(WindowCode.WINDOW_FULL, error.code)
        assertEquals(listOf("claim:big needs 101 tokens, window holds 100"), error.problems)
        assertEquals(0, context.usedTokens)
        assertEquals(0, context.stats().evictions)
    }

    @Test
    fun `invalid capacities and token counts fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            window(capacityTokens = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            window(maxPages = 0)
        }
        val context = window()
        assertThrows(IllegalArgumentException::class.java) {
            context.fault("claim", "cl.1", 0)
        }
    }

    @Test
    fun `the optional object repository supplies parsed content`() {
        val objects = ObjectRepository { kind, id ->
            when ("$kind:$id") {
                "doc:d.1" -> ObjectRow("""{"text":"hello"}""")
                "doc:raw" -> ObjectRow("not json")
                else -> null
            }
        }
        val context = window(objects = objects)

        val parsed = context.fault("doc", "d.1", 10)
        assertEquals(jsonObj("text" to jsonText("hello")), parsed.content)

        val raw = context.fault("doc", "raw", 10)
        assertEquals(JsonValue.Str("not json"), raw.content)

        val missing = context.fault("doc", "missing", 10)
        assertNull(missing.content)

        val explicit = context.fault("doc", "d.2", 10, jsonObj("explicit" to JsonValue.Bool(true)))
        assertEquals(jsonObj("explicit" to JsonValue.Bool(true)), explicit.content)
    }

    @Test
    fun `defaults and stats are inspectable`() {
        val context = window()
        assertEquals(128, context.maxPages)
        val stats = context.stats()
        assertEquals(0, stats.hits)
        assertEquals(0, stats.faults)
        assertEquals(0, stats.evictions)
        assertEquals(0, stats.pages)
        assertEquals(100, stats.capacityTokens)
        assertNotEquals(0, WindowCode.entries.size)
    }
}
