package com.eloykim.lyra.amp.protocol.interaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionTest {

    @Test
    fun `混合归一化并拒绝非法权重`() {
        val weights = normalizeInteractionMix(
            mapOf(InteractionKind.AFFECT to 3.0, InteractionKind.THINK to 1.0),
        )
        assertEquals(0.75, weights.getValue(InteractionKind.AFFECT), 1e-12)
        assertEquals(0.25, weights.getValue(InteractionKind.THINK), 1e-12)
        assertEquals(0.0, weights.getValue(InteractionKind.INSPIRE), 0.0)

        assertThrows(IllegalArgumentException::class.java) {
            normalizeInteractionMix(mapOf(InteractionKind.AFFECT to -0.1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeInteractionMix(mapOf(InteractionKind.AFFECT to Double.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeInteractionMix(emptyMap())
        }
    }

    @Test
    fun `主次模式活跃集合与阶段顺序`() {
        val plan = resolveInteraction(
            mapOf(
                InteractionKind.AFFECT to 0.6,
                InteractionKind.CONVERGE to 0.3,
                InteractionKind.THINK to 0.1,
            ),
        )
        assertEquals(InteractionKind.AFFECT, plan.primary)
        assertEquals(InteractionKind.CONVERGE, plan.secondary)
        assertEquals(listOf(InteractionKind.AFFECT, InteractionKind.THINK, InteractionKind.CONVERGE), plan.active)
        assertEquals(
            listOf(InteractionKind.AFFECT, InteractionKind.THINK, InteractionKind.CONVERGE),
            plan.sequence,
        )
        assertEquals(InteractionPhase.OPEN, phaseFor(plan))
        assertEquals(1.0, plan.contextWeights.values.sum(), 1e-12)
    }

    @Test
    fun `moves 按权重降序名称升序截断十项`() {
        val plan = resolveInteraction(mapOf(InteractionKind.AFFECT to 1.0))
        assertEquals(
            listOf(
                InteractionMove.BOUNDARY,
                InteractionMove.LABEL,
                InteractionMove.REFLECT,
                InteractionMove.SUPPORT,
                InteractionMove.VALIDATE,
            ),
            plan.moves,
        )
        assertEquals(5, plan.moves.size)
    }

    @Test
    fun `权重决定响应风格注意力和安全开关`() {
        val plan = resolveInteraction(
            mapOf(InteractionKind.AFFECT to 0.75, InteractionKind.THINK to 0.25),
        )
        assertEquals(ResponsePace.SLOW, plan.response.pace)
        assertEquals(ResponseStructure.DIALOGUE, plan.response.structure)
        assertEquals(ResponseVerbosity.NORMAL, plan.response.verbosity)
        assertEquals(0.275, plan.attention.breadth, 1e-12)
        assertEquals(0.6, plan.attention.depth, 1e-12)
        assertEquals(0.55, plan.attention.switchTolerance, 1e-12)
        assertEquals(0.35, plan.response.questionRatio, 1e-12)

        assertTrue(plan.safety.noDiagnosis)
        assertTrue(plan.safety.noManipulation)
        assertTrue(plan.safety.inviteDissent)
        assertTrue(plan.safety.escalateRisk)
        assertTrue(plan.safety.labelHypotheses)
        assertEquals(3, plan.guardrails.size)
        assertTrue(plan.guardrails[0].startsWith("情感模式"))
        assertTrue(plan.guardrails[1].startsWith("高风险信号"))
        assertTrue(plan.guardrails[2].startsWith("思维模式"))
    }

    @Test
    fun `低权重模式不进入活跃集合也不触发安全开关`() {
        val plan = resolveInteraction(mapOf(InteractionKind.AFFECT to 0.97, InteractionKind.THINK to 0.03))
        assertEquals(listOf(InteractionKind.AFFECT), plan.active)
        assertFalse(plan.safety.inviteDissent)
        assertTrue(plan.safety.noDiagnosis)
        assertTrue(plan.safety.escalateRisk)
        // affect 的权重 > 0.05，仍然给出两条护栏。
        assertEquals(2, plan.guardrails.size)
    }

    @Test
    fun `阶段阈值`() {
        assertEquals(InteractionPhase.OPEN, phaseFor(resolveInteraction(mapOf(InteractionKind.AFFECT to 1.0))))
        assertEquals(InteractionPhase.EXPLORE, phaseFor(resolveInteraction(mapOf(InteractionKind.INSPIRE to 1.0))))
        assertEquals(InteractionPhase.ANALYZE, phaseFor(resolveInteraction(mapOf(InteractionKind.THINK to 1.0))))
        assertEquals(InteractionPhase.DECIDE, phaseFor(resolveInteraction(mapOf(InteractionKind.CONVERGE to 1.0))))
        assertEquals(
            InteractionPhase.COORDINATE,
            phaseFor(resolveInteraction(mapOf(InteractionKind.ORCHESTRATE to 1.0))),
        )
        val balanced = resolveInteraction(
            InteractionKind.entries.associateWith { 0.2 },
        )
        assertEquals(InteractionPhase.CLOSE, phaseFor(balanced))
        assertTrue(isSafeToClose(balanced))
        assertTrue(isSafeToClose(resolveInteraction(mapOf(InteractionKind.AFFECT to 1.0))))
    }

    @Test
    fun `组合 context 权重按 40 60 混合并归一化`() {
        val combined = combineContextWeights(
            scenario = mapOf(InteractionContextKey.SELF to 1.0),
            interaction = mapOf(InteractionContextKey.SELF to 0.0, InteractionContextKey.ROLE to 1.0),
        )
        // self = 0.4*1 + 0.6*0；role 只有 interaction 给出，直接取 1；其余 5 维两边都缺失，取 1。
        assertEquals(0.4 / 6.4, combined.getValue(InteractionContextKey.SELF), 1e-12)
        assertEquals(1.0 / 6.4, combined.getValue(InteractionContextKey.ROLE), 1e-12)
        assertEquals(1.0 / 6.4, combined.getValue(InteractionContextKey.AFFECT), 1e-12)
        assertEquals(1.0, combined.values.sum(), 1e-12)
    }

    @Test
    fun `组合 memory 权重保留参考的不归一化行为`() {
        val combined = combineMemoryWeights(
            scenario = mapOf(MemoryKind.EPISODIC to 0.5),
            interaction = null,
        )
        assertEquals(0.5, combined!!.getValue(MemoryKind.EPISODIC), 0.0)
        assertEquals(1.0, combined.getValue(MemoryKind.SEMANTIC), 0.0)
        assertEquals(4.5, combined.values.sum(), 0.0)
        assertNull(combineMemoryWeights(null, null))
    }
}
