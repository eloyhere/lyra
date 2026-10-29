package com.eloykim.lyra.amp.protocol.runtime

import com.eloykim.lyra.amp.protocol.attention.CognitiveState
import com.eloykim.lyra.amp.protocol.attention.planAttention
import com.eloykim.lyra.amp.protocol.attention.processAttention
import com.eloykim.lyra.amp.protocol.bayes.BayesianEngine
import com.eloykim.lyra.amp.protocol.interaction.InteractionKind
import com.eloykim.lyra.amp.protocol.interaction.resolveInteraction
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.scenario.ScenarioKind
import com.eloykim.lyra.amp.protocol.scenario.resolveScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `session.ts` 与 `replay.ts` 的行为固定测试。
 *
 * 会话侧钉住：记录后注意力状态如何被遥测推进、快照/恢复保留轮次计数与历史、
 * 反馈成为证据而不是覆写分数。重放侧钉住：引用完整性失败必须抛错，而不是静默
 * 返回半张图。
 */
class SessionReplayTest {

    private val engine = BayesianEngine()

    private fun attention(rest: Boolean): com.eloykim.lyra.amp.protocol.attention.AttentionPlan {
        val state = CognitiveState(
            load = 0.3,
            vigilance = if (rest) 0.2 else 0.8,
            arousal = 0.2,
            mastery = 0.6,
            accountability = 0.5,
            cyclesSinceRest = if (rest) 8 else 0,
            capacityEff = 4,
            pLapse = 0.2,
        )
        return planAttention(processAttention(7, state), emptyList())
    }

    private fun record(
        turnId: String = "rt.1",
        conflict: BehaviorConflict? = null,
    ): SessionTurnRecord = SessionTurnRecord(
        turnId = turnId,
        at = "2026-01-01T00:00:00Z",
        scope = "task:engineering",
        user = engine.scoreUser("fix the build error please"),
        answerScore = engine.scoreAnswer("Here is the failing test and the patch."),
        attention = attention(rest = false),
        latencyMs = 6000,
        tokens = 200,
        toolErrors = 1,
        telemetry = TelemetrySample(systemLoad = 0.5, eventLoopLagMs = 80.0, errorRate = 0.1),
        behavior = BehaviorPlan(
            activations = emptyList(),
            guardrails = emptyList(),
            maxActionsPerTurn = 1,
            preferredActions = emptyList(),
            conflicts = conflict?.let { listOf(it) } ?: emptyList(),
        ),
    )

    @Test
    fun `record advances the cognitive state from telemetry and quality`() {
        val session = RuntimeSession(RuntimeSessionOptions(id = "session.1", scope = "test"))
        assertEquals("rt.1", session.nextTurnId())

        session.record(record())

        assertEquals(1, session.history.size)
        assertEquals(1, session.state.cyclesSinceRest)
        assertTrue(session.state.fatigue > 0.2)
        assertTrue(session.state.recoveryDebt > 0.1)
        assertTrue(session.state.vigilance < 0.8)
        assertEquals(1.0 - session.state.vigilance, session.state.pLapse, 1e-9)
    }

    @Test
    fun `record counts behavior conflicts by winner and loser`() {
        val session = RuntimeSession(RuntimeSessionOptions())
        session.record(
            record(
                conflict = BehaviorConflict(
                    winner = "affect_then_converge",
                    loser = "clarify_when_uncertain",
                    reason = "x",
                    winnerPriority = 80,
                    loserPriority = 75,
                ),
            ),
        )
        assertEquals(1, session.behaviorConflicts.getValue("affect_then_converge<-clarify_when_uncertain"))
        session.record(record(turnId = "rt.2"))
        assertEquals(1, session.behaviorConflicts.size)
    }

    @Test
    fun `serialize and restore keep turn counter history priors and scope`() {
        val session = RuntimeSession(RuntimeSessionOptions(id = "session.1", scope = "test"))
        session.nextTurnId()
        session.record(record())

        val snapshot = session.serialize()
        assertEquals("session.1", snapshot.id)
        assertEquals("test", snapshot.scope)
        assertEquals(1, snapshot.turnCounter)
        assertEquals(session.bayes.snapshot(), snapshot.priors)

        val restored = RuntimeSession.restore(snapshot)
        assertEquals("rt.2", restored.nextTurnId())
        assertEquals(1, restored.history.size)
        assertEquals(session.state, restored.state)
        assertEquals(session.scope, restored.scope)
        assertEquals(session.bayes.snapshot(), restored.bayes.snapshot())
        assertEquals(session.behaviorConflicts, restored.behaviorConflicts)
    }

    @Test
    fun `feedback becomes evidence and never overwrites the score`() {
        val session = RuntimeSession(RuntimeSessionOptions())
        session.record(record())
        val before = session.state

        val evidence = session.feedback(
            "rt.1",
            TurnFeedback(helpful = false, correction = "The patch was wrong.", actualCorrect = false),
        )
        assertEquals(3, evidence.size)
        assertTrue(evidence.all { it.dimension == com.eloykim.lyra.amp.protocol.bayes.Dimension.HELPFULNESS || it.dimension == com.eloykim.lyra.amp.protocol.bayes.Dimension.CORRECTNESS })
        assertTrue(session.state.stress > before.stress)
        assertEquals(2, session.serialize().version)
        assertThrows(IllegalArgumentException::class.java) {
            session.feedback("rt.404", TurnFeedback(helpful = true))
        }
    }

