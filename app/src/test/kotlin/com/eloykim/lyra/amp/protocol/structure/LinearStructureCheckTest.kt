package com.eloykim.lyra.amp.protocol.structure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `tools/linear-structure-check.ts` 的 Kotlin 版本：栈、队列、双端队列与二叉堆，
 * 包含与原生序列模型的随机差分检查。
 */
class LinearStructureCheckTest {

    private fun drainHeap(heap: BinaryHeap<Int>): List<Int> {
        val values = ArrayList<Int>()
        while (!heap.isEmpty) {
            val value = heap.pop()
            assertNotNull(value)
            values.add(value!!)
        }
        assertNull(heap.pop())
        return values
    }

    private fun sortedNumbers(values: List<Int>): List<Int> = values.sorted()

    private fun runHeapDifferential(order: HeapOrder, seed: Int) {
        val random = Mulberry32(seed)
        val heap = BinaryHeap<Int>(order = order)
        val sorted = ArrayList<Int>()

        for (step in 0 until 5_000) {
            val push = sorted.isEmpty() || random.next() < 0.59
            if (push) {
                val value = Math.floor(random.next() * 2_001).toInt() - 1_000
                heap.push(value)
                insertSorted(sorted, value)
            } else {
                val expected = if (order == HeapOrder.MIN) sorted.removeAt(0) else sorted.removeAt(sorted.size - 1)
                assertEquals(expected, heap.pop())
            }
            assertEquals(sorted.size, heap.size)
        }

        while (sorted.isNotEmpty()) {
            val expected = if (order == HeapOrder.MIN) sorted.removeAt(0) else sorted.removeAt(sorted.size - 1)
            assertEquals(expected, heap.pop())
        }
        assertEquals(0, heap.size)
        assertTrue(heap.isEmpty)
    }

    @Test
    fun stackIsLifoAndEmptyPopPeekReturnNull() {
        val stack = Stack<Int>()
        assertEquals(0, stack.size)
        assertTrue(stack.isEmpty)
        assertNull(stack.pop())
        assertNull(stack.peek())

        assertSame(stack, stack.push(1))
        stack.push(2).push(3)
        assertEquals(3, stack.size)
        assertFalse(stack.isEmpty)
        assertEquals(3, stack.peek())
        assertEquals(3, stack.pop())
        assertEquals(2, stack.pop())
        assertEquals(1, stack.peek())

        stack.clear()
        assertEquals(0, stack.size)
        assertTrue(stack.isEmpty)
        assertNull(stack.pop())
        assertNull(stack.peek())
        assertEquals(emptyList<Int>(), stack.toArray())
    }

    @Test
    fun stackConstructorIterationOrderAndToArrayCopiesAreCorrect() {
        val stack = Stack(listOf("first", "second", "third"))
        assertEquals(3, stack.size)
        assertEquals("third", stack.peek())
        assertEquals(listOf("third", "second", "first"), stack.toList())
        assertEquals(listOf("third", "second", "first"), stack.toArray())

        val copy = stack.toArray().toMutableList()
        copy[0] = "mutated"
        copy.add("extra")
        assertEquals(listOf("third", "second", "first"), stack.toArray())

        val snapshot = stack.iterator()
        stack.push("fourth")
        assertEquals(listOf("third", "second", "first"), snapshot.asSequence().toList())
        assertEquals(listOf("fourth", "third", "second", "first"), stack.toList())
    }

    @Test
    fun queuePreservesFifoOrderAndCopiesToArrayAndIterationResults() {
        val queue = Queue(listOf(10, 20, 30))
        assertEquals(3, queue.size)
        assertFalse(queue.isEmpty)
        assertEquals(10, queue.peek())
        assertSame(queue, queue.enqueue(40))
        assertEquals(listOf(10, 20, 30, 40), queue.toList())
        assertEquals(listOf(10, 20, 30, 40), queue.toArray())

        val copy = queue.toArray().toMutableList()
        copy[0] = -1
        copy.add(50)
        assertEquals(listOf(10, 20, 30, 40), queue.toArray())

        val snapshot = queue.iterator()
        queue.enqueue(50)
        assertEquals(listOf(10, 20, 30, 40), snapshot.asSequence().toList())

        assertEquals(10, queue.dequeue())
        assertEquals(20, queue.dequeue())
        assertEquals(30, queue.peek())
        assertEquals(listOf(30, 40, 50), queue.toList())

        queue.clear()
        assertEquals(0, queue.size)
        assertTrue(queue.isEmpty)
        assertNull(queue.dequeue())
        assertNull(queue.peek())
        assertEquals(emptyList<Int>(), queue.toArray())
        assertEquals(emptyList<Int>(), queue.toList())
        queue.enqueue(7)
        assertEquals(listOf(7), queue.toList())
    }

