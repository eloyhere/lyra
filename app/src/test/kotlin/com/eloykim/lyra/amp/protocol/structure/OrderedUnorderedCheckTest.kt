package com.eloykim.lyra.amp.protocol.structure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ConcurrentModificationException

/**
 * 插入有序/无序哈希容器的检查。参考仓库没有专门的 ordered/unordered 检查文件，
 * 这里按 `ordered.ts` / `unordered.ts` 的文档契约补齐，并加入随机差分检查。
 */
class OrderedUnorderedCheckTest {

    @Test
    fun orderedSetIteratesInInsertionOrderAndMovesReAddedValues() {
        val set = OrderedSet(listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), set.toList())
        set.add("b")
        assertEquals(listOf("a", "b", "c"), set.keys().toList())
        assertTrue(set.delete("a"))
        assertFalse(set.has("a"))
        set.add("a")
        assertEquals(listOf("b", "c", "a"), set.toList())
        assertEquals(setOf("a", "b", "c"), set.keys().toSet())
        assertEquals(3, set.size)
        assertEquals(listOf("b" to "b", "c" to "c", "a" to "a"), set.entries().toList())
        assertFalse(set.isEmpty)
        set.clear()
        assertEquals(0, set.size)
        assertTrue(set.isEmpty)
        assertEquals(emptyList<String>(), set.toList())
    }

    @Test
    fun orderedMapKeepsInsertionOrderOnUpdatesAndAppendsReAddedKeys() {
        val map = OrderedMap(listOf("x" to 1, "y" to 2))
        map.set("x", 10)
        assertEquals(listOf("x", "y"), map.keys().toList())
        assertEquals(10, map.get("x"))
        map.set("z", 3)
        assertEquals(listOf("x", "y", "z"), map.keys().toList())
        assertTrue(map.delete("y"))
        map.set("y", 20)
        assertEquals(listOf("x", "z", "y"), map.keys().toList())
        assertEquals(listOf(10, 3, 20), map.values().toList())
        assertEquals(listOf("x" to 10, "z" to 3, "y" to 20), map.entries().toList())
        assertEquals(listOf("x" to 10, "z" to 3, "y" to 20), map.toList())
        assertFalse(map.isEmpty)
    }

    @Test
    fun orderedMapAllowsNullValuesAndUsesHasForMembership() {
        val map = OrderedMap<String, Int?>()
        map.set("present", null)
        assertTrue(map.has("present"))
        assertNull(map.get("present"))
        assertFalse(map.has("absent"))
        assertNull(map.get("absent"))
        assertEquals(1, map.size)
    }

    @Test
    fun orderedContainersAreFailFastOnStructuralMutation() {
        val map = OrderedMap(listOf("a" to 1))
        val iterator = map.iterator()
        assertTrue(iterator.hasNext())
        assertEquals("a" to 1, iterator.next())
        map.set("b", 2)
        assertThrows(ConcurrentModificationException::class.java) { iterator.next() }

        // 更新已有键的值不属于结构性修改。
        val updateIterator = map.iterator()
        map.set("a", 99)
        assertEquals(2, updateIterator.asSequence().toList().size)

        val deleteIterator = map.iterator()
        map.delete("b")
        assertThrows(ConcurrentModificationException::class.java) { deleteIterator.next() }

        val clearIterator = map.iterator()
        map.clear()
        assertThrows(ConcurrentModificationException::class.java) { clearIterator.next() }

        val empty = OrderedMap<String, Int>()
        val emptyIterator = empty.iterator()
        empty.clear()
        assertFalse(emptyIterator.hasNext())
    }

    @Test
    fun orderedSetIsFailFastAndReAddingAnExistingValueIsNotStructural() {
        val set = OrderedSet(listOf(1))
        val duplicateIterator = set.iterator()
        set.add(1)
        assertEquals(listOf(1), duplicateIterator.asSequence().toList())

        val iterator = set.iterator()
        set.add(2)
        assertThrows(ConcurrentModificationException::class.java) { iterator.next() }
    }

    @Test
    fun unorderedContainersDeduplicateAndIterateUnspecifiedOrder() {
        val set = UnorderedSet<Int>()
        for (index in 0 until 200) {
            set.add(index % 100)
        }
        assertEquals(100, set.size)
        for (index in 0 until 100) {
            assertTrue(set.has(index))
        }
        assertEquals(100, set.keys().toSet().size)
        assertEquals(set.keys().toSet(), set.toList().toSet())

        for (index in 0 until 100 step 2) {
            assertTrue(set.delete(index))
        }
        assertFalse(set.delete(0))
        assertEquals(50, set.size)
        for (index in 0 until 100 step 2) {
            assertFalse(set.has(index))
        }
        set.clear()
        assertTrue(set.isEmpty)
        assertEquals(0, set.size)
    }

    @Test
    fun unorderedMapGrowsAcrossResizesAndKeepsEveryLiveKey() {
        val map = UnorderedMap<Int, String>()
        for (index in 0 until 1_000) {
            map.set(index, "value-$index")
        }
        assertEquals(1_000, map.size)
        for (index in 0 until 1_000) {
            assertEquals("value-$index", map.get(index))
        }
        assertEquals(1_000, map.keys().toSet().size)
        for (index in 0 until 1_000 step 3) {
            assertTrue(map.delete(index))
            assertFalse(map.has(index))
        }
        assertEquals(666, map.size)
        map.clear()
        assertTrue(map.isEmpty)
        assertNull(map.get(5))
    }

    @Test
    fun unorderedContainersAreFailFastOnStructuralMutation() {
        val set = UnorderedSet<Int>()
        set.add(1)
        val duplicateIterator = set.iterator()
        set.add(1)
        assertEquals(listOf(1), duplicateIterator.asSequence().toList())

        val iterator = set.iterator()
        set.add(2)
        assertThrows(ConcurrentModificationException::class.java) { iterator.next() }

        val map = UnorderedMap(listOf("a" to 1))
        val mapIterator = map.iterator()
        map.set("a", 2)
        assertEquals(1, mapIterator.asSequence().toList().size)
        val deleteIterator = map.iterator()
        map.delete("a")
        assertThrows(ConcurrentModificationException::class.java) { deleteIterator.next() }
    }

    @Test
    fun customKeyStrategiesAreHonoredByBothMaps() {
        data class Row(val id: Int, val label: String)

        val strategy = KeyStrategy<Row>(
            hash = { row -> hashKey(row.id) },
            equals = { left, right -> left.id == right.id },
        )

        val ordered = OrderedMap<Row, String>(null, strategy)
        ordered.set(Row(1, "first"), "a")
        ordered.set(Row(1, "second"), "b")
        assertEquals(1, ordered.size)
        assertEquals("b", ordered.get(Row(1, "lookup")))
        assertEquals(listOf(Row(1, "first")), ordered.keys().toList())

        val unordered = UnorderedMap<Row, String>(null, strategy)
        unordered.set(Row(2, "first"), "a")
        unordered.set(Row(2, "second"), "b")
        assertEquals(1, unordered.size)
        assertEquals("b", unordered.get(Row(2, "lookup")))
        assertTrue(unordered.has(Row(2, "other")))
    }

    @Test
    fun orderedMapMatchesLinkedHashMapAcross5000RandomOperations() {
        val random = Mulberry32(0x0c7d)
        val map = OrderedMap<Int, Int>()
        val model = LinkedHashMap<Int, Int>()

        for (step in 0 until 5_000) {
            val key = Math.floor(random.next() * 300).toInt()
            val value = Math.floor(random.next() * 10_000).toInt()
            val roll = random.next()
            if (roll < 0.5) {
                map.set(key, value)
                model[key] = value
            } else if (roll < 0.72) {
                assertEquals(model.remove(key) != null, map.delete(key))
            } else if (roll < 0.86) {
                assertEquals(model[key], map.get(key))
            } else {
                assertEquals(model.containsKey(key), map.has(key))
            }
            assertEquals(model.size, map.size)
            if (step % 250 == 0) {
                assertEquals(model.keys.toList(), map.keys().toList())
                assertEquals(model.entries.map { it.key to it.value }, map.entries().toList())
            }
        }

        assertEquals(model.keys.toList(), map.keys().toList())
        assertEquals(model.values.toList(), map.values().toList())
    }

    @Test
    fun unorderedSetMatchesHashSetAcross5000RandomOperations() {
        val random = Mulberry32(0x51e7)
        val set = UnorderedSet<Int>()
        val model = HashSet<Int>()

        for (step in 0 until 5_000) {
            val value = Math.floor(random.next() * 500).toInt()
            if (random.next() < 0.6) {
                set.add(value)
                model.add(value)
            } else {
                assertEquals(model.remove(value), set.delete(value))
            }
            assertEquals(model.size, set.size)
            assertEquals(model.contains(value), set.has(value))
            if (step % 250 == 0) {
                assertEquals(model, set.keys().toSet())
            }
        }

        assertEquals(model, set.keys().toSet())
    }
}
