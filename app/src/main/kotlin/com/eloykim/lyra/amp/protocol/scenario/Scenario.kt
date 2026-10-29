package com.eloykim.lyra.amp.protocol.scenario

import com.eloykim.lyra.amp.protocol.attention.AttentionResourcePolicy
import com.eloykim.lyra.amp.protocol.attention.DefaultAttentionPolicy
import com.eloykim.lyra.amp.protocol.interaction.MemoryKind
import java.util.Locale

/**
 * `src/scenario/scenario.ts` 的忠实移植：场景是加权混合，而不是互斥模式。
 *
 * 一个任务可以 60% chat、30% engineering、10% collaboration。混合决定：值得加载哪些
 * context、哪些 memory 相关、token 乘数、注意力策略（前台上限、预留、休息节奏）以及
 * 输出风格/工具偏好。目标不是给任务贴标签，而是让不需要上下文的任务少花上下文，同时
 * 为混合任务保留相关侧面。
 *
 * ## 与既有窄端口的关系
 *
 * `runtime/Routing.kt` 已有一份只保留 `weights`/`active`/`tokenBudgetFactor`/
 * `contextDepth`/`mode`/`rationale` 的窄投影（自带同名类型）。本文件是完整移植，额外
 * 提供 `contextWeights`、`memoryKindWeights`、`attention`、`style`，以及
 * [contextBudgetTokens] / [scenarioAttentionPolicy] / [shouldUseBareContext]。
 *
 * ## 绑定
 *
 * - `MemoryKind` 复用 `interaction` 包里的窄局部模型（`src/process/agent.ts` 尚未移植）。
 * - 注意力策略直接使用已移植的 `attention.AttentionResourcePolicy`，默认值取
 *   `DefaultAttentionPolicy`，与参考实现的导入一致。
 */

enum class ScenarioKind(val wire: String) {
    CHAT("chat"),
    WRITING("writing"),
    OFFICE("office"),
    COLLABORATION("collaboration"),
    INVESTIGATION("investigation"),
    RESEARCH("research"),
    ENGINEERING("engineering"),
    ITERATION("iteration"),
    ;

    companion object {
        fun fromWire(value: String): ScenarioKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 scenario kind: $value")
    }
}

/** 参考的 `ScenarioMix = Partial<Record<ScenarioKind, number>>`：缺项即 0。 */
typealias ScenarioMix = Map<ScenarioKind, Double>

enum class ScenarioContextKey(val wire: String) {
    SELF("self"),
    ROLE("role"),
    AFFECT("affect"),
    MEMORY("memory"),
    RELATIONSHIP("relationship"),
    MEMBERSHIP("membership"),
    BACKGROUND("background"),
    ;
}

enum class ScenarioMode(val wire: String) {
    BARE("bare"),
    COMPACT("compact"),
    FULL("full"),
}

enum class ScenarioVerbosity(val wire: String) {
    TERSE("terse"),
    NORMAL("normal"),
    DETAILED("detailed"),
}

enum class ToolBias(val wire: String) {
    NONE("none"),
    LIGHT("light"),
    HEAVY("heavy"),
}

data class ScenarioAttention(
    val maxForegroundUtil: Double,
    val reserveRatio: Double,
    val minSliceSlots: Int,
    val restAfterCycles: Int,
)

data class ScenarioStyle(
    val verbosity: ScenarioVerbosity,
    val toolBias: ToolBias,
)

data class ScenarioProfile(
    val context: Map<ScenarioContextKey, Double>,
    val memory: Map<MemoryKind, Double>,
    val tokenFactor: Double,
    val contextDepth: Double,
    val attention: ScenarioAttention,
    val style: ScenarioStyle,
)

