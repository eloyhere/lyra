package com.eloykim.lyra.amp.protocol.interaction

import java.util.Locale

/**
 * `src/interaction/interaction.ts` 的忠实移植：交互模式回答“这一轮需要什么动作”，
 * 而不是“任务是什么”。
 *
 * scenario 回答“这是什么领域”，interaction 回答“这一轮需要哪种动作”（affect、think、
 * inspire、converge、orchestrate）。两者是正交的权重向量，可以组合。模式不是人格测试，
 * 也不是治疗协议，而是带显式护栏的响应塑形策略。
 *
 * ## 与既有窄端口的关系
 *
 * `runtime/Routing.kt` 已有一份**窄投影**（只保留运行时读取的字段，并自带同名
 * `InteractionPlan`）。本文件是参考实现的完整移植，包含 `contextWeights`、
 * `memoryKindWeights`、`sequence` 与 `guardrails`。两者包名不同、互不冲突；运行时要
 * 切换到完整类型时，按字段做一次适配即可（窄端口的字段是这里字段的子集）。
 */

enum class InteractionKind(val wire: String) {
    AFFECT("affect"),
    THINK("think"),
    INSPIRE("inspire"),
    CONVERGE("converge"),
    ORCHESTRATE("orchestrate"),
    ;

    companion object {
        fun fromWire(value: String): InteractionKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 interaction kind: $value")
    }
}

/** 参考的 `InteractionMix = Partial<Record<InteractionKind, number>>`：缺项即 0。 */
typealias InteractionMix = Map<InteractionKind, Double>

enum class InteractionContextKey(val wire: String) {
    SELF("self"),
    ROLE("role"),
    AFFECT("affect"),
    MEMORY("memory"),
    RELATIONSHIP("relationship"),
    MEMBERSHIP("membership"),
    BACKGROUND("background"),
    ;
}

enum class InteractionMove(val wire: String) {
    VALIDATE("validate"),
    LABEL("label"),
    REFLECT("reflect"),
    SUPPORT("support"),
    BOUNDARY("boundary"),
    ANALYZE("analyze"),
    EXPLAIN("explain"),
    COMPARE("compare"),
    TEST("test"),
    QUESTION("question"),
    DIVERGE("diverge"),
    ANALOGIZE("analogize"),
    REFRAME("reframe"),
    PROMPT("prompt"),
    IMAGINE("imagine"),
    SYNTHESIZE("synthesize"),
    DECIDE("decide"),
    COMMIT("commit"),
    SUMMARIZE("summarize"),
    CLOSE("close"),
    PLAN("plan"),
    ASSIGN("assign"),
    COORDINATE("coordinate"),
    CHECKIN("checkin"),
    HANDOFF("handoff"),
    ;

    companion object {
        fun fromWire(value: String): InteractionMove =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 interaction move: $value")
    }
}

enum class InteractionPhase(val wire: String) {
    OPEN("open"),
    EXPLORE("explore"),
    ANALYZE("analyze"),
    DECIDE("decide"),
    CLOSE("close"),
    COORDINATE("coordinate"),
    ;
}

enum class ResponsePace(val wire: String) {
    FAST("fast"),
    NORMAL("normal"),
    SLOW("slow"),
}

enum class ResponseVerbosity(val wire: String) {
    TERSE("terse"),
    NORMAL("normal"),
    DETAILED("detailed"),
}

enum class ResponseStructure(val wire: String) {
    NARRATIVE("narrative"),
    BULLETS("bullets"),
    DIALOGUE("dialogue"),
}

data class InteractionResponse(
    val pace: ResponsePace,
    val verbosity: ResponseVerbosity,
    val questionRatio: Double,
    val structure: ResponseStructure,
)

data class InteractionAttention(
    /** 发散广度，[0,1]。 */
    val breadth: Double,
    /** 收敛深度，[0,1]。 */
    val depth: Double,
    /** 模式切换容忍度，[0,1]。 */
    val switchTolerance: Double,
)

