package com.eloykim.lyra.amp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLogTest {

    @Test
    fun `追加后链完整`() {
        val log = EventLog()
        log.append("controller", "frame", """{"id":"f.1"}""")
        log.append("model", "receipt", """{"id":"rc.1"}""")
        log.append("user", "interrupt", """{"id":"i.1"}""")

        assertTrue(log.verify().isEmpty())
        assertEquals(3, log.size)
    }

    @Test
    fun `篡改 payload 会被发现`() {
        val log = EventLog()
        log.append("controller", "frame", """{"id":"f.1"}""")
        log.append("model", "receipt", """{"id":"rc.1"}""")

        val tampered = log.snapshot().mapIndexed { index, event ->
            if (index == 0) event.copy(payload = """{"id":"f.9"}""") else event
        }

        val problems = EventLog.verifyChain(tampered)
        assertTrue(problems.any { it.contains("篡改") })
    }

    @Test
    fun `删除中间事件会被发现`() {
        val log = EventLog()
        log.append("a", "frame", "{}")
        log.append("b", "receipt", "{}")
        log.append("c", "note", "{}")

        val shortened = log.snapshot().filterIndexed { index, _ -> index != 1 }
        val problems = EventLog.verifyChain(shortened)

        assertTrue(problems.any { it.contains("seq 不连续") || it.contains("prev_digest") })
    }
}
