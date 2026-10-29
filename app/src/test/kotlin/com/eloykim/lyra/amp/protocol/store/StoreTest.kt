package com.eloykim.lyra.amp.protocol.store

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StoreTest {

    private lateinit var fake: FakeDatabase

    @Before
    fun setUp() {
        fake = FakeDatabase()
        StoreDatabaseFactories.install { fake }
    }

    @After
    fun tearDown() {
        shutdownStore()
        StoreDatabaseFactories.clear()
    }

    @Test
    fun `configureStore installs a database and database() returns it`() {
        val db = configureStore(":memory:")
        assertSame(fake, db)
        assertSame(fake, database())
    }

    @Test
    fun `database() fails before configureStore`() {
        val error = assertThrows(IllegalStateException::class.java) { database() }
        assertTrue(error.message!!.contains("configureStore(path)"))
    }

    @Test
    fun `a missing factory is reported instead of guessing a database`() {
        StoreDatabaseFactories.clear()
        assertThrows(IllegalStateException::class.java) { configureStore(":memory:") }
    }

    @Test
    fun `ProcessStore reads through, hits and invalidates`() {
        configureStore(":memory:")
        val process = FakeProcess(7)
        fake.processes[7] = process

        val first = ProcessStore.get(7)
        val second = ProcessStore.get(7)
        assertSame(process, first)
        assertSame(first, second)
        assertEquals(1, ProcessStore.stats().loads)
        assertTrue(ProcessStore.stats().hits >= 1)
        assertSame(first, ProcessStore.entities()["7"])

        ProcessStore.invalidate(7)
        val third = ProcessStore.get(7)
        assertSame(process, third)
        assertEquals(2, ProcessStore.stats().loads)
    }

    @Test
    fun `each store binds its own capacity and loader`() {
        configureStore(":memory:")
        fake.threads[1] = FakeThread(1)
        fake.handles[2] = FakeHandle(2)
        fake.messages[3] = FakeMessage(3)
        fake.windows[4] = FakeWindow(4)
        fake.objects[5] = FakeObject(5)

        assertEquals(1, ThreadStore.get(1)?.tid)
        assertEquals(2, HandleStore.get(2)?.hid)
        assertEquals(3, MessageStore.get(3)?.msg_id)
        assertEquals(4, WindowStore.get(4)?.tid)
        assertEquals(5, ObjectStore.get(5)?.seq)

        assertEquals(256, ThreadStore.stats().capacity)
        assertEquals(256, HandleStore.stats().capacity)
        assertEquals(256, MessageStore.stats().capacity)
        assertEquals(128, WindowStore.stats().capacity)
        assertEquals(512, ObjectStore.stats().capacity)

        assertNull(ThreadStore.get(99))
    }

    @Test
    fun `reconfiguring the store rebuilds caches instead of serving stale rows`() {
        configureStore(":memory:")
        fake.processes[1] = FakeProcess(1)
        assertEquals(1, ProcessStore.get(1)?.pid)

        val replacement = FakeDatabase()
        replacement.processes[1] = FakeProcess(2)
        fake = replacement

        val reconfigured = configureStore(":memory:")
        assertSame(replacement, reconfigured)
        assertEquals(2, ProcessStore.get(1)?.pid)
    }

    @Test
    fun `shutdownStore closes the database and forgets it`() {
        configureStore(":memory:")
        fake.processes[1] = FakeProcess(1)
        ProcessStore.get(1)

        shutdownStore()
        assertTrue(fake.closed)
        assertThrows(IllegalStateException::class.java) { database() }
        assertThrows(IllegalStateException::class.java) { ProcessStore.get(1) }
    }

    @Test
    fun `store put and clear invalidation work`() {
        configureStore(":memory:")
        ProcessStore.put(FakeProcess(3))
        assertSame(ProcessStore.entities()["3"], ProcessStore.get(3))

        ThreadStore.put(FakeThread(4))
        WindowStore.put(FakeWindow(4))
        ObjectStore.put(FakeObject(4))
        HandleStore.put(FakeHandle(4))
        MessageStore.put(FakeMessage(4))

        ThreadStore.invalidate()
        WindowStore.invalidate()
        ObjectStore.invalidate()
        HandleStore.invalidate()
        MessageStore.invalidate()
        assertEquals(0, ThreadStore.stats().size)
        assertEquals(0, WindowStore.stats().size)
        assertEquals(0, ObjectStore.stats().size)
        assertEquals(0, HandleStore.stats().size)
        assertEquals(0, MessageStore.stats().size)
    }

    private data class FakeProcess(override val pid: Int) : ProcessEntity
    private data class FakeThread(override val tid: Int) : ThreadEntity
    private data class FakeHandle(override val hid: Int) : HandleEntity
    private data class FakeMessage(override val msg_id: Int) : MessageEntity
    private data class FakeWindow(override val tid: Int) : WindowEntity
    private data class FakeObject(override val seq: Int) : ObjectEntity

    private class FakeDatabase : StoreDatabase {

        val processes = LinkedHashMap<Int, ProcessEntity>()
        val threads = LinkedHashMap<Int, ThreadEntity>()
        val handles = LinkedHashMap<Int, HandleEntity>()
        val messages = LinkedHashMap<Int, MessageEntity>()
        val windows = LinkedHashMap<Int, WindowEntity>()
        val objects = LinkedHashMap<Int, ObjectEntity>()
        var closed: Boolean = false

        override val processService: ProcessService = ProcessService { pid -> processes[pid] }
        override val threadService: ThreadService = ThreadService { tid -> threads[tid] }
        override val handleService: HandleService = HandleService { hid -> handles[hid] }
        override val messageService: MessageService = MessageService { msgId -> messages[msgId] }
        override val windowService: WindowService = WindowService { tid -> windows[tid] }
        override val objectService: ObjectService = ObjectService { seq -> objects[seq] }

        override fun close() {
            closed = true
        }
    }
}
