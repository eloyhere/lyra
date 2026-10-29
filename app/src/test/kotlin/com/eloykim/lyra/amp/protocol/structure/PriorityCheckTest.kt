package com.eloykim.lyra.amp.protocol.structure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `tools/priority-check.ts` 的 Kotlin 版本：稳定比较优先队列、键控优先队列、
 * 整数桶队列与有界 TopK，包含与稳定排序模型对比的随机差分检查。
 */
class PriorityCheckTest {

    private data class Row(val id: Int, val rank: Int)

    private val compareRows = Comparator<Row> { left, right -> left.rank - right.rank }

    /** 比较队列的参考顺序：先优先级，再到达顺序（list 按到达顺序保存且排序稳定）。 */
    private fun orderRows(rows: List<Row>, order: HeapOrder): List<Row> {
        val copy = rows.toMutableList()
        copy.sortWith(Comparator { left, right ->
            if (order == HeapOrder.MAX) compareRows.compare(right, left) else compareRows.compare(left, right)
        })
        return copy
    }

    private data class BucketRow(val value: Int, val priority: Int)

    private fun orderBuckets(rows: List<BucketRow>, order: HeapOrder): List<BucketRow> {
        val copy = rows.toMutableList()
        copy.sortWith(Comparator { left, right ->
            if (order == HeapOrder.MAX) right.priority - left.priority else left.priority - right.priority
        })
        return copy
    }

    /** 逐位比较序列；`assertEquals` 在几千项时会把首个差异埋在完整 dump 里。 */
    private fun <T> assertSameSequence(actual: List<T>, expected: List<T>, label: String) {
        assertEquals("$label: length ${actual.size} != ${expected.size}", expected.size, actual.size)
        for (index in expected.indices) {
            if (actual[index] != expected[index]) {
                throw AssertionError("$label: index $index: ${actual[index]} != ${expected[index]}")
            }
        }
    }

    private fun runPriorityQueueDifferential(order: HeapOrder, seed: Int) {
        val random = Mulberry32(seed)
        val queue = PriorityQueue<Row>(compare = compareRows, order = order)
        val model = ArrayList<Row>()
        var nextId = 0

        for (step in 0 until 2_000) {
            if (model.isEmpty() || random.next() < 0.6) {
                val row = Row(nextId, Math.floor(random.next() * 12).toInt())
                nextId += 1
                queue.enqueue(row)
                model.add(row)
                continue
            }
            val expected = orderRows(model, order)[0]
            val actual = queue.dequeue()
            assertEquals(expected.id, actual?.id)
            model.removeAt(model.indexOf(expected))
        }

        assertEquals(model.size, queue.size)
        assertSameSequence(queue.toArray().map { it.id }, orderRows(model, order).map { it.id }, "priority queue $order")
    }

    private fun runIndexedDifferential(order: HeapOrder, seed: Int) {
        val random = Mulberry32(seed)
        val queue = IndexedPriorityQueue<Int, Row>(keyOf = { row -> row.id }, compare = compareRows, order = order)
        val model = LinkedHashMap<Int, Row>()
        var nextId = 0

        for (step in 0 until 3_000) {
            val roll = random.next()
            if (roll < 0.45) {
                val keys = model.keys.toList()
                val reuse = keys.isNotEmpty() && random.next() < 0.5
                val id = if (reuse) keys[Math.floor(random.next() * keys.size).toInt()] else nextId
                if (!reuse) {
                    nextId += 1
                }
                val row = Row(id, Math.floor(random.next() * 12).toInt())
                queue.set(row)
                model[id] = row
            } else if (roll < 0.7) {
                val keys = model.keys.toList()
                if (keys.isEmpty()) {
                    continue
                }
                val key = keys[Math.floor(random.next() * keys.size).toInt()]
                assertEquals(model.remove(key) != null, queue.delete(key))
            } else if (model.isNotEmpty()) {
                val expected = orderRows(model.values.toList(), order)[0]
                val actual = queue.dequeue()
                assertEquals(expected.id, actual?.id)
                model.remove(expected.id)
            }
            assertEquals(model.size, queue.size)
            if (model.isNotEmpty()) {
                assertEquals(orderRows(model.values.toList(), order)[0].id, queue.peek()?.id)
            }
        }

        assertSameSequence(queue.keys(), orderRows(model.values.toList(), order).map { it.id }, "indexed queue keys $order")
    }

