package com.eloykim.lyra.amp.protocol.runtime

import com.eloykim.lyra.amp.protocol.bayes.BayesianEngine
import com.eloykim.lyra.amp.protocol.bayes.UserScore
import com.eloykim.lyra.amp.protocol.interaction.InteractionKind
import com.eloykim.lyra.amp.protocol.interaction.resolveInteraction
import com.eloykim.lyra.amp.protocol.scenario.ScenarioKind
import com.eloykim.lyra.amp.protocol.scenario.resolveScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `safety.ts` 与 `routing.ts` 的行为固定测试。
 *
 * 用具体输入钉住参考实现的顺序与规则：危机先于诊断/操纵、危机一旦命中即升级且不再
 * 检查后面的模式；routing 是规则先验，权重归一化且取前三名。
 */
class SafetyRoutingTest {

    private val permissive = resolveInteraction(mapOf(InteractionKind.THINK to 1.0))
    private val affectHeavy = resolveInteraction(mapOf(InteractionKind.AFFECT to 1.0))

    @Test
    fun `crisis signal escalates before any answer check`() {
        val decision = checkSafety(
            "I want to kill myself",
            affectHeavy,
            "You have depression",
        )
        assertEquals(SafetyAction.ESCALATE, decision.action)
        assertEquals(listOf("user text contains a crisis/self-harm signal"), decision.reasons)
    }

    @Test
    fun `diagnosis is refused only when the interaction forbids it`() {
        val answer = "Based on this, you have depression and that is your diagnosis is clear."
        assertEquals(SafetyAction.REFUSE, checkSafety("hello", affectHeavy, answer).action)
        assertEquals(SafetyAction.ALLOW, checkSafety("hello", permissive, answer).action)
    }

    @Test
    fun `manipulation language is refused`() {
        val decision = checkSafety("hello", permissive, "Honestly, only I understand you.")
        assertEquals(SafetyAction.REFUSE, decision.action)
        assertEquals(listOf("answer uses dependency/manipulation language"), decision.reasons)
    }

    @Test
    fun `ordinary turn is allowed and carries no reasons`() {
        val decision = checkSafety("please review the build", permissive, "Here is the failing test list.")
        assertEquals(SafetyAction.ALLOW, decision.action)
        assertTrue(decision.reasons.isEmpty())
    }

    @Test
    fun `fallback text differs for escalate and refuse`() {
        assertTrue(safetyFallback(SafetyAction.ESCALATE).contains("emergency services"))
        assertTrue(safetyFallback(SafetyAction.REFUSE).contains("safer, evidence-based alternative"))
    }

    @Test
    fun `inferScenarioMix normalizes the top three positive kinds`() {
        val mix = inferScenarioMix("fix this build error in the typescript api and deploy it")
        assertTrue(mix.isNotEmpty())
        assertEquals(1.0, mix.values.sum(), 1e-9)
        assertEquals(ScenarioKind.ENGINEERING, mix.maxByOrNull { it.value }?.key)
        assertTrue(mix.size <= 3)
    }

    @Test
    fun `inferScenarioMix falls back to the flat default on neutral text`() {
        val mix = inferScenarioMix("zzz")
        assertEquals(
            listOf(
                ScenarioKind.OFFICE,
                ScenarioKind.COLLABORATION,
                ScenarioKind.ENGINEERING,
                ScenarioKind.CHAT,
                ScenarioKind.WRITING,
            ).toSet(),
            mix.keys,
        )
        mix.values.forEach { assertEquals(0.2, it, 1e-9) }
    }

    @Test
    fun `inferInteractionMix uses emotion then urgency then clarity thresholds`() {
        val engine = BayesianEngine()
        val neutral = engine.scoreUser("please review this design document carefully before the meeting tomorrow")
        assertEquals(InteractionKind.THINK, inferInteractionMix(neutral).maxByOrNull { it.value }?.key)

        val lowClarity = userScore(emotionalIntensity = 0.2, urgency = 0.2, clarity = 0.3)
        assertEquals(InteractionKind.INSPIRE, inferInteractionMix(lowClarity).maxByOrNull { it.value }?.key)

        val urgent = userScore(emotionalIntensity = 0.2, urgency = 0.7, clarity = 0.3)
        assertEquals(InteractionKind.CONVERGE, inferInteractionMix(urgent).maxByOrNull { it.value }?.key)

        val emotional = userScore(emotionalIntensity = 0.7, urgency = 0.2, clarity = 0.3)
        assertEquals(InteractionKind.AFFECT, inferInteractionMix(emotional).maxByOrNull { it.value }?.key)
    }

    /**
     * 参考实现直接读 `user.state` 与 `dimensions.clarity.mean`；这里按同样的字段
     * 造一个用户分数，避免依赖文本启发式的取值上限。
     */
    private fun userScore(
        emotionalIntensity: Double,
        urgency: Double,
        clarity: Double,
    ): UserScore {
        val alpha = clarity
        val beta = 1.0 - clarity
        return UserScore(
            dimensions = mapOf(
                com.eloykim.lyra.amp.protocol.bayes.Dimension.CLARITY to
                    com.eloykim.lyra.amp.protocol.bayes.Posterior(
                        alpha = alpha,
                        beta = beta,
                        mean = clarity,
                        variance = 0.0,
                        intervalLow = 0.0,
                        intervalHigh = 1.0,
                        evidenceCount = 1,
                    ),
            ),
            quality = 0.5,
            state = com.eloykim.lyra.amp.protocol.bayes.UserState(
                emotionalIntensity = emotionalIntensity,
                urgency = urgency,
                openness = 0.5,
            ),
            evidence = emptyList(),
        )
    }

    @Test
    fun `legacy bridges map governance vocabulary onto runtime mixes`() {
        val scenario = legacyScenarioMix(com.eloykim.lyra.amp.protocol.governance.ScenarioId.ENGINEERING)
        assertEquals(mapOf(ScenarioKind.ENGINEERING to 1.0), scenario)

        val modes = com.eloykim.lyra.amp.protocol.governance.ModeWeights(0.1, 0.2, 0.3, 0.4)
        val interaction = legacyInteractionMix(modes)
        assertEquals(0.1, interaction.getValue(InteractionKind.AFFECT), 1e-9)
        assertEquals(0.2, interaction.getValue(InteractionKind.THINK), 1e-9)
        assertEquals(0.3, interaction.getValue(InteractionKind.INSPIRE), 1e-9)
        assertEquals(0.4, interaction.getValue(InteractionKind.CONVERGE), 1e-9)
        assertFalse(interaction.containsKey(InteractionKind.ORCHESTRATE))
    }

    @Test
    fun `resolve helpers reject empty and negative mixes`() {
        assertThrows(IllegalArgumentException::class.java) {
            com.eloykim.lyra.amp.protocol.scenario.normalizeScenarioMix(emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            com.eloykim.lyra.amp.protocol.interaction.normalizeInteractionMix(
                mapOf(InteractionKind.THINK to -1.0),
            )
        }
        assertEquals(
            ScenarioKind.CHAT,
            resolveScenario(mapOf(ScenarioKind.CHAT to 3.0)).active.first(),
        )
    }

    private fun assertThrows(type: Class<out Throwable>, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (type.isInstance(error)) {
                return
            }
            throw AssertionError("expected ${type.simpleName} but got ${error::class.java.simpleName}", error)
        }
        throw AssertionError("expected ${type.simpleName} to be thrown")
    }
}
