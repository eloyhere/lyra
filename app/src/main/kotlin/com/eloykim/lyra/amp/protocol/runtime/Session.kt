package com.eloykim.lyra.amp.protocol.runtime

import com.eloykim.lyra.amp.protocol.attention.AttentionPlan
import com.eloykim.lyra.amp.protocol.attention.AttentionResourcePolicy
import com.eloykim.lyra.amp.protocol.attention.CognitiveSource
import com.eloykim.lyra.amp.protocol.attention.CognitiveState
import com.eloykim.lyra.amp.protocol.attention.SlotRole
import com.eloykim.lyra.amp.protocol.attention.applyRest
import com.eloykim.lyra.amp.protocol.attention.shouldRest
import com.eloykim.lyra.amp.protocol.bayes.AnswerScore
import com.eloykim.lyra.amp.protocol.bayes.BayesianEngine
import com.eloykim.lyra.amp.protocol.bayes.BetaBelief
import com.eloykim.lyra.amp.protocol.bayes.Dimension
import com.eloykim.lyra.amp.protocol.bayes.Evidence
import com.eloykim.lyra.amp.protocol.bayes.ReliabilityBelief
import com.eloykim.lyra.amp.protocol.bayes.ScopedPriors
import com.eloykim.lyra.amp.protocol.bayes.ScopedPriorsSnapshot
import com.eloykim.lyra.amp.protocol.bayes.SourceReliability
import com.eloykim.lyra.amp.protocol.bayes.UserScore
import com.eloykim.lyra.amp.protocol.diagnosis.DiagnosticFrame
import com.eloykim.lyra.amp.protocol.interaction.InteractionMix
import com.eloykim.lyra.amp.protocol.scenario.ScenarioMix

/**
 * `src/runtime/session.ts` 的移植：多轮会话的记忆。
 *
 * 会话是循环的记忆，不是模型的上下文：它记录后验证据，并根据遥测更新智能体的
 * 认知状态。
 *
 * 依赖说明：评分与先验来自已移植的 `bayes` 包（[BayesianEngine]、[SourceReliability]、
 * [ScopedPriors]），与参考实现 `src/bayes/engine.ts` 的绑定一致。
 */

/** 会话的注意力状态：认知状态加上压力、疲劳与恢复债务。 */
data class SessionAttentionState(
    val load: Double,
    val vigilance: Double,
    val arousal: Double,
    val mastery: Double,
    val accountability: Double,
    val cyclesSinceRest: Int,
    val capacityEff: Int,
    val pLapse: Double,
    val dominantRole: SlotRole? = null,
    val source: CognitiveSource = CognitiveSource.DEFAULT,
    val samples: Int = 0,
    val stress: Double,
    val fatigue: Double,
    val recoveryDebt: Double,
) {

    fun toCognitiveState(): CognitiveState = CognitiveState(
        load = load,
        vigilance = vigilance,
        arousal = arousal,
        mastery = mastery,
        accountability = accountability,
        cyclesSinceRest = cyclesSinceRest,
        capacityEff = capacityEff,
        pLapse = pLapse,
        dominantRole = dominantRole,
        source = source,
        samples = samples,
    )

    /** 用 `attention.applyRest` 恢复，再拼回会话字段（参考实现的 `as` 转换等价物）。 */
    fun rested(cycles: Int): SessionAttentionState {
        val rested = applyRest(toCognitiveState(), cycles)
        return of(rested, stress, fatigue, recoveryDebt)
    }

    companion object {
        fun of(
            state: CognitiveState,
            stress: Double,
            fatigue: Double,
            recoveryDebt: Double,
        ): SessionAttentionState = SessionAttentionState(
            load = state.load,
            vigilance = state.vigilance,
            arousal = state.arousal,
            mastery = state.mastery,
            accountability = state.accountability,
            cyclesSinceRest = state.cyclesSinceRest,
            capacityEff = state.capacityEff,
            pLapse = state.pLapse,
            dominantRole = state.dominantRole,
            source = state.source,
            samples = state.samples,
            stress = stress,
            fatigue = fatigue,
            recoveryDebt = recoveryDebt,
        )

        /** 参考实现的 `defaultState()`。 */
        fun initial(): SessionAttentionState = SessionAttentionState(
            load = 0.3,
            vigilance = 0.8,
            arousal = 0.2,
            mastery = 0.6,
            accountability = 0.5,
            cyclesSinceRest = 0,
            capacityEff = 4,
            pLapse = 0.2,
            source = CognitiveSource.DEFAULT,
            samples = 0,
            stress = 0.2,
            fatigue = 0.2,
            recoveryDebt = 0.1,
        )
    }
}

