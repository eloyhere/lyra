package com.eloykim.lyra.amp.protocol.attention

import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Usage

/**
 * `src/attention/budget.ts` 的忠实移植：唯一计费点（A-3）。
 *
 * 2026 年的 CVE-2026-114386 说明计费不能放在沙箱内：绕过外层计数器的嵌套调用会让
 * 配额变成建议。这里 [Dispatcher.run] 在**执行前**扣费，所以循环编排的嵌套调用仍
 * 命中同一个计数器。
 */

/** 参考实现只对三个维度计费；`interrupts`/`money` 由其它层管理。 */
enum class BudgetDimension(val wire: String) {
    TOKENS("tokens"),
    CALLS("calls"),
    MS("ms"),
    ;

    companion object {
        fun fromWire(value: String): BudgetDimension =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知预算维度: $value")
    }
}

class BudgetExceeded(
    val dimension: BudgetDimension,
    val limit: Double,
    val observed: Double,
) : RuntimeException("budget exceeded on ${dimension.wire}: observed $observed > limit $limit") {

    val code: String = "budget_exceeded"
}

/** 单次调用的成本；未给出的维度按参考实现取默认值（tokens=0、calls=1、ms=0）。 */
data class CallCost(
    val tokens: Int? = null,
    val calls: Int? = null,
    val ms: Long? = null,
)

class Dispatcher(val lease: Budget) {

    private var usedTokens: Double = 0.0
    private var usedCalls: Double = 0.0
    private var usedMs: Double = 0.0

    /**
     * 先扣费再执行。任一维度超限都会抛 [BudgetExceeded]，此时 `block` 不会运行。
     * 与参考实现一致，扣费按 tokens → calls → ms 的顺序进行，因此后面的维度失败时
     * 前面的维度已经记账。
     */
    fun <T> run(cost: CallCost = CallCost(), block: () -> T): T {
        charge(BudgetDimension.TOKENS, (cost.tokens ?: 0).toDouble())
        charge(BudgetDimension.CALLS, (cost.calls ?: 1).toDouble())
        charge(BudgetDimension.MS, (cost.ms ?: 0L).toDouble())
        return block()
    }

    private fun charge(dimension: BudgetDimension, amount: Double) {
        if (amount == 0.0) {
            return
        }
        val limit = when (dimension) {
            BudgetDimension.TOKENS -> lease.tokens?.toDouble()
            BudgetDimension.CALLS -> lease.calls?.toDouble()
            BudgetDimension.MS -> lease.ms?.toDouble()
        }
        val current = when (dimension) {
            BudgetDimension.TOKENS -> usedTokens
            BudgetDimension.CALLS -> usedCalls
            BudgetDimension.MS -> usedMs
        }
        val next = current + amount
        if (limit != null && next > limit) {
            throw BudgetExceeded(dimension, limit, next)
        }
        when (dimension) {
            BudgetDimension.TOKENS -> usedTokens = next
            BudgetDimension.CALLS -> usedCalls = next
            BudgetDimension.MS -> usedMs = next
        }
    }

    fun usage(): Usage = Budget(
        tokens = usedTokens.toInt(),
        calls = usedCalls.toInt(),
        ms = usedMs.toLong(),
    )

    fun remaining(): Budget = Budget(
        tokens = maxOf(0.0, (lease.tokens ?: 0).toDouble() - usedTokens).toInt(),
        calls = maxOf(0.0, (lease.calls ?: 0).toDouble() - usedCalls).toInt(),
        ms = maxOf(0.0, (lease.ms ?: 0L).toDouble() - usedMs).toLong(),
    )
}
