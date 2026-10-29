package com.eloykim.lyra.amp.protocol.attention

import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Timestamp
import java.time.Instant
import java.time.ZoneOffset

/**
 * `src/attention/social.ts` 的忠实移植：Attention Broker（Profile `Social`）。
 *
 * 人的注意力是被预算的资源，不是可随意调用的函数。安全类永远绕过预算；其余请求
 * 排队、冷却或被拒绝。
 */

enum class Decision(val wire: String) {
    GRANT("grant"),
    DEFER("defer"),
    DENY("deny"),
    ;

    companion object {
        fun fromWire(value: String): Decision =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 decision: $value")
    }
}

data class QuietHours(val start: Int, val end: Int)

private val defaultInterruptBudgets: Map<InterruptClass, Int> = mapOf(
    InterruptClass.SAFETY to 1,
    InterruptClass.COMMITMENT to 3,
    InterruptClass.OPPORTUNITY to 2,
    InterruptClass.CURIOSITY to 1,
)

/**
 * 中断策略。参考实现接受 `Partial<AttentionPolicy>` 并按键合并；Kotlin 侧用默认参数
 * 表达同样的“部分覆盖”，并用 [withBudgets] 合并类预算。
 */
data class AttentionPolicy(
    /** 每类每小时允许的授予次数（滚动窗口）。安全类不受此限制。 */
    val budgets: Map<InterruptClass, Int> = defaultInterruptBudgets,
    val quietHours: QuietHours? = QuietHours(22, 7),
    val cooldownMinutes: Int? = 10,
    /** curiosity 必须达到的期望价值，否则根本不会授予。 */
    val curiosityFloor: Double? = 0.7,
) {

    fun withBudgets(overrides: Map<InterruptClass, Int>): AttentionPolicy =
        copy(budgets = defaultInterruptBudgets + overrides)

    companion object {
        val DEFAULT_BUDGETS: Map<InterruptClass, Int> = defaultInterruptBudgets
        val DEFAULT: AttentionPolicy = AttentionPolicy()
    }
}

/** 参考实现导出的 `DefaultPolicy`。 */
val DefaultPolicy: AttentionPolicy = AttentionPolicy()

data class AttentionDecision(
    val request: Id,
    val decision: Decision,
    val reason: String,
    val at: Timestamp,
    val interruptClass: InterruptClass,
    val debtAdded: Boolean,
)

data class DebtEntry(
    val request: Id,
    val interruptClass: InterruptClass,
    val openedAt: Timestamp,
    val weight: Double,
    val resolvedAt: Timestamp? = null,
)

/** 债务结清方式；参考实现只记录 resolvedAt，方式本身不入账。 */
enum class DebtResolution(val wire: String) {
    ANSWERED("answered"),
    EXPIRED("expired"),
    WITHDRAWN("withdrawn"),
    ;

    companion object {
        fun fromWire(value: String): DebtResolution =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 debt resolution: $value")
    }
}

data class AttentionBrokerOptions(
    val now: () -> Timestamp,
    val policy: AttentionPolicy = AttentionPolicy(),
)

/**
 * 注意力代理。
 *
 * 预算、安静时段、冷却与债务都只在这里判定，`AttentionLedger` 只做记录封装。
 */
