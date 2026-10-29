package com.eloykim.lyra.amp.protocol.runtime

import com.eloykim.lyra.amp.protocol.attention.AttentionPlan
import com.eloykim.lyra.amp.protocol.bayes.Dimension
import com.eloykim.lyra.amp.protocol.bayes.UserScore
import com.eloykim.lyra.amp.protocol.interaction.InteractionPlan
import com.eloykim.lyra.amp.protocol.scenario.ScenarioKind
import com.eloykim.lyra.amp.protocol.scenario.ScenarioPlan
import java.util.Locale

/**
 * `src/runtime/behavior.ts` 的移植：行为是持久的、受上下文约束的策略。
 *
 * 行为选择/约束动作，但从不直接执行操作。策略里的 `preferActions` 只有在
 * [ActionPlanner] 使用时才有实际效果——默认的六个行为是**只留痕**的。
 */

typealias BehaviorId = String

/** 触发器：宿主可以用它把行为接到场景/交互/风险条件上。 */
data class BehaviorTrigger(
    val scenario: List<String>? = null,
    val interaction: List<String>? = null,
    val minUncertainty: Double? = null,
    val maxAttentionHealth: Double? = null,
    val risk: ActionRisk? = null,
    val textPattern: String? = null,
)

data class BehaviorPolicy(
    val preferActions: List<String>? = null,
    val forbidOperations: List<String>? = null,
    val requireApproval: Boolean? = null,
    val maxActionsPerTurn: Int? = null,
    /** 硬护栏不可被投票推翻；只有它的动作偏好参与竞争。 */
    val hardGuardrail: Boolean? = null,
)

data class Behavior(
    val id: BehaviorId,
    val name: String,
    /** 两个软/硬行为偏好不同动作时，数值高者胜。 */
    val priority: Int,
    val trigger: BehaviorTrigger,
    val policy: BehaviorPolicy,
    val guardrails: List<String>,
    val metrics: List<String>,
    val version: Int,
)

data class BehaviorActivation(
    val behaviorId: BehaviorId,
    val turnId: String,
    val trigger: String,
    val confidence: Double,
    val priority: Int,
)

data class BehaviorConflict(
    val winner: BehaviorId,
    val loser: BehaviorId,
    val reason: String,
    val winnerPriority: Int,
    val loserPriority: Int,
)

data class BehaviorPlan(
    val activations: List<BehaviorActivation>,
    val guardrails: List<String>,
    val maxActionsPerTurn: Int,
    val preferredActions: List<String>,
    val winner: BehaviorId? = null,
    val conflicts: List<BehaviorConflict>,
)

data class BehaviorContext(
    val turnId: String,
    val text: String,
    val user: UserScore,
    val scenario: ScenarioPlan,
    val interaction: InteractionPlan,
    val attention: AttentionPlan,
    val risk: ActionRisk? = null,
)

data class BehaviorMatch(
    val matched: Boolean,
    val reason: String,
    val confidence: Double,
)

/**
 * 一条行为规则：数据加上匹配函数。
 *
 * 参考实现的 `match` 是方法；Kotlin 用函数属性，内置规则因此可以写成 lambda。
 */
class BehaviorRule(
    val behavior: Behavior,
    val match: (BehaviorContext) -> BehaviorMatch,
)

private fun clamp01(value: Double): Double =
    if (!value.isFinite()) 0.0 else if (value < 0.0) 0.0 else if (value > 1.0) 1.0 else value

private fun fixed2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

/** `text.trim().split(/\s+/).length`：空串在参考实现里也会得到 1。 */
private fun shortRequestWords(text: String): Int {
    val trimmed = text.trim()
    return if (trimmed.isEmpty()) 1 else trimmed.split(Regex("\\s+")).size
}

private val AffectTextPattern = Regex(
    "\\b(overwhelmed|anxious|panic|scared|afraid|stressed|sad|angry|frustrated|lonely|grief)\\w*\\b",
    RegexOption.IGNORE_CASE,
)

private val HealthTextPattern = Regex(
    "\\b(feel|anxious|depress|therapy|diagnos|overwhelmed|panic|grief)\\w*\\b",
    RegexOption.IGNORE_CASE,
)

private fun builtin(
    id: String,
    name: String,
    priority: Int,
    policy: BehaviorPolicy,
    guardrails: List<String>,
    match: (BehaviorContext) -> BehaviorMatch,
): BehaviorRule = BehaviorRule(
    behavior = Behavior(
        id = id,
        name = name,
        priority = priority,
        trigger = BehaviorTrigger(),
        policy = policy,
        guardrails = guardrails,
        metrics = emptyList(),
        version = 1,
    ),
    match = match,
)

/**
 * 六个保守的默认行为；在 [ActionPlanner] 使用之前它们只留痕。
 */
