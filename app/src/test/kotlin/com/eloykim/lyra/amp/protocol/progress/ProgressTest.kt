package com.eloykim.lyra.amp.protocol.progress

import com.eloykim.lyra.amp.protocol.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProgressTest {

    private class FakeStore(
        private val row: ProcessProgressRow?,
        private val threads: List<ThreadStateCount>,
        private val steps: Int,
    ) : ProgressStore {

        override fun process(pid: Int): ProcessProgressRow? = row

        override fun threadStates(pid: Int): List<ThreadStateCount> = threads

        override fun stepCount(pid: Int): Int = steps
    }

    private class RecordingSink : ProgressObjectSink {

        val entries = ArrayList<Triple<String, String, JsonValue.Obj>>()
        var lastAt: String? = null

        override fun putObject(kind: String, id: String, value: JsonValue.Obj, at: String) {
            entries.add(Triple(kind, id, value))
            lastAt = at
        }
    }

    @Test
    fun `聚合线程状态与 tokens`() {
        val store = FakeStore(
            row = ProcessProgressRow(state = "running", used = """{"tokens":1200,"calls":3}"""),
            threads = listOf(
                ThreadStateCount("ready", 1),
                ThreadStateCount("done", 3),
                ThreadStateCount("failed", 1),
            ),
            steps = 4,
        )
        val snapshot = computeProgress(store, 1)
        assertEquals(1, snapshot.pid)
        assertEquals("running", snapshot.state)
        assertEquals(5, snapshot.totalThreads)
        assertEquals(3, snapshot.doneThreads)
        assertEquals(1, snapshot.failedThreads)
        assertEquals(4, snapshot.steps)
        assertEquals(1200, snapshot.tokens)
        assertEquals(60.0, snapshot.percent, 0.0)
        assertNull(snapshot.checkpoint)
    }

    @Test
    fun `百分比保留一位小数且零线程为零`() {
        val twoOfThree = FakeStore(
            row = ProcessProgressRow("idle", "{}"),
            threads = listOf(ThreadStateCount("done", 2), ThreadStateCount("running", 1)),
            steps = 0,
        )
        assertEquals(66.7, computeProgress(twoOfThree, 2).percent, 0.0)

        val none = FakeStore(ProcessProgressRow("created", "{}"), emptyList(), 0)
        assertEquals(0.0, computeProgress(none, 3).percent, 0.0)
        assertEquals(0, computeProgress(none, 3).tokens)
    }

    @Test
    fun `未知进程抛错`() {
        val store = FakeStore(null, emptyList(), 0)
        assertThrows(IllegalStateException::class.java) { computeProgress(store, 9) }
    }

    @Test
    fun `checkpoint 追加不可变 progress 对象并返回引用`() {
        val store = FakeStore(
            row = ProcessProgressRow("running", """{"tokens":1200}"""),
            threads = listOf(ThreadStateCount("done", 3), ThreadStateCount("ready", 2)),
            steps = 4,
        )
        val sink = RecordingSink()
        val at = "2026-10-24T10:00:00Z"
        val snapshot = checkpointProgress(store, sink, 1, at)

        assertEquals("pg.1.4", snapshot.checkpoint)
        assertEquals(60.0, snapshot.percent, 0.0)
        assertEquals(at, sink.lastAt)
        val (kind, id, value) = sink.entries.single()
        assertEquals("progress", kind)
        assertEquals("pg.1.4", id)
        assertEquals("0.1", value.string("amp"))
        assertEquals("progress", value.string("kind"))
        assertEquals("pg.1.4", value.string("id"))
        assertEquals(at, value.string("at"))
        assertEquals(1, value.int("pid"))
        assertEquals(5, value.int("totalThreads"))
        assertEquals(3, value.int("doneThreads"))
        assertEquals(0, value.int("failedThreads"))
        assertEquals(1200, value.int("tokens"))
        assertEquals(60.0, value.double("percent")!!, 0.0)
    }
}
