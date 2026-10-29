package com.eloykim.lyra.amp.protocol.attention

/**
 * `src/attention/resource.ts` 的忠实移植：注意力作为有界、多维资源。
 *
 * 这是 `cognition.ts` 之上的资源层：
 * - [ProcessAttention] 是智能体级容量、预留与恢复债务。
 * - [ThreadAttention] 是容量内按任务的分配。
 *
 * CPU 类比只在结构上成立：CPU 可以永远满负荷，人不行。因此策略把前台利用率压到
 * 1 以下、保留安全/预留下限，并在恢复债务高时安排休息。
 */

data class AttentionResourcePolicy(
    /** 没有实测容量时假设的工作记忆块数。 */
    val baseSlots: Int = 4,
    /** 留给前台工作之外的份额（后台、安全、恢复）。 */
    val reserveRatio: Double = 0.25,
    /** 硬性安全下限占容量的比例。 */
    val safetyFloorRatio: Double = 0.1,
    /** 前台利用率上限；必须低于 1.0。 */
    val maxForegroundUtil: Double = 0.75,
    /** 被接纳任务的最小分配（块）。 */
    val minSliceSlots: Int = 1,
    /** 连续多少个周期没有 rest frame 就休息。 */
    val restAfterCycles: Int = 8,
    /** 警觉降到该水平就休息。 */
    val vigilanceFloor: Double = 0.25,
    /** 负载达到该水平就休息。 */
    val loadCeiling: Double = 0.9,
    /** 恢复债务达到该水平就休息。 */
    val recoveryDebtThreshold: Double = 0.7,
)

val DefaultAttentionPolicy: AttentionResourcePolicy = AttentionResourcePolicy()

data class ProcessAttention(
    val pid: Int,
    /** 施加负载惩罚后的有效工作记忆块数。 */
    val effectiveSlots: Int,
    /** 前台上限（`floor(effective * maxForegroundUtil)`）。 */
    val foregroundCap: Int,
    /** 不参与前台分配的块数。 */
    val reserveSlots: Int,
    /** 预留中的硬性安全下限。 */
    val safetySlots: Int,
    val state: CognitiveState,
    /** [0, 1] 累积的休息需求。 */
    val recoveryDebt: Double,
    /** 策略判定不应运行任何前台工作时为 true。 */
    val offline: Boolean,
)

enum class ThreadDeferReason(val wire: String) {
    BUDGET("budget"),
    OFFLINE("offline"),
    LOW_PRIORITY("low-priority"),
    /** 参考联合类型中存在，当前规划算法不会发出。 */
    FLOOR("floor"),
}

data class ThreadAttentionCandidate(
    val tid: Int,
    val pid: Int,
    val priority: Int,
    /** 期望块数；规划器会夹到容量内。 */
    val preferredSlots: Int,
    /** 被接纳时的最小块数；默认取 `policy.minSliceSlots`。 */
    val minSlots: Int? = null,
    /** 后台任务使用预留，永远不占前台槽。 */
    val foreground: Boolean? = null,
    /** 角色/上下文切换带来的额外压力。 */
    val switchCost: Double? = null,
    val role: String? = null,
)

data class ThreadAttention(
    val tid: Int,
    val pid: Int,
    val slots: Int,
    val foreground: Boolean,
    val priority: Int,
    val switchCost: Double,
    val role: String? = null,
)

data class DeferredAttention(val tid: Int, val reason: ThreadDeferReason)

data class AttentionPlan(
    val process: ProcessAttention,
    val foreground: ThreadAttention? = null,
    val background: List<ThreadAttention>,
    val deferred: List<DeferredAttention>,
    /** 已分配块 / 有效块。 */
    val utilization: Double,
    /** 延迟压力加切换成本，[0, 1+]。 */
    val pressure: Double,
    /** 策略希望出现 rest frame 时为 true。 */
    val rest: Boolean,
)

fun recoveryDebtOf(state: CognitiveState): Double =
    clamp01(0.5 * (1 - state.vigilance) + 0.3 * state.load + 0.2 * state.pLapse)

fun shouldRest(
    state: CognitiveState,
    policy: AttentionResourcePolicy = DefaultAttentionPolicy,
): Boolean =
    state.cyclesSinceRest >= policy.restAfterCycles ||
        state.vigilance <= policy.vigilanceFloor ||
        state.load >= policy.loadCeiling ||
        recoveryDebtOf(state) >= policy.recoveryDebtThreshold

fun processAttention(
    pid: Int,
    state: CognitiveState,
    policy: AttentionResourcePolicy = DefaultAttentionPolicy,
): ProcessAttention {
    val effectiveSlots: Int = maxOf(1, Math.floor(state.capacityEff.toDouble()).toInt())
    val safetySlots: Int = minOf(
        effectiveSlots,
        maxOf(1, Math.floor(effectiveSlots * policy.safetyFloorRatio).toInt()),
    )
    val rawCap: Int = maxOf(0, Math.floor(effectiveSlots * policy.maxForegroundUtil).toInt())
    val foregroundCap: Int = maxOf(0, minOf(rawCap, effectiveSlots - safetySlots))
    val reserveSlots: Int = maxOf(safetySlots, effectiveSlots - foregroundCap)
    return ProcessAttention(
        pid = pid,
        effectiveSlots = effectiveSlots,
        foregroundCap = foregroundCap,
        reserveSlots = reserveSlots,
        safetySlots = safetySlots,
        state = state,
        recoveryDebt = recoveryDebtOf(state),
        offline = shouldRest(state, policy),
    )
}

