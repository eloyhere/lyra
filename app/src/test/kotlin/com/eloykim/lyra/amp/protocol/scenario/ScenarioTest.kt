package com.eloykim.lyra.amp.protocol.scenario

import com.eloykim.lyra.amp.protocol.attention.DefaultAttentionPolicy
import com.eloykim.lyra.amp.protocol.interaction.MemoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioTest {

    @Test
    fun `单一 chat 场景给出 bare 上下文与注意力上限`() {
        val plan = resolveScenario(mapOf(ScenarioKind.CHAT to 1.0))
        assertEquals(1.0, plan.weights.getValue(ScenarioKind.CHAT), 0.0)
        assertEquals(0.4, plan.tokenBudgetFactor, 1e-12)
        assertEquals(0.2, plan.contextDepth, 1e-12)
        assertEquals(ScenarioMode.BARE, plan.mode)
        assertEquals(listOf(ScenarioKind.CHAT), plan.active)
        assertEquals(0.5, plan.attention.maxForegroundUtil, 1e-12)
        assertEquals(0.35, plan.attention.reserveRatio, 1e-12)
        assertEquals(1, plan.attention.minSliceSlots)
        assertEquals(10, plan.attention.restAfterCycles)
        assertEquals(ScenarioVerbosity.TERSE, plan.style.verbosity)
        assertEquals(ToolBias.NONE, plan.style.toolBias)
        assertEquals("chat 1.00 -> bare; token x0.40; context depth 0.20", plan.rationale)
        assertTrue(shouldUseBareContext(plan))

        assertEquals(1.0, plan.contextWeights.values.sum(), 1e-12)
        assertEquals(0.15 / 1.15, plan.contextWeights.getValue(ScenarioContextKey.SELF), 1e-12)
        // memory 权重不归一化：参考实现直接使用加权和。
        assertEquals(0.3, plan.memoryKindWeights.getValue(MemoryKind.EPISODIC), 1e-12)
        assertEquals(1.25, plan.memoryKindWeights.values.sum(), 1e-12)
    }

    @Test
    fun `research 场景给出 full 上下文与更高预算`() {
        val plan = resolveScenario(mapOf(ScenarioKind.RESEARCH to 1.0))
        assertEquals(1.3, plan.tokenBudgetFactor, 1e-12)
        assertEquals(0.9, plan.contextDepth, 1e-12)
        assertEquals(ScenarioMode.FULL, plan.mode)
        assertEquals(0.8, plan.attention.maxForegroundUtil, 1e-12)
        assertEquals(0.2, plan.attention.reserveRatio, 1e-12)
        assertEquals(2, plan.attention.minSliceSlots)
        assertEquals(8, plan.attention.restAfterCycles)
        assertEquals(ScenarioVerbosity.DETAILED, plan.style.verbosity)
        assertEquals(ToolBias.HEAVY, plan.style.toolBias)
        assertFalse(shouldUseBareContext(plan))
    }

    @Test
    fun `混合场景按权重插值并对平局取先出现者`() {
        val plan = resolveScenario(
            mapOf(ScenarioKind.CHAT to 0.5, ScenarioKind.RESEARCH to 0.5),
        )
        assertEquals(0.85, plan.tokenBudgetFactor, 1e-12)
        assertEquals(0.55, plan.contextDepth, 1e-12)
        assertEquals(ScenarioMode.COMPACT, plan.mode)
        assertEquals(0.65, plan.attention.maxForegroundUtil, 1e-12)
        assertEquals(0.275, plan.attention.reserveRatio, 1e-12)
        assertEquals(2, plan.attention.minSliceSlots)
        assertEquals(9, plan.attention.restAfterCycles)
        assertEquals(listOf(ScenarioKind.CHAT, ScenarioKind.RESEARCH), plan.active)
        // 平局：chat 先出现，因此 TERSE / NONE 胜出。
        assertEquals(ScenarioVerbosity.TERSE, plan.style.verbosity)
        assertEquals(ToolBias.NONE, plan.style.toolBias)
        assertEquals("chat 0.50 + research 0.50 -> compact; token x0.85; context depth 0.55", plan.rationale)
    }

    @Test
    fun `归一化拒绝负数 NaN 与空混合`() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeScenarioMix(mapOf(ScenarioKind.CHAT to -1.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeScenarioMix(mapOf(ScenarioKind.CHAT to Double.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeScenarioMix(emptyMap())
        }
        assertEquals(1.0, normalizeScenarioMix(mapOf(ScenarioKind.CHAT to 2.0)).getValue(ScenarioKind.CHAT), 0.0)
    }

    @Test
    fun `上下文预算按乘数缩放并保留 32 的下限`() {
        val chat = resolveScenario(mapOf(ScenarioKind.CHAT to 1.0))
        assertEquals(400, contextBudgetTokens(chat, 1000))
        assertEquals(40, contextBudgetTokens(chat, 100))
        assertEquals(32, contextBudgetTokens(chat, 10))
        val research = resolveScenario(mapOf(ScenarioKind.RESEARCH to 1.0))
        assertEquals(130, contextBudgetTokens(research, 100))
        assertThrows(IllegalArgumentException::class.java) { contextBudgetTokens(chat, -1) }
        assertThrows(IllegalArgumentException::class.java) {
            contextBudgetTokens(chat, Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `注意力策略只覆盖四个字段`() {
        val plan = resolveScenario(mapOf(ScenarioKind.CHAT to 1.0))
        val policy = scenarioAttentionPolicy(plan)
        assertEquals(0.5, policy.maxForegroundUtil, 1e-12)
        assertEquals(0.35, policy.reserveRatio, 1e-12)
        assertEquals(1, policy.minSliceSlots)
        assertEquals(10, policy.restAfterCycles)
        // 基础策略的其余字段保持默认。
        assertEquals(DefaultAttentionPolicy.baseSlots, policy.baseSlots)
        assertEquals(DefaultAttentionPolicy.vigilanceFloor, policy.vigilanceFloor, 0.0)
        assertEquals(DefaultAttentionPolicy.safetyFloorRatio, policy.safetyFloorRatio, 0.0)
    }
}
