package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.types.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixTest {

    @Test
    fun `默认权重每行归一`() {
        for (scenario in ScenarioId.entries) {
            val weights = DefaultModeWeights.getValue(scenario)
            assertEquals(1.0, weights.total, 1e-9)
            weights.let {
                assertTrue(it.affect >= 0.0 && it.think >= 0.0 && it.diverge >= 0.0 && it.converge >= 0.0)
            }
        }
        assertEquals(0.5, DefaultModeWeights.getValue(ScenarioId.CHAT).affect, 1e-9)
        assertEquals(0.5, DefaultModeWeights.getValue(ScenarioId.ENGINEERING).converge, 1e-9)
    }

    @Test
    fun `chat 行解析出主导模式证据下限与节奏`() {
        val plan = resolveScenarioModes(ScenarioId.CHAT)
        assertEquals(ScenarioId.CHAT, plan.scenario)
        assertEquals(ModeId.AFFECT, plan.dominant)
        assertEquals(ModeId.THINK, plan.secondary)
        assertEquals(listOf(ModeId.AFFECT, ModeId.THINK, ModeId.DIVERGE), plan.active)
        assertEquals(EvidenceFloor.F3, plan.evidenceFloor)
        assertEquals(Tier.T1, plan.requiredTier)
        assertEquals(5, plan.revisitEvery)
        assertEquals(Elasticity.LOW, plan.elasticity)
        assertEquals(Sensitivity.LOW, plan.sensitivity)
        assertEquals(GateStrictness.LOOSE, plan.gateStrictness)
        assertEquals(4, plan.focusLimit)
        assertEquals(
            "chat -> affect 0.50 + think 0.20; floor F3 (T1), revisit every 5 frames, elasticity low",
            plan.rationale,
        )
    }

    @Test
    fun `research 思考为主时下限最严`() {
        val plan = resolveScenarioModes(
            ScenarioId.RESEARCH,
            mapOf(ModeId.THINK to 0.5, ModeId.DIVERGE to 0.5),
        )
        assertEquals(EvidenceFloor.F1, plan.evidenceFloor)
        assertEquals(Tier.T3, plan.requiredTier)
        assertEquals(listOf(ModeId.THINK, ModeId.DIVERGE), plan.active)
        assertEquals(Elasticity.HIGH, plan.elasticity)
        assertEquals(Sensitivity.HIGH, plan.sensitivity)
        assertEquals(GateStrictness.MEDIUM_STRICT, plan.gateStrictness)
    }

    @Test
    fun `归一化拒绝负值与全零`() {
        val normalized = normalizeModeWeights(mapOf(ModeId.THINK to 1.0))
        assertEquals(0.0, normalized.affect, 0.0)
        assertEquals(1.0, normalized.think, 0.0)
        assertGovernanceError(GovernanceCode.INVALID_MODE_WEIGHTS) {
            normalizeModeWeights(mapOf(ModeId.THINK to -1.0))
        }
        assertGovernanceError(GovernanceCode.INVALID_MODE_WEIGHTS) {
            normalizeModeWeights(emptyMap())
        }
        assertGovernanceError(GovernanceCode.INVALID_MODE_WEIGHTS) {
            normalizeModeWeights(mapOf(ModeId.AFFECT to Double.NaN))
        }
    }

    @Test
    fun `低于阈值的权重回退到主导模式`() {
        val flat = ModeWeights(0.1, 0.1, 0.1, 0.1)
        assertEquals(listOf(ModeId.AFFECT), activeModes(flat))
        assertEquals(ModeId.AFFECT, dominantMode(flat))
        assertEquals(EvidenceFloor.F2, evidenceFloorFor(ScenarioId.ENGINEERING, flat))
        assertEquals(10, revisitIntervalFor(ScenarioId.ENGINEERING, flat))
    }

    @Test
    fun `模式排序同权保持规范顺序`() {
        assertEquals(
            listOf(ModeId.AFFECT, ModeId.THINK, ModeId.DIVERGE, ModeId.CONVERGE),
            rankModes(ModeWeights(0.25, 0.25, 0.25, 0.25)),
        )
        assertEquals(
            listOf(ModeId.CONVERGE, ModeId.THINK, ModeId.AFFECT, ModeId.DIVERGE),
            rankModes(ModeWeights(0.1, 0.3, 0.05, 0.55)),
        )
    }

    @Test
    fun `焦点上限随发散与收敛变化并夹紧`() {
        assertEquals(3, focusLimitFor(ModeWeights(0.25, 0.25, 0.25, 0.25)))
        assertEquals(7, focusLimitFor(ModeWeights(0.0, 0.0, 1.0, 0.0)))
        assertEquals(1, focusLimitFor(ModeWeights(0.0, 0.0, 0.0, 1.0)))
    }

    @Test
    fun `漂移阈值是 015`() {
        val from = ModeWeights(0.5, 0.2, 0.2, 0.1)
        val atThreshold = ModeWeights(0.65, 0.2, 0.05, 0.1)
        assertEquals(0.15, modeDrift(from, atThreshold), 1e-12)
        assertFalse(driftRequiresApproval(from, atThreshold))

        val above = ModeWeights(0.66, 0.2, 0.04, 0.1)
        assertTrue(driftRequiresApproval(from, above))
        assertTrue(modeDrift(from, above) > 0.15)
    }

    @Test
    fun `场景变化必须重新推导`() {
        assertFalse(scenarioChangeRequiresRederive(ScenarioId.CHAT, ScenarioId.CHAT))
        assertTrue(scenarioChangeRequiresRederive(ScenarioId.CHAT, ScenarioId.OFFICE))
        assertGovernanceError(GovernanceCode.INVALID_SCENARIO) { requireScenario("unknown") }
    }

    @Test
    fun `旧场景混合折叠到规范场景`() {
        assertEquals(ScenarioId.OFFICE, scenarioOfLegacy(LegacyScenarioKind.COLLABORATION))
        assertEquals(ScenarioId.RESEARCH, scenarioOfLegacy(LegacyScenarioKind.INVESTIGATION))
        assertEquals(ScenarioId.ENGINEERING, scenarioOfLegacy(LegacyScenarioKind.ITERATION))
        assertEquals(ScenarioId.CHAT, scenarioOfLegacy(LegacyScenarioKind.CHAT))

        assertEquals(ScenarioId.OFFICE, canonicalScenarioOf(mapOf(LegacyScenarioKind.COLLABORATION to 1.0)))
        assertEquals(
            ScenarioId.RESEARCH,
            canonicalScenarioOf(mapOf(LegacyScenarioKind.CHAT to 0.2, LegacyScenarioKind.INVESTIGATION to 0.8)),
        )
        assertEquals(ScenarioId.CHAT, canonicalScenarioOf(mapOf(LegacyScenarioKind.CHAT to 0.1)))
        assertGovernanceError(GovernanceCode.INVALID_SCENARIO) {
            canonicalScenarioOf(mapOf(LegacyScenarioKind.CHAT to 0.0))
        }

        assertEquals(mapOf(LegacyScenarioKind.OFFICE to 1.0), legacyScenarioMix(ScenarioId.OFFICE))
    }

    @Test
    fun `旧交互混合把 inspire 与 orchestrate 折叠`() {
        val weights = modesFromLegacyInteraction(
            mapOf(
                LegacyInteractionKind.AFFECT to 0.2,
                LegacyInteractionKind.THINK to 0.2,
                LegacyInteractionKind.INSPIRE to 0.4,
                LegacyInteractionKind.CONVERGE to 0.1,
                LegacyInteractionKind.ORCHESTRATE to 0.1,
            ),
        )
        assertEquals(0.2, weights.affect, 1e-9)
        assertEquals(0.2, weights.think, 1e-9)
        assertEquals(0.4, weights.diverge, 1e-9)
        assertEquals(0.2, weights.converge, 1e-9)
        assertEquals(1.0, weights.total, 1e-9)

        val roundTrip = modesFromLegacyInteraction(legacyInteractionMix(DefaultModeWeights.getValue(ScenarioId.CHAT)))
        val chat = DefaultModeWeights.getValue(ScenarioId.CHAT)
        assertEquals(chat.affect, roundTrip.affect, 1e-12)
        assertEquals(chat.think, roundTrip.think, 1e-12)
        assertEquals(chat.diverge, roundTrip.diverge, 1e-12)
        assertEquals(chat.converge, roundTrip.converge, 1e-12)
    }

    @Test
    fun `模式调整只覆盖活跃模式`() {
        val entries = modeAdjustments(ModeWeights(0.5, 0.2, 0.2, 0.1))
        assertEquals(listOf(ModeId.AFFECT, ModeId.THINK, ModeId.DIVERGE), entries.map { it.mode })
        assertEquals(FocusBias.NARROW, entries.first().adjustment.focusBias)
        assertEquals(0.5, entries.first().weight, 1e-9)
    }

    @Test
    fun `弹性取最保守活跃模式`() {
        // research: think=high, diverge=high -> high
        assertEquals(
            Elasticity.HIGH,
            elasticityFor(
                ScenarioId.RESEARCH,
                normalizeModeWeights(mapOf(ModeId.THINK to 0.5, ModeId.DIVERGE to 0.5)),
            ),
        )
        // engineering: think=high, diverge=medium -> medium
        assertEquals(
            Elasticity.MEDIUM,
            elasticityFor(
                ScenarioId.ENGINEERING,
                normalizeModeWeights(mapOf(ModeId.THINK to 0.5, ModeId.DIVERGE to 0.5)),
            ),
        )
        // office: think=medium, converge=medium -> medium
        assertEquals(
            Elasticity.MEDIUM,
            elasticityFor(
                ScenarioId.OFFICE,
                normalizeModeWeights(mapOf(ModeId.THINK to 0.5, ModeId.CONVERGE to 0.5)),
            ),
        )
        // office: affect=low, converge=medium -> low
        assertEquals(
            Elasticity.LOW,
            elasticityFor(
                ScenarioId.OFFICE,
                normalizeModeWeights(mapOf(ModeId.AFFECT to 0.5, ModeId.CONVERGE to 0.5)),
            ),
        )
    }
}