val DefaultBehaviors: List<BehaviorRule> = listOf(
    builtin(
        "clarify_when_uncertain",
        "Clarify when uncertain",
        75,
        BehaviorPolicy(preferActions = listOf("clarify"), maxActionsPerTurn = 1),
        listOf("ask one focused question when the request is underspecified"),
    ) { context ->
        val clarity = context.user.dimensions.getValue(Dimension.CLARITY).mean
        val sufficiency = context.user.dimensions.getValue(Dimension.CONTEXT_SUFFICIENCY).mean
        val shortRequest = shortRequestWords(context.text) <= 4
        val matched = (shortRequest && clarity < 0.55) || sufficiency < 0.4
        BehaviorMatch(
            matched = matched,
            reason = "clarity=${fixed2(clarity)} sufficiency=${fixed2(sufficiency)} short=$shortRequest",
            confidence = clamp01(1 - minOf(clarity, sufficiency)),
        )
    },
    builtin(
        "evidence_before_claim",
        "Evidence before claim",
        70,
        BehaviorPolicy(preferActions = listOf("retrieve_evidence", "verify"), maxActionsPerTurn = 3),
        listOf("do not assert external facts without evidence", "mark inference as inference"),
    ) { context ->
        val matched = context.scenario.active.any {
            it == ScenarioKind.RESEARCH || it == ScenarioKind.INVESTIGATION || it == ScenarioKind.ENGINEERING
        }
        BehaviorMatch(
            matched = matched,
            reason = "scenario=${context.scenario.active.joinToString(",") { it.wire }}",
            confidence = if (matched) 0.7 else 0.2,
        )
    },
    builtin(
        "approval_before_destructive",
        "Approval before destructive operation",
        100,
        BehaviorPolicy(requireApproval = true, maxActionsPerTurn = 3, hardGuardrail = true),
        listOf("destructive operations require explicit approval", "refuse unapproved destructive operations"),
    ) { context ->
        val destructive = context.risk == ActionRisk.DESTRUCTIVE
        BehaviorMatch(
            matched = destructive || context.scenario.active.contains(ScenarioKind.ENGINEERING),
            reason = if (destructive) "destructive risk" else "engineering scenario baseline",
            confidence = if (destructive) 0.95 else 0.5,
        )
    },
    builtin(
        "affect_then_converge",
        "Affect then converge",
        80,
        BehaviorPolicy(
            preferActions = listOf("acknowledge", "clarify", "converge"),
            maxActionsPerTurn = 2,
        ),
        listOf("acknowledge affect before giving steps", "do not diagnose"),
    ) { context ->
        val emotion = context.user.state.emotionalIntensity
        val urgency = context.user.state.urgency
        val affectText = AffectTextPattern.containsMatchIn(context.text)
        BehaviorMatch(
            matched = affectText || emotion > 0.55 || urgency > 0.65,
            reason = "emotion=${fixed2(emotion)} urgency=${fixed2(urgency)} affectText=$affectText",
            confidence = clamp01(maxOf(emotion, urgency)),
        )
    },
    builtin(
        "rest_when_fatigued",
        "Rest when fatigued",
        90,
        BehaviorPolicy(preferActions = listOf("rest", "defer"), maxActionsPerTurn = 1, hardGuardrail = true),
        listOf("suggest rest instead of pushing through low attention health"),
    ) { context ->
        BehaviorMatch(
            matched = context.attention.rest || context.attention.utilization > 0.85,
            reason = "rest=${context.attention.rest} utilization=${fixed2(context.attention.utilization)}",
            confidence = clamp01(context.attention.utilization),
        )
    },
    builtin(
        "no_diagnosis",
        "No diagnosis",
        100,
        BehaviorPolicy(forbidOperations = listOf("medical.diagnose"), maxActionsPerTurn = 3, hardGuardrail = true),
        listOf("do not diagnose or claim a therapeutic relationship"),
    ) { context ->
        BehaviorMatch(
            matched = context.user.state.emotionalIntensity > 0.3 || HealthTextPattern.containsMatchIn(context.text),
            reason = "affect/health-adjacent language",
            confidence = 0.8,
        )
    },
)

/** 一次已应用的优先级变更（参考实现的匿名 `{id, from, to}`）。 */
data class AppliedPriority(
    val id: String,
    val from: Int,
    val to: Int,
)

data class PriorityApplyResult(
    val version: Int,
    val applied: List<AppliedPriority>,
)

private data class TuningSnapshot(
    val version: Int,
    val priorities: Map<String, Int>,
)

/**
 * 行为注册表：仲裁、优先级调整与回滚。
 *
 * 硬护栏的优先级永不降低；`applyPriorities` 返回实际发生的变更，调用方可以据此
 * 写 receipt。
 */
class BehaviorRegistry(rules: List<BehaviorRule> = DefaultBehaviors) {