private fun toAttention(
    candidate: ThreadAttentionCandidate,
    slots: Int,
    foreground: Boolean,
): ThreadAttention = ThreadAttention(
    tid = candidate.tid,
    pid = candidate.pid,
    slots = slots,
    foreground = foreground,
    priority = candidate.priority,
    switchCost = maxOf(0.0, candidate.switchCost ?: 0.0),
    role = candidate.role,
)

/**
 * 分配一个前台任务，并从预留中分配后台任务。
 *
 * 下限刻意不对称：安全永远预留，每个被接纳的任务都拿到 `minSlots`，但被延迟的任务
 * 没有下限。给每个候选都保底会让压力下的“接纳”失去意义。
 */
fun planAttention(
    process: ProcessAttention,
    candidates: List<ThreadAttentionCandidate>,
    policy: AttentionResourcePolicy = DefaultAttentionPolicy,
): AttentionPlan {
    val deferred = ArrayList<DeferredAttention>()
    val background = ArrayList<ThreadAttention>()
    val sorted: List<ThreadAttentionCandidate> = candidates.sortedWith(
        compareByDescending<ThreadAttentionCandidate> { it.priority }.thenBy { it.tid },
    )
    val foregroundCandidates: List<ThreadAttentionCandidate> = sorted.filter { it.foreground != false }
    val backgroundCandidates: List<ThreadAttentionCandidate> = sorted.filter { it.foreground == false }

    if (process.offline) {
        for (candidate in foregroundCandidates) {
            deferred.add(DeferredAttention(candidate.tid, ThreadDeferReason.OFFLINE))
        }
        return AttentionPlan(
            process = process,
            foreground = null,
            background = background,
            deferred = deferred,
            utilization = 0.0,
            pressure = 1.0,
            rest = true,
        )
    }

    var foreground: ThreadAttention? = null
    var remaining: Int = process.foregroundCap
    for (index in foregroundCandidates.indices) {
        val candidate: ThreadAttentionCandidate = foregroundCandidates[index]
        val minSlots: Int = maxOf(1, candidate.minSlots ?: policy.minSliceSlots)
        if (index == 0 && remaining >= minSlots) {
            val slots: Int = minOf(remaining, maxOf(minSlots, candidate.preferredSlots))
            foreground = toAttention(candidate, slots, foreground = true)
            remaining -= slots
        } else {
            deferred.add(
                DeferredAttention(
                    candidate.tid,
                    if (index == 0) ThreadDeferReason.BUDGET else ThreadDeferReason.LOW_PRIORITY,
                ),
            )
        }
    }

    var reserveRemaining: Int =
        maxOf(0, process.effectiveSlots - process.safetySlots - (foreground?.slots ?: 0))
    for (candidate in backgroundCandidates) {
        val minSlots: Int = maxOf(1, candidate.minSlots ?: policy.minSliceSlots)
        if (reserveRemaining < minSlots) {
            deferred.add(DeferredAttention(candidate.tid, ThreadDeferReason.BUDGET))
            continue
        }
        val slots: Int = minOf(reserveRemaining, maxOf(minSlots, candidate.preferredSlots))
        background.add(toAttention(candidate, slots, foreground = false))
        reserveRemaining -= slots
    }

    val allocated: Int = (foreground?.slots ?: 0) + background.sumOf { it.slots }
    val utilization: Double = allocated.toDouble() / process.effectiveSlots
    val switchPressure: Double = if (foreground == null) {
        0.0
    } else {
        minOf(1.0, foreground.switchCost / maxOf(1, process.effectiveSlots))
    }
    val pressure: Double = minOf(1.5, deferred.size.toDouble() / maxOf(1, candidates.size) + switchPressure)

    return AttentionPlan(
        process = process,
        foreground = foreground,
        background = background,
        deferred = deferred,
        utilization = utilization,
        pressure = pressure,
        rest = false,
    )
}

/**
 * 休息恢复警觉、降低负载并清空休息计数。
 */
fun applyRest(state: CognitiveState, cycles: Int = 1): CognitiveState {
    val amount: Int = maxOf(1, cycles)
    val vigilance: Double = clamp01(state.vigilance + 0.25 * amount)
    val load: Double = clamp01(state.load - 0.2 * amount)
    return state.copy(
        vigilance = vigilance,
        load = load,
        pLapse = clamp01(1 - vigilance),
        cyclesSinceRest = 0,
    )
}

data class OutcomeStats(
    val successes: Int,
    val attempts: Int,
    val errors: Int,
    val rework: Int,
    val attentionSlots: Int,
)

/**
 * 效率是每单位注意力产出的有用结果，而不是原始利用率。错误与返工被扣除，因此
 * “很忙但做错”的智能体赢不了。
 */
fun qualityAdjustedThroughput(stats: OutcomeStats): Double {
    val useful: Int = maxOf(0, stats.successes - stats.errors - stats.rework)
    return useful.toDouble() / maxOf(1, stats.attentionSlots)
}

data class AttentionHealth(
    val utilization: Double,
    val pressure: Double,
    val headroom: Double,
    val rest: Boolean,
)

fun attentionHealth(plan: AttentionPlan): AttentionHealth = AttentionHealth(
    utilization = plan.utilization,
    pressure = plan.pressure,
    headroom = maxOf(0.0, 1 - plan.utilization),
    rest = plan.rest,
)