data class InteractionSafety(
    val noDiagnosis: Boolean,
    val noManipulation: Boolean,
    val inviteDissent: Boolean,
    val escalateRisk: Boolean,
    val labelHypotheses: Boolean,
)

data class InteractionProfile(
    val context: Map<InteractionContextKey, Double>,
    val memory: Map<MemoryKind, Double>,
    val moves: List<InteractionMove>,
    val attention: InteractionAttention,
    val response: InteractionResponse,
    val safety: InteractionSafety,
)

data class InteractionPlan(
    val weights: Map<InteractionKind, Double>,
    val active: List<InteractionKind>,
    val primary: InteractionKind,
    val secondary: InteractionKind? = null,
    /** 有序阶段：affect -> inspire -> think -> converge -> orchestrate。 */
    val sequence: List<InteractionKind>,
    val moves: List<InteractionMove>,
    val contextWeights: Map<InteractionContextKey, Double>,
    val memoryKindWeights: Map<MemoryKind, Double>,
    val attention: InteractionAttention,
    val response: InteractionResponse,
    val safety: InteractionSafety,
    val guardrails: List<String>,
    val rationale: String,
)

private fun contextWeights(
    self: Double,
    role: Double,
    affect: Double,
    memory: Double,
    relationship: Double,
    membership: Double,
    background: Double,
): Map<InteractionContextKey, Double> = linkedMapOf(
    InteractionContextKey.SELF to self,
    InteractionContextKey.ROLE to role,
    InteractionContextKey.AFFECT to affect,
    InteractionContextKey.MEMORY to memory,
    InteractionContextKey.RELATIONSHIP to relationship,
    InteractionContextKey.MEMBERSHIP to membership,
    InteractionContextKey.BACKGROUND to background,
)

private fun memoryWeights(
    episodic: Double,
    semantic: Double,
    procedural: Double,
    social: Double,
    affective: Double,
): Map<MemoryKind, Double> = linkedMapOf(
    MemoryKind.EPISODIC to episodic,
    MemoryKind.SEMANTIC to semantic,
    MemoryKind.PROCEDURAL to procedural,
    MemoryKind.SOCIAL to social,
    MemoryKind.AFFECTIVE to affective,
)

private fun safety(
    noDiagnosis: Boolean,
    noManipulation: Boolean,
    inviteDissent: Boolean,
    escalateRisk: Boolean,
    labelHypotheses: Boolean,
): InteractionSafety = InteractionSafety(
    noDiagnosis = noDiagnosis,
    noManipulation = noManipulation,
    inviteDissent = inviteDissent,
    escalateRisk = escalateRisk,
    labelHypotheses = labelHypotheses,
)

