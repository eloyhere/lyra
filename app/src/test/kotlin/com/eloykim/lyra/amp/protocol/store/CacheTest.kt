package com.eloykim.lyra.amp.protocol.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheTest {

    private data class Value(val id: String)

    @Test
    fun `EntityCache respects TTL and LRU capacity`() {
        var clock = 0L
        var loads = 0
        val cache = EntityCache(
            EntityCacheOptions(
                load = { key ->
                    loads += 1
                    Value(key)
                },
                keyOf = { value -> value.id },
                capacity = 1,
                ttlMs = 10,
                now = { clock },
            ),
        )

        cache.get("1")
        cache.get("1")
        assertEquals(1, loads)

        clock = 20
        cache.get("1")
        assertEquals(2, loads)

        cache.get("2")
        assertTrue(cache.stats().evictions >= 1)

        cache.get("1")
        assertEquals(4, loads)
    }

    @Test
    fun `the TTL boundary is inclusive and counters match the reference`() {
        var clock = 0L
        val cache = EntityCache(
            EntityCacheOptions(
                load = { key -> Value(key) },
                keyOf = { value -> value.id },
                ttlMs = 10,
                now = { clock },
            ),
        )

        cache.get("1")
        clock = 10
        cache.get("1")
        assertEquals(1, cache.stats().loads)
        assertEquals(1, cache.stats().hits)
        assertEquals(1, cache.stats().misses)

        clock = 11
        cache.get("1")
        assertEquals(2, cache.stats().loads)
        assertEquals(2, cache.stats().misses)
    }

    @Test
    fun `a missing entity counts as a miss but not a load`() {
        var calls = 0
        val cache = EntityCache(
            EntityCacheOptions<Value>(
                load = {
                    calls += 1
                    null
                },
                keyOf = { value -> value.id },
            ),
        )

        assertNull(cache.get("missing"))
        assertNull(cache.get("missing"))
        assertEquals(2, cache.stats().misses)
        assertEquals(0, cache.stats().loads)
        assertEquals(0, cache.size)
        assertEquals(2, calls)
    }

    @Test
    fun `put, peek, entries, entities and invalidation`() {
        val cache = EntityCache(
            EntityCacheOptions(
                load = { key -> Value(key) },
                keyOf = { value -> value.id },
                ttlMs = 1000,
                now = { 0L },
            ),
        )

        val first = Value("1")
        cache.put(first)
        assertSame(first, cache.get("1"))
        assertEquals(0, cache.stats().loads)
        assertEquals(1, cache.stats().hits)

        cache.put(Value("2"))
        assertEquals(listOf("1", "2"), cache.entries().map { it.id })
        assertSame(first, cache.peek("1"))
        assertEquals(setOf("1", "2"), cache.entities().keys)
        assertEquals("1", cache.entities()["1"]?.id)

        cache.invalidate("1")
        assertNull(cache.peek("1"))
        assertEquals(listOf("2"), cache.entries().map { it.id })

        cache.invalidate()
        assertEquals(0, cache.size)
        assertEquals(0, cache.entities().size)
    }

    @Test
    fun `integer keys are stringified like the reference`() {
        val cache = EntityCache(
            EntityCacheOptions(
                load = { key -> Value(key) },
                keyOf = { value -> "#${value.id}" },
                now = { 0L },
            ),
        )
        assertSame(cache.get("42"), cache.get(42))
        cache.put(Value("7"))
        assertEquals(setOf("42", "#7"), cache.entities().keys)
    }

    @Test
    fun `a non-positive capacity is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            EntityCache(
                EntityCacheOptions(
                    load = { key -> Value(key) },
                    keyOf = { value -> value.id },
                    capacity = 0,
                ),
            )
        }
    }

    @Test
    fun `eviction removes the least recently used entry`() {
        val cache = EntityCache(
            EntityCacheOptions(
                load = { key -> Value(key) },
                keyOf = { value -> value.id },
                capacity = 2,
                ttlMs = 1000,
                now = { 0L },
            ),
        )
        cache.put(Value("a"))
        cache.put(Value("b"))
        assertSame(cache.entities()["a"], cache.get("a")) // a becomes most recent
        cache.put(Value("c"))

        assertNull(cache.peek("b"))
        assertTrue(cache.entities().keys.containsAll(listOf("a", "c")))
        assertEquals(1, cache.stats().evictions)
    }
}