class AttentionBroker(
    private val now: () -> Timestamp,
    val policy: AttentionPolicy = AttentionPolicy(),
) {

    constructor(options: AttentionBrokerOptions) : this(options.now, options.policy)

    private val requests = LinkedHashMap<Id, AttentionRequest>()
    private val decisions = LinkedHashMap<Id, AttentionDecision>()
    private val grants = LinkedHashMap<InterruptClass, MutableList<Timestamp>>()

    /** 键是目标 id；参考实现还会把 `kind` 字符串写进来，此处保留该行为。 */
    private val lastTouch = HashMap<String, Long>()
    private val debt = ArrayList<DebtEntry>()

    fun submit(request: AttentionRequest): AttentionDecision {
        require(request.whyNow.isNotBlank() && request.ifIgnored.isNotBlank()) {
            "an attention request needs why_now and if_ignored"
        }
        check(!requests.containsKey(request.id)) { "duplicate request: ${request.id}" }
        requests[request.id] = request

        val at: Timestamp = now()
        val kind: InterruptClass = request.interruptClass
        val decision: AttentionDecision = decide(request, at, kind)
        decisions[request.id] = decision

        if (decision.decision == Decision.GRANT) {
            recordGrant(kind, at)
            lastTouch[request.target.id] = timestampMillis(at)
        }
        if (decision.decision != Decision.GRANT) {
            lastTouch[request.id] = timestampMillis(at)
        }
        if (decision.debtAdded) {
            debt.add(
                DebtEntry(
                    request = request.id,
                    interruptClass = kind,
                    openedAt = at,
                    weight = request.urgency.toDouble(),
                ),
            )
        }
        return decision
    }

    private fun decide(request: AttentionRequest, at: Timestamp, kind: InterruptClass): AttentionDecision {
        if (kind == InterruptClass.SAFETY) {
            return AttentionDecision(request.id, Decision.GRANT, "safety_bypass", at, kind, debtAdded = false)
        }

        val hour: Int = Instant.parse(at).atZone(ZoneOffset.UTC).hour
        val quiet = policy.quietHours
        val inQuietHours: Boolean = when {
            quiet == null -> false
            quiet.start <= quiet.end -> hour >= quiet.start && hour < quiet.end
            else -> hour >= quiet.start || hour < quiet.end
        }
        if (inQuietHours) {
            return defer(request, at, kind, "quiet_hours")
        }

        val cooldown: Int = policy.cooldownMinutes ?: 0
        val touched: Long? = lastTouch[request.target.id]
        if (touched != null && cooldown > 0) {
            val elapsed: Double = (timestampMillis(at) - touched) / 60_000.0
            if (elapsed < cooldown) {
                return defer(request, at, kind, "cooldown")
            }
        }

        if (kind == InterruptClass.CURIOSITY && (request.expectedValue ?: 0.0) < (policy.curiosityFloor ?: 0.0)) {
            return AttentionDecision(request.id, Decision.DENY, "curiosity_below_floor", at, kind, debtAdded = false)
        }

        val limit: Int? = policy.budgets[kind]
        if (limit != null && grantedThisHour(kind, at) >= limit) {
            return if (request.fallback == Fallback.QUEUE) {
                defer(request, at, kind, "budget_exhausted")
            } else {
                AttentionDecision(request.id, Decision.DENY, "budget_exhausted", at, kind, debtAdded = false)
            }
        }

        return AttentionDecision(request.id, Decision.GRANT, "within_budget", at, kind, debtAdded = false)
    }

    private fun defer(request: AttentionRequest, at: Timestamp, kind: InterruptClass, reason: String): AttentionDecision {
        if (request.fallback == Fallback.DROP) {
            return AttentionDecision(request.id, Decision.DENY, "$reason:fallback_drop", at, kind, debtAdded = false)
        }
        lastTouch[request.target.id] = timestampMillis(at)
        return AttentionDecision(request.id, Decision.DEFER, reason, at, kind, debtAdded = true)
    }

    private fun grantedThisHour(kind: InterruptClass, at: Timestamp): Int {
        val nowMs: Long = timestampMillis(at)
        val window: List<Timestamp> = (grants[kind] ?: emptyList())
            .filter { stamp -> nowMs - timestampMillis(stamp) < 3_600_000 }
        grants[kind] = window.toMutableList()
        return window.size
    }

    private fun recordGrant(kind: InterruptClass, at: Timestamp) {
        val list: MutableList<Timestamp> = grants[kind] ?: ArrayList()
        list.add(at)
        grants[kind] = list
        // 参考实现以 kind 字符串为键再写一次 lastTouch；只影响同名 target id，保留原样。
        lastTouch[kind.wire] = timestampMillis(at)
    }

    /**
     * 结清一笔延迟请求：被回答、过期或撤回。参考实现不区分 outcome，只写 resolvedAt。
     */
    fun resolve(requestId: Id, outcome: DebtResolution = DebtResolution.ANSWERED) {
        val index = debt.indexOfFirst { entry -> entry.request == requestId && entry.resolvedAt == null }
        check(index >= 0) { "no open debt for $requestId" }
        debt[index] = debt[index].copy(resolvedAt = now())
        // outcome 与参考实现一样不参与记账。
    }

    /**
     * 债务分数：未结清时长（分钟）× 紧急度，四舍五入到整数。越高表示欠得越多。
     */
    fun debtScore(at: Timestamp = now()): Long {
        val nowMs: Long = timestampMillis(at)
        var sum: Double = 0.0
        for (entry in debt) {
            val end: Long = entry.resolvedAt?.let { timestampMillis(it) } ?: nowMs
            sum += maxOf(0.0, (end - timestampMillis(entry.openedAt)) / 60_000.0) * entry.weight
        }
        return Math.round(sum)
    }

    fun openDebt(): List<DebtEntry> = debt.filter { it.resolvedAt == null }

    /** 授予次数（供统一账本快照使用）。 */
    fun grantedCount(): Int = decisions.values.count { it.decision == Decision.GRANT }

    fun decisionOf(requestId: Id): AttentionDecision? = decisions[requestId]
}
