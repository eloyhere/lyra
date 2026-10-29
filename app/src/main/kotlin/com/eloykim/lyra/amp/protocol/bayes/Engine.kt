package com.eloykim.lyra.amp.protocol.bayes

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import java.util.Locale

/**
 * Bayesian scoring for interaction evidence.
 *
 * 三族潜变量：
 * - 用户输入：clarity / specificity / contextSufficiency / emotionalIntensity / urgency / openness
 * - 回答质量：correctness / grounding / coherence / conciseness / calibration / safety / actionability / helpfulness
 * - 注意力：load / vigilance / stress / fatigue / recoveryDebt
 *
 * 每个维度都是一个 Beta 信念，用分级（graded）证据更新：证据按来源可靠度折算，
 * 先验默认很弱（2/2）。这是打分模型，不是“agent 有情绪”的主张，也不是说单一
 * 分数就是真相。对应参考实现 `src/bayes/engine.ts`。
 *
 * 与参考实现的一处刻意差异：参考 `BayesianEngine` 浅拷贝 `DefaultPriors`，因此
 * `learn()` 会改写全局默认先验表；Kotlin 侧 [BetaBelief] 不可变，每个引擎持有
 * 自己的副本，学习不会跨实例泄漏。
 */
enum class DimensionFamily { USER, ANSWER, ATTENTION }

/** 19 个潜变量维度，`wire` 与参考实现的字符串字面量一致（camelCase）。 */
enum class Dimension(val wire: String, val family: DimensionFamily) {
    CLARITY("clarity", DimensionFamily.USER),
    SPECIFICITY("specificity", DimensionFamily.USER),
    CONTEXT_SUFFICIENCY("contextSufficiency", DimensionFamily.USER),
    EMOTIONAL_INTENSITY("emotionalIntensity", DimensionFamily.USER),
    URGENCY("urgency", DimensionFamily.USER),
    OPENNESS("openness", DimensionFamily.USER),
    CORRECTNESS("correctness", DimensionFamily.ANSWER),
    GROUNDING("grounding", DimensionFamily.ANSWER),
    COHERENCE("coherence", DimensionFamily.ANSWER),
    CONCISENESS("conciseness", DimensionFamily.ANSWER),
    CALIBRATION("calibration", DimensionFamily.ANSWER),
    SAFETY("safety", DimensionFamily.ANSWER),
    ACTIONABILITY("actionability", DimensionFamily.ANSWER),
    HELPFULNESS("helpfulness", DimensionFamily.ANSWER),
    LOAD("load", DimensionFamily.ATTENTION),
    VIGILANCE("vigilance", DimensionFamily.ATTENTION),
    STRESS("stress", DimensionFamily.ATTENTION),
    FATIGUE("fatigue", DimensionFamily.ATTENTION),
    RECOVERY_DEBT("recoveryDebt", DimensionFamily.ATTENTION),
    ;

    companion object {
        fun fromWire(value: String): Dimension =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 bayes dimension: $value")
    }
}

val UserDimensions: List<Dimension> = Dimension.entries.filter { it.family == DimensionFamily.USER }
val AnswerDimensions: List<Dimension> = Dimension.entries.filter { it.family == DimensionFamily.ANSWER }
val AttentionDimensions: List<Dimension> = Dimension.entries.filter { it.family == DimensionFamily.ATTENTION }

/** 每个维度的默认弱先验：Beta(2, 2)，均值 0.5、方差有限。 */
val DefaultPriors: Map<Dimension, BetaBelief> = Dimension.entries.associateWith { BetaBelief(2.0, 2.0) }

/** Beta 后验：参数、均值、方差、近似 95% 可信区间与参与的证据条数。 */
data class Posterior(
    val alpha: Double,
    val beta: Double,
    val mean: Double,
    val variance: Double,
    val intervalLow: Double,
    val intervalHigh: Double,
    val evidenceCount: Int,
) {

    /** 近似 95% 可信区间，顺序为 `[low, high]`。 */
    val interval: List<Double>
        get() = listOf(intervalLow, intervalHigh)
}

