package com.eloykim.lyra.agent

import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.attention.AttentionSelector
import com.eloykim.lyra.attention.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmpFrameAdapterTest {

    @Test
    fun `焦点草稿变成可校验的协议 Frame`() {
        val selector = AttentionSelector()
        val draft = selector.select(
            cycle = 3,
            goal = "打开设置并找到无障碍",
            candidates = listOf(
                Candidate(id = "8", label = "设置", goalFit = 0.9f, clickable = true, costTokens = 24),
                Candidate(id = "11", label = "网络与互联网", goalFit = 0.7f, clickable = true, costTokens = 20),
                Candidate(id = "20", label = "隐藏布局", goalFit = 0.1f, costTokens = 40),
            ),
        )

        val frame = draft.toProtocolFrame(agentId = "a.lyra", ts = "2026-10-29T00:00:00Z")

        assertEquals(emptyList<String>(), AmpSchemas.validateKind("frame", frame.toJson()))
        assertEquals(draft.slots.size, frame.slots.size)
        assertEquals("打开设置并找到无障碍", frame.goal?.label)
        assertTrue(frame.digest.startsWith("sha256:"))
        assertEquals(frame.digest, CanonicalJson.digest(frame.unsignedJsonForTest()))
        assertNotEquals("", frame.id)
    }

    @Test
    fun `忽略项保留理由与重访条件`() {
        val selector = AttentionSelector(maxSlots = 3, cycleTokenBudget = 30)
        val draft = selector.select(
            cycle = 1,
            goal = "寻找提交按钮",
            candidates = (1..6).map { index ->
                Candidate(
                    id = index.toString(),
                    label = "候选 $index",
                    goalFit = 0.8f,
                    costTokens = 20,
                )
            },
        )
        val frame = draft.toProtocolFrame()
        val ignored = frame.ignored.orEmpty()
        assertTrue(ignored.isNotEmpty())
        assertTrue(ignored.all { it.revisitIf.isNotBlank() })
        assertTrue(ignored.all { it.reason.wire in setOf("budget", "deferred", "low_voi", "untrusted") })
    }
}

/** Test-only body without the digest field; production code always uses [frameFrom]. */
private fun com.eloykim.lyra.amp.protocol.frame.Frame.unsignedJsonForTest() =
    toJson().without("digest")