    @Test
    fun `rest clears the cycle counter and raises vigilance`() {
        val session = RuntimeSession(RuntimeSessionOptions())
        session.record(record())
        val before = session.state
        val rested = session.rest(2)
        assertEquals(0, rested.cyclesSinceRest)
        assertTrue(rested.vigilance > before.vigilance)
        assertEquals(1.0 - rested.vigilance, rested.pLapse, 1e-9)
        assertEquals(2, session.serialize().version)
    }

    @Test
    fun `summary reports turns and scoped priors`() {
        val session = RuntimeSession(RuntimeSessionOptions())
        session.record(record())
        val summary = session.snapshot()
        assertEquals(1, summary.turns)
        assertEquals(session.state, summary.state)
        assertTrue(summary.priors.isNotEmpty())
        assertTrue(summary.scopedPriors.scopes?.containsKey("task:engineering") == true)
    }

    // ------------------------------------------------------------------
    // replay
    // ------------------------------------------------------------------

    private fun replayableTurn(): RuntimeTurn {
        val user = engine.scoreUser("hello")
        return RuntimeTurn(
            turnId = "rt.7",
            sessionId = "session.1",
            scope = "default:chat",
            pid = 3,
            input = "hello",
            at = "2026-01-01T00:00:00Z",
            user = user,
            scenario = resolveScenario(mapOf(ScenarioKind.CHAT to 1.0)),
            interaction = resolveInteraction(mapOf(InteractionKind.THINK to 1.0)),
            attention = attention(rest = false),
            answer = "hi there",
            safety = SafetyDecision(SafetyAction.ALLOW, emptyList()),
            receiptId = "rc.runtime.1",
            trace = listOf("scope=default:chat"),
            behavior = BehaviorPlan(
                activations = listOf(BehaviorActivation("clarify_when_uncertain", "rt.7", "short", 0.5, 75)),
                guardrails = emptyList(),
                maxActionsPerTurn = 1,
                preferredActions = listOf("clarify"),
                winner = "clarify_when_uncertain",
                conflicts = emptyList(),
            ),
            actions = listOf(
                ActionRecord(
                    id = "act.rt.7.1",
                    turnId = "rt.7",
                    goal = "g",
                    intent = "clarify",
                    expectedEffect = "e",
                    preconditions = emptyList(),
                    successCriteria = emptyList(),
                    risk = ActionRisk.NONE,
                    status = ActionStatus.DONE,
                    operationIds = mutableListOf("op.rt.7.llm"),
                ),
            ),
            operations = listOf(
                OperationRecord(
                    id = "op.rt.7.llm",
                    actionId = "act.rt.7.1",
                    kind = OperationKind.LLM_COMPLETE,
                    input = jsonObj(),
                    executor = "llm",
                    sideEffect = SideEffect.NONE,
                    status = OperationStatus.OK,
                ),
            ),
        )
    }

    @Test
    fun `replayTurn rebuilds the action and operation graph`() {
        val replayed = replayTurn(replayableTurn())
        assertEquals("rt.7", replayed.turnId)
        assertEquals("session.1", replayed.sessionId)
        assertEquals(3, replayed.pid)
        assertEquals("hello", replayed.input)
        assertEquals("hi there", replayed.output)
        assertEquals(listOf("clarify_when_uncertain"), replayed.behaviorIds)
        assertEquals(1, replayed.actions.size)
        assertEquals("clarify", replayed.actions[0].intent)
        assertEquals("done", replayed.actions[0].status)
        assertEquals(listOf("op.rt.7.llm"), replayed.actions[0].operations)
        assertEquals("llm.complete", replayed.operations[0].kind)
        assertEquals("ok", replayed.operations[0].status)
        assertEquals("act.rt.7.1", replayed.operations[0].actionId)
    }

    @Test
    fun `replayTurn rejects operations that point at a missing action`() {
        val turn = replayableTurn()
        val broken = turn.copy(
            operations = listOf(turn.operations!!.first().copy(actionId = "act.missing")),
        )
        val error = assertThrows(IllegalStateException::class.java) { replayTurn(broken) }
        assertTrue(error.message!!.contains("references missing action act.missing"))
    }

    @Test
    fun `replayTurn rejects actions that point at a missing operation`() {
        val turn = replayableTurn()
        val broken = turn.copy(
            actions = listOf(turn.actions!!.first().copy(operationIds = mutableListOf("op.missing"))),
        )
        val error = assertThrows(IllegalStateException::class.java) { replayTurn(broken) }
        assertTrue(error.message!!.contains("references missing operation op.missing"))
    }

    @Test
    fun `replayStoredTurn reads the turn store and rejects unknown ids`() {
        val store = MemoryTurnStore()
        store.save(replayableTurn())
        assertEquals("rt.7", replayStoredTurn(store, "rt.7").turnId)
        assertEquals(1, store.list(3).size)
        assertTrue(store.list(4).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            replayStoredTurn(store, "rt.404")
        }
        assertNull(store.get("rt.404"))
        assertFalse(store.list(3, limit = 0).isNotEmpty())
    }
}