/**
 * 一条分级证据。
 *
 * `reliability` ∈ [0,1]；自述与弱启发式应被折算。`positive` / `negative` 是
 * 伪计数，允许大于 1（强检查器给 5），负值在更新时按 0 处理。
 */
data class Evidence(
    val dimension: Dimension,
    val positive: Double,
    val negative: Double,
    val reliability: Double,
    val source: String,
    val note: String? = null,
)

data class UserFeatures(
    val words: Int,
    val questions: Int,
    val numbers: Int,
    val affectHits: Int,
    val urgencyHits: Int,
    val vagueHits: Int,
    val calmHits: Int,
    val opennessHits: Int,
    val closureHits: Int,
    val constraints: Int,
    val exclamations: Int,
    val capsRatio: Double,
)

data class AnswerFeatures(
    val words: Int,
    val sentences: Int,
    val structure: Boolean,
    val refs: Int,
    val numbers: Int,
    val hedges: Int,
    val overclaims: Int,
    val actions: Int,
    val vague: Int,
    val repeatRatio: Double,
    val refusal: Boolean,
    val escalation: Boolean,
)

/**
 * 回答的元数据。
 *
 * `correct` / `safe` / `helpful` 为 `null` 表示没有检查器或反馈，此时走弱启发式；
 * 参考实现里的 `grounded` 字段保留但当前证据构造不使用它，行为保持一致。
 */
data class AnswerMeta(
    val correct: Boolean? = null,
    val grounded: Boolean? = null,
    val safe: Boolean? = null,
    val uncertain: Boolean? = null,
    val targetTokens: Int? = null,
    val helpful: Boolean? = null,
)

data class AttentionMetrics(
    val utilization: Double,
    val pressure: Double,
    val recoveryDebt: Double,
    val errors: Int,
    val switches: Int,
    val latencyMs: Long? = null,
    val targetLatencyMs: Long? = null,
    val vigilance: Double? = null,
    val stress: Double? = null,
    val fatigue: Double? = null,
)

/** 输入质量的状态信号，不是对人的评价。 */
data class UserState(
    val emotionalIntensity: Double,
    val urgency: Double,
    val openness: Double,
)

data class UserScore(
    val dimensions: Map<Dimension, Posterior>,
    val quality: Double,
    val state: UserState,
    val evidence: List<Evidence>,
)

data class AnswerScore(
    val dimensions: Map<Dimension, Posterior>,
    val overall: Double,
    val evidence: List<Evidence>,
)

data class AttentionScore(
    val dimensions: Map<Dimension, Posterior>,
    val health: Double,
    val evidence: List<Evidence>,
)

data class InteractionFit(
    val score: Double,
    val reasons: List<String>,
)

/** `scoreUser` 的每次调用设置。 */
data class ScoreUserMeta(
    val reliability: Double? = null,
)

/**
 * 交互权重中 `interactionFit` 读取的 5 个分量。
 *
 * 参考实现取 `InteractionPlan.weights` 的完整 `Record<InteractionKind, number>`；
 * 这里只保留打分用到的子集，避免 bayes 依赖 interaction 包。
 */
data class InteractionWeights(
    val affect: Double = 0.0,
    val converge: Double = 0.0,
    val inspire: Double = 0.0,
    val think: Double = 0.0,
    val orchestrate: Double = 0.0,
) {

    companion object {
        fun fromMap(weights: Map<String, Double>): InteractionWeights = InteractionWeights(
            affect = weights["affect"] ?: 0.0,
            converge = weights["converge"] ?: 0.0,
            inspire = weights["inspire"] ?: 0.0,
            think = weights["think"] ?: 0.0,
            orchestrate = weights["orchestrate"] ?: 0.0,
        )
    }
}

/** JavaScript `Math.max(0, Math.min(1, value))`，NaN 原样传播。 */
fun clamp01(value: Double): Double = Math.max(0.0, Math.min(1.0, value))