    @Test
    fun queueHandlesWrapAroundAndGrowthAcrossManyEnqueuesAndDequeues() {
        val queue = Queue<Int>()
        for (value in 0 until 120) {
            queue.enqueue(value)
        }
        for (value in 0 until 75) {
            assertEquals(value, queue.dequeue())
        }
        for (value in 120 until 260) {
            queue.enqueue(value)
        }

        val expected = (75 until 260).toList()
        assertEquals(expected.size, queue.size)
        assertEquals(75, queue.peek())
        assertEquals(expected, queue.toArray())
        assertEquals(expected, queue.toList())

        for (value in expected) {
            assertEquals(value, queue.dequeue())
        }
        assertTrue(queue.isEmpty)
        assertNull(queue.dequeue())
    }

    @Test
    fun queueMatchesANativeDequeAcross15000InterleavedOperations() {
        val random = Mulberry32(0x51e4e)
        val queue = Queue<Int>()
        val reference = java.util.ArrayDeque<Int>()

        for (step in 0 until 15_000) {
            if (reference.isEmpty() || random.next() < 0.58) {
                val value = Math.floor(random.next() * 100_000).toInt()
                queue.enqueue(value)
                reference.addLast(value)
            } else {
                assertEquals(reference.removeFirst(), queue.dequeue())
            }
            assertEquals(reference.size, queue.size)
            if (step % 499 == 0) {
                assertEquals(reference.isEmpty(), queue.isEmpty)
                assertEquals(reference.peekFirst(), queue.peek())
                assertEquals(reference.toList(), queue.toArray())
                assertEquals(reference.toList(), queue.toList())
            }
        }

        assertEquals(reference.size, queue.size)
        assertEquals(reference.toList(), queue.toArray())
        while (reference.isNotEmpty()) {
            assertEquals(reference.removeFirst(), queue.dequeue())
        }
        assertNull(queue.dequeue())
    }

    @Test
    fun dequeSupportsBothEndsIterationAndIndependentToArrayCopies() {
        val deque = Deque(listOf(2, 3))
        assertEquals(2, deque.size)
        assertEquals(2, deque.peekFront())
        assertEquals(3, deque.peekBack())
        assertSame(deque, deque.pushFront(1))
        assertSame(deque, deque.pushBack(4))
        assertSame(deque, deque.push(5))
        assertEquals(listOf(1, 2, 3, 4, 5), deque.toList())
        assertEquals(listOf(1, 2, 3, 4, 5), deque.toArray())

        val copy = deque.toArray().toMutableList()
        copy[0] = 99
        copy.add(6)
        assertEquals(listOf(1, 2, 3, 4, 5), deque.toArray())

        assertEquals(1, deque.popFront())
        assertEquals(5, deque.popBack())
        assertEquals(2, deque.peekFront())
        assertEquals(4, deque.peekBack())
        assertEquals(listOf(2, 3, 4), deque.toList())

        val snapshot = deque.iterator()
        deque.pushBack(6)
        assertEquals(listOf(2, 3, 4), snapshot.asSequence().toList())
        assertEquals(listOf(2, 3, 4, 6), deque.toList())
    }

    @Test
    fun dequeEmptyOperationsReturnNullAndClearResetsState() {
        val deque = Deque<String>()
        assertEquals(0, deque.size)
        assertTrue(deque.isEmpty)
        assertNull(deque.popFront())
        assertNull(deque.popBack())
        assertNull(deque.peekFront())
        assertNull(deque.peekBack())

        deque.pushFront("a").pushBack("b")
        assertEquals(listOf("a", "b"), deque.toList())
        deque.clear()
        assertEquals(0, deque.size)
        assertTrue(deque.isEmpty)
        assertNull(deque.popFront())
        assertNull(deque.popBack())
        assertEquals(emptyList<String>(), deque.toArray())
    }