data class ScenarioPlan(
    /** 归一化权重；总和为 1。 */
    val weights: Map<ScenarioKind, Double>,
    val active: List<ScenarioKind>,
    val contextWeights: Map<ScenarioContextKey, Double>,
    val memoryKindWeights: Map<MemoryKind, Double>,
    val tokenBudgetFactor: Double,
    val contextDepth: Double,
    val mode: ScenarioMode,
    val attention: ScenarioAttention,
    val style: ScenarioStyle,
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
): Map<ScenarioContextKey, Double> = linkedMapOf(
    ScenarioContextKey.SELF to self,
    ScenarioContextKey.ROLE to role,
    ScenarioContextKey.AFFECT to affect,
    ScenarioContextKey.MEMORY to memory,
    ScenarioContextKey.RELATIONSHIP to relationship,
    ScenarioContextKey.MEMBERSHIP to membership,
    ScenarioContextKey.BACKGROUND to background,
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

private fun attention(
    maxForegroundUtil: Double,
    reserveRatio: Double,
    minSliceSlots: Int,
    restAfterCycles: Int,
): ScenarioAttention = ScenarioAttention(
    maxForegroundUtil = maxForegroundUtil,
    reserveRatio = reserveRatio,
    minSliceSlots = minSliceSlots,
    restAfterCycles = restAfterCycles,
)

private val Profiles: Map<ScenarioKind, ScenarioProfile> = linkedMapOf(
    ScenarioKind.CHAT to ScenarioProfile(
        context = contextWeights(0.15, 0.05, 0.3, 0.35, 0.25, 0.0, 0.05),
        memory = memoryWeights(0.3, 0.1, 0.05, 0.5, 0.3),
        tokenFactor = 0.4,
        contextDepth = 0.2,
        attention = attention(0.5, 0.35, 1, 10),
        style = ScenarioStyle(ScenarioVerbosity.TERSE, ToolBias.NONE),
    ),
    ScenarioKind.WRITING to ScenarioProfile(
        context = contextWeights(0.35, 0.2, 0.1, 0.25, 0.05, 0.0, 0.05),
        memory = memoryWeights(0.2, 0.4, 0.3, 0.05, 0.05),
        tokenFactor = 0.8,
        contextDepth = 0.5,
        attention = attention(0.7, 0.25, 1, 8),
        style = ScenarioStyle(ScenarioVerbosity.NORMAL, ToolBias.LIGHT),
    ),
    ScenarioKind.OFFICE to ScenarioProfile(
        context = contextWeights(0.2, 0.3, 0.1, 0.25, 0.1, 0.15, 0.05),
        memory = memoryWeights(0.2, 0.35, 0.3, 0.1, 0.05),
        tokenFactor = 0.7,
        contextDepth = 0.6,
        attention = attention(0.65, 0.3, 1, 8),
        style = ScenarioStyle(ScenarioVerbosity.NORMAL, ToolBias.LIGHT),
    ),
    ScenarioKind.COLLABORATION to ScenarioProfile(
        context = contextWeights(0.15, 0.25, 0.2, 0.15, 0.3, 0.15, 0.05),
        memory = memoryWeights(0.2, 0.15, 0.1, 0.4, 0.15),
        tokenFactor = 0.8,
        contextDepth = 0.7,
        attention = attention(0.7, 0.3, 1, 8),
        style = ScenarioStyle(ScenarioVerbosity.NORMAL, ToolBias.LIGHT),
    ),
    ScenarioKind.INVESTIGATION to ScenarioProfile(
        context = contextWeights(0.1, 0.15, 0.05, 0.35, 0.15, 0.1, 0.1),
        memory = memoryWeights(0.4, 0.2, 0.15, 0.15, 0.1),
        tokenFactor = 1.2,
        contextDepth = 0.9,
        attention = attention(0.8, 0.2, 2, 6),
        style = ScenarioStyle(ScenarioVerbosity.DETAILED, ToolBias.HEAVY),
    ),
    ScenarioKind.RESEARCH to ScenarioProfile(
        context = contextWeights(0.2, 0.15, 0.05, 0.45, 0.05, 0.05, 0.05),
        memory = memoryWeights(0.15, 0.5, 0.2, 0.05, 0.05),
        tokenFactor = 1.3,
        contextDepth = 0.9,
        attention = attention(0.8, 0.2, 2, 8),
        style = ScenarioStyle(ScenarioVerbosity.DETAILED, ToolBias.HEAVY),
    ),
    ScenarioKind.ENGINEERING to ScenarioProfile(
        context = contextWeights(0.15, 0.2, 0.1, 0.35, 0.05, 0.05, 0.1),
        memory = memoryWeights(0.2, 0.25, 0.4, 0.05, 0.1),
        tokenFactor = 1.1,
        contextDepth = 0.85,
        attention = attention(0.8, 0.2, 2, 6),
        style = ScenarioStyle(ScenarioVerbosity.NORMAL, ToolBias.HEAVY),
    ),
    ScenarioKind.ITERATION to ScenarioProfile(
        context = contextWeights(0.15, 0.2, 0.2, 0.35, 0.05, 0.05, 0.1),
        memory = memoryWeights(0.4, 0.1, 0.3, 0.05, 0.15),
        tokenFactor = 0.8,
        contextDepth = 0.7,
        attention = attention(0.7, 0.25, 2, 5),
        style = ScenarioStyle(ScenarioVerbosity.TERSE, ToolBias.LIGHT),
    ),
)

private fun clamp(value: Double, min: Double, max: Double): Double = Math.max(min, Math.min(max, value))

private fun clamp01(value: Double): Double = clamp(value, 0.0, 1.0)

private fun fixed2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

private val DefaultContextWeights: Map<ScenarioContextKey, Double> = contextWeights(
    self = 1.0,
    role = 0.0,
    affect = 0.0,
    memory = 0.0,
    relationship = 0.0,
    membership = 0.0,
    background = 0.0,
)

private fun normalizeContext(weights: Map<ScenarioContextKey, Double>): Map<ScenarioContextKey, Double> {
    var total = 0.0
    for (value in weights.values) {
        total += Math.max(0.0, value)
    }
    if (total <= 0.0) {
        return LinkedHashMap(DefaultContextWeights)
    }
    val result = LinkedHashMap<ScenarioContextKey, Double>()
    for (key in weights.keys) {
        result[key] = Math.max(0.0, weights.getValue(key)) / total
    }
    return result
}

private fun weightedContext(weights: Map<ScenarioKind, Double>): Map<ScenarioContextKey, Double> {
    val result = LinkedHashMap<ScenarioContextKey, Double>()
    for (key in ScenarioContextKey.entries) {
        result[key] = ScenarioKind.entries.sumOf { kind ->
            weights.getValue(kind) * Profiles.getValue(kind).context.getValue(key)
        }
    }
    return result
}

private fun weightedMemory(weights: Map<ScenarioKind, Double>): Map<MemoryKind, Double> {
    val result = LinkedHashMap<MemoryKind, Double>()
    for (key in MemoryKind.entries) {
        result[key] = ScenarioKind.entries.sumOf { kind ->
            weights.getValue(kind) * Profiles.getValue(kind).memory.getValue(key)
        }
    }
    return result
}

private fun <K> vote(weights: Map<ScenarioKind, Double>, read: (ScenarioProfile) -> K): K {
    val scores = LinkedHashMap<K, Double>()
    for (kind in ScenarioKind.entries) {
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
    return best ?: throw IllegalArgumentException("scenario vote has no candidate")
}

/**
 * 归一化混合；零权重允许，负数与空混合被拒绝。
 */
fun normalizeScenarioMix(mix: ScenarioMix): Map<ScenarioKind, Double> {
    val result = LinkedHashMap<ScenarioKind, Double>()
    var total = 0.0
    for (kind in ScenarioKind.entries) {
        val raw: Double = mix[kind] ?: 0.0
        if (!raw.isFinite() || raw < 0.0) {
            throw IllegalArgumentException("scenario weight ${kind.wire} must be a finite non-negative number")
        }
        result[kind] = raw
        total += raw
    }
    if (total <= 0.0) {
        throw IllegalArgumentException("scenario mix must contain at least one positive weight")
    }
    for (kind in ScenarioKind.entries) {
        result[kind] = result.getValue(kind) / total
    }
    return result
}

/** 把混合解析成计划。 */
fun resolveScenario(mix: ScenarioMix): ScenarioPlan {
    val weights: Map<ScenarioKind, Double> = normalizeScenarioMix(mix)
    val contextRaw: Map<ScenarioContextKey, Double> = weightedContext(weights)
    val memoryKindWeights: Map<MemoryKind, Double> = weightedMemory(weights)
    val tokenBudgetFactor: Double = clamp(
        ScenarioKind.entries.sumOf { weights.getValue(it) * Profiles.getValue(it).tokenFactor },
        0.3,
        1.5,
    )
    val contextDepth: Double = clamp01(
        ScenarioKind.entries.sumOf { weights.getValue(it) * Profiles.getValue(it).contextDepth },
    )
    val attention = ScenarioAttention(
        maxForegroundUtil = clamp(
            ScenarioKind.entries.sumOf { weights.getValue(it) * Profiles.getValue(it).attention.maxForegroundUtil },
            0.4,
            0.9,
        ),
        reserveRatio = clamp(
            ScenarioKind.entries.sumOf { weights.getValue(it) * Profiles.getValue(it).attention.reserveRatio },
            0.15,
            0.4,
        ),
        minSliceSlots = Math.max(
            1,
            Math.round(
                ScenarioKind.entries.sumOf { weights.getValue(it) * Profiles.getValue(it).attention.minSliceSlots },
            ).toInt(),
        ),
        restAfterCycles = Math.max(
            3,
            Math.round(
                ScenarioKind.entries.sumOf { weights.getValue(it) * Profiles.getValue(it).attention.restAfterCycles },
            ).toInt(),
        ),
    )
    val active: List<ScenarioKind> = ScenarioKind.entries.filter { weights.getValue(it) >= 0.1 }
    val mode: ScenarioMode = when {
        contextDepth < 0.35 -> ScenarioMode.BARE
        contextDepth < 0.7 -> ScenarioMode.COMPACT
        else -> ScenarioMode.FULL
    }
    val style = ScenarioStyle(
        verbosity = vote(weights) { it.style.verbosity },
        toolBias = vote(weights) { it.style.toolBias },
    )
    val activeText: String = if (active.isEmpty()) {
        "no active scenario"
    } else {
        active.joinToString(" + ") { "${it.wire} ${fixed2(weights.getValue(it))}" }
    }
    val rationale: String = "$activeText -> ${mode.wire}; token x${fixed2(tokenBudgetFactor)}; " +
        "context depth ${fixed2(contextDepth)}"
    return ScenarioPlan(
        weights = weights,
        active = active,
        contextWeights = normalizeContext(contextRaw),
        memoryKindWeights = memoryKindWeights,
        tokenBudgetFactor = tokenBudgetFactor,
        contextDepth = contextDepth,
        mode = mode,
        attention = attention,
        style = style,
        rationale = rationale,
    )
}

/**
 * 按 token 乘数缩放基础预算；下限 32，与参考实现的 `Math.max(32, Math.round(...))`
 * 一致。负值与非有限值被拒绝。
 */
fun contextBudgetTokens(plan: ScenarioPlan, baseTokens: Double): Int {
    if (!baseTokens.isFinite() || baseTokens < 0.0) {
        throw IllegalArgumentException("baseTokens must be a non-negative finite number")
    }
    return Math.max(32, Math.round(baseTokens * plan.tokenBudgetFactor).toInt())
}

/** `number` 是 JS 唯一的数值类型；整数调用点是常态，这里补一个便利重载。 */
fun contextBudgetTokens(plan: ScenarioPlan, baseTokens: Int): Int = contextBudgetTokens(plan, baseTokens.toDouble())

/** 用场景的注意力参数覆盖基础策略的四个字段，其余字段保持基础策略。 */
fun scenarioAttentionPolicy(
    plan: ScenarioPlan,
    base: AttentionResourcePolicy = DefaultAttentionPolicy,
): AttentionResourcePolicy = base.copy(
    maxForegroundUtil = plan.attention.maxForegroundUtil,
    reserveRatio = plan.attention.reserveRatio,
    minSliceSlots = plan.attention.minSliceSlots,
    restAfterCycles = plan.attention.restAfterCycles,
)

fun shouldUseBareContext(plan: ScenarioPlan): Boolean = plan.mode == ScenarioMode.BARE
