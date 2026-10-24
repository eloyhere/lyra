package com.eloykim.lyra.amp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionTest {

    private fun log(n: Int): EventLog {
        val log = EventLog()
        repeat(n) { index ->
            log.append("agent", "receipt", """{"id":"rc.$index","seq":$index}""")
        }
        return log
    }

    @Test
    fun `压缩后 tail 接续 checkpoint`() {
        val events = log(12).snapshot()
        val result = Compaction.compact(events, keepRecent = 4)

        assertEquals(8, result.removed.size)
        assertEquals(4, result.tail.size)
        assertEquals(result.checkpoint.tip, result.tail.first().prevDigest)
        assertTrue(Compaction.verify(result.checkpoint, result.tail, result.removed).isEmpty())
    }

    @Test
    fun `不压缩时返回空检查点`() {
        val events = log(3).snapshot()
        val result = Compaction.compact(events, keepRecent = 5)

        assertEquals(0, result.removed.size)
        assertEquals(3, result.tail.size)
    }

    @Test
    fun `tail 引用被移除对象时拒绝压缩`() {
        val log = EventLog()
        repeat(4) { log.append("agent", "receipt", """{"id":"rc.$it"}""") }
        // 第 5 条引用最早那条的 id
        log.append("agent", "note", """{"ref":"rc.0"}""")

        val refused = runCatching { Compaction.compact(log.snapshot(), keepRecent = 2) }
        assertTrue(refused.exceptionOrNull() is CompactionRefused)
    }

    @Test
    fun `篡改 removed 会让 root 校验失败`() {
        val events = log(10).snapshot()
        val result = Compaction.compact(events, keepRecent = 3)

        val tampered = result.removed.mapIndexed { index, event ->
            if (index == 0) event.copy(payload = """{"id":"rc.999"}""") else event
        }

        val problems = Compaction.verify(result.checkpoint, result.tail, tampered)
        assertTrue(problems.any { it.contains("root") || it.contains("tip") })
    }

    @Test
    fun `没有 removed 时只校验链续接`() {
        val events = log(6).snapshot()
        val result = Compaction.compact(events, keepRecent = 2)

        assertTrue(Compaction.verify(result.checkpoint, result.tail).isEmpty())

        val brokenTail = result.tail.drop(1)
        assertTrue(Compaction.verify(result.checkpoint, brokenTail).any { it.contains("prev") })
    }
}