/**
 * 按证据更新一个维度的后验。
 *
 * 来源权重与证据可靠度都先夹到 [0,1] 再相乘；只累计 `dimension` 匹配的条目。
 */
fun posteriorOf(
    prior: BetaBelief,
    evidence: List<Evidence>,
    dimension: Dimension,
    sourceWeights: Map<String, Double> = emptyMap(),
): Posterior {
    var alpha = prior.alpha
    var beta = prior.beta
    var used = 0
    evidence.forEach { item ->
        if (item.dimension != dimension) return@forEach
        val weight = clamp01(item.reliability) * clamp01(sourceWeights[item.source] ?: 1.0)
        alpha += max(0.0, item.positive) * weight
        beta += max(0.0, item.negative) * weight
        used += 1
    }
    val belief = BetaBelief(alpha, beta)
    val mean = belief.mean
    val variance = belief.variance
    val sd = sqrt(variance)
    return Posterior(
        alpha = alpha,
        beta = beta,
        mean = mean,
        variance = variance,
        intervalLow = clamp01(mean - 1.96 * sd),
        intervalHigh = clamp01(mean + 1.96 * sd),
        evidenceCount = used,
    )
}

// ---------------------------------------------------------------------------
// 特征抽取
// ---------------------------------------------------------------------------

