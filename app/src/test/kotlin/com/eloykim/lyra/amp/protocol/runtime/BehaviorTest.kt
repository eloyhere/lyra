package com.eloykim.lyra.amp.protocol.runtime

import com.eloykim.lyra.amp.protocol.attention.CognitiveState
import com.eloykim.lyra.amp.protocol.attention.AttentionPlan
import com.eloykim.lyra.amp.protocol.attention.planAttention
import com.eloykim.lyra.amp.protocol.attention.processAttention
import com.eloykim.lyra.amp.protocol.bayes.BayesianEngine
import com.eloykim.lyra.amp.protocol.interaction.InteractionKind
import com.eloykim.lyra.amp.protocol.interaction.resolveInteraction
import com.eloykim.lyra.amp.protocol.scenario.ScenarioKind
import com.eloykim.lyra.amp.protocol.scenario.ScenarioMix
import com.eloykim.lyra.amp.protocol.scenario.resolveScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `behavior.ts` / `behaviorTuning.ts` 的行为固定测试。
 *
 * 钉住四件事：默认六个行为的声明顺序、仲裁按 priority 再按 confidence、硬护栏
 * 优先级永不被降低、调优建议只产出提案且同一份冲突统计只应用一次。
 */
class BehaviorTest {

    private fun state(
        cyclesSinceRest: Int = 0,
        vigilance: Double = 0.8,
        load: Double = 0.3,
    ): CognitiveState = CognitiveState(
        load = load,
        vigilance = vigilance,
        arousal = 0.2,
        mastery = 0.6,
        accountability = 0.5,
        cyclesSinceRest = cyclesSinceRest,
        capacityEff = 4,
        pLapse = 0.2,
    )

    private fun attention(cognitive: CognitiveState = state()): AttentionPlan =
        planAttention(processAttention(1, cognitive), emptyList())

    private fun context(
        text: String = "fix the build error",
        scenarioMix: ScenarioMix = mapOf(ScenarioKind.ENGINEERING to 1.0),
        cognitive: CognitiveState = state(),
        risk: ActionRisk? = null,
    ): BehaviorContext = BehaviorContext(
        turnId = "rt.1",
        text = text,
        user = BayesianEngine().scoreUser(text),
        scenario = resolveScenario(scenarioMix),
        interaction = resolveInteraction(mapOf(InteractionKind.THINK to 1.0)),
        attention = attention(cognitive),
        risk = risk,
    )

    @Test
    fun `six defaults keep their declaration order and hard guardrails`() {
        val registry = BehaviorRegistry()
        val ids = registry.list().map { it.id }
        assertEquals(
            listOf(
                "clarify_when_uncertain",
                "evidence_before_claim",
                "approval_before_destructive",
                "affect_then_converge",
                "rest_when_fatigued",
                "no_diagnosis",
            ),
            ids,
        )
        val hard = registry.list().filter { it.policy.hardGuardrail == true }.map { it.id }.toSet()
        assertEquals(setOf("approval_before_destructive", "rest_when_fatigued", "no_diagnosis"), hard)
        assertEquals(0, registry.tuning())
    }

    @Test
    fun `select arbitrates by priority then confidence and records conflicts`() {
        val registry = BehaviorRegistry()
        val plan = registry.select(context())

        val activeIds = plan.activations.map { it.behaviorId }
        assertTrue(activeIds.contains("clarify_when_uncertain"))
        assertTrue(activeIds.contains("evidence_before_claim"))
        assertTrue(activeIds.contains("approval_before_destructive"))
        assertEquals("clarify_when_uncertain", plan.winner)
        assertEquals(listOf("clarify"), plan.preferredActions)
        // clarify 的 maxActionsPerTurn=1 是三个激活行为里最小的。
        assertEquals(1, plan.maxActionsPerTurn)
        assertEquals(1, plan.conflicts.size)
        assertEquals("evidence_before_claim", plan.conflicts[0].loser)
        assertEquals(75, plan.conflicts[0].winnerPriority)
        assertEquals(70, plan.conflicts[0].loserPriority)
        assertTrue(plan.guardrails.contains("ask one focused question when the request is underspecified"))
    }

    @Test
    fun `rest guardrail wins when attention says rest`() {
        val registry = BehaviorRegistry()
        val plan = registry.select(context(cognitive = state(cyclesSinceRest = 8, vigilance = 0.8)))
        assertEquals("rest_when_fatigued", plan.winner)
        assertEquals(listOf("rest", "defer"), plan.preferredActions)
    }

    @Test
    fun `destructive risk activates the approval guardrail`() {
        val registry = BehaviorRegistry()
        val plan = registry.select(context(risk = ActionRisk.DESTRUCTIVE))
        val approval = plan.activations.first { it.behaviorId == "approval_before_destructive" }
        assertEquals(0.95, approval.confidence, 1e-9)
        assertEquals("destructive risk", approval.trigger)
        assertTrue(plan.guardrails.contains("destructive operations require explicit approval"))
    }

    @Test
    fun `applyPriorities refuses to lower a hard guardrail`() {
        val registry = BehaviorRegistry()
        val result = registry.applyPriorities(mapOf("rest_when_fatigued" to 10))
        assertTrue(result.applied.isEmpty())
        assertEquals(0, result.version)
        assertEquals(90, registry.list().first { it.id == "rest_when_fatigued" }.priority)
    }