/** 一条已记录的轮次。 */
data class SessionTurnRecord(
    val turnId: String,
    val at: String,
    /** 本轮的先验 scope，例如 `session:engineering`。 */
    val scope: String? = null,
    val user: UserScore,
    val answerScore: AnswerScore? = null,
    val attention: AttentionPlan,
    val latencyMs: Long? = null,
    val tokens: Int? = null,
    val toolErrors: Int? = null,
    val telemetry: TelemetrySample? = null,
    val behavior: BehaviorPlan? = null,
    val actions: List<ActionRecord>? = null,
    val operations: List<OperationRecord>? = null,
    val approvals: List<ApprovalRecord>? = null,
    val diagnosis: DiagnosticFrame? = null,
)

/** 用户反馈：作为证据，而不是直接覆写分数。 */
data class TurnFeedback(
    val helpful: Boolean? = null,
    val correction: String? = null,
    /** 答案质量的真值；给出时更新来源可靠度。 */
    val actualCorrect: Boolean? = null,
)

/** 会话的可持久化快照。 */
data class SessionSnapshot(
    val id: String? = null,
    val scope: String? = null,
    val state: SessionAttentionState,
    val priors: Map<Dimension, BetaBelief>,
    val history: List<SessionTurnRecord>,
    /** 单调轮次计数，使 ID 能跨运行时重启存活。 */
    val turnCounter: Int? = null,
    /** 单调的会话变更版本。 */
    val version: Int? = null,
    /** 行为仲裁计数，例如 `"affect<-clarify" to 3`。 */
    val behaviorConflicts: Map<String, Int>? = null,
    /** 学到的来源可靠度（Beta 信念）。 */
    val sourceReliability: Map<String, ReliabilityBelief>? = null,
    /** Scope 级先验（全局 + 本地计数）。 */
    val scopedPriors: ScopedPriorsSnapshot? = null,
    /** 智能体已归档但快照保留时写入。 */
    val archivedAt: String? = null,
)

data class RuntimeSessionOptions(
    val id: String? = null,
    /** 先验 scope，例如 task/scenario id。 */
    val scope: String? = null,
    val initial: SessionAttentionState? = null,
    val bayes: BayesianEngine? = null,
    val scenarioMix: ScenarioMix? = null,
    val interactionMix: InteractionMix? = null,
    val maxHistory: Int = 100,
)

/** `snapshot()` 的返回形状。 */
data class SessionSummary(
    val state: SessionAttentionState,
    val priors: Map<Dimension, BetaBelief>,
    val turns: Int,
    val behaviorConflicts: Map<String, Int>,
    val sourceReliability: Map<String, ReliabilityBelief>,
    val scopedPriors: ScopedPriorsSnapshot,
)

private fun clamp01(value: Double): Double =
    if (!value.isFinite()) 0.0 else if (value < 0.0) 0.0 else if (value > 1.0) 1.0 else value

class RuntimeSession(options: RuntimeSessionOptions = RuntimeSessionOptions()) {

    val id: String = options.id ?: "session.local"
    var state: SessionAttentionState = options.initial ?: SessionAttentionState.initial()
    val bayes: BayesianEngine = options.bayes ?: BayesianEngine()
    val history: MutableList<SessionTurnRecord> = ArrayList()
    val behaviorConflicts: MutableMap<String, Int> = LinkedHashMap()
    val reliability: SourceReliability = SourceReliability()
    val scope: String = options.scope ?: "default"
    val scopedPriors: ScopedPriors = ScopedPriors()
    val scenarioMix: ScenarioMix? = options.scenarioMix
    val interactionMix: InteractionMix? = options.interactionMix
    private val maxHistory: Int = options.maxHistory
    private var turnCounter: Int = 0
    private var version: Int = 0

