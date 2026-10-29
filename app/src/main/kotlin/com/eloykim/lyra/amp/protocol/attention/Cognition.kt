package com.eloykim.lyra.amp.protocol.attention

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Confidence
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.requireConfidence
import kotlin.math.exp
import kotlin.math.pow

/**
 * `src/attention/cognition.ts` 的忠实移植：认知状态模型（提案）。
 *
 * 前提：智能体和人面对同一个**控制**问题——资源受限的决策者必须分配有限的认知、
 * 监视自身，并被他人观察。这里借用心理学的*形式*，从不借用一个魔数：每个常数都是
 * 具名参数，带出处和状态（`measured` 或 `convention`）。
 *
 * 五个状态变量都能只从账本估计：
 * `load`（工作记忆占用）、`vigilance`（漏检概率）、`arousal`（误差压力）、
 * `mastery`（长期成功率）、`accountability`（可识别性）。
 *
 * 绑定说明：[CognitiveFrame] / [CognitiveSlot] 是 `frame.Frame` / `frame.Slot` 的窄
 * 适配模型，只取本算法需要的 mode/slots/ignored/chunk_weight；[CognitiveInputs] 的
 * receipts 使用 [AttentionReceipt]。集成时由 coordinator 用纯转换接入
 * `frame.Frame` / `receipt.Receipt`，算法无需改动。
 */

enum class LawStatus(val wire: String) {
    MEASURED("measured"),
    CONVENTION("convention"),
}

data class LawParameter(
    val value: Double,
    val status: LawStatus,
    val source: String,
)

/**
 * 具名定律参数。每一项都说明数值是在人身上测得的还是本实现的约定——协议可以引用
 * 方向，但不能凭空引用量级。
 */
object CognitiveLaws {
    /** 前景理论的损失不对称中位数（Kahneman & Tversky 1992）。 */
    val lossAsymmetry: LawParameter = LawParameter(
        2.25,
        LawStatus.MEASURED,
        "λ ≈ 2.25, median across studies",
    )

    /** 警觉半衰期，单位是*周期*而不是分钟：待测量的约定。 */
    val vigilanceTau: LawParameter = LawParameter(
        12.0,
        LawStatus.CONVENTION,
        "calibrate per model from receipts",
    )

    /** 返回抑制核，与 novelty 衰减不同。 */
    val iorTau: LawParameter = LawParameter(
        3.0,
        LawStatus.CONVENTION,
        "seconds-scale effect mapped onto cycles",
    )

    /** 负载施加前的基础块容量（Cowan 2001：约 4 个未复述块）。 */
    val baseCapacity: LawParameter = LawParameter(
        4.0,
        LawStatus.MEASURED,
        "Cowan 2001, BBS 24(1):87–114",
    )

    /** 负载最多占用一半容量。 */
    val loadPenalty: LawParameter = LawParameter(
        0.5,
        LawStatus.CONVENTION,
        "monotone, bounded",
    )

    /** 低于该负载时，空余容量不得被 novelty 填满（Lavie）。 */
    val noveltyLoadFloor: LawParameter = LawParameter(
        0.3,
        LawStatus.CONVENTION,
        "Lavie 2005, TiCS 9(2):75–82",
    )

    /** Arousal 通过提高 goal-fit 指数收窄广度（Easterbrook 1959）。 */
    val arousalNarrowing: LawParameter = LawParameter(
        0.5,
        LawStatus.CONVENTION,
        "Easterbrook 1959, Psych Review 66:183–201",
    )

    /** 每评估一个候选收取的筛选开销。 */
    val metaCharge: LawParameter = LawParameter(
        0.1,
        LawStatus.CONVENTION,
        "Russell & Wefald 1991",
    )

    /** 一类目标的实现价值对其未来优先级的影响强度。 */
    val valueLearning: LawParameter = LawParameter(
        2.0,
        LawStatus.CONVENTION,
        "Anderson, Laurent & Yantis 2011, PNAS 108(25); calibrate per host",
    )
}

/**
 * 一个目标的历史，由先前的 frame / receipt 推导，从不存成权重。
 */
data class CandidateHistory(
    /** 过去被选中时 `realized_voi` 的均值；没有样本时为 0.5。 */
    val realized: Double? = null,
    /** 距该目标上次占据槽位的周期数。 */
    val lastSelectedAge: Int? = null,
    /** 距该目标上次被明确排除的周期数。 */
    val lastIgnoredAge: Int? = null,
)

/**
 * 候选，加上认知定律需要的字段——全部由宿主提供。
 */