    @Test
    fun `applyPriorities changes soft priorities and bumps behavior version`() {
        val registry = BehaviorRegistry()
        val result = registry.applyPriorities(
            mapOf(
                "clarify_when_uncertain" to 10,
                "evidence_before_claim" to 1000,
            ),
        )
        assertEquals(1, result.version)
        assertEquals(2, result.applied.size)
        val clarify = registry.list().first { it.id == "clarify_when_uncertain" }
        assertEquals(10, clarify.priority)
        assertEquals(2, clarify.version)
        // 超过 100 的请求被夹到 100。
        assertEquals(100, registry.list().first { it.id == "evidence_before_claim" }.priority)
        // 相同值不再计价。
        assertEquals(0, registry.applyPriorities(mapOf("clarify_when_uncertain" to 10)).applied.size)
    }

    @Test
    fun `rollback restores the snapshot before the version`() {
        val registry = BehaviorRegistry()
        registry.applyPriorities(mapOf("clarify_when_uncertain" to 10))
        assertEquals(1, registry.tuning())
        assertTrue(registry.rollbackPriorities(1))
        assertEquals(75, registry.list().first { it.id == "clarify_when_uncertain" }.priority)
        assertEquals(2, registry.tuning())
        // 只剩 version 0 的快照，因此 2 没有可回滚的目标。
        assertFalse(registry.rollbackPriorities(2))
    }

    @Test
    fun `rollback of an unknown version returns false`() {
        assertFalse(BehaviorRegistry().rollbackPriorities(7))
    }

    // ------------------------------------------------------------------
    // behaviorTuning
    // ------------------------------------------------------------------

    private fun behavior(
        id: String,
        priority: Int,
        hard: Boolean = false,
    ): Behavior = Behavior(
        id = id,
        name = id,
        priority = priority,
        trigger = BehaviorTrigger(),
        policy = BehaviorPolicy(preferActions = listOf(id), hardGuardrail = hard),
        guardrails = emptyList(),
        metrics = emptyList(),
        version = 1,
    )

    @Test
    fun `suggestions raise the winner and lower the loser once past the threshold`() {
        val behaviors = listOf(behavior("a", 50), behavior("b", 40))
        assertEquals(
            emptyList<PrioritySuggestion>(),
            suggestPriorityTuning(behaviors, mapOf("a<-b" to 2)),
        )
        val suggestions = suggestPriorityTuning(behaviors, mapOf("a<-b" to 3))
        assertEquals(2, suggestions.size)
        assertEquals("a", suggestions[0].behaviorId)
        assertEquals(51, suggestions[0].suggested)
        assertEquals("b", suggestions[1].behaviorId)
        assertEquals(39, suggestions[1].suggested)
        assertTrue(suggestions[0].reason.contains("a beat b 3x"))
        assertTrue(suggestions[1].reason.contains("b lost to a 3x"))
    }

    @Test
    fun `hard guardrails are never lowered by a suggestion`() {
        val behaviors = listOf(behavior("a", 50), behavior("b", 40, hard = true))
        val suggestions = suggestPriorityTuning(behaviors, mapOf("a<-b" to 3))
        assertEquals(listOf("a"), suggestions.map { it.behaviorId })
    }

    @Test
    fun `delta is clamped by maxDelta`() {
        val behaviors = listOf(
            behavior("a", 50),
            behavior("b", 40),
            behavior("c", 40),
            behavior("d", 40),
            behavior("e", 40),
            behavior("f", 40),
            behavior("g", 40),
        )
        val conflicts = mapOf(
            "a<-b" to 3,
            "a<-c" to 3,
            "a<-d" to 3,
            "a<-e" to 3,
            "a<-f" to 3,
            "a<-g" to 3,
        )
        val suggestions = suggestPriorityTuning(behaviors, conflicts, TuningOptions(maxDelta = 5))
        assertEquals(55, suggestions.first { it.behaviorId == "a" }.suggested)
    }

    @Test
    fun `apply and rollback return copies and leave the input untouched`() {
        val original = listOf(behavior("a", 50), behavior("b", 40))
        val suggestions = suggestPriorityTuning(original, mapOf("a<-b" to 3))
        val tuned = applyPrioritySuggestions(original, suggestions)
        assertEquals(51, tuned.first { it.id == "a" }.priority)
        assertEquals(50, original.first { it.id == "a" }.priority)
        val restored = rollbackPrioritySuggestions(tuned, original)
        assertEquals(50, restored.first { it.id == "a" }.priority)
        assertEquals(51, tuned.first { it.id == "a" }.priority)
    }

    @Test
    fun `controller applies the same conflict signature only once`() {
        val registry = BehaviorRegistry()
        val controller = BehaviorTuningController(registry)
        val conflicts = mapOf("clarify_when_uncertain<-evidence_before_claim" to 3)
        val first = controller.autoApply(conflicts, AutoTuneOptions(enabled = true))
        assertEquals(2, first.suggestions.size)
        assertEquals(2, first.applied)
        assertEquals(1, first.version)
        val second = controller.autoApply(conflicts, AutoTuneOptions(enabled = true))
        assertEquals(0, second.applied)
        val disabled = BehaviorTuningController(BehaviorRegistry())
            .autoApply(conflicts, AutoTuneOptions(enabled = false))
        assertEquals(0, disabled.applied)
        assertTrue(controller.rollback(1))
    }

    @Test
    fun `unknown conflict keys are ignored`() {
        val behaviors = listOf(behavior("a", 50))
        assertEquals(
            emptyList<PrioritySuggestion>(),
            suggestPriorityTuning(behaviors, mapOf("x<-y" to 9, "broken" to 9)),
        )
        assertNull(
            suggestPriorityTuning(emptyList(), mapOf("a<-b" to 9)).firstOrNull(),
        )
    }
}
