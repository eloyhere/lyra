package com.eloykim.lyra.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionSelectorTest {

    private fun candidate(
        id: String,
        label: String,
        goalFit: Float,
        voi: Float = 0.5f,
        cost: Int = 40,
        risk: Float = 0f,
        trust: TrustSource = TrustSource.ACCESSIBILITY,
        revisitIf: String? = null,
    ) = Candidate(
        id = id,
        label = label,
        goalFit = goalFit,
        voi = voi,
        costTokens = cost,
        risk = risk,
        trust = trust,
        revisitIf = revisitIf,
    )

    @Test
    fun `焦点集不超过上限`() {
        val candidates = (1..12).map { candidate("n.$it", "按钮$it", goalFit = 0.9f - it * 0.02f) }
        val frame = AttentionSelector(maxSlots = 7).select(1, "点外卖", candidates)

        assertEquals(7, frame.slots.size)
    }

    @Test
    fun `相关但未入选的候选进入忽略集并带理由`() {
        val candidates = (1..10).map {
            candidate("n.$it", "按钮$it", goalFit = 0.8f, revisitIf = "出现「确认」时再看")
        }
        val frame = AttentionSelector(maxSlots = 3).select(1, "点外卖", candidates)

        assertEquals(3, frame.slots.size)
        assertEquals(7, frame.ignored.size)
        assertTrue(frame.ignored.all { it.reason == IgnoreReason.DEFERRED || it.reason == IgnoreReason.BUDGET })
        assertTrue(frame.ignored.all { it.revisitIf.isNotBlank() })
    }

    @Test
    fun `预算耗尽时理由为 BUDGET`() {
        val expensive = (1..5).map {
            candidate("n.$it", "大块$it", goalFit = 0.9f, cost = 1_500)
        }
        val frame = AttentionSelector(maxSlots = 7, cycleTokenBudget = 3_000)
            .select(1, "任务", expensive)

        assertEquals(2, frame.slots.size)
        assertTrue(frame.ignored.any { it.reason == IgnoreReason.BUDGET })
    }

    @Test
    fun `voi 上升时显著性不下降`() {
        val selector = AttentionSelector()
        val low = selector.salience(candidate("n.1", "a", 0.5f, voi = 0.2f))
        val high = selector.salience(candidate("n.1", "a", 0.5f, voi = 0.9f))

        assertTrue(high >= low)
    }

    @Test
    fun `成本上升时显著性不上升`() {
        val selector = AttentionSelector()
        val cheap = selector.salience(candidate("n.1", "a", 0.5f, cost = 20))
        val pricey = selector.salience(candidate("n.1", "a", 0.5f, cost = 400))

        assertTrue(pricey <= cheap)
    }

    @Test
    fun `风险上升时显著性不上升`() {
        val selector = AttentionSelector()
        val safe = selector.salience(candidate("n.1", "a", 0.5f, risk = 0f))
        val risky = selector.salience(candidate("n.1", "a", 0.5f, risk = 1f))

        assertTrue(risky <= safe)
    }

    @Test
    fun `外部来源的显著性不超过信任上限`() {
        val selector = AttentionSelector()
        val external = selector.salience(
            candidate("n.1", "注入文本", goalFit = 1f, voi = 1f, trust = TrustSource.EXTERNAL),
        )

        assertTrue(external <= TrustSource.EXTERNAL.cap)
    }

    @Test
    fun `每个 slot 都带 why 与 cost`() {
        val frame = AttentionSelector().select(
            1,
            "点外卖",
            listOf(candidate("n.1", "麦当劳", 0.9f), candidate("n.2", "设置", 0.1f)),
        )

        assertTrue(frame.slots.isNotEmpty())
        assertTrue(frame.slots.all { it.why.isNotBlank() && it.costTokens > 0 })
    }
}