    /** 分配稳定的轮次 ID；能跨序列化/恢复存活。 */
    fun nextTurnId(): String {
        turnCounter += 1
        return "rt.$turnCounter"
    }

    /** 该 scope 的引擎（本 scope 先验 + 学到的来源权重）。 */
    fun engineFor(scope: String = this.scope): BayesianEngine {
        val engine = scopedPriors.engineFor(scope)
        engine.setSourceWeights(reliability.weights())
        return engine
    }

    /** 记录完成的轮次，并据证据更新注意力与先验。 */
    fun record(turn: SessionTurnRecord) {
        val quality = turn.answerScore?.overall ?: 0.5
        val toolErrors = turn.toolErrors ?: 0
        val latencyPressure = if ((turn.latencyMs ?: 0L) > 5000L) 0.2 else 0.0
        val systemLoad = clamp01(turn.telemetry?.systemLoad ?: 0.0)
        val loopLag = turn.telemetry?.eventLoopLagMs ?: 0.0
        val errorRate = clamp01(turn.telemetry?.errorRate ?: 0.0)
        val pressure = clamp01(
            turn.attention.pressure + latencyPressure + toolErrors * 0.1 + systemLoad * 0.1 +
                (if (loopLag > 50.0) 0.2 else 0.0) + errorRate * 0.2,
        )
        val effectiveUtilization = clamp01(
            maxOf(turn.attention.utilization, (turn.tokens ?: 0) / 4000.0, systemLoad * 0.5),
        )
        val vigilance = 0.9 * state.vigilance + 0.1 * (if (quality > 0.55) 0.85 else 0.4) -
            toolErrors * 0.02 - errorRate * 0.05
        state = state.copy(
            load = clamp01(0.7 * state.load + 0.3 * effectiveUtilization),
            vigilance = clamp01(vigilance),
            arousal = clamp01(0.8 * state.arousal + 0.2 * pressure),
            stress = clamp01(0.7 * state.stress + 0.3 * pressure),
            fatigue = clamp01(
                state.fatigue + 0.01 + pressure * 0.02 + toolErrors * 0.02 +
                    (if (loopLag > 50.0) 0.02 else 0.0),
            ),
            cyclesSinceRest = state.cyclesSinceRest + 1,
            recoveryDebt = clamp01(0.7 * state.recoveryDebt + 0.3 * (1 - quality)),
            pLapse = clamp01(1 - vigilance),
        )
        turn.answerScore?.let { score ->
            bayes.learn(score.evidence)
            scopedPriors.observe(turn.scope ?: scope, score.evidence)
        }
        for (conflict in turn.behavior?.conflicts ?: emptyList()) {
            val key = "${conflict.winner}<-${conflict.loser}"
            behaviorConflicts[key] = (behaviorConflicts[key] ?: 0) + 1
        }
        history.add(turn)
        while (history.size > maxHistory) {
            history.removeAt(0)
        }
        version += 1
    }

