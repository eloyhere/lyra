package com.eloykim.lyra.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCondenserTest {

    private fun turn(id: Long, role: String, text: String, quote: Long? = null) =
        ChatTurn(id, role, text, quote, at = id * 60_000)

    @Test
    fun `小会话不压缩`() {
        val turns = (1..5).map { turn(it.toLong(), if (it % 2 == 0) "assistant" else "user", "消息 $it") }
        val result = ChatCondenser.condense(turns)

        assertFalse(result.condensed)
        assertEquals(5, result.turns.size)
        assertNull(result.summary)
    }

    @Test
    fun `超过条数阈值时保留尾部并压缩前缀`() {
        val turns = (1..60).map { turn(it.toLong(), "user", "第 $it 条内容，足够长以避免被当成噪音") }
        val result = ChatCondenser.condense(turns, keepRecent = 10, maxTurns = 20)

        assertTrue(result.condensed)
        assertEquals(10, result.turns.size)
        assertNotNull(result.summary)
        assertNotNull(result.digest)
        assertTrue(result.dropped.any { it.reason == ChatDropReason.compressed })
    }

    @Test
    fun `寒暄与空消息被当作噪音丢弃`() {
        // 噪音放在较早区域（尾部是硬保留窗口，不参与剪枝）
        val turns = listOf(turn(99, "user", "好的"), turn(100, "user", "  ")) +
            (1..30).map { turn(it.toLong(), "user", "有内容的消息 $it") }
        val result = ChatCondenser.condense(turns, keepRecent = 5, maxTurns = 10)

        assertTrue(result.dropped.any { it.id == 99L && it.reason == ChatDropReason.noise })
        assertTrue(result.dropped.any { it.id == 100L && it.reason == ChatDropReason.noise })
    }

    @Test
    fun `重复内容被之前的相同消息取代`() {
        val repeated = "这是一段重复出现的相同内容"
        // 较早区域重复，尾部保留一份 → 较早那条判 superseded
        val withDup = listOf(turn(90, "assistant", repeated)) +
            (1..30).map { turn(it.toLong(), "user", "普通消息 $it") } +
            turn(91, "assistant", repeated)

        val result = ChatCondenser.condense(withDup, keepRecent = 5, maxTurns = 10)
        assertTrue(result.dropped.any { it.id == 90L && it.reason == ChatDropReason.superseded })
    }

    @Test
    fun `被引用消息不会悬空`() {
        val turns = (1..30).map { turn(it.toLong(), "user", "第 $it 条消息，带一点长度") }
        // 尾部第 32 条引用第 3 条
        val withQuote = turns + turn(31, "user", "关于第 3 条", quote = 3)

        val result = ChatCondenser.condense(withQuote, keepRecent = 5, maxTurns = 10)
        assertTrue("被引用的旧消息必须留在视图里", result.turns.any { it.id == 3L })
    }

    @Test
    fun `摘要与 digest 稳定`() {
        val turns = (1..40).map { turn(it.toLong(), "user", "内容 $it 号") }
        val a = ChatCondenser.condense(turns, keepRecent = 8, maxTurns = 12)
        val b = ChatCondenser.condense(turns, keepRecent = 8, maxTurns = 12)

        assertEquals(a.digest, b.digest)
        assertEquals(a.summary, b.summary)
        assertTrue(a.summary!!.contains("已压缩"))
    }

    @Test
    fun `每个丢弃都有 revisit_if`() {
        val turns = (1..50).map { turn(it.toLong(), "user", "内容 $it") }
        val result = ChatCondenser.condense(turns, keepRecent = 6, maxTurns = 10)

        assertTrue(result.dropped.isNotEmpty())
        assertTrue(result.dropped.all { it.revisitIf.isNotBlank() })
    }
}