private val Profiles: Map<InteractionKind, InteractionProfile> = linkedMapOf(
    InteractionKind.AFFECT to InteractionProfile(
        context = contextWeights(0.2, 0.05, 0.4, 0.15, 0.25, 0.0, 0.05),
        memory = memoryWeights(0.2, 0.05, 0.0, 0.4, 0.4),
        moves = listOf(
            InteractionMove.VALIDATE,
            InteractionMove.LABEL,
            InteractionMove.REFLECT,
            InteractionMove.SUPPORT,
            InteractionMove.BOUNDARY,
        ),
        attention = InteractionAttention(breadth = 0.3, depth = 0.5, switchTolerance = 0.6),
        response = InteractionResponse(
            pace = ResponsePace.SLOW,
            verbosity = ResponseVerbosity.NORMAL,
            questionRatio = 0.4,
            structure = ResponseStructure.DIALOGUE,
        ),
        safety = safety(
            noDiagnosis = true,
            noManipulation = true,
            inviteDissent = false,
            escalateRisk = true,
            labelHypotheses = false,
        ),
    ),
    InteractionKind.THINK to InteractionProfile(
        context = contextWeights(0.15, 0.1, 0.05, 0.4, 0.0, 0.0, 0.1),
        memory = memoryWeights(0.2, 0.4, 0.25, 0.05, 0.05),
        moves = listOf(
            InteractionMove.ANALYZE,
            InteractionMove.EXPLAIN,
            InteractionMove.COMPARE,
            InteractionMove.TEST,
            InteractionMove.QUESTION,
        ),
        attention = InteractionAttention(breadth = 0.2, depth = 0.9, switchTolerance = 0.4),
        response = InteractionResponse(
            pace = ResponsePace.NORMAL,
            verbosity = ResponseVerbosity.DETAILED,
            questionRatio = 0.2,
            structure = ResponseStructure.NARRATIVE,
        ),
        safety = safety(
            noDiagnosis = false,
            noManipulation = true,
            inviteDissent = true,
            escalateRisk = false,
            labelHypotheses = true,
        ),
    ),
    InteractionKind.INSPIRE to InteractionProfile(
        context = contextWeights(0.15, 0.0, 0.1, 0.5, 0.1, 0.0, 0.15),
        memory = memoryWeights(0.3, 0.35, 0.1, 0.1, 0.15),
        moves = listOf(
            InteractionMove.DIVERGE,
            InteractionMove.ANALOGIZE,
            InteractionMove.REFRAME,
            InteractionMove.PROMPT,
            InteractionMove.IMAGINE,
        ),
        attention = InteractionAttention(breadth = 0.9, depth = 0.3, switchTolerance = 0.7),
        response = InteractionResponse(
            pace = ResponsePace.FAST,
            verbosity = ResponseVerbosity.NORMAL,
            questionRatio = 0.5,
            structure = ResponseStructure.BULLETS,
        ),
        safety = safety(
            noDiagnosis = false,
            noManipulation = true,
            inviteDissent = true,
            escalateRisk = false,
            labelHypotheses = true,
        ),
    ),
    InteractionKind.CONVERGE to InteractionProfile(
        context = contextWeights(0.2, 0.25, 0.05, 0.25, 0.15, 0.0, 0.1),
        memory = memoryWeights(0.25, 0.3, 0.25, 0.1, 0.05),
        moves = listOf(
            InteractionMove.SYNTHESIZE,
            InteractionMove.DECIDE,
            InteractionMove.COMMIT,
            InteractionMove.SUMMARIZE,
            InteractionMove.CLOSE,
        ),
        attention = InteractionAttention(breadth = 0.2, depth = 0.8, switchTolerance = 0.3),
        response = InteractionResponse(
            pace = ResponsePace.NORMAL,
            verbosity = ResponseVerbosity.TERSE,
            questionRatio = 0.1,
            structure = ResponseStructure.BULLETS,
        ),
        safety = safety(
            noDiagnosis = false,
            noManipulation = true,
            inviteDissent = true,
            escalateRisk = false,
            labelHypotheses = false,
        ),
    ),
    InteractionKind.ORCHESTRATE to InteractionProfile(
        context = contextWeights(0.1, 0.3, 0.05, 0.1, 0.25, 0.2, 0.0),
        memory = memoryWeights(0.2, 0.15, 0.2, 0.35, 0.05),
        moves = listOf(
            InteractionMove.PLAN,
            InteractionMove.ASSIGN,
            InteractionMove.COORDINATE,
            InteractionMove.CHECKIN,
            InteractionMove.HANDOFF,
        ),
        attention = InteractionAttention(breadth = 0.6, depth = 0.5, switchTolerance = 0.9),
        response = InteractionResponse(
            pace = ResponsePace.NORMAL,
            verbosity = ResponseVerbosity.NORMAL,
            questionRatio = 0.25,
            structure = ResponseStructure.BULLETS,
        ),
        safety = safety(
            noDiagnosis = false,
            noManipulation = true,
            inviteDissent = true,
            escalateRisk = false,
            labelHypotheses = false,
        ),
    ),
)

private fun clamp01(value: Double): Double = Math.max(0.0, Math.min(1.0, value))

private fun fixed2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

private val DefaultContextWeights: Map<InteractionContextKey, Double> = contextWeights(
    self = 1.0,
    role = 0.0,
    affect = 0.0,
    memory = 0.0,
    relationship = 0.0,
    membership = 0.0,
    background = 0.0,
)

