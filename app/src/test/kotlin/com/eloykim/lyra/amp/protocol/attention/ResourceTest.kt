package com.eloykim.lyra.amp.protocol.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceTest {

    private val restingState = cognitiveStateFixture(
        vigilance = 0.8,
        load = 0.5,
        pLapse = 0.2,
        cyclesSinceRest = 2,
        capacityEff = 9,
    )

    @Test
    fun `进程容量保留安全与预留下限`() {
        val small = processAttention(42, restingState.copy(capacityEff = 4))
        assertEquals(4, small.effectiveSlots)
        assertEquals(1, small.safetySlots)
        assertEquals(3, small.foregroundCap)
        assertEquals(1, small.reserveSlots)
        assertEquals(0.29, small.recoveryDebt, 1e-12)
        assertFalse(small.offline)

        val big = processAttention(42, restingState)
        assertEquals(9, big.effectiveSlots)
        assertEquals(1, big.safetySlots)
        assertEquals(6, big.foregroundCap)
        assertEquals(3, big.reserveSlots)
    }

    @Test
    fun `休息判定覆盖四个阈值`() {
        assertFalse(shouldRest(restingState))
        assertTrue(shouldRest(restingState.copy(cyclesSinceRest = 8)))
        assertTrue(shouldRest(restingState.copy(vigilance = 0.2)))
        assertTrue(shouldRest(restingState.copy(load = 0.95)))
        assertTrue(shouldRest(restingState.copy(vigilance = 0.0, load = 1.0, pLapse = 1.0)))
    }

    @Test
    fun `前台只接纳一个任务其余记为低优先级`() {
        val process = processAttention(pid = 1, state = restingState)
        val candidates = listOf(
            ThreadAttentionCandidate(1, 1, 10, 3, foreground = true, switchCost = 0.5),
            ThreadAttentionCandidate(2, 1, 5, 2, foreground = true),
            ThreadAttentionCandidate(3, 1, 1, 2, foreground = false),
            ThreadAttentionCandidate(4, 1, 2, 4, minSlots = 2, foreground = false),
        )

        val plan = planAttention(process, candidates)

        assertEquals(1, plan.foreground?.tid)
        assertEquals(3, plan.foreground?.slots)
        assertEquals(listOf(4, 3), plan.background.map { it.tid })
        assertEquals(listOf(4, 1), plan.background.map { it.slots })
        assertEquals(listOf(2), plan.deferred.map { it.tid })
        assertEquals(ThreadDeferReason.LOW_PRIORITY, plan.deferred.single().reason)
        assertEquals(8.0 / 9.0, plan.utilization, 1e-12)
        assertEquals(0.25 + 0.5 / 9.0, plan.pressure, 1e-12)
        assertFalse(plan.rest)
    }

    @Test
    fun `离线时只延迟前台任务并强制休息`() {
        val process = processAttention(1, restingState.copy(cyclesSinceRest = 10))
        assertTrue(process.offline)

        val plan = planAttention(
            process,
            listOf(
                ThreadAttentionCandidate(1, 1, 10, 3, foreground = true),
                ThreadAttentionCandidate(2, 1, 5, 2, foreground = true),
                ThreadAttentionCandidate(3, 1, 1, 2, foreground = false),
            ),
        )

        assertNull(plan.foreground)
        assertTrue(plan.background.isEmpty())
        assertEquals(listOf(1, 2), plan.deferred.map { it.tid })
        assertTrue(plan.deferred.all { it.reason == ThreadDeferReason.OFFLINE })
        assertEquals(0.0, plan.utilization, 0.0)
        assertEquals(1.0, plan.pressure, 0.0)
        assertTrue(plan.rest)
    }

    @Test
    fun `首个前台任务放不下时记为 budget`() {
        val state = cognitiveStateFixture(
            vigilance = 1.0,
            load = 0.0,
            pLapse = 0.0,
            cyclesSinceRest = 0,
            capacityEff = 2,
        )
        val process = processAttention(1, state)
        assertEquals(1, process.foregroundCap)

        val plan = planAttention(
            process,
            listOf(ThreadAttentionCandidate(1, 1, 10, 2, minSlots = 2, foreground = true)),
        )
        assertEquals(ThreadDeferReason.BUDGET, plan.deferred.single().reason)
        assertNull(plan.foreground)
    }

    @Test
    fun `休息恢复警觉并清空周期计数`() {
        val rested = applyRest(
            cognitiveStateFixture(vigilance = 0.4, load = 0.8, pLapse = 0.6, cyclesSinceRest = 3),
            cycles = 1,
        )
        assertEquals(0.65, rested.vigilance, 1e-12)
        assertEquals(0.6, rested.load, 1e-12)
        assertEquals(0.35, rested.pLapse, 1e-12)
        assertEquals(0, rested.cyclesSinceRest)
    }

    @Test
    fun `质量调整吞吐扣除错误与返工`() {
        assertEquals(1.75, qualityAdjustedThroughput(OutcomeStats(10, 12, 2, 1, 4)), 0.0)
        assertEquals(0.0, qualityAdjustedThroughput(OutcomeStats(2, 2, 2, 1, 4)), 0.0)
    }

    @Test
    fun `健康度暴露余量`() {
        val process = processAttention(1, restingState)
        val plan = planAttention(process, listOf(ThreadAttentionCandidate(1, 1, 10, 3, foreground = true)))
        val health = attentionHealth(plan)
        assertEquals(plan.utilization, health.utilization, 0.0)
        assertEquals(plan.pressure, health.pressure, 0.0)
        assertEquals(1.0 - plan.utilization, health.headroom, 1e-12)
        assertFalse(health.rest)
    }
}
