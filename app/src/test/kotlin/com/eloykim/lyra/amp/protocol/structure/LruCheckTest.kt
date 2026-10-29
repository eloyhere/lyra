package com.eloykim.lyra.amp.protocol.structure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `tools/lru-check.ts` 的 Kotlin 版本：LRU 链表、有序/无序映射与集合。
 */
class LruCheckTest {

    @Test
    fun lruListMaintainsFrontToBackOrderAndNodeOperations() {
        val list = LRUList(listOf(2, 3))
        val one = list.pushFront(1)
        val four = list.pushBack(4)
        assertEquals(listOf(1, 2, 3, 4), list.toList())
        assertEquals(1, list.front)
        assertEquals(4, list.back)
        assertEquals(4, list.size)

        assertTrue(list.moveToFront(four))
        assertEquals(listOf(4, 1, 2, 3), list.toList())
        assertTrue(list.remove(one))
        assertEquals(listOf(4, 2, 3), list.toList())
        assertFalse(list.remove(one))
        assertEquals(4, list.popFront())
        assertEquals(3, list.popBack())
        assertEquals(listOf(2), list.toList())
        list.clear()
        assertEquals(0, list.size)
        assertNull(list.front)
        assertTrue(list.isEmpty)
    }

    @Test
    fun lruListNodesBelongToOneListOnly() {
        val first = LRUList<Int>()
        val second = LRUList<Int>()
        val node = first.pushBack(1)
        assertFalse(second.remove(node))
        assertFalse(second.moveToFront(node))
        assertTrue(first.remove(node))
        assertTrue(first.isEmpty)
    }

    @Test
    fun lruOrderedMapEvictsByRecencyWhileIteratingInInsertionOrder() {
        val map = LRUOrderedMap<String, Int>(3)
        map.set("a", 1).set("b", 2).set("c", 3)
        assertEquals(listOf("a", "b", "c"), map.keys().toList())
        assertEquals(1, map.get("a"))
        assertEquals("a" to 1, map.mostRecentlyUsed())
        assertEquals("b" to 2, map.leastRecentlyUsed())

        map.set("d", 4)
        assertFalse(map.has("b"))
        assertEquals(listOf("a", "c", "d"), map.keys().toList())
        assertEquals(3, map.size)
        assertTrue(map.isFull)
        assertEquals(listOf("a" to 1, "c" to 3, "d" to 4), map.entries().toList())
        assertEquals(listOf(1, 3, 4), map.values().toList())

        map.set("c", 30)
        assertEquals(listOf("a", "c", "d"), map.keys().toList())
        assertEquals(30, map.get("c"))
        assertEquals("c" to 30, map.mostRecentlyUsed())
    }

    @Test
    fun lruOrderedMapHasPeekDoNotTouchAndTouchDoes() {
        val map = LRUOrderedMap<String, Int>(2)
        map.set("a", 1).set("b", 2)
        assertEquals(1, map.peek("a"))
        assertTrue(map.has("a"))
        assertEquals("a" to 1, map.leastRecentlyUsed())
        assertTrue(map.touch("a"))
        assertEquals("b" to 2, map.leastRecentlyUsed())
        map.set("c", 3)
        assertFalse(map.has("b"))
        assertFalse(map.touch("missing"))
        assertNull(map.peek("missing"))
    }

    @Test
    fun lruOrderedMapDeleteReAddMovesTheKeyToTheInsertionTail() {
        val map = LRUOrderedMap(3, listOf("a" to 1, "b" to 2))
        assertTrue(map.delete("a"))
        map.set("a", 10)
        assertEquals(listOf("b", "a"), map.keys().toList())
        assertEquals(10, map.get("a"))
    }

    @Test
    fun lruOrderedMapClearAndChainingBehave() {
        val map = LRUOrderedMap<String, Int>(2)
        assertSame(map, map.set("a", 1))
        map.set("b", 2)
        map.clear()
        assertEquals(0, map.size)
        assertFalse(map.has("a"))
        assertTrue(map.keys().toList().isEmpty())
        assertNull(map.get("a"))
    }