private fun normalizeContext(weights: Map<InteractionContextKey, Double>): Map<InteractionContextKey, Double> {
    var total = 0.0
    for (value in weights.values) {
        total += Math.max(0.0, value)
    }
    if (total <= 0.0) {
        return LinkedHashMap(DefaultContextWeights)
    }
    val result = LinkedHashMap<InteractionContextKey, Double>()
    for (key in weights.keys) {
        result[key] = Math.max(0.0, weights.getValue(key)) / total
    }
    return result
}

private fun weightedContext(
    weights: Map<InteractionKind, Double>,
    read: (InteractionProfile) -> Map<InteractionContextKey, Double>,
): Map<InteractionContextKey, Double> {
    val result = LinkedHashMap<InteractionContextKey, Double>()
    for (key in InteractionContextKey.entries) {
        result[key] = InteractionKind.entries.sumOf { kind ->
            weights.getValue(kind) * read(Profiles.getValue(kind)).getValue(key)
        }
    }
    return result
}

private fun weightedMemory(
    weights: Map<InteractionKind, Double>,
    read: (InteractionProfile) -> Map<MemoryKind, Double>,
): Map<MemoryKind, Double> {
    val result = LinkedHashMap<MemoryKind, Double>()
    for (key in MemoryKind.entries) {
        result[key] = InteractionKind.entries.sumOf { kind ->
            weights.getValue(kind) * read(Profiles.getValue(kind)).getValue(key)
        }
    }
    return result
}

/**
 * 投票：每个 kind 把它选的值加上自己的权重，取总分最高者；并列取先出现者
 * （参考实现用 `>` 比较，等价于第一个最大值）。
 */
private fun <K> vote(weights: Map<InteractionKind, Double>, read: (InteractionProfile) -> K): K {
    val scores = LinkedHashMap<K, Double>()
    for (kind in InteractionKind.entries) {
        val key: K = read(Profiles.getValue(kind))
        scores[key] = (scores[key] ?: 0.0) + weights.getValue(kind)
    }
    var best: K? = null
    var bestScore = -1.0
    for ((key, score) in scores) {
        if (score > bestScore) {
            best = key
            bestScore = score
        }
    }
    return best ?: throw IllegalArgumentException("interaction vote has no candidate")
}

private fun moveScores(weights: Map<InteractionKind, Double>): Map<InteractionMove, Double> {
    val scores = LinkedHashMap<InteractionMove, Double>()
    for (kind in InteractionKind.entries) {
        for (move in Profiles.getValue(kind).moves) {
            scores[move] = (scores[move] ?: 0.0) + weights.getValue(kind)
        }
    }
    return scores
}

private fun sequenceFor(active: List<InteractionKind>): List<InteractionKind> {
    val order = listOf(
        InteractionKind.AFFECT,
        InteractionKind.INSPIRE,
        InteractionKind.THINK,
        InteractionKind.CONVERGE,
        InteractionKind.ORCHESTRATE,
    )
    val present = active.toSet()
    return order.filter { present.contains(it) }
}

private fun guardrailsFor(weights: Map<InteractionKind, Double>): List<String> {
    val result = ArrayList<String>()
    val affect: Double = weights.getValue(InteractionKind.AFFECT)
    if (affect > 0.05) {
        result.add("情感模式：只做倾听、标注、验证与陪伴；不诊断、不宣称治疗、不制造依赖。")
        if (affect > 0.4) {
            result.add("高风险信号（自伤/伤人/急性危机）必须升级给人类或紧急资源。")
        }
    }
    if (weights.getValue(InteractionKind.INSPIRE) > 0.05) {
        result.add("启发模式：把点子标为假设，限制数量，避免用大量选项淹没判断。")
    }
    if (weights.getValue(InteractionKind.THINK) > 0.05) {
        result.add("思维模式：区分事实/推断/未知，给出可检查的依据，不用流畅感代替正确性。")
    }
    if (weights.getValue(InteractionKind.CONVERGE) > 0.05) {
        result.add("收束模式：先给判据与取舍，再给结论；主动邀请反方意见，避免过早闭合。")
    }
    if (weights.getValue(InteractionKind.ORCHESTRATE) > 0.05) {
        result.add("统筹模式：明确 owner、依赖与共享状态；不隐藏资源分配与失败。")
    }
    return result
}