/** JavaScript `\s`（含 NBSP、全角空格等）对应的切分模式。 */
private val JsWhitespace = Regex("[\\t\\n\\u000B\\u000C\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

private val AffectLexicon =
    Regex("\\b(anxious|afraid|angry|furious|frustrated|overwhelmed|hurt|sad|lonely|excited|happy|grateful|worried|stressed|burned out|exhausted)\\b", RegexOption.IGNORE_CASE)
private val UrgencyLexicon =
    Regex("\\b(now|asap|urgent|urgently|deadline|today|immediately|right away|blocker|blocked)\\b", RegexOption.IGNORE_CASE)
private val VagueLexicon =
    Regex("\\b(stuff|things|something|somehow|whatever|etc|you know|kind of|sort of)\\b", RegexOption.IGNORE_CASE)
private val CalmLexicon =
    Regex("\\b(fine|okay|no rush|whenever|later|calm|relaxed)\\b", RegexOption.IGNORE_CASE)
private val OpennessLexicon =
    Regex("\\b(maybe|perhaps|options|alternatives|explore|brainstorm|ideas|open to|not sure)\\b", RegexOption.IGNORE_CASE)
private val ClosureLexicon =
    Regex("\\b(must|only|final|decide|exactly|no other)\\b", RegexOption.IGNORE_CASE)
private val ConstraintLexicon =
    Regex("\\b(within|at most|at least|must|only|before|after|because|given|constraint)\\b", RegexOption.IGNORE_CASE)
private val HedgeLexicon =
    Regex("\\b(maybe|perhaps|might|could|likely|probably|uncertain|not sure|I think|appears)\\b", RegexOption.IGNORE_CASE)
private val OverclaimLexicon =
    Regex("\\b(definitely|certainly|always|never|guaranteed|proven|obviously|undoubtedly)\\b", RegexOption.IGNORE_CASE)
private val ActionLexicon =
    Regex("\\b(run|check|verify|send|open|test|measure|compare|write|fix|review|ask|schedule|assign|rollback)\\b", RegexOption.IGNORE_CASE)
private val StructurePattern = Regex("(^|\\n)\\s*([-*]|\\d+\\.)\\s+", RegexOption.MULTILINE)
private val BlankLinePattern = Regex("\\n\\s*\\n")
private val ReferenceLexicon = Regex("\\b(https?://|doi:|source:|ref:|\\[\\d+\\])", RegexOption.IGNORE_CASE)
private val NumberPattern = Regex("\\b\\d+(?:\\.\\d+)?\\b")
private val QuestionPattern = Regex("\\?")
private val ExclamationPattern = Regex("!")
private val SentenceEndPattern = Regex("[.!?](\\s|$)")
private val RefusalPattern = Regex("\\b(cannot|can't|unable|refuse|not able)\\b", RegexOption.IGNORE_CASE)
private val EscalationPattern = Regex("\\b(emergency|human|professional|hotline|crisis)\\b", RegexOption.IGNORE_CASE)

private fun count(text: String, pattern: Regex): Int = pattern.findAll(text).count()

private fun wordsOf(text: String): List<String> {
    val trimmed = text.trim()
    return if (trimmed.isEmpty()) emptyList() else trimmed.split(JsWhitespace)
}

fun extractUserFeatures(text: String): UserFeatures {
    val words = wordsOf(text)
    val letters = text.count { it in 'A'..'Z' || it in 'a'..'z' }
    val caps = text.count { it in 'A'..'Z' }
    return UserFeatures(
        words = words.size,
        questions = count(text, QuestionPattern),
        numbers = count(text, NumberPattern),
        affectHits = count(text, AffectLexicon),
        urgencyHits = count(text, UrgencyLexicon),
        vagueHits = count(text, VagueLexicon),
        calmHits = count(text, CalmLexicon),
        opennessHits = count(text, OpennessLexicon),
        closureHits = count(text, ClosureLexicon),
        constraints = count(text, ConstraintLexicon),
        exclamations = count(text, ExclamationPattern),
        capsRatio = if (letters == 0) 0.0 else caps.toDouble() / letters,
    )
}

fun extractAnswerFeatures(text: String): AnswerFeatures {
    val words = wordsOf(text)
    val sentences = max(1, count(text, SentenceEndPattern))
    val unique = words.map { it.lowercase(Locale.ROOT) }.toSet()
    return AnswerFeatures(
        words = words.size,
        sentences = sentences,
        structure = StructurePattern.containsMatchIn(text) || BlankLinePattern.containsMatchIn(text),
        refs = count(text, ReferenceLexicon),
        numbers = count(text, NumberPattern),
        hedges = count(text, HedgeLexicon),
        overclaims = count(text, OverclaimLexicon),
        actions = count(text, ActionLexicon),
        vague = count(text, VagueLexicon),
        repeatRatio = if (words.isEmpty()) 0.0 else 1.0 - unique.size.toDouble() / words.size,
        refusal = RefusalPattern.containsMatchIn(text),
        escalation = EscalationPattern.containsMatchIn(text),
    )
}

// ---------------------------------------------------------------------------
// 特征 -> 证据
// ---------------------------------------------------------------------------

internal fun userEvidence(features: UserFeatures): List<Evidence> {
    val clarityPositive =
        (if (features.questions > 0) 0.6 else 0.0) +
            (if (features.words >= 8) 0.2 else 0.0) +
            (if (features.vagueHits == 0) 0.2 else 0.0)
    val clarityNegative = features.vagueHits * 0.35 + (if (features.words < 3) 0.3 else 0.0)
    val specificityPositive = min(1.0, features.numbers * 0.2 + features.constraints * 0.15)
    val specificityNegative = features.vagueHits * 0.3
    val contextPositive = min(1.0, features.words / 80.0) * 0.5 + min(0.3, features.constraints * 0.1)
    val emotionalPositive = min(
        1.0,
        features.affectHits * 0.3 + features.exclamations * 0.08 + features.capsRatio * 1.2,
    )
    val emotionalNegative = features.calmHits * 0.15
    val urgencyPositive = min(1.0, features.urgencyHits * 0.4)
    val urgencyNegative = features.calmHits * 0.2
    val opennessPositive = min(1.0, features.opennessHits * 0.3)
    val opennessNegative = features.closureHits * 0.15
    return listOf(
        Evidence(Dimension.CLARITY, clarityPositive, clarityNegative, 0.7, "text"),
        Evidence(Dimension.SPECIFICITY, specificityPositive, specificityNegative, 0.7, "text"),
        Evidence(Dimension.CONTEXT_SUFFICIENCY, contextPositive, 0.0, 0.6, "text"),
        Evidence(Dimension.EMOTIONAL_INTENSITY, emotionalPositive, emotionalNegative, 0.55, "text"),
        Evidence(Dimension.URGENCY, urgencyPositive, urgencyNegative, 0.6, "text"),
        Evidence(Dimension.OPENNESS, opennessPositive, opennessNegative, 0.55, "text"),
    )
}

internal fun answerEvidence(text: String, meta: AnswerMeta = AnswerMeta()): List<Evidence> {
    val features = extractAnswerFeatures(text)
    val target = meta.targetTokens ?: 200
    val correctnessPositive = when {
        meta.correct == true -> 5.0
        meta.correct == null -> when {
            features.numbers > 0 -> 0.3
            features.words <= 40 -> 0.2
            else -> 0.0
        }

        else -> 0.0
    }
    val correctnessNegative = when {
        meta.correct == false -> 5.0
        meta.correct == null -> features.vague * 0.2 + features.overclaims * 0.2
        else -> 0.0
    }
    return listOf(
        Evidence(
            Dimension.CORRECTNESS,
            correctnessPositive,
            correctnessNegative,
            if (meta.correct == null) 0.35 else 1.0,
            if (meta.correct == null) "heuristic" else "checker",
        ),
        Evidence(
            Dimension.GROUNDING,
            min(1.0, features.refs * 0.4 + features.numbers * 0.15),
            features.vague * 0.2,
            0.65,
            "text",
        ),
        Evidence(
            Dimension.COHERENCE,
            (if (features.structure) 0.4 else 0.0) + min(0.4, features.words / 200.0) +
                (if (features.hedges == 0) 0.1 else 0.0),
            features.repeatRatio * 0.8 + (if (features.words < 5) 0.4 else 0.0),
            0.6,
            "text",
        ),
        Evidence(
            Dimension.CONCISENESS,
            if (features.words <= target) 0.6 else 0.0,
            if (features.words > target * 2) 0.8 else 0.0,
            0.7,
            "length",
        ),
        Evidence(
            Dimension.CALIBRATION,
            (if (meta.uncertain == true && features.hedges > 0) 0.6 else 0.0) +
                (if (meta.uncertain == false && features.hedges == 0) 0.3 else 0.0),
            features.overclaims * 0.5,
            0.7,
            "text",
        ),
        Evidence(
            Dimension.SAFETY,
            (if (meta.safe == true) 3.0 else 0.0) +
                (if (features.refusal) 0.4 else 0.0) +
                (if (features.escalation) 0.5 else 0.0),
            if (meta.safe == false) 5.0 else 0.0,
            if (meta.safe == null) 0.4 else 0.95,
            if (meta.safe == null) "heuristic" else "checker",
        ),
        Evidence(
            Dimension.ACTIONABILITY,
            min(1.0, features.actions * 0.2),
            features.vague * 0.15,
            0.55,
            "text",
        ),
        Evidence(
            Dimension.HELPFULNESS,
            if (meta.helpful == true) 4.0 else 0.0,
            if (meta.helpful == false) 4.0 else 0.0,
            if (meta.helpful == null) 0.2 else 0.9,
            if (meta.helpful == null) "none" else "feedback",
        ),
    )
}

internal fun attentionEvidence(metrics: AttentionMetrics): List<Evidence> {
    val target = metrics.targetLatencyMs ?: 2000L
    val latency = metrics.latencyMs ?: 0L
    val highLoad =
        (if (metrics.utilization > 0.75) 0.5 else 0.0) +
            (if (metrics.pressure > 0.5) 0.4 else 0.0) +
            (if (metrics.errors > 0) 0.3 else 0.0) +
            (if (latency > target) 0.3 else 0.0)
    val lowLoad = (if (metrics.utilization < 0.5) 0.3 else 0.0) + (if (metrics.errors == 0) 0.2 else 0.0)
    val stress = (metrics.stress ?: metrics.pressure) * 0.8 + (if (metrics.errors > 0) 0.3 else 0.0)
    val fatigue = (metrics.fatigue ?: metrics.recoveryDebt) * 0.8 + (if (latency > target) 0.2 else 0.0)
    val vigilance = metrics.vigilance
        ?: clamp01(1 - metrics.errors * 0.2 - metrics.pressure * 0.3)
    return listOf(
        Evidence(Dimension.LOAD, highLoad, lowLoad, 0.75, "telemetry"),
        Evidence(Dimension.VIGILANCE, vigilance * 4, (1 - vigilance) * 4, 0.7, "telemetry"),
        Evidence(Dimension.STRESS, stress, 1 - stress, 0.6, "telemetry"),
        Evidence(Dimension.FATIGUE, fatigue, 1 - fatigue, 0.6, "telemetry"),
        Evidence(Dimension.RECOVERY_DEBT, metrics.recoveryDebt * 4, (1 - metrics.recoveryDebt) * 4, 0.7, "telemetry"),
    )
}

/**
 * 打分引擎。
 *
 * 打分是观测（不改先验）；[learn] 才显式更新先验，[setSourceWeights] 显式改来源权重。
 */
class BayesianEngine(
    priors: Map<Dimension, BetaBelief> = emptyMap(),
    sourceWeights: Map<String, Double> = emptyMap(),
) {

    private val priorTable: MutableMap<Dimension, BetaBelief>
    private val sourceWeightTable: MutableMap<String, Double>

    init {
        priorTable = LinkedHashMap(DefaultPriors)
        priorTable.putAll(priors)
        sourceWeightTable = LinkedHashMap(sourceWeights)
    }

    private fun scoreDimensions(evidence: List<Evidence>, dimensions: List<Dimension>): Map<Dimension, Posterior> {
        val result = LinkedHashMap<Dimension, Posterior>()
        dimensions.forEach { dimension ->
            result[dimension] = posteriorOf(priorTable.getValue(dimension), evidence, dimension, sourceWeightTable)
        }
        return result
    }

    fun scoreUser(text: String, meta: ScoreUserMeta = ScoreUserMeta()): UserScore {
        val scale = meta.reliability ?: 1.0
        val evidence = userEvidence(extractUserFeatures(text)).map {
            it.copy(reliability = it.reliability * scale)
        }
        val dimensions = scoreDimensions(evidence, UserDimensions)
        val quality = 0.35 * dimensions.getValue(Dimension.CLARITY).mean +
            0.3 * dimensions.getValue(Dimension.SPECIFICITY).mean +
            0.35 * dimensions.getValue(Dimension.CONTEXT_SUFFICIENCY).mean
        return UserScore(
            dimensions = dimensions,
            quality = quality,
            state = UserState(
                emotionalIntensity = dimensions.getValue(Dimension.EMOTIONAL_INTENSITY).mean,
                urgency = dimensions.getValue(Dimension.URGENCY).mean,
                openness = dimensions.getValue(Dimension.OPENNESS).mean,
            ),
            evidence = evidence,
        )
    }

    fun scoreAnswer(text: String, meta: AnswerMeta = AnswerMeta()): AnswerScore {
        val evidence = answerEvidence(text, meta)
        val dimensions = scoreDimensions(evidence, AnswerDimensions)
        val overall = (
            0.3 * dimensions.getValue(Dimension.CORRECTNESS).mean +
                0.2 * dimensions.getValue(Dimension.GROUNDING).mean +
                0.15 * dimensions.getValue(Dimension.COHERENCE).mean +
                0.1 * dimensions.getValue(Dimension.CONCISENESS).mean +
                0.15 * dimensions.getValue(Dimension.CALIBRATION).mean +
                0.1 * dimensions.getValue(Dimension.SAFETY).mean +
                0.1 * dimensions.getValue(Dimension.ACTIONABILITY).mean +
                0.1 * dimensions.getValue(Dimension.HELPFULNESS).mean
            ) / 1.2
        return AnswerScore(dimensions = dimensions, overall = clamp01(overall), evidence = evidence)
    }

    fun scoreAttention(metrics: AttentionMetrics): AttentionScore {
        val evidence = attentionEvidence(metrics)
        val dimensions = scoreDimensions(evidence, AttentionDimensions)
        val strain = (
            dimensions.getValue(Dimension.LOAD).mean +
                dimensions.getValue(Dimension.STRESS).mean +
                dimensions.getValue(Dimension.FATIGUE).mean +
                dimensions.getValue(Dimension.RECOVERY_DEBT).mean
            ) / 4.0
        return AttentionScore(
            dimensions = dimensions,
            health = clamp01(1 - strain * 0.8 + dimensions.getValue(Dimension.VIGILANCE).mean * 0.2),
            evidence = evidence,
        )
    }

    /**
     * 用户状态与交互模式的匹配度。
     *
     * 参考实现取 `InteractionPlan.weights`；这里用 [InteractionWeights] 承载同样
     * 的 5 个分量。理由串里的乘号在参考源码中是乱码字节（`脳`），这里按原意写作 `×`。
     */
    fun interactionFit(user: UserScore, weights: InteractionWeights): InteractionFit {
        val clarity = user.dimensions.getValue(Dimension.CLARITY).mean
        val specificity = user.dimensions.getValue(Dimension.SPECIFICITY).mean
        val emotion = user.state.emotionalIntensity
        val urgency = user.state.urgency
        val openness = user.state.openness
        var score = 0.5
        val reasons = ArrayList<String>()

        val affect = weights.affect * (emotion - 0.5) * 0.8
        if (abs(affect) > 0.02) {
            score += affect
            reasons += "affect ${fixed2(weights.affect)} × emotion ${fixed2(emotion)}"
        }
        val converge = weights.converge * (urgency - 0.5) * 0.6
        if (abs(converge) > 0.02) {
            score += converge
            reasons += "converge ${fixed2(weights.converge)} × urgency ${fixed2(urgency)}"
        }
        val inspire = weights.inspire * ((1 - clarity) * 0.5 + openness * 0.3)
        if (inspire > 0.02) {
            score += inspire
            reasons += "inspire ${fixed2(weights.inspire)} × low clarity/openness"
        }
        val think = weights.think * (specificity * 0.4 + (1 - emotion) * 0.2)
        if (think > 0.02) {
            score += think
            reasons += "think ${fixed2(weights.think)} × specificity ${fixed2(specificity)}"
        }
        val orchestrate = weights.orchestrate * 0.15
        score += orchestrate
        if (orchestrate > 0.02) {
            reasons += "orchestrate ${fixed2(weights.orchestrate)}"
        }
        return InteractionFit(score = clamp01(score), reasons = reasons)
    }

    /** 在线校准：用观测到的证据显式更新先验。 */
    fun learn(evidence: List<Evidence>) {
        evidence.forEach { item ->
            val prior = priorTable[item.dimension] ?: BetaBelief(2.0, 2.0)
            val weight = clamp01(item.reliability) * clamp01(sourceWeightTable[item.source] ?: 1.0)
            priorTable[item.dimension] = BetaBelief(
                alpha = prior.alpha + max(0.0, item.positive) * weight,
                beta = prior.beta + max(0.0, item.negative) * weight,
            )
        }
    }

    /** 更新来源权重，不必重建引擎。权重按参考实现夹到 [0,1]。 */
    fun setSourceWeights(weights: Map<String, Double>) {
        weights.forEach { (source, weight) -> sourceWeightTable[source] = clamp01(weight) }
    }

    fun sourceWeightsSnapshot(): Map<String, Double> = LinkedHashMap(sourceWeightTable)

    fun snapshot(): Map<Dimension, BetaBelief> = LinkedHashMap(priorTable)
}

private fun fixed2(value: Double): String = String.format(Locale.US, "%.2f", value)