    /**
     * 用户反馈成为证据，而不是直接的分数覆写。
     *
     * 未知 `turnId` 与参考实现一样立即失败。
     */
    fun feedback(turnId: String, feedback: TurnFeedback): List<Evidence> {
        val turn = history.firstOrNull { it.turnId == turnId }
            ?: throw IllegalArgumentException("unknown turn $turnId")
        val evidence = ArrayList<Evidence>()
        feedback.helpful?.let { helpful ->
            evidence.add(
                Evidence(
                    dimension = Dimension.HELPFULNESS,
                    positive = if (helpful) 4.0 else 0.0,
                    negative = if (helpful) 0.0 else 4.0,
                    reliability = 0.9,
                    source = "user-feedback",
                ),
            )
        }
        feedback.correction?.let { correction ->
            if (correction.trim().isNotEmpty()) {
                evidence.add(
                    Evidence(
                        dimension = Dimension.CORRECTNESS,
                        positive = 0.0,
                        negative = 3.0,
                        reliability = 0.7,
                        source = "user-correction",
                        note = correction.take(200),
                    ),
                )
            }
        }
        feedback.actualCorrect?.let { actualCorrect ->
            evidence.add(
                Evidence(
                    dimension = Dimension.CORRECTNESS,
                    positive = if (actualCorrect) 4.0 else 0.0,
                    negative = if (actualCorrect) 0.0 else 4.0,
                    reliability = 0.95,
                    source = "user-feedback",
                ),
            )
            for (item in turn.answerScore?.evidence ?: emptyList()) {
                if (item.positive <= 0.0 && item.negative <= 0.0) {
                    continue
                }
                val predictedPositive = item.positive >= item.negative
                reliability.observe(item.source, predictedPositive == actualCorrect, maxOf(0.1, item.reliability))
            }
            bayes.setSourceWeights(reliability.weights())
        }
        if (evidence.isNotEmpty()) {
            bayes.learn(evidence)
            scopedPriors.observe(turn.scope ?: scope, evidence)
        }
        state = state.copy(
            vigilance = clamp01(state.vigilance + (if (feedback.helpful == true) 0.02 else -0.02)),
            stress = clamp01(state.stress + (if (feedback.helpful == false) 0.05 else 0.0)),
            recoveryDebt = clamp01(state.recoveryDebt + (if (feedback.helpful == false) 0.05 else 0.0)),
        )
        version += 1
        return evidence
    }

    fun shouldRest(policy: AttentionResourcePolicy? = null): Boolean =
        if (policy == null) {
            shouldRest(state.toCognitiveState())
        } else {
            shouldRest(state.toCognitiveState(), policy)
        }

    fun rest(cycles: Int = 1): SessionAttentionState {
        state = state.rested(cycles)
        version += 1
        return state
    }

    fun snapshot(): SessionSummary = SessionSummary(
        state = state.copy(),
        priors = bayes.snapshot(),
        turns = history.size,
        behaviorConflicts = LinkedHashMap(behaviorConflicts),
        sourceReliability = reliability.snapshot(),
        scopedPriors = scopedPriors.snapshot(),
    )

    fun serialize(): SessionSnapshot = SessionSnapshot(
        id = id,
        scope = scope,
        state = state.copy(),
        priors = bayes.snapshot(),
        history = history.map {
            it.copy(
                actions = it.actions?.map { action -> action.frozen() },
                operations = it.operations?.map { operation -> operation.copy() },
                approvals = it.approvals?.map { approval -> approval.copy() },
            )
        },
        turnCounter = turnCounter,
        version = version,
        behaviorConflicts = LinkedHashMap(behaviorConflicts),
        sourceReliability = reliability.snapshot(),
        scopedPriors = scopedPriors.snapshot(),
    )

    companion object {
        /** 从快照恢复；`initial`/`bayes` 由快照决定，与参考实现一致。 */
        fun restore(
            snapshot: SessionSnapshot,
            options: RuntimeSessionOptions = RuntimeSessionOptions(),
        ): RuntimeSession {
            val session = RuntimeSession(
                options.copy(
                    id = snapshot.id ?: options.id,
                    scope = snapshot.scope ?: options.scope,
                    initial = snapshot.state.copy(),
                    bayes = BayesianEngine(snapshot.priors),
                ),
            )
            session.state = snapshot.state.copy()
            session.history.clear()
            session.history.addAll(snapshot.history.takeLast(session.maxHistory))
            session.turnCounter = snapshot.turnCounter ?: session.history.size
            session.version = snapshot.version ?: 0
            snapshot.behaviorConflicts?.let { session.behaviorConflicts.putAll(it) }
            snapshot.sourceReliability?.let { session.reliability.load(it) }
            snapshot.scopedPriors?.let { session.scopedPriors.load(it) }
            session.bayes.setSourceWeights(session.reliability.weights())
            return session
        }
    }
}