/** 归一化交互混合；零权重允许，负数与空混合被拒绝。 */
fun normalizeInteractionMix(mix: InteractionMix): Map<InteractionKind, Double> {
    val result = LinkedHashMap<InteractionKind, Double>()
    var total = 0.0
    for (kind in InteractionKind.entries) {
        val raw: Double = mix[kind] ?: 0.0
        if (!raw.isFinite() || raw < 0.0) {
            throw IllegalArgumentException(
                "interaction weight ${kind.wire} must be a finite non-negative number",
            )
        }
        result[kind] = raw
        total += raw
    }
    if (total <= 0.0) {
        throw IllegalArgumentException("interaction mix must contain at least one positive weight")
    }
    for (kind in InteractionKind.entries) {
        result[kind] = result.getValue(kind) / total
    }
    return result
}

/** 把混合解析成计划。 */
fun resolveInteraction(mix: InteractionMix): InteractionPlan {
    val weights: Map<InteractionKind, Double> = normalizeInteractionMix(mix)
    val active: List<InteractionKind> = InteractionKind.entries.filter { weights.getValue(it) >= 0.1 }
    val ranked: List<InteractionKind> = InteractionKind.entries.sortedByDescending { weights.getValue(it) }
    val primary: InteractionKind = ranked[0]
    val secondary: InteractionKind? = ranked.getOrNull(1)

    val contextWeights: Map<InteractionContextKey, Double> =
        normalizeContext(weightedContext(weights) { it.context })
    val memoryKindWeights: Map<MemoryKind, Double> =
        weightedMemory(weights) { it.memory }

    val attention = InteractionAttention(
        breadth = clamp01(InteractionKind.entries.sumOf { weights.getValue(it) * Profiles.getValue(it).attention.breadth }),
        depth = clamp01(InteractionKind.entries.sumOf { weights.getValue(it) * Profiles.getValue(it).attention.depth }),
        switchTolerance = clamp01(
            InteractionKind.entries.sumOf { weights.getValue(it) * Profiles.getValue(it).attention.switchTolerance },
        ),
    )
    val response = InteractionResponse(
        pace = vote(weights) { it.response.pace },
        verbosity = vote(weights) { it.response.verbosity },
        questionRatio = clamp01(
            InteractionKind.entries.sumOf { weights.getValue(it) * Profiles.getValue(it).response.questionRatio },
        ),
        structure = vote(weights) { it.response.structure },
    )
    fun dominates(pick: (InteractionSafety) -> Boolean): Boolean =
        InteractionKind.entries.any { weights.getValue(it) >= 0.1 && pick(Profiles.getValue(it).safety) }

    val safety = InteractionSafety(
        noDiagnosis = dominates { it.noDiagnosis },
        noManipulation = dominates { it.noManipulation },
        inviteDissent = dominates { it.inviteDissent },
        escalateRisk = dominates { it.escalateRisk },
        labelHypotheses = dominates { it.labelHypotheses },
    )
    val moves: List<InteractionMove> = moveScores(weights).entries
        .filter { it.value > 0.0 }
        .sortedWith(
            compareByDescending<Map.Entry<InteractionMove, Double>> { it.value }
                .thenBy { it.key.wire },
        )
        .take(10)
        .map { it.key }
    val activeText: String = if (active.isEmpty()) {
        "no active mode"
    } else {
        active.joinToString(" + ") { "${it.wire} ${fixed2(weights.getValue(it))}" }
    }
    val rationale: String = "$activeText; moves ${moves.joinToString(", ") { it.wire }}; " +
        "breadth ${fixed2(attention.breadth)}, depth ${fixed2(attention.depth)}"
    return InteractionPlan(
        weights = weights,
        active = active,
        primary = primary,
        secondary = secondary,
        sequence = sequenceFor(active),
        moves = moves,
        contextWeights = contextWeights,
        memoryKindWeights = memoryKindWeights,
        attention = attention,
        response = response,
        safety = safety,
        guardrails = guardrailsFor(weights),
        rationale = rationale,
    )
}

