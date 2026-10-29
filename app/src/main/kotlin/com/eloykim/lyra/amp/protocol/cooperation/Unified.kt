package com.eloykim.lyra.amp.protocol.cooperation

import com.eloykim.lyra.amp.protocol.attention.AttentionBroker
import com.eloykim.lyra.amp.protocol.attention.AttentionDecision
import com.eloykim.lyra.amp.protocol.attention.AttentionPolicy
import com.eloykim.lyra.amp.protocol.attention.AttentionRequest
import com.eloykim.lyra.amp.protocol.attention.CallCost
import com.eloykim.lyra.amp.protocol.attention.Dispatcher
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.nowTimestamp

/**
 * 统一账本（`src/cooperation/unified.ts` 的忠实移植）。
 *
 * 一份账本同时记录算力与人的注意力。两种预算仍然不可兑换（A-3），但它们共享
 * 时钟与快照，团队不能一边声称“还有预算”一边欠着中断。
 */
data class UnifiedSnapshot(
    val tokens: Int,
    val calls: Int,
    val ms: Long,
    val interruptsGranted: Int,
    val debtScore: Long,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "tokens" to jsonNumber(tokens),
        "calls" to jsonNumber(calls),
        "ms" to jsonNumber(ms),
        "interruptsGranted" to jsonNumber(interruptsGranted),
        "debtScore" to jsonNumber(debtScore),
    )

    companion object {
        fun fromJson(value: JsonValue): UnifiedSnapshot {
            val obj = value.asObject("unified snapshot")
            return UnifiedSnapshot(
                tokens = obj.requireInt("tokens"),
                calls = obj.requireInt("calls"),
                ms = obj.long("ms") ?: throw IllegalArgumentException("缺少整数字段 ms"),
                interruptsGranted = obj.requireInt("interruptsGranted"),
                debtScore = obj.long("debtScore") ?: throw IllegalArgumentException("缺少整数字段 debtScore"),
            )
        }
    }
}

/**
 * 一个用于算力与人类注意力的账户。
 *
 * 先扣费再派发，超预算的工作永远不会开始。注意力请求走 [AttentionBroker]；
 * 债务与授予次数进入同一快照。
 */
class UnifiedLedger(
    budget: Budget,
    private val now: () -> Timestamp = { nowTimestamp() },
    policy: AttentionPolicy = AttentionPolicy(),
) {

    val dispatcher: Dispatcher = Dispatcher(budget)
    val attention: AttentionBroker = AttentionBroker(now, policy)

    /**
     * 派发前扣费；超预算的工作不会开始。
     */
    fun <T> spend(fn: () -> T, cost: CallCost): T = dispatcher.run(cost, fn)

    fun requestAttention(request: AttentionRequest): AttentionDecision = attention.submit(request)

    fun snapshot(): UnifiedSnapshot {
        val usage = dispatcher.usage()
        return UnifiedSnapshot(
            tokens = usage.tokens ?: 0,
            calls = usage.calls ?: 0,
            ms = usage.ms ?: 0,
            interruptsGranted = attention.grantedCount(),
            debtScore = attention.debtScore(now()),
        )
    }

    fun remaining(): Budget = dispatcher.remaining()
}