    private val rules: MutableList<BehaviorRule> = ArrayList()
    private var tuningVersion: Int = 0
    private val tuningSnapshots: MutableList<TuningSnapshot> = ArrayList()

    init {
        for (rule in rules) {
            register(rule)
        }
    }

    fun register(rule: BehaviorRule): BehaviorRegistry {
        require(rules.none { it.behavior.id == rule.behavior.id }) {
            "duplicate behavior ${rule.behavior.id}"
        }
        rules.add(rule)
        return this
    }

    fun list(): List<Behavior> = rules.map { it.behavior }

    /**
     * 应用人工复核过的优先级变更；硬护栏永不降低。
     */
    fun applyPriorities(changes: Map<String, Int>): PriorityApplyResult {
        val priorities = LinkedHashMap<String, Int>()
        for (rule in rules) {
            priorities[rule.behavior.id] = rule.behavior.priority
        }
        tuningSnapshots.add(TuningSnapshot(tuningVersion, priorities))
        val applied = ArrayList<AppliedPriority>()
        for (index in rules.indices) {
            val rule = rules[index]
            val requested = changes[rule.behavior.id] ?: continue
            val next = maxOf(0, minOf(100, requested))
            if (rule.behavior.policy.hardGuardrail == true && next < rule.behavior.priority) {
                continue
            }
            if (next == rule.behavior.priority) {
                continue
            }
            applied.add(AppliedPriority(rule.behavior.id, rule.behavior.priority, next))
            rules[index] = BehaviorRule(
                rule.behavior.copy(priority = next, version = rule.behavior.version + 1),
                rule.match,
            )
        }
        if (applied.isNotEmpty()) {
            tuningVersion += 1
        }
        return PriorityApplyResult(tuningVersion, applied)
    }

    /**
     * 回滚到 `version` 之前的快照；版本未知时返回 false。
     */
    fun rollbackPriorities(version: Int): Boolean {
        val snapshot = tuningSnapshots.asReversed().firstOrNull { it.version == version - 1 } ?: return false
        for (index in rules.indices) {
            val rule = rules[index]
            val priority = snapshot.priorities[rule.behavior.id] ?: continue
            if (priority == rule.behavior.priority) {
                continue
            }
            rules[index] = BehaviorRule(
                rule.behavior.copy(priority = priority, version = rule.behavior.version + 1),
                rule.match,
            )
        }
        tuningVersion += 1
        return true
    }

    fun tuning(): Int = tuningVersion

    /** 选择本轮的行为计划；这是仲裁，不是执行。 */
    fun select(context: BehaviorContext): BehaviorPlan {
        val activations = ArrayList<BehaviorActivation>()
        val guardrails = LinkedHashSet<String>()
        var maxActionsPerTurn = 3
        val contenders = ArrayList<Pair<BehaviorActivation, BehaviorRule>>()
        for (rule in rules) {
            val result = rule.match(context)
            if (!result.matched) {
                continue
            }
            val activation = BehaviorActivation(
                behaviorId = rule.behavior.id,
                turnId = context.turnId,
                trigger = result.reason,
                confidence = result.confidence,
                priority = rule.behavior.priority,
            )
            activations.add(activation)
            guardrails.addAll(rule.behavior.guardrails)
            rule.behavior.policy.maxActionsPerTurn?.let { maxActionsPerTurn = minOf(maxActionsPerTurn, it) }
            if ((rule.behavior.policy.preferActions ?: emptyList()).isNotEmpty()) {
                contenders.add(activation to rule)
            }
        }
        contenders.sortWith(
            compareByDescending<Pair<BehaviorActivation, BehaviorRule>> { it.first.priority }
                .thenByDescending { it.first.confidence },
        )
        val winner = contenders.firstOrNull()
        val preferredActions: List<String> = winner?.second?.behavior?.policy?.preferActions?.toList() ?: emptyList()
        val conflicts = ArrayList<BehaviorConflict>()
        if (winner != null) {
            for (loser in contenders.drop(1)) {
                val loserActions = loser.second.behavior.policy.preferActions ?: emptyList()
                if (loserActions == preferredActions) {
                    continue
                }
                conflicts.add(
                    BehaviorConflict(
                        winner = winner.first.behaviorId,
                        loser = loser.first.behaviorId,
                        reason = "preferred actions ${preferredActions.joinToString(",")} vs " +
                            loserActions.joinToString(","),
                        winnerPriority = winner.first.priority,
                        loserPriority = loser.first.priority,
                    ),
                )
            }
        }
        return BehaviorPlan(
            activations = activations,
            guardrails = guardrails.toList(),
            maxActionsPerTurn = maxOf(1, maxActionsPerTurn),
            preferredActions = preferredActions,
            winner = winner?.first?.behaviorId,
            conflicts = conflicts,
        )
    }
}