/**
 * 把 scenario 与 interaction 的 context 权重按加权混合。
 *
 * scenario 设定任务预算，interaction 重新加权其中的重要性。默认 40/60 让当前交互模式
 * 领先，同时不抹掉任务的领域需求；缺失维度使用另一个 plan 的值。
 */
fun combineContextWeights(
    scenario: Map<InteractionContextKey, Double>? = null,
    interaction: Map<InteractionContextKey, Double>? = null,
    scenarioWeight: Double = 0.4,
): Map<InteractionContextKey, Double> {
    val w: Double = Math.max(0.0, Math.min(1.0, scenarioWeight))
    val combined = LinkedHashMap<InteractionContextKey, Double>()
    var total = 0.0
    for (key in InteractionContextKey.entries) {
        val scenarioValue: Double? = scenario?.get(key)
        val interactionValue: Double? = interaction?.get(key)
        val value: Double = when {
            scenarioValue == null && interactionValue == null -> 1.0
            scenarioValue == null -> interactionValue!!
            interactionValue == null -> scenarioValue
            else -> w * scenarioValue + (1 - w) * interactionValue
        }
        combined[key] = Math.max(0.0, value)
        total += combined.getValue(key)
    }
    if (total <= 0.0) {
        return LinkedHashMap(DefaultContextWeights)
    }
    for (key in InteractionContextKey.entries) {
        combined[key] = combined.getValue(key) / total
    }
    return combined
}

/**
 * 混合记忆类别权重。
 *
 * 注意参考实现的不对称：两边都给出某类别时取 `max(0, blend)`；只有一边给出时直接使用
 * 该值（不再 clamp），两边都缺失时为 1；整个结果**不归一化**。这里逐字保留。
 */
fun combineMemoryWeights(
    scenario: Map<MemoryKind, Double>? = null,
    interaction: Map<MemoryKind, Double>? = null,
    scenarioWeight: Double = 0.4,
): Map<MemoryKind, Double>? {
    if (scenario == null && interaction == null) {
        return null
    }
    val w: Double = Math.max(0.0, Math.min(1.0, scenarioWeight))
    val combined = LinkedHashMap<MemoryKind, Double>()
    for (key in MemoryKind.entries) {
        val scenarioValue: Double? = scenario?.get(key)
        val interactionValue: Double? = interaction?.get(key)
        combined[key] = when {
            scenarioValue == null && interactionValue == null -> 1.0
            scenarioValue == null -> interactionValue!!
            interactionValue == null -> scenarioValue
            else -> Math.max(0.0, w * scenarioValue + (1 - w) * interactionValue)
        }
    }
    return combined
}

fun phaseFor(plan: InteractionPlan): InteractionPhase {
    if (plan.weights.getValue(InteractionKind.AFFECT) >= 0.4) {
        return InteractionPhase.OPEN
    }
    if (plan.weights.getValue(InteractionKind.INSPIRE) >= 0.4) {
        return InteractionPhase.EXPLORE
    }
    if (plan.weights.getValue(InteractionKind.THINK) >= 0.4) {
        return InteractionPhase.ANALYZE
    }
    if (plan.weights.getValue(InteractionKind.CONVERGE) >= 0.4) {
        return InteractionPhase.DECIDE
    }
    if (plan.weights.getValue(InteractionKind.ORCHESTRATE) >= 0.4) {
        return InteractionPhase.COORDINATE
    }
    return InteractionPhase.CLOSE
}

fun isSafeToClose(plan: InteractionPlan): Boolean =
    plan.safety.inviteDissent || plan.weights.getValue(InteractionKind.CONVERGE) < 0.5
