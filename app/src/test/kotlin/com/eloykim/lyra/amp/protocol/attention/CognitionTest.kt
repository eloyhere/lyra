package com.eloykim.lyra.amp.protocol.attention

import com.eloykim.lyra.amp.protocol.types.Budget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class CognitionTest {

    @Test
    fun `认知显著性满足十条单调性`() {
        val base = cognitiveCandidateFixture()
        val state = cognitiveStateFixture()
        assertTrue(respectsCognitiveMonotonicity(base, state))

        val reference = salienceFromState(base, state)
        assertTrue(salienceFromState(base.copy(voi = 0.9), state) >= reference)
        assertTrue(salienceFromState(base.copy(costTokens = 300), state) <= reference)
        assertTrue(salienceFromState(base.copy(risk = 0.2), state) <= reference)
        assertTrue(salienceFromState(base.copy(trustCap = 0.2), state) <= reference)
        assertTrue(salienceFromState(base.copy(hardFloor = 1.0), state) >= reference)
        assertTrue(salienceFromState(base.copy(switchCostTokens = 2000), state) <= reference)
        assertTrue(salienceFromState(base, state.copy(arousal = 1.0)) <= reference)
        assertTrue(salienceFromState(base, state.copy(load = 0.0)) <= reference)
    }

    @Test
    fun `历史乘数由价值学习减去返回抑制`() {
        assertEquals(1.0, historyFactor(null), 0.0)
        assertEquals(2.0, historyFactor(CandidateHistory(realized = 1.0)), 0.0)
        assertEquals(0.1, historyFactor(CandidateHistory(realized = 0.0)), 0.0)
        assertEquals(0.5, historyFactor(CandidateHistory(lastIgnoredAge = 0)), 1e-12)
        assertEquals(1.0 - 0.5 * exp(-1.0), historyFactor(CandidateHistory(lastIgnoredAge = 3)), 1e-12)
    }

    @Test
    fun `空账本给出默认状态`() {
        val state = estimateCognitiveState(CognitiveInputs(frames = emptyList(), receipts = emptyList()))

        assertEquals(0.0, state.load, 0.0)
        assertEquals(1.0, state.vigilance, 0.0)
        assertEquals(0.0, state.arousal, 0.0)
        assertEquals(0.5, state.mastery, 0.0)
        assertEquals(0.0, state.accountability, 0.0)
        assertEquals(0, state.cyclesSinceRest)
        assertEquals(4, state.capacityEff)
        assertEquals(0.0, state.pLapse, 0.0)
        assertNull(state.dominantRole)
        assertEquals(CognitiveSource.DEFAULT, state.source)
        assertEquals(0, state.samples)
    }

    @Test
    fun `从 frame 估计负载与主导角色`() {
        val frames = listOf(
            CognitiveFrame(
                mode = FrameMode.EXPLORE,
                slots = listOf(
                    CognitiveSlot(SlotRole.EVIDENCE, SlotState.ACTIVE, chunkWeight = 2),
                    CognitiveSlot(SlotRole.GOAL, SlotState.ACTIVE),
                    CognitiveSlot(SlotRole.TOOL, SlotState.RELEASED),
                ),
                ignored = listOf(
                    AttentionIgnored(attentionRef("c.i1"), IgnoredReason.DEFERRED, "稍后"),
                    AttentionIgnored(attentionRef("c.i2"), IgnoredReason.BUDGET, "预算"),
                    AttentionIgnored(attentionRef("c.i3"), IgnoredReason.LOW_VOI, "低价值"),
                ),
            ),
        )

        val state = estimateCognitiveState(CognitiveInputs(frames = frames, receipts = emptyList()))

        assertEquals(0.55, state.load, 0.0)
        assertEquals(3, state.capacityEff)
        assertEquals(SlotRole.EVIDENCE, state.dominantRole)
        assertEquals(1, state.cyclesSinceRest)
        assertEquals(0.92, state.vigilance, 0.0)
        assertEquals(0.08, state.pLapse, 0.0)
    }

    @Test
    fun `rest frame 重置周期计数与警觉`() {
        val justRested = listOf(
            CognitiveFrame(FrameMode.EXPLORE),
            CognitiveFrame(FrameMode.REST),
        )
        val restored = estimateCognitiveState(CognitiveInputs(frames = justRested, receipts = emptyList()))
        assertEquals(0, restored.cyclesSinceRest)
        assertEquals(1.0, restored.vigilance, 0.0)
        assertEquals(0.0, restored.pLapse, 0.0)

        val oneCycleLater = estimateCognitiveState(
            CognitiveInputs(
                frames = justRested + CognitiveFrame(FrameMode.EXPLOIT),
                receipts = emptyList(),
            ),
        )
        assertEquals(1, oneCycleLater.cyclesSinceRest)
        assertEquals(0.92, oneCycleLater.vigilance, 0.0)
    }

    @Test
    fun `receipt 估计近期误差压力与长期成功率`() {
        val receipts = ArrayList<AttentionReceipt>()
        repeat(18) { index -> receipts.add(receiptFixture("r.s$index", Outcome.SUCCESS)) }
        repeat(6) { index -> receipts.add(receiptFixture("r.f$index", Outcome.FAILURE)) }
        repeat(3) { index -> receipts.add(receiptFixture("r.p$index", Outcome.PARTIAL)) }
        repeat(3) { index -> receipts.add(receiptFixture("r.b$index", Outcome.BLOCKED)) }

        val state = estimateCognitiveState(CognitiveInputs(frames = emptyList(), receipts = receipts))
        assertEquals(30, state.samples)
        assertEquals(CognitiveSource.MEASURED, state.source)
        assertEquals(0.6, state.mastery, 0.0)
        // 最近 12 条 = 6 failure + 3 blocked + 3 partial；blocked 计入 failures。
        assertEquals(0.625, state.arousal, 0.0)

        val narrow = estimateCognitiveState(
            CognitiveInputs(frames = emptyList(), receipts = receipts, window = 6),
        )
        // 最近 6 条 = 3 blocked + 3 partial。
        assertEquals(0.5, narrow.arousal, 0.0)
        assertEquals(0.6, narrow.mastery, 0.0)
    }

    @Test
    fun `硬下限先于打分放置`() {
        val floorCandidate = cognitiveCandidateFixture(id = "c.safety", goalFit = 0.0, hardFloor = 0.5, why = "安全信号")
        val normal = cognitiveCandidateFixture(id = "c.normal", voi = 0.6)

        val selection = selectFromState(
            listOf(floorCandidate, normal),
            cognitiveStateFixture(),
            CognitiveSelectionOptions(budgetTokens = 10_000),
        )

        assertEquals(listOf("c.safety", "c.normal"), selection.slots.map { it.target.id })
        assertEquals(0.5, selection.slots[0].salience, 0.0)
        assertTrue(selection.ignored.isEmpty())
        assertEquals(2, selection.capacityUsed)
    }

    @Test
    fun `容量冲突的硬下限给出 INFEASIBLE`() {
        val selection = selectFromState(
            listOf(cognitiveCandidateFixture(hardFloor = 1.0, chunkWeight = 5)),
            cognitiveStateFixture(capacityEff = 4),
            CognitiveSelectionOptions(budgetTokens = 100),
        )
        assertTrue(selection.slots.isEmpty())
        assertEquals(ImpasseState.INFEASIBLE, selection.impasse)
        assertEquals(CognitiveIgnoreReason.CAPACITY, selection.ignored.single().reason)
    }

    @Test
    fun `排除原因区分低敏感与返回抑制`() {
        val lowSensitivity = cognitiveCandidateFixture(voi = 0.05, goalFit = 0.2, uncertainty = 0.8)
        val inhibited = cognitiveCandidateFixture(
            id = "c.inh",
            voi = 0.05,
            goalFit = 0.2,
            uncertainty = 0.8,
            history = CandidateHistory(lastIgnoredAge = 1),
        )
        val selection = selectFromState(
            listOf(lowSensitivity, inhibited),
            cognitiveStateFixture(pLapse = 0.6),
            CognitiveSelectionOptions(budgetTokens = 1_000),
        )
        val reasons = selection.ignored.associate { it.target.id to it.reason }
        assertEquals(CognitiveIgnoreReason.LOW_SENSITIVITY, reasons["c.1"])
        assertEquals(CognitiveIgnoreReason.INHIBITED, reasons["c.inh"])
    }

    @Test
    fun `切换成本计入预算而不改动成本字段`() {
        val state = cognitiveStateFixture(dominantRole = SlotRole.GOAL)
        val candidate = cognitiveCandidateFixture(role = SlotRole.EVIDENCE, costTokens = 100)

        val blocked = selectFromState(
            listOf(candidate),
            state,
            CognitiveSelectionOptions(budgetTokens = 150, switchCostTokens = 100),
        )
        assertTrue(blocked.slots.isEmpty())
        assertEquals(ImpasseState.STUCK, blocked.impasse)
        assertEquals(CognitiveIgnoreReason.BUDGET, blocked.ignored.single().reason)

        val allowed = selectFromState(
            listOf(candidate),
            state,
            CognitiveSelectionOptions(budgetTokens = 200, switchCostTokens = 100),
        )
        val slot = allowed.slots.single()
        assertEquals(Budget(tokens = 100), slot.cost)
        assertEquals(Budget(tokens = 200), slot.budget)

        val sameRole = selectFromState(
            listOf(cognitiveCandidateFixture(role = SlotRole.GOAL, costTokens = 100)),
            state,
            CognitiveSelectionOptions(budgetTokens = 100, switchCostTokens = 100),
        )
        assertEquals(1, sameRole.slots.size)
        assertEquals(Budget(tokens = 100), sameRole.slots.single().budget)
    }

    @Test
    fun `块容量限制与 capacityUsed`() {
        val selection = selectFromState(
            listOf(
                cognitiveCandidateFixture(id = "c.a", chunkWeight = 2, costTokens = 10),
                cognitiveCandidateFixture(id = "c.b", chunkWeight = 2, costTokens = 10),
            ),
            cognitiveStateFixture(capacityEff = 3),
            CognitiveSelectionOptions(budgetTokens = 100),
        )
        assertEquals(1, selection.slots.size)
        assertEquals(2, selection.capacityUsed)
        assertEquals(CognitiveIgnoreReason.CAPACITY, selection.ignored.single().reason)
    }

    @Test
    fun `并列候选给出 TIE`() {
        val selection = selectFromState(
            listOf(cognitiveCandidateFixture(id = "c.x"), cognitiveCandidateFixture(id = "c.y")),
            cognitiveStateFixture(capacityEff = 4),
            CognitiveSelectionOptions(budgetTokens = 1_000),
        )
        assertEquals(2, selection.slots.size)
        assertEquals(ImpasseState.TIE, selection.impasse)
    }

    @Test
    fun `attention 槽位可纯转换为认知输入`() {
        val slot = AttentionSlot(
            target = attentionRef("c.1"),
            role = SlotRole.GOAL,
            salience = 0.5,
            cost = Budget(tokens = 10),
            why = "目标",
            state = SlotState.ACTIVE,
        )
        val cognitive = slot.toCognitiveSlot(chunkWeight = 2)
        assertEquals(SlotRole.GOAL, cognitive.role)
        assertEquals(SlotState.ACTIVE, cognitive.state)
        assertEquals(2, cognitive.chunkWeight)
    }
}