data class CognitiveCandidate(
    val target: Ref,
    val role: SlotRole,
    val voi: Confidence,
    val goalFit: Confidence,
    val uncertainty: Confidence,
    val novelty: Confidence,
    val costTokens: Int,
    val risk: Confidence,
    /** 信任上限：外部内容只能降低显著性，不能买到显著性（A-4）。 */
    val trustCap: Confidence,
    val why: String,
    val tier: Tier? = null,
    /** 乘法分数无法抵消的加性下限（提案 A-10）。 */
    val hardFloor: Confidence? = null,
    /** 目标占用的块数；两个无关子目标算两块（提案 A-7）。 */
    val chunkWeight: Int? = null,
    /** 相对上一焦点的确定性切换成本，单位为 tokens（提案 A-12）。 */
    val switchCostTokens: Int? = null,
    val history: CandidateHistory? = null,
)

/**
 * 本定律可能发出的忽略原因。前七个之外的五种需要 schema 增补，放进更宽的本地联合
 * 类型可以让本模块保持增量、不改动既有契约。
 */
enum class CognitiveIgnoreReason(val wire: String) {
    LOW_VOI("low_voi"),
    OUT_OF_SCOPE("out_of_scope"),
    UNTRUSTED("untrusted"),
    BUDGET("budget"),
    BLOCKED("blocked"),
    HABITUATED("habituated"),
    DEFERRED("deferred"),
    INHIBITED("inhibited"),
    LOW_SENSITIVITY("low_sensitivity"),
    CONSERVATIVE_CRITERION("conservative_criterion"),
    CAPACITY("capacity"),
    EXHAUSTED("exhausted"),
    ;

    companion object {
        fun fromWire(value: String): CognitiveIgnoreReason =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 ignore reason: $value")
    }
}