    @Test
    fun dequeGrowsAndShrinksCorrectlyAroundBothEnds() {
        val deque = Deque<Int>()
        val reference = ArrayList<Int>()
        for (value in 0 until 2_048) {
            deque.pushBack(value)
            reference.add(value)
        }

        for (step in 0 until 1_800) {
            if (step % 2 == 0) {
                assertEquals(reference.removeAt(0), deque.popFront())
            } else {
                assertEquals(reference.removeAt(reference.size - 1), deque.popBack())
            }
        }
        assertEquals(reference.size, deque.size)
        assertEquals(reference, deque.toArray())

        for (value in 1..300) {
            deque.pushFront(-value)
            reference.add(0, -value)
        }
        for (value in 3_000 until 3_300) {
            deque.pushBack(value)
            reference.add(value)
        }
        assertEquals(reference.size, deque.size)
        assertEquals(reference, deque.toList())
        assertEquals(reference[0], deque.peekFront())
        assertEquals(reference[reference.size - 1], deque.peekBack())

        while (reference.isNotEmpty()) {
            if (reference.size % 2 == 0) {
                assertEquals(reference.removeAt(reference.size - 1), deque.popBack())
            } else {
                assertEquals(reference.removeAt(0), deque.popFront())
            }
        }
        assertTrue(deque.isEmpty)
        assertNull(deque.popFront())
        assertNull(deque.popBack())
    }

    @Test
    fun dequeMatchesANativeDequeAcross15000InterleavedOperations() {
        val random = Mulberry32(0xd3e9e)
        val deque = Deque<Int>()
        val reference = java.util.ArrayDeque<Int>()

        for (step in 0 until 15_000) {
            val action = if (reference.isEmpty()) 0.0 else random.next()
            if (action < 0.28) {
                val value = Math.floor(random.next() * 100_000).toInt()
                deque.pushBack(value)
                reference.addLast(value)
            } else if (action < 0.56) {
                val value = Math.floor(random.next() * 100_000).toInt()
                deque.pushFront(value)
                reference.addFirst(value)
            } else if (action < 0.78) {
                assertEquals(reference.removeFirst(), deque.popFront())
            } else {
                assertEquals(reference.removeLast(), deque.popBack())
            }

            assertEquals(reference.size, deque.size)
            if (step % 503 == 0) {
                assertEquals(reference.isEmpty(), deque.isEmpty)
                assertEquals(reference.peekFirst(), deque.peekFront())
                assertEquals(reference.peekLast(), deque.peekBack())
                assertEquals(reference.toList(), deque.toArray())
                assertEquals(reference.toList(), deque.toList())
            }
        }

        assertEquals(reference.size, deque.size)
        assertEquals(reference.toList(), deque.toArray())
        while (reference.isNotEmpty()) {
            assertEquals(reference.removeFirst(), deque.popFront())
            if (reference.isNotEmpty()) {
                assertEquals(reference.removeLast(), deque.popBack())
            }
        }
        assertTrue(deque.isEmpty)
        assertNull(deque.popFront())
        assertNull(deque.popBack())
    }

    @Test
    fun binaryHeapDefaultsToMinOrderAndSupportsExplicitMaxOrder() {
        val source = listOf(4, 1, 7, 2, 9, 4)
        val minHeap = BinaryHeap(values = source)
        assertEquals(1, minHeap.peek())
        assertEquals(listOf(1, 2, 4, 4, 7, 9), drainHeap(minHeap))

        val maxHeap = BinaryHeap(order = HeapOrder.MAX, values = source)
        assertEquals(9, maxHeap.peek())
        assertEquals(listOf(9, 7, 4, 4, 2, 1), drainHeap(maxHeap))

        val strings = BinaryHeap(values = listOf("pear", "apple", "fig"))
        assertEquals(listOf("apple", "fig", "pear"), drainStrings(strings))
    }

    private fun drainStrings(heap: BinaryHeap<String>): List<String> {
        val values = ArrayList<String>()
        while (!heap.isEmpty) {
            values.add(heap.pop()!!)
        }
        return values
    }

    @Test
    fun binaryHeapMinAndMaxMatchSortedArraysAcross5000RandomOperationsEach() {
        runHeapDifferential(HeapOrder.MIN, 0x91e4)
        runHeapDifferential(HeapOrder.MAX, 0x7a11)
    }

    @Test
    fun binaryHeapHeapifiesConstructorValuesAndFromBuildsAHeap() {
        val source = (0 until 257).map { index -> ((index * 37) % 257) - 128 }
        val heap = BinaryHeap(values = source)
        val view = heap.toArray()
        assertEquals(source.size, view.size)
        for (child in 1 until view.size) {
            assertTrue(view[(child - 1) / 2] <= view[child])
        }
        assertEquals(sortedNumbers(source), drainHeap(heap))

        val from = BinaryHeap.from(listOf(5, 3, 8, 1), order = HeapOrder.MAX)
        assertEquals(4, from.size)
        assertEquals(listOf(8, 5, 3, 1), drainHeap(from))
    }

