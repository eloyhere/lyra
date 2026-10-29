package com.eloykim.lyra.amp.protocol.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionQueueTest {

    @Test
    fun `高优先级先出队`() {
        var now = 0L
        val queue = MissionQueue { now }
        queue.enqueue(pid = 1, priority = 1)
        now = 1
        queue.enqueue(pid = 2, priority = 5)
        now = 2
        queue.enqueue(pid = 3, priority = 3)

        assertEquals(2, queue.dequeue())
        assertEquals(3, queue.dequeue())
        assertEquals(1, queue.dequeue())
        assertNull(queue.dequeue())
    }

    @Test
    fun `同优先级按入队时间再按 pid`() {
        var now = 10L
        val queue = MissionQueue { now }
        queue.enqueue(pid = 5, priority = 0)
        queue.enqueue(pid = 3, priority = 0)
        // 入队时间相同时 pid 小的先出队。
        assertEquals(3, queue.dequeue())
        assertEquals(5, queue.dequeue())

        now = 20
        queue.enqueue(pid = 9, priority = 0)
        now = 30
        queue.enqueue(pid = 4, priority = 0)
        assertEquals(9, queue.dequeue())
    }

    @Test
    fun `重复入队被忽略且 size 只算成员`() {
        val queue = MissionQueue { 0 }
        assertTrue(queue.enqueue(7))
        assertFalse(queue.enqueue(7, priority = 9))
        assertEquals(1, queue.size)
        assertTrue(queue.has(7))
    }

    @Test
    fun `移除后跳过堆中的过期条目`() {
        var now = 0L
        val queue = MissionQueue { now }
        queue.enqueue(1, 5)
        now = 1
        queue.enqueue(2, 9)
        now = 2
        queue.enqueue(3, 7)

        assertTrue(queue.remove(2))
        assertFalse(queue.remove(2))
        assertEquals(2, queue.size)

        assertEquals(3, queue.dequeue())
        assertEquals(1, queue.dequeue())
        assertNull(queue.dequeue())
        assertEquals(0, queue.size)
    }
}
