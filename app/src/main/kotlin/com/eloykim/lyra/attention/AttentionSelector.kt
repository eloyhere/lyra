package com.eloykim.lyra.attention

import com.eloykim.lyra.amp.protocol.semantic.Sha256
import kotlin.math.min

/**
 * 焦点选择（AMP A-1 / A-2 / A-4）。
 *
 * 规范不规定显著性公式，只规定契约的单调性；这里的公式是「起步实现」，
 * 可以替换，但必须保持 [SalienceContractTest] 里的单调性约束。
 */
enum class Role { ELEMENT, OCR, TOOL, GOAL }

enum class TrustSource(val cap: Float) {
    USER(1.0f),
    VERIFIED(1.0f),
    ACCESSIBILITY(0.85f),
    OCR(0.5f),
    EXTERNAL(0.3f),
}

enum class IgnoreReason {
    LOW_VOI,
    OUT_OF_SCOPE,
    UNTRUSTED,
    BUDGET,
    BLOCKED,
    HABITUATED,
    DEFERRED,
}

data class Candidate(
    val id: String,
    val label: String,
    val role: Role = Role.ELEMENT,
    val goalFit: Float,
    val voi: Float = 0.5f,
    val uncertainty: Float = 0.5f,
    val novelty: Float = 1f,
    val costTokens: Int = 40,
    val risk: Float = 0f,
    val trust: TrustSource = TrustSource.ACCESSIBILITY,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val revisitIf: String? = null,
)

data class Slot(
    val id: String,
    val label: String,
    val role: Role,
    val salience: Float,
    val voi: Float,
    val costTokens: Int,
    val why: String,
    val state: String = "active",
)

data class Ignored(
    val id: String,
    val label: String,
    val reason: IgnoreReason,
    val revisitIf: String,
)

data class FrameDraft(
    val cycle: Int,
    val mode: String,
    val goal: String,
    val slots: List<Slot>,
    val ignored: List<Ignored>,
    val allocatedTokens: Int,
    val spentTokens: Int,
)

/**
 * 选择器：显著性排序 + 预算截断 + 忽略集生成。
 *
 * 三个硬约束：
 * - 每个 slot 必带 `why` 与 `cost`（A-1）；
 * - `goalFit ≥ ignoreThreshold` 却没入选的，必须进 ignored（A-2）；
 * - 外部来源的 salience 不得超过 [TrustSource.EXTERNAL].cap（A-4）。
 */
class AttentionSelector(
    private val maxSlots: Int = 7,
    private val cycleTokenBudget: Int = 4_000,
    private val ignoreThreshold: Float = 0.4f,
) {

    fun select(
        cycle: Int,
        goal: String,
        candidates: List<Candidate>,
        mode: String = "exploit",
    ): FrameDraft {
        require(maxSlots in 3..9) { "焦点集上限必须是 3–9（AMP §5.3）" }

        val scored = candidates.map { it to salience(it) }
            .sortedWith(compareByDescending<Pair<Candidate, Float>> { it.second }.thenBy { it.first.costTokens })

        val slots = ArrayList<Slot>()
        val ignored = ArrayList<Ignored>()
        var spent = 0

        for ((candidate, score) in scored) {
            val fits = slots.size < maxSlots && spent + candidate.costTokens <= cycleTokenBudget
            if (fits) {
                slots += Slot(
                    id = candidate.id,
                    label = candidate.label,
                    role = candidate.role,
                    salience = score,
                    voi = candidate.voi,
                    costTokens = candidate.costTokens,
                    why = why(candidate),
                )
                spent += candidate.costTokens
            } else if (candidate.goalFit >= ignoreThreshold) {
                ignored += Ignored(
                    id = candidate.id,
                    label = candidate.label,
                    reason = ignoreReason(candidate, score, spent + candidate.costTokens > cycleTokenBudget),
                    revisitIf = candidate.revisitIf
                        ?: "候选重新出现或任务目标变化时重新评估",
                )
            } else {
                ignored += Ignored(
                    id = candidate.id,
                    label = candidate.label,
                    reason = if (candidate.trust == TrustSource.EXTERNAL) {
                        IgnoreReason.UNTRUSTED
                    } else {
                        IgnoreReason.LOW_VOI
                    },
                    revisitIf = candidate.revisitIf ?: "任务目标变化时重新评估",
                )
            }
        }

        return FrameDraft(
            cycle = cycle,
            mode = mode,
            goal = goal,
            slots = slots,
            ignored = ignored,
            allocatedTokens = cycleTokenBudget,
            spentTokens = spent,
        )
    }

    /** 显著性：契约要求 voi↑↗、cost↑↘、risk↑↘、trust 只设上限。 */
    fun salience(candidate: Candidate): Float {
        val raw = 0.45f * candidate.goalFit +
            0.30f * candidate.voi +
            0.15f * candidate.uncertainty +
            0.10f * candidate.novelty

        val costPenalty = 1f + 0.6f * min(candidate.costTokens / 200f, 1f)
        val riskPenalty = 1f - 0.5f * candidate.risk.coerceIn(0f, 1f)

        return min(raw / costPenalty * riskPenalty, candidate.trust.cap).coerceIn(0f, 1f)
    }

    private fun ignoreReason(candidate: Candidate, score: Float, overBudget: Boolean): IgnoreReason = when {
        candidate.trust == TrustSource.EXTERNAL -> IgnoreReason.UNTRUSTED
        overBudget -> IgnoreReason.BUDGET
        score < 0.2f -> IgnoreReason.LOW_VOI
        else -> IgnoreReason.DEFERRED
    }

    private fun why(candidate: Candidate): String = buildString {
        append("goalFit=%.2f".format(candidate.goalFit))
        append(" · voi=%.2f".format(candidate.voi))
        if (candidate.editable) append(" · 可输入")
        else if (candidate.clickable) append(" · 可点击")
        append(" · 约 ${candidate.costTokens} tokens")
    }
}
/** Frame 的内容摘要：Inspector 用它串起帧链。 */
fun FrameDraft.digest(): String = Sha256.digestString(
    buildString {
        append(cycle).append('|').append(mode).append('|').append(goal).append('|')
        slots.forEach { append(it.id).append(':').append("%.3f".format(it.salience)).append(',') }
        append('|')
        ignored.forEach { append(it.id).append(':').append(it.reason).append(',') }
    },
)