data class CognitiveIgnored(
    val target: Ref,
    val reason: CognitiveIgnoreReason,
    val revisitIf: String,
) {

    init {
        require(revisitIf.isNotBlank()) { "ignored.revisit_if 不能为空" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "target" to target.toJson(),
        "reason" to JsonValue.Str(reason.wire),
        "revisit_if" to JsonValue.Str(revisitIf),
    )

    companion object {
        fun fromJson(value: JsonValue): CognitiveIgnored {
            val obj = value.asObject("cognitive.ignored")
            return CognitiveIgnored(
                target = Ref.fromJson(obj.requireObj("target")),
                reason = CognitiveIgnoreReason.fromWire(obj.requireString("reason")),
                revisitIf = obj.requireString("revisit_if"),
            )
        }
    }
}

enum class CognitiveSource(val wire: String) {
    MEASURED("measured"),
    DEFAULT("default"),
}

/**
 * 五个状态变量及其推导结果。
 */
data class CognitiveState(
    val load: Confidence,
    val vigilance: Confidence,
    val arousal: Confidence,
    val mastery: Confidence,
    val accountability: Confidence,
    /** 距上一个 `rest` frame 的周期数；驱动 `vigilance`。 */
    val cyclesSinceRest: Int,
    /** 施加负载惩罚后的 `baseCapacity`，单位是块。 */
    val capacityEff: Int,
    /** 已触发的 `revisit_if` 未被检查的概率；rest 修复的正是这个。 */
    val pLapse: Confidence,
    /** 上一个 frame 中占主导的角色；切换成本按它计费。 */
    val dominantRole: SlotRole? = null,
    /** receipt 足够多时为 `measured`，否则 `default`。 */
    val source: CognitiveSource = CognitiveSource.DEFAULT,
    val samples: Int = 0,
)

/** 窄适配：`frame.Frame` 的 mode/slots/ignored。 */
data class CognitiveFrame(
    val mode: FrameMode,
    val slots: List<CognitiveSlot> = emptyList(),
    val ignored: List<AttentionIgnored> = emptyList(),
)

/** 窄适配：`frame.Slot` 的 role/state/chunk_weight。 */
data class CognitiveSlot(
    val role: SlotRole,
    val state: SlotState = SlotState.ACTIVE,
    /** 参考实现用 `slot.chunk_weight` 旁路字段表达，不属于 frame schema。 */
    val chunkWeight: Int? = null,
)

/** 纯转换：把 attention 槽位适配成认知输入；`chunk_weight` 由宿主另行提供。 */
fun AttentionSlot.toCognitiveSlot(chunkWeight: Int? = null): CognitiveSlot =
    CognitiveSlot(role = role, state = state, chunkWeight = chunkWeight)

data class CognitiveInputs(
    val frames: List<CognitiveFrame>,
    val receipts: List<AttentionReceipt>,
    /** 宿主有真值就提供；否则由 frame 推导。 */
    val accountability: Confidence? = null,
    /** `arousal` 认为“近期”的 receipt 数，默认 12。 */
    val window: Int? = null,
    val baseCapacity: Int? = null,
    val tauVigilance: Double? = null,
)

/**
 * 从账本估计状态。
 *
 * 同一信号的两个时间尺度被刻意分开：`arousal` 是*近期*误差压力（快、相位性），
 * `mastery` 是长期成功率（慢、特质性）。合成一个数会丢掉“能干的智能体也可以短暂
 * 惊慌、无能的也可以平静”这一事实。
 */
fun estimateCognitiveState(input: CognitiveInputs): CognitiveState {
    val frames: List<CognitiveFrame> = input.frames
    val last: CognitiveFrame? = frames.lastOrNull()
    val baseCapacity: Int = input.baseCapacity ?: CognitiveLaws.baseCapacity.value.toInt()
    val tau: Double = input.tauVigilance ?: CognitiveLaws.vigilanceTau.value
    val window: Int = input.window ?: 12

    var cyclesSinceRest: Int = frames.size
    for (index in frames.indices.reversed()) {
        if (frames[index].mode == FrameMode.REST) {
            cyclesSinceRest = frames.size - 1 - index
            break
        }
    }

    val active: List<CognitiveSlot> = last?.slots?.filter { it.state == SlotState.ACTIVE } ?: emptyList()
    val chunks: Int = active.sumOf { it.chunkWeight ?: 1 }
    val deferred: List<AttentionIgnored> = last?.ignored?.filter {
        it.reason == IgnoredReason.DEFERRED || it.reason == IgnoredReason.BUDGET
    } ?: emptyList()
    val rootCapacity: Int = maxOf(1, baseCapacity)
    val load: Double = clamp01(
        0.6 * minOf(1.0, chunks.toDouble() / rootCapacity) +
            0.4 * minOf(1.0, deferred.size / 8.0),
    )

    // `Receipt.outcome` 在参考接口中必填，因此这里的 scored 等于 recent（过滤恒真）。
    val recent: List<AttentionReceipt> = if (window <= 0) emptyList() else input.receipts.takeLast(window)
    val scored: List<AttentionReceipt> = recent
    val failures: Int = scored.count { it.outcome == Outcome.BLOCKED || it.outcome == Outcome.FAILURE }
    val partials: Int = scored.count { it.outcome == Outcome.PARTIAL }
    val failureRate: Double = if (scored.isNotEmpty()) failures.toDouble() / scored.size else 0.0
    val partialRate: Double = if (scored.isNotEmpty()) partials.toDouble() / scored.size else 0.0
    val arousal: Double = clamp01(0.75 * failureRate + 0.25 * partialRate)

    val allScored: List<AttentionReceipt> = input.receipts
    val wins: Int = allScored.count { it.outcome == Outcome.SUCCESS }
    val mastery: Double = if (allScored.isNotEmpty()) clamp01(wins.toDouble() / allScored.size) else 0.5

    val vigilance: Double = clamp01(exp(-cyclesSinceRest / maxOf(1.0, tau)))
    val capacityEff: Int = minOf(
        9,
        maxOf(1, Math.round(rootCapacity * (1 - CognitiveLaws.loadPenalty.value * load)).toInt()),
    )
    val dominantRole: SlotRole? = dominantRoleOf(active)

    return CognitiveState(
        load = round3(load),
        vigilance = round3(vigilance),
        arousal = round3(arousal),
        mastery = round3(mastery),
        accountability = clamp01(input.accountability ?: 0.0),
        cyclesSinceRest = cyclesSinceRest,
        capacityEff = capacityEff,
        pLapse = round3(1 - vigilance),
        dominantRole = dominantRole,
        source = if (allScored.size >= 30) CognitiveSource.MEASURED else CognitiveSource.DEFAULT,
        samples = allScored.size,
    )
}

/**
 * 焦点集中块权重最大的角色；并列时取先出现者。
 */
private fun dominantRoleOf(slots: List<CognitiveSlot>): SlotRole? {
    val weight = LinkedHashMap<SlotRole, Int>()
    for (slot in slots) {
        weight[slot.role] = (weight[slot.role] ?: 0) + (slot.chunkWeight ?: 1)
    }
    var best: SlotRole? = null
    var bestWeight: Int = -1
    for ((role, total) in weight) {
        if (total > bestWeight) {
            best = role
            bestWeight = total
        }
    }
    return best
}

/**
 * 历史乘数：价值学习减去返回抑制。
 *
 * 这是两个符号相反的机制，不是一个“novelty”旋钮。付过报酬的候选值得再选；刚被拒绝
 * 的候选*不*值得再选，两个事实都必须活到下一轮。
 */
fun historyFactor(history: CandidateHistory?): Double {
    if (history == null) {
        return 1.0
    }
    val realized: Double = history.realized ?: 0.5
    val learned: Double = 1 + CognitiveLaws.valueLearning.value * (realized - 0.5)
    val age: Int? = history.lastIgnoredAge
    val ior: Double = if (age == null) 0.0 else 0.5 * exp(-age / CognitiveLaws.iorTau.value)
    return maxOf(0.1, learned - ior)
}

/**
 * 认知显著性定律。
 *
 * `S = floor ⊕ [ voi · goalFit^γ · (1 + novelty·openness) · history ]
 *      / [ 1 + c_tok + c_risk + c_switch·(1 + load) + c_meta ]`
 *
 * 其中 `γ = 1 + arousalNarrowing · arousal`。四个后果值得点名：
 *
 * 1. **Arousal 收窄。** `goalFit` 被提升到 >1 的指数，偏离目标的候选死得更快，
 *    在目标上的候选保持排名（Easterbrook，用指数表达，不引入任何情感项）。
 * 2. **低负载不招致俘获。** 低于 `noveltyLoadFloor` 时 novelty 不贡献任何分数，
 *    因为空余容量正是无关材料进入的地方（Lavie）。
 * 3. **硬下限绕过乘法。** 下限是最大值而非乘数，所以 goalFit 接近 0 的安全信号
 *    不会被乘成 `low_voi` 排除。
 * 4. **决定有成本。** 每个候选支付 `metaCharge`；与上一焦点不同还要支付切换成本，
 *    高负载时付两次，因为负载下重配置更难。
 */
fun salienceFromState(candidate: CognitiveCandidate, state: CognitiveState): Confidence {
    val gamma: Double = 1 + CognitiveLaws.arousalNarrowing.value * state.arousal
    val openness: Double = if (state.load >= CognitiveLaws.noveltyLoadFloor.value) 1.0 else 0.0
    val value: Double =
        candidate.voi *
            clamp01(candidate.goalFit).pow(gamma) *
            (1 + 0.5 * clamp01(candidate.novelty) * openness) *
            historyFactor(candidate.history)

    val switchTokens: Int = candidate.switchCostTokens ?: 0
    val loadOnSwitch: Double = 1 + state.load
    val cost: Double =
        1 +
            2 * clamp01(candidate.costTokens / 2000.0) +
            3 * clamp01(candidate.risk) +
            0.5 * clamp01(switchTokens / 2000.0) * loadOnSwitch +
            CognitiveLaws.metaCharge.value

    val capped: Double = minOf(clamp01(value / cost), clamp01(candidate.trustCap))
    val floor: Double = clamp01(candidate.hardFloor ?: 0.0)
    return round3(maxOf(capped, floor))
}

data class CognitiveSelectionOptions(
    val budgetTokens: Int,
    /** 宿主有更好的估计时覆盖 `state.capacityEff`。 */
    val capacity: Int? = null,
    val threshold: Double? = null,
    /** 改变焦点集主导角色所收取的 token 成本。 */
    val switchCostTokens: Int? = null,
)

data class CognitiveSelection(
    val slots: List<AttentionSlot>,
    val ignored: List<CognitiveIgnored>,
    val impasse: ImpasseState? = null,
    /** 已消耗块数，调用方可直接断言容量规则。 */
    val capacityUsed: Int,
)

/**
 * 在状态调制定律下选择焦点集。
 *
 * 顺序本身就是要点：下限在任何打分之前先放置，所以容量压力永远不能把安全信号变成
 * `low_voi`。放不下的下限产生 `INFEASIBLE`，而不是静默丢弃。
 */
fun selectFromState(
    candidates: List<CognitiveCandidate>,
    state: CognitiveState,
    options: CognitiveSelectionOptions,
): CognitiveSelection {
    val threshold: Double = options.threshold ?: 0.12
    val capacity: Int = maxOf(1, options.capacity ?: state.capacityEff)
    val switchCost: Int = options.switchCostTokens ?: 0
    val scored: List<Pair<CognitiveCandidate, Double>> = candidates
        .map { candidate -> candidate to salienceFromState(candidate, state) }
        .sortedByDescending { entry -> entry.second }

    val ignored = ArrayList<CognitiveIgnored>()
    val slots = ArrayList<AttentionSlot>()
    var usedChunks: Int = 0
    var remaining: Int = options.budgetTokens
    var floorConflict: Boolean = false

    for (entry in scored) {
        val candidate: CognitiveCandidate = entry.first
        val chunks: Int = maxOf(1, candidate.chunkWeight ?: 1)
        val reserved: Boolean = (candidate.hardFloor ?: 0.0) > 0
        if (usedChunks + chunks > capacity) {
            if (reserved) {
                floorConflict = true
            }
            ignored.add(CognitiveIgnored(candidate.target, CognitiveIgnoreReason.CAPACITY, "焦点释放后"))
            continue
        }
        val cost: Int = candidate.costTokens
        val withSwitch: Int = cost + if (candidate.role == state.dominantRole) 0 else switchCost
        if (withSwitch > remaining) {
            ignored.add(CognitiveIgnored(candidate.target, CognitiveIgnoreReason.BUDGET, "预算恢复后"))
            continue
        }
        if (!reserved && entry.second < threshold) {
            ignored.add(
                CognitiveIgnored(
                    target = candidate.target,
                    reason = reasonForIgnore(candidate, state),
                    revisitIf = if (candidate.target.kind == RefKind.UNKNOWN) {
                        "VOI 上升或目标改变"
                    } else {
                        "重新评估后"
                    },
                ),
            )
            continue
        }
        remaining -= withSwitch
        usedChunks += chunks
        slots.add(
            AttentionSlot(
                target = candidate.target,
                role = candidate.role,
                tier = candidate.tier,
                salience = entry.second,
                voi = candidate.voi,
                cost = Budget(tokens = cost),
                budget = Budget(tokens = withSwitch),
                why = candidate.why,
                state = SlotState.ACTIVE,
            ),
        )
    }

    val impasse: ImpasseState? = when {
        floorConflict -> ImpasseState.INFEASIBLE
        slots.isEmpty() -> ImpasseState.STUCK
        slots.size > 1 && kotlin.math.abs(slots[0].salience - slots[1].salience) < 0.02 -> ImpasseState.TIE
        else -> null
    }

    return CognitiveSelection(slots, ignored, impasse, usedChunks)
}

/**
 * 一个条目为什么被排除，选择的原因要让审计者区分三件事：不值得（`low_voi`）、
 * 分不清（`low_sensitivity`）、或被有意忽略（`conservative_criterion`）。混淆它们
 * 会让告警疲劳无法归因。
 */
private fun reasonForIgnore(candidate: CognitiveCandidate, state: CognitiveState): CognitiveIgnoreReason {
    val age: Int? = candidate.history?.lastIgnoredAge
    if (age != null && age <= CognitiveLaws.iorTau.value) {
        return CognitiveIgnoreReason.INHIBITED
    }
    if (state.pLapse > 0.5 && candidate.uncertainty > 0.5) {
        return CognitiveIgnoreReason.LOW_SENSITIVITY
    }
    return CognitiveIgnoreReason.LOW_VOI
}

/**
 * 契约，以测试而非公式表达：十条单调性主张——原来的五条，加上认知定律新增的五条。
 * 实现可以替换上面所有常数而仍然合规，只要这些成立。
 */
fun respectsCognitiveMonotonicity(base: CognitiveCandidate, state: CognitiveState): Boolean {
    fun probe(
        over: (CognitiveCandidate) -> CognitiveCandidate = { it },
        stateOver: (CognitiveState) -> CognitiveState = { it },
    ): Double = salienceFromState(over(base), stateOver(state))

    val reference: Double = probe()
    val checks: List<Boolean> = listOf(
        probe(over = { it.copy(voi = minOf(1.0, base.voi + 0.1)) }) >= reference,
        probe(over = { it.copy(costTokens = base.costTokens + 200) }) <= reference,
        probe(over = { it.copy(risk = minOf(1.0, base.risk + 0.1)) }) <= reference,
        probe(over = { it.copy(trustCap = minOf(base.trustCap, 0.2)) }) <= reference,
        probe(
            over = {
                it.copy(
                    history = CandidateHistory(
                        realized = 1.0,
                        lastSelectedAge = base.history?.lastSelectedAge,
                        lastIgnoredAge = base.history?.lastIgnoredAge,
                    ),
                )
            },
        ) >= reference,
        probe(
            over = {
                it.copy(
                    history = CandidateHistory(
                        realized = base.history?.realized,
                        lastSelectedAge = 0,
                        lastIgnoredAge = 0,
                    ),
                )
            },
        ) <= reference,
        probe(over = { it.copy(hardFloor = 1.0) }) >= reference,
        probe(over = { it.copy(switchCostTokens = 2000) }) <= reference,
        probe(stateOver = { it.copy(arousal = 1.0) }) <= reference,
        probe(stateOver = { it.copy(load = 0.0) }) <= reference,
    )
    return checks.all { it }
}
