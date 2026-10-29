package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.bayes.BetaBelief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlTest {

    private val balanced = ModeWeights(0.25, 0.25, 0.25, 0.25)

    private fun observe(
        alpha: Double,
        beta: Double,
        modes: ModeWeights = balanced,
        conflict: Boolean = false,
        legalOverride: Boolean = false,
        voi: Double? = null,
        openQuestions: Int? = null,
    ): ControlDecision = decideControl(
        ControlObservation(
            scenario = ScenarioId.CHAT,
            modes = modes,
            posterior = BetaBelief(alpha, beta),
            conflict = conflict,
            legalOverride = legalOverride,
            voi = voi,
            openQuestions = openQuestions,
        ),
    )

    @Test
    fun `不确定时扩大并提高发散`() {
        val decision = observe(1.0, 1.0)
        assertEquals(ControlAction.EXPAND, decision.action)
        assertEquals(0.5, decision.mean, 1e-9)
        assertEquals(0.57735, decision.uncertainty, 1e-4)
        assertTrue(decision.modes.diverge > balanced.diverge)
        assertEquals(0.3181818, decision.modes.diverge, 1e-6)
        assertEquals(1.0, decision.modes.total, 1e-9)
        assertTrue(decision.escalate)
        assertEquals(PerspectiveId.SUPERVISORY, decision.escalateTo)
        assertEquals(0.1, decision.budget.nextObservationShare, 1e-9)
        assertEquals("information value 0.00 keeps an exploratory share", decision.budget.reason)
        assertFalse(decision.approvalRequired)
        assertTrue(decision.rationale[0].startsWith("posterior mean 0.50, uncertainty 0.58"))
        assertTrue(decision.rationale.any { it.contains("escalate to supervisory: residual uncertainty") })
    }

    @Test
    fun `高置信时收缩并提高收敛`() {
        val decision = observe(9.0, 1.0)
        assertEquals(ControlAction.SHRINK, decision.action)
        assertEquals(0.9, decision.mean, 1e-9)
        assertTrue(decision.modes.converge > balanced.converge)
        assertFalse(decision.stop)
        assertFalse(decision.escalate)
        assertNull(decision.escalateTo)
        assertEquals(0.05, decision.budget.nextObservationShare, 1e-9)
        assertEquals("confidence allows a narrow share", decision.budget.reason)
        assertTrue(decision.rationale.any { it.startsWith("confidence 0.90 raises convergence by 0.10") })
    }

    @Test
    fun `高置信无未决问题且信息价值低时停止`() {
        val decision = observe(99.0, 1.0, voi = 0.01, openQuestions = 0)
        assertTrue(decision.stop)
        assertEquals(ControlAction.SHRINK, decision.action)
        assertTrue(decision.rationale.any { it.contains("stop") })
        assertFalse(observe(99.0, 1.0, voi = 0.01, openQuestions = 2).stop)
        assertFalse(observe(99.0, 1.0, voi = 0.5).stop)
    }

    @Test
    fun `冲突提高思考并在低置信时升级总经理`() {
        val decision = observe(2.0, 8.0, conflict = true)
        assertEquals(ControlAction.EXPAND, decision.action)
        assertTrue(decision.modes.think > balanced.think)
        assertTrue(decision.escalate)
        assertEquals(PerspectiveId.GENERAL_MANAGER, decision.escalateTo)
        assertTrue(decision.rationale.any { it.contains("evidence conflict raises thinking by 0.15") })
        assertTrue(decision.rationale.any { it.contains("escalate to general-manager: unresolved evidence conflict") })
    }

    @Test
    fun `法务强制思考并升级法务`() {
        val decision = observe(9.0, 1.0, legalOverride = true)
        assertTrue(decision.escalate)
        assertEquals(PerspectiveId.LEGAL, decision.escalateTo)
        assertEquals(0.3461538, decision.modes.think, 1e-6)
        assertTrue(decision.rationale.any { it == "legal forced the thinking mode on" })
        assertTrue(decision.rationale.any { it.contains("escalate to legal: legal override") })
    }

    @Test
    fun `模式漂移超过阈值要求批准`() {
        val skewed = ModeWeights(0.7, 0.1, 0.1, 0.1)
        val decision = observe(9.0, 1.0, modes = skewed, conflict = true, legalOverride = true)
        assertTrue(decision.approvalRequired)
        assertTrue(decision.rationale.any { it.contains("needs the general manager's approval") })
        assertTrue(modeDrift(skewed, decision.modes) > 0.15)

        val small = observe(9.0, 1.0, modes = ModeWeights(0.3, 0.2, 0.3, 0.2))
        assertFalse(small.approvalRequired)
    }

    @Test
    fun `概率夹紧与不确定性上限`() {
        assertEquals(0.001, clampProbability(-3.0), 0.0)
        assertEquals(0.999, clampProbability(3.0), 0.0)
        assertEquals(0.001, clampProbability(Double.NaN), 0.0)
        assertEquals(0.25, clampProbability(0.25), 0.0)
        assertEquals(0.001, ProbabilityClamp.min, 0.0)
        assertEquals(0.999, ProbabilityClamp.max, 0.0)

        val uncertain = observe(0.0001, 0.0001)
        assertTrue(uncertain.uncertainty in 0.0..1.0)
        assertEquals(ControlAction.EXPAND, uncertain.action)
    }

    @Test
    fun `信息价值影响观察份额`() {
        val exploratory = observe(1.0, 1.0, voi = 1.0)
        assertEquals(0.5, exploratory.budget.nextObservationShare, 1e-9)
        assertEquals("information value 1.00 keeps an exploratory share", exploratory.budget.reason)
        val none = observe(1.0, 1.0, voi = 0.0)
        assertEquals(0.1, none.budget.nextObservationShare, 1e-9)
    }

    @Test
    fun `变化最大的模式`() {
        assertEquals(
            ModeId.THINK,
            changedMode(ModeWeights(0.25, 0.25, 0.25, 0.25), ModeWeights(0.1, 0.5, 0.2, 0.2)),
        )
        // 全部下降时仍返回变化最大的一维（收敛项 0 变化最大）。
        assertEquals(
            ModeId.CONVERGE,
            changedMode(ModeWeights(0.5, 0.2, 0.2, 0.1), ModeWeights(0.1, 0.1, 0.1, 0.1)),
        )
    }
}