    @Test
    fun binaryHeapReplaceSwapsTheRootInLogarithmicTimeAndHandlesEmptyHeaps() {
        val heap = BinaryHeap(values = listOf(5, 1, 9, 3, 7))
        assertEquals(5, heap.size)
        assertEquals(1, heap.replace(0))
        assertEquals(5, heap.size)
        assertEquals(0, heap.peek())
        assertEquals(0, heap.replace(8))
        assertEquals(3, heap.peek())
        assertEquals(listOf(3, 5, 7, 8, 9), drainHeap(heap))

        val empty = BinaryHeap<Int>()
        assertNull(empty.replace(42))
        assertEquals(1, empty.size)
        assertEquals(listOf(42), empty.toArray())

        val maxHeap = BinaryHeap<Int>(order = HeapOrder.MAX)
        assertNull(maxHeap.replace(100))
        maxHeap.push(50)
        assertEquals(100, maxHeap.replace(10))
        assertEquals(50, maxHeap.peek())
        maxHeap.push(75)
        assertEquals(75, maxHeap.replace(150))
        assertEquals(150, maxHeap.peek())
    }

    @Test
    fun binaryHeapKeepsDuplicateValues() {
        val heap = BinaryHeap(values = listOf(5, 1, 5, 1, 3, 5))
        assertEquals(6, heap.size)
        assertEquals(listOf(1, 1, 3, 5, 5, 5), drainHeap(heap))

        heap.push(2).push(2).push(2)
        assertEquals(listOf(2, 2, 2), drainHeap(heap))
    }

    @Test
    fun binaryHeapHonorsCustomComparatorsAndReversesThemForMaxOrder() {
        data class Job(val name: String, val priority: Int)

        val jobs = listOf(
            Job("low", 1),
            Job("urgent", 9),
            Job("medium", 5),
            Job("background", 3),
            Job("high", 7),
        )
        val compareJobs = Comparator<Job> { a, b -> a.priority - b.priority }

        val minJobs = BinaryHeap(compare = compareJobs, values = jobs)
        assertEquals("low", minJobs.peek()?.name)
        val minOrder = ArrayList<String>()
        while (!minJobs.isEmpty) {
            minOrder.add(minJobs.pop()!!.name)
        }
        assertEquals(listOf("low", "background", "medium", "high", "urgent"), minOrder)

        val maxJobs = BinaryHeap(compare = compareJobs, order = HeapOrder.MAX, values = jobs)
        assertEquals("urgent", maxJobs.peek()?.name)
        maxJobs.push(Job("critical", 10))
        assertEquals("critical", maxJobs.pop()?.name)

        val byMagnitude = BinaryHeap<Int>(compare = Comparator { a, b -> Math.abs(a) - Math.abs(b) }, values = listOf(5, -1, 3, -7))
        assertEquals(listOf(-1, 3, 5, -7), drainHeap(byMagnitude))
    }

    @Test
    fun binaryHeapToArrayAndIterationExposeHeapOrderedCopies() {
        val source = listOf(9, 4, 7, 1, 8, 2, 6, 3, 5)
        val heap = BinaryHeap(values = source)
        val view = heap.toArray()
        assertEquals(source.size, view.size)
        assertEquals(sortedNumbers(view), sortedNumbers(source))
        for (child in 1 until view.size) {
            assertTrue(view[(child - 1) / 2] <= view[child])
        }
        assertEquals(view, heap.toList())

        val snapshot = heap.iterator()
        heap.push(0)
        assertEquals(view, snapshot.asSequence().toList())

        val copy = heap.toArray().toMutableList()
        copy[0] = -1
        copy.add(100)
        assertEquals(source.size + 1, heap.size)
        assertEquals(sortedNumbers(source + 0), sortedNumbers(heap.toArray()))
    }

    @Test
    fun binaryHeapEmptyBehaviorAndClearAreWellDefined() {
        val heap = BinaryHeap<Int>()
        assertEquals(0, heap.size)
        assertTrue(heap.isEmpty)
        assertNull(heap.peek())
        assertNull(heap.pop())
        assertEquals(emptyList<Int>(), heap.toArray())
        assertEquals(emptyList<Int>(), heap.toList())

        heap.push(2).push(1).push(3)
        assertEquals(3, heap.size)
        heap.clear()
        assertEquals(0, heap.size)
        assertTrue(heap.isEmpty)
        assertNull(heap.peek())
        assertNull(heap.pop())
        assertEquals(emptyList<Int>(), heap.toArray())

        heap.push(11)
        assertEquals(11, heap.pop())
        assertTrue(heap.isEmpty)
    }
}