    @Test
    fun lruOrderedSetSharesRecencyAndCapacitySemantics() {
        val set = LRUOrderedSet(2, listOf(1, 2))
        assertTrue(set.has(1))
        assertTrue(set.get(1))
        assertEquals(listOf(1, 2), set.toList())
        set.add(3)
        assertFalse(set.has(2))
        assertEquals(listOf(1, 3), set.toList())
        assertEquals(1, set.leastRecentlyUsed())
        set.touch(1)
        assertEquals(3, set.leastRecentlyUsed())
        set.delete(1)
        assertFalse(set.has(1))
        assertEquals(1, set.size)
        set.add(1)
        assertEquals(listOf(3, 1), set.toList())
        assertEquals(2, set.capacity)
    }

    @Test
    fun lruUnorderedMapPreservesRecencyAndEvictionWithoutOrderPromises() {
        val map = LRUUnorderedMap<String, Int>(2)
        map.set("a", 1).set("b", 2)
        assertEquals(1, map.get("a"))
        map.set("c", 3)
        assertFalse(map.has("b"))
        assertEquals(2, map.size)
        assertEquals(setOf("a", "c"), map.keys().toSet())
        assertEquals("a" to 1, map.leastRecentlyUsed())
        assertEquals("c" to 3, map.mostRecentlyUsed())
    }

    @Test
    fun lruUnorderedSetPreservesRecencyAndCapacity() {
        val set = LRUUnorderedSet(2, listOf("a", "b"))
        assertTrue(set.has("a"))
        set.add("c")
        assertFalse(set.has("a"))
        assertTrue(set.has("b"))
        assertEquals(2, set.size)
        assertEquals("b", set.leastRecentlyUsed())
        set.touch("b")
        assertEquals("c", set.leastRecentlyUsed())
        assertEquals(2, set.entries().toList().size)
        assertEquals(set.keys().toList(), set.entries().map { it.first }.toList())
    }

    @Test
    fun capacityAndCustomKeyStrategiesAreHonored() {
        assertThrows(IllegalArgumentException::class.java) { LRUOrderedMap<String, Int>(0) }
        assertThrows(IllegalArgumentException::class.java) { LRUUnorderedSet<String>(0) }

        data class Key(val id: Int)

        val strategy = KeyStrategy<Key>(
            hash = { key -> hashKey(key.id) },
            equals = { left, right -> left.id == right.id },
        )
        val map = LRUOrderedMap<Key, String>(2, null, strategy)
        map.set(Key(1), "a")
        map.set(Key(2), "b")
        assertEquals("a", map.get(Key(1)))
        map.set(Key(3), "c")
        assertFalse(map.has(Key(2)))
        assertEquals("a", map.get(Key(1)))
    }

    @Test
    fun forEachAndConstructorCapacityEviction() {
        val map = LRUOrderedMap(2, listOf("a" to 1, "b" to 2, "c" to 3))
        assertEquals(2, map.size)
        assertFalse(map.has("a"))
        val seen = ArrayList<Pair<String, Int>>()
        map.forEach { (key, value) -> seen.add(key to value) }
        assertEquals(listOf("b" to 2, "c" to 3), seen)
    }

    @Test
    fun lruOrderedMapUpdatesExistingKeyValueWithoutMovingInsertionPosition() {
        val map = LRUOrderedMap(3, listOf("a" to 1, "b" to 2, "c" to 3))
        map.set("b", 20)
        assertEquals(listOf("a", "b", "c"), map.keys().toList())
        assertEquals(20, map.get("b"))
        assertEquals("b" to 20, map.mostRecentlyUsed())
        assertNotEquals(listOf("b", "a", "c"), map.keys().toList())
    }
}