    private fun runBucketDifferential(order: HeapOrder, seed: Int) {
        val random = Mulberry32(seed)
        val queue = BucketQueue<Int>(order = order)
        val model = ArrayList<BucketRow>()
        var nextValue = 0

        for (step in 0 until 2_000) {
            if (model.isEmpty() || random.next() < 0.55) {
                val priority = Math.floor(random.next() * 9).toInt() - 4
                queue.enqueue(nextValue, priority)
                model.add(BucketRow(nextValue, priority))
                nextValue += 1
                continue
            }
            val expected = orderBuckets(model, order)[0]
            assertEquals(expected.priority, queue.peekPriority())
            assertEquals(expected.value, queue.dequeue())
            model.removeAt(model.indexOf(expected))
        }

        assertEquals(model.size, queue.size)
        assertSameSequence(queue.toArray(), orderBuckets(model, order).map { it.value }, "bucket queue $order")
    }

    @Test
    fun priorityQueueServesTheGreatestValueFirstByDefault() {
        val queue = PriorityQueue<Int>()
        queue.enqueue(3).enqueue(9).enqueue(1).enqueue(7)
        assertEquals(4, queue.size)
        assertFalse(queue.isEmpty)
        assertEquals(9, queue.peek())
        assertEquals(9, queue.dequeue())
        assertEquals(7, queue.dequeue())
        assertEquals(3, queue.dequeue())
        assertEquals(1, queue.dequeue())
        assertNull(queue.dequeue())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun priorityQueueServesTheSmallestValueFirstWithMinOrder() {
        val queue = PriorityQueue<Int>(order = HeapOrder.MIN)
        queue.enqueueAll(listOf(5, 2, 8, 1))
        assertEquals(1, queue.peek())
        assertEquals(listOf(1, 2, 5, 8), queue.drain())
    }

    @Test
    fun priorityQueueBreaksTiesByArrivalInBothOrders() {
        val compare = Comparator<String> { left, right -> left.length - right.length }
        val maxQueue = PriorityQueue(compare = compare, values = listOf("bb", "a", "cc", "dd", "e"))
        assertEquals(listOf("bb", "cc", "dd", "a", "e"), maxQueue.toArray())

        val minQueue = PriorityQueue(compare = compare, order = HeapOrder.MIN, values = listOf("bb", "a", "cc", "dd", "e"))
        assertEquals(listOf("a", "e", "bb", "cc", "dd"), minQueue.toArray())
    }

    @Test
    fun priorityQueueSeedsFromValuesEnqueueAllAndFrom() {
        val seeded = PriorityQueue(values = listOf(4, 1, 4, 2))
        assertEquals(4, seeded.size)
        assertEquals(listOf(4, 4, 2, 1), seeded.drain())

        val from = PriorityQueue.from(listOf(5, 3, 8, 1))
        assertEquals(4, from.size)
        assertEquals(listOf(8, 5, 3, 1), from.toList())

        val minFrom = PriorityQueue.from(listOf("pear", "apple", "fig"), order = HeapOrder.MIN)
        assertEquals(listOf("apple", "fig", "pear"), minFrom.drain())
    }

    @Test
    fun priorityQueueSnapshotsAreNonDestructiveAndDrainRemovesEverything() {
        val queue = PriorityQueue(values = listOf(2, 7, 4))
        val view = queue.toArray()
        assertEquals(listOf(7, 4, 2), view)
        assertEquals(3, queue.size)

        val copy = view.toMutableList()
        copy[0] = -1
        copy.add(100)
        assertEquals(listOf(7, 4, 2), queue.toArray())
        assertEquals(listOf(7, 4, 2), queue.toList())

        val iterator = queue.iterator()
        queue.enqueue(10)
        assertEquals(listOf(7, 4, 2), iterator.asSequence().toList())

        assertEquals(listOf(10, 7, 4, 2), queue.drain())
        assertTrue(queue.isEmpty)
        assertEquals(emptyList<Int>(), queue.toArray())
        assertEquals(emptyList<Int>(), queue.toList())
    }

    @Test
    fun priorityQueueAcceptsAKeyDerivedComparator() {
        data class Job(val name: String, val priority: Int)

        val jobs = listOf(
            Job("low", 1),
            Job("urgent", 9),
            Job("medium", 5),
        )
        val queue = PriorityQueue(compare = byKey(select = { job: Job -> job.priority }))
        queue.enqueueAll(jobs)
        assertEquals("urgent", queue.peek()?.name)
        queue.enqueue(Job("critical", 10))
        assertEquals(listOf("critical", "urgent", "medium", "low"), queue.drain().map { it.name })
    }

    @Test
    fun priorityQueueEmptyBehaviorAndClearAreWellDefined() {
        val queue = PriorityQueue<String>()
        assertEquals(0, queue.size)
        assertTrue(queue.isEmpty)
        assertNull(queue.peek())
        assertNull(queue.dequeue())
        assertEquals(emptyList<String>(), queue.toArray())
        assertEquals(emptyList<String>(), queue.drain())

        queue.enqueue("a").enqueue("b")
        assertEquals(2, queue.size)
        queue.clear()
        assertEquals(0, queue.size)
        assertTrue(queue.isEmpty)
        assertNull(queue.peek())

        queue.enqueue("z")
        assertEquals("z", queue.dequeue())
    }

    @Test
    fun priorityQueueKeepsNullValuesWithoutLosingCount() {
        val compare = Comparator<Int?> { left, right -> (left ?: -1) - (right ?: -1) }
        val queue = PriorityQueue<Int?>(compare = compare)
        queue.enqueue(null).enqueue(1)
        assertEquals(2, queue.size)
        assertEquals(1, queue.dequeue())
        assertEquals(1, queue.size)
        assertNull(queue.dequeue())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun priorityQueueDrains10000ValuesInPriorityOrder() {
        val random = Mulberry32(0x5eed)
        val queue = PriorityQueue<Int>()
        val values = ArrayList<Int>()
        for (index in 0 until 10_000) {
            val value = Math.floor(random.next() * 1_000).toInt()
            values.add(value)
            queue.enqueue(value)
        }
        val expected = values.sortedDescending()
        assertSameSequence(queue.drain(), expected, "10,000 value drain")
    }

    @Test
    fun priorityQueueMatchesAStableSortModelAcross2000RandomOperationsPerOrder() {
        runPriorityQueueDifferential(HeapOrder.MAX, 0x9e37)
        runPriorityQueueDifferential(HeapOrder.MIN, 0x1f2a)
    }

    @Test
    fun indexedPriorityQueueOrdersByPriorityAndExposesKeys() {
        val queue = IndexedPriorityQueue(keyOf = { row: Row -> row.id }, compare = compareRows)
        queue.set(Row(2, 2)).set(Row(5, 5)).set(Row(3, 2))
        assertEquals(3, queue.size)
        assertFalse(queue.isEmpty)
        assertEquals(5, queue.peek()?.id)
        assertEquals(5, queue.peekKey())
        assertEquals(listOf(5, 2, 3), queue.keys())
        assertEquals(listOf(5, 2, 3), queue.toArray().map { it.id })
        assertEquals(2, queue.get(3)?.rank)
        assertTrue(queue.has(5))
        assertFalse(queue.has(9))
        assertEquals(5, queue.dequeue()?.id)
        assertEquals(2, queue.size)
    }

    @Test
    fun indexedPriorityQueueReprioritisesAnExistingKeyWithoutReorderingTies() {
        val queue = IndexedPriorityQueue(keyOf = { row: Row -> row.id }, compare = compareRows)
        queue.set(Row(0, 2))
        queue.set(Row(1, 5))
        queue.set(Row(2, 2))
        assertEquals(listOf(1, 0, 2), queue.keys())

        queue.set(Row(0, 9))
        assertEquals(listOf(0, 1, 2), queue.keys())
        assertEquals(3, queue.size)

        queue.set(Row(0, 2))
        assertEquals(listOf(1, 0, 2), queue.keys())

        queue.set(Row(0, 1))
        assertEquals(listOf(1, 2, 0), queue.keys())
        assertEquals(3, queue.size)
    }

    @Test
    fun indexedPriorityQueueReplacesTheValueUnderAnExistingKey() {
        data class NoteRow(val id: String, val rank: Int, val note: String)

        val queue = IndexedPriorityQueue(
            keyOf = { value: NoteRow -> value.id },
            compare = Comparator<NoteRow> { left, right -> left.rank - right.rank },
        )
        queue.set(NoteRow("x", 1, "old"))
        queue.set(NoteRow("x", 1, "new"))
        assertEquals(1, queue.size)
        assertEquals("new", queue.get("x")?.note)
        assertEquals(listOf("x"), queue.keys())
    }

    @Test
    fun indexedPriorityQueueDeletesByKeyAtEveryHeapPosition() {
        val queue = IndexedPriorityQueue(keyOf = { row: Row -> row.id }, compare = compareRows)
        for (rank in 1..5) {
            queue.set(Row(rank - 1, rank))
        }
        assertEquals(listOf(4, 3, 2, 1, 0), queue.keys())

        assertFalse(queue.delete(9))
        assertTrue(queue.delete(2))
        assertEquals(listOf(4, 3, 1, 0), queue.keys())
        assertEquals(4, queue.size)

        assertTrue(queue.delete(4))
        assertEquals(listOf(3, 1, 0), queue.keys())
        assertTrue(queue.delete(0))
        assertEquals(listOf(3, 1), queue.keys())
        assertTrue(queue.delete(3))
        assertEquals(listOf(1), queue.keys())
        assertTrue(queue.delete(1))
        assertTrue(queue.isEmpty)
        assertNull(queue.peek())
        assertNull(queue.dequeue())
    }

    @Test
    fun indexedPriorityQueueClearResetsKeysAndIteration() {
        val queue = IndexedPriorityQueue(keyOf = { row: Row -> row.id }, compare = compareRows)
        queue.set(Row(1, 1)).set(Row(2, 2))
        queue.clear()
        assertEquals(0, queue.size)
        assertFalse(queue.has(1))
        assertEquals(emptyList<Row>(), queue.toList())
        queue.set(Row(3, 3))
        assertEquals(listOf(3), queue.keys())
    }

    @Test
    fun indexedPriorityQueueMatchesAMapModelAcross3000RandomOperationsPerOrder() {
        runIndexedDifferential(HeapOrder.MAX, 0x2c1b)
        runIndexedDifferential(HeapOrder.MIN, 0x77e1)
    }

    @Test
    fun bucketQueueServesPrioritiesInOrderAndStaysFifoInsideABucket() {
        val queue = BucketQueue<String>()
        queue.enqueue("cold", 0).enqueue("warm", 1).enqueue("hot", 9).enqueue("warm again", 1)
        assertEquals(4, queue.size)
        assertEquals(9, queue.peekPriority())
        assertEquals("hot", queue.peek())
        assertEquals(listOf("hot", "warm", "warm again", "cold"), queue.toArray())
        assertEquals(4, queue.size)

        assertEquals("hot", queue.dequeue())
        assertEquals(1, queue.peekPriority())
        assertEquals("warm", queue.dequeue())
        assertEquals("warm again", queue.dequeue())
        assertEquals("cold", queue.dequeue())
        assertNull(queue.dequeue())
        assertNull(queue.peekPriority())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun bucketQueueHonorsMinOrder() {
        val queue = BucketQueue<Int>(order = HeapOrder.MIN)
        queue.enqueue(5, 3).enqueue(1, 1).enqueue(9, 9)
        assertEquals(1, queue.peekPriority())
        assertEquals(listOf(1, 5, 9), listOf(queue.dequeue(), queue.dequeue(), queue.dequeue()))
    }

    @Test
    fun bucketQueueToleratesSparseAndNegativePriorities() {
        val queue = BucketQueue<String>()
        queue.enqueue("far", 1_000_000).enqueue("near", -1_000_000).enqueue("middle", 0)
        assertEquals(listOf("far", "middle", "near"), queue.toArray())
        assertEquals(1_000_000, queue.peekPriority())
        queue.dequeue()
        assertEquals(0, queue.peekPriority())
        queue.dequeue()
        assertEquals(-1_000_000, queue.peekPriority())
    }

    @Test
    fun bucketQueueDrainsASingleLargeBucketInFifoOrder() {
        val queue = BucketQueue<Int>()
        for (index in 0 until 500) {
            queue.enqueue(index, 5)
        }
        val drained = ArrayList<Int>()
        while (!queue.isEmpty) {
            val value = queue.dequeue()
            assertTrue(value != null)
            drained.add(value!!)
        }
        assertEquals(500, drained.size)
        assertEquals((0 until 500).toList(), drained)
    }

    @Test
    fun bucketQueueToArrayIsASortedViewAndClearResetsTheQueue() {
        val queue = BucketQueue<Int>()
        queue.enqueue(1, 2).enqueue(2, 4).enqueue(3, 2)
        val view = queue.toArray()
        assertEquals(listOf(2, 1, 3), view)
        view.toMutableList().add(99)
        assertEquals(listOf(2, 1, 3), queue.toArray())
        assertEquals(3, queue.size)

        queue.clear()
        assertEquals(0, queue.size)
        assertTrue(queue.isEmpty)
        assertNull(queue.peek())
        assertNull(queue.peekPriority())
        assertEquals(emptyList<Int>(), queue.toArray())
    }

    @Test
    fun bucketQueueMatchesAStableSortModelAcross2000RandomOperationsPerOrder() {
        runBucketDifferential(HeapOrder.MAX, 0x51ab)
        runBucketDifferential(HeapOrder.MIN, 0x0d1e)
    }

    @Test
    fun topKKeepsTheKBestValuesInOrder() {
        val best = TopK<Int>(3)
        assertEquals(3, best.capacity)
        assertEquals(0, best.size)
        assertFalse(best.isFull)
        assertNull(best.worst)

        for (value in listOf(5, 1, 9, 3, 7)) {
            assertTrue(best.offer(value))
        }
        assertEquals(3, best.size)
        assertTrue(best.isFull)
        assertEquals(listOf(9, 7, 5), best.toArray())
        assertEquals(5, best.worst)

        assertFalse(best.offer(0))
        assertFalse(best.offer(5))
        assertFalse(best.offer(4))
        assertTrue(best.offer(6))
        assertEquals(listOf(9, 7, 6), best.toArray())
        assertEquals(6, best.worst)
        assertEquals(listOf(9, 7, 6), best.toList())

        best.clear()
        assertEquals(0, best.size)
        assertEquals(emptyList<Int>(), best.toArray())
    }

    @Test
    fun topKHonorsMinOrderAndCustomComparators() {
        data class RankedName(val name: String, val rank: Int)

        val smallest = TopK<Int>(3, order = HeapOrder.MIN)
        for (value in listOf(5, 1, 9, 3, 7)) {
            smallest.offer(value)
        }
        assertEquals(listOf(1, 3, 5), smallest.toArray())
        assertEquals(5, smallest.worst)

        val best = TopK(2, compare = Comparator<RankedName> { left, right -> left.rank - right.rank })
        best.offer(RankedName("a", 5))
        best.offer(RankedName("b", 5))
        assertFalse(best.offer(RankedName("c", 5)))
        assertEquals(2, best.size)
        assertTrue(best.offer(RankedName("d", 6)))
        assertEquals(listOf("b", "d"), best.toArray().map { it.name }.sorted())
    }

    @Test
    fun topKRejectsCapacitiesThatCannotBoundABuffer() {
        assertThrows(IllegalArgumentException::class.java) { TopK<Int>(0) }
        assertThrows(IllegalArgumentException::class.java) { TopK<Int>(-3) }
    }

    @Test
    fun topKMatchesAFullSortAcross1500RandomOffersPerOrder() {
        val cases = listOf(HeapOrder.MAX to 0x3a5f, HeapOrder.MIN to 0x64c9)
        for ((order, seed) in cases) {
            val random = Mulberry32(seed)
            val best = TopK<Int>(7, order = order)
            val offered = ArrayList<Int>()
            for (step in 0 until 1_500) {
                val value = Math.floor(random.next() * 60).toInt()
                offered.add(value)
                best.offer(value)
            }
            val expected = offered.sortedWith(
                if (order == HeapOrder.MAX) {
                    Comparator<Int> { left, right -> right - left }
                } else {
                    Comparator<Int> { left, right -> left - right }
                },
            ).take(7)
            assertSameSequence(best.toArray(), expected, "top-k $order")
            assertEquals(7, best.size)
        }
    }
}
