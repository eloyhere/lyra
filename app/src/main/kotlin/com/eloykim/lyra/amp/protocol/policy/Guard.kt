package com.eloykim.lyra.amp.protocol.policy

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.asObject

/**
 * 派发前的限流、熔断与金钱护栏（`src/policy/guard.ts` 的忠实移植）。
 *
 * [com.eloykim.lyra.amp.protocol.attention.Dispatcher] 执行的是硬预算（A-3），
 * 它不限速、不阻止打爆失败依赖，也不记金钱。这些是控制手段而不是计量表，
 * 属于派发之前，而不是报告之中。
 */

/** 滑动窗口限流。 */
data class RateLimitOptions(
    /** 一个滑动窗口内允许的最大派发次数。 */
    val limit: Int,
    /** 窗口长度（毫秒）。 */
    val intervalMs: Long,
) {
    init {
        require(limit >= 0) { "rate.limit must be a non-negative integer" }
        require(intervalMs > 0L) { "rate.intervalMs must be a positive finite number" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "limit" to jsonNumber(limit),
        "intervalMs" to jsonNumber(intervalMs),
    )

    companion object {
        fun fromJson(value: JsonValue): RateLimitOptions {
            val obj = value.asObject("rate limit")
            return RateLimitOptions(
                limit = obj.requireInt("limit"),
                intervalMs = obj.long("intervalMs")
                    ?: throw IllegalArgumentException("缺少整数字段 intervalMs"),
            )
        }
    }
}

/** 连续失败熔断。 */
data class BreakerOptions(
    /** 打开电路的连续失败次数。 */
    val failures: Int,
    /** 打开后到半开试探之间的冷却时间。 */
    val cooldownMs: Long,
) {
    init {
        require(failures > 0) { "breaker.failures must be a positive integer" }
        require(cooldownMs >= 0L) { "breaker.cooldownMs must be a non-negative finite number" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "failures" to jsonNumber(failures),
        "cooldownMs" to jsonNumber(cooldownMs),
    )

    companion object {
        fun fromJson(value: JsonValue): BreakerOptions {
            val obj = value.asObject("breaker")
            return BreakerOptions(
                failures = obj.requireInt("failures"),
                cooldownMs = obj.long("cooldownMs")
                    ?: throw IllegalArgumentException("缺少整数字段 cooldownMs"),
            )
        }
    }
}

/** 护栏选项。 */
data class GuardOptions(
    /** 注入时钟（毫秒）。默认 `System.currentTimeMillis()`，绝不直接散落在逻辑里。 */
    val now: () -> Long = { System.currentTimeMillis() },
    val rate: RateLimitOptions? = null,
    val breaker: BreakerOptions? = null,
    /** 允许累计承诺的最大金额。 */
    val costCeiling: Double? = null,
)

/** 护栏快照。 */
data class GuardSnapshot(
    /** 通过 `beforeDispatch` 累计承诺的金额。 */
    val spent: Double,
    /** 自上次成功以来的连续失败次数。 */
    val failures: Int,
    /** 电路打开期间为 true。 */
    val open: Boolean,
    /** 当前限流窗口内记录的派发次数。 */
    val windowCount: Int,
    /** 被阻断时，下一次允许尝试前的毫秒数。 */
    val retryAfterMs: Double,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "spent" to jsonNumber(spent),
        "failures" to jsonNumber(failures),
        "open" to JsonValue.Bool(open),
        "windowCount" to jsonNumber(windowCount),
        "retryAfterMs" to jsonNumber(retryAfterMs),
    )

    companion object {
        fun fromJson(value: JsonValue): GuardSnapshot {
            val obj = value.asObject("guard snapshot")
            return GuardSnapshot(
                spent = obj.double("spent") ?: throw IllegalArgumentException("缺少数字字段 spent"),
                failures = obj.requireInt("failures"),
                open = obj.bool("open") ?: throw IllegalArgumentException("缺少布尔字段 open"),
                windowCount = obj.requireInt("windowCount"),
                retryAfterMs = obj.double("retryAfterMs")
                    ?: throw IllegalArgumentException("缺少数字字段 retryAfterMs"),
            )
        }
    }
}

/** 限流拒绝。 */
class RateLimited(val retryAfterMs: Double) :
    RuntimeException("rate limit exceeded; retry after ${retryText(retryAfterMs)}ms") {

    val code: String = "rate_limited"
}

/** 熔断拒绝。 */
class CircuitOpen(val retryAfterMs: Double) :
    RuntimeException("circuit open; retry after ${retryText(retryAfterMs)}ms") {

    val code: String = "circuit_open"
}

/** 金额上限拒绝。 */
class CostCeilingExceeded(val limit: Double, val observed: Double) :
    RuntimeException("budget exceeded on money: observed ${CanonicalJson.formatNumber(observed)} > limit ${CanonicalJson.formatNumber(limit)}") {

    val code: String = "budget_exceeded"
    val dimension: String = "money"
}

private fun retryText(retryAfterMs: Double): Long =
    maxOf(0L, Math.ceil(retryAfterMs).toLong())

private fun assertNonNegative(value: Double, name: String) {
    if (!value.isFinite() || value < 0.0) {
        throw IllegalArgumentException("$name must be a non-negative finite number")
    }
}

/**
 * 一个小而同步的护栏。没有定时器、没有 sleep：如何处理 [RateLimited] /
 * [CircuitOpen] 由宿主决定（排队、退避或失败）。
 *
 * 限流窗口是滑动的；断路器基于连续失败。一次成功派发会关闭断路器。护栏在
 * `beforeDispatch` 时记账，与派发器的“先扣费再执行”规则一致。
 */
class Guard(options: GuardOptions = GuardOptions()) {

    private val clock: () -> Long = options.now
    private val rate: RateLimitOptions? = options.rate
    private val breaker: BreakerOptions? = options.breaker
    private val costCeiling: Double? = options.costCeiling

    private val events = ArrayDeque<Long>()
    private var failures = 0
    private var openedAt: Long? = null
    private var spent = 0.0

    init {
        options.costCeiling?.let { assertNonNegative(it, "costCeiling") }
    }

    /**
     * 检查所有控制项并承诺金额。任何工具运行之前就会抛错。
     */
    fun beforeDispatch(money: Double = 0.0) {
        assertNonNegative(money, "money")
        val now = clock()

        if (rate != null) {
            prune(now)
            val window = rate
            if (events.size >= window.limit) {
                val oldest = events.firstOrNull() ?: now
                throw RateLimited((window.intervalMs - (now - oldest)).toDouble())
            }
        }

        val circuit = breaker
        val opened = openedAt
        if (circuit != null && opened != null) {
            val elapsed = now - opened
            if (elapsed < circuit.cooldownMs) {
                throw CircuitOpen((circuit.cooldownMs - elapsed).toDouble())
            }
            openedAt = null
            failures = 0
        }

        val ceiling = costCeiling
        if (ceiling != null && spent + money > ceiling) {
            throw CostCeilingExceeded(ceiling, spent + money)
        }

        if (rate != null) {
            events.addLast(now)
        }
        spent += money
    }

    fun recordSuccess() {
        failures = 0
        openedAt = null
    }

    fun recordFailure() {
        failures += 1
        val circuit = breaker
        if (circuit != null && failures >= circuit.failures) {
            openedAt = clock()
        }
    }

    fun snapshot(): GuardSnapshot {
        val now = clock()
        if (rate != null) {
            prune(now)
        }
        var retryAfterMs = 0.0
        val window = rate
        if (window != null && events.size >= window.limit) {
            val oldest = events.firstOrNull() ?: now
            retryAfterMs = maxOf(0.0, (window.intervalMs - (now - oldest)).toDouble())
        }
        val circuit = breaker
        val opened = openedAt
        if (circuit != null && opened != null) {
            retryAfterMs = maxOf(retryAfterMs, (circuit.cooldownMs - (now - opened)).toDouble())
        }
        return GuardSnapshot(
            spent = spent,
            failures = failures,
            open = openedAt != null,
            windowCount = events.size,
            retryAfterMs = retryAfterMs,
        )
    }

    private fun prune(now: Long) {
        val window = rate ?: return
        val horizon = now - window.intervalMs
        while (events.isNotEmpty() && events.first() <= horizon) {
            events.removeFirst()
        }
    }
}
