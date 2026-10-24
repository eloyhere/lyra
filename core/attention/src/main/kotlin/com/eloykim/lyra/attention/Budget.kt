package com.eloykim.lyra.attention

/**
 * 注意力预算（AMP A-3 / §3.9 / §5.1）。
 *
 * 五维向量，Lyra 在设备上只用其中四维（money 由模型单价折算，暂不计入配额）。
 * 关键约束：**唯一计费点**。任何嵌套调用（OCR 重试、翻译、AIDL 重绑）都必须经过
 * 这里，否则计费会被绕过——AMP 引用的 CVE-2026-114386 正是「在沙箱内计费」的后果。
 */
data class BudgetVector(
    val tokens: Int = 0,
    val ms: Long = 0,
    val calls: Int = 0,
    val interrupts: Int = 0,
    /** 微美元（1e-6 USD）；1M tokens × $1 = 1_000_000 micros。 */
    val moneyMicros: Long = 0,
) {
    operator fun plus(other: BudgetVector) = BudgetVector(
        tokens = tokens + other.tokens,
        ms = ms + other.ms,
        calls = calls + other.calls,
        interrupts = interrupts + other.interrupts,
    )

    fun fitsIn(other: BudgetVector): Boolean =
        tokens <= other.tokens && ms <= other.ms && calls <= other.calls && interrupts <= other.interrupts && moneyMicros <= other.moneyMicros
}

enum class BudgetDimension { TOKENS, MS, CALLS, INTERRUPTS, MONEY }

enum class DegradeLevel {
    /** 按字段投影后再注入。 */
    PROJECT,

    /** 转句柄，只注入摘要 + 游标。 */
    HANDLE,
}

sealed interface Admission {
    data object Allow : Admission
    data class Degrade(val level: DegradeLevel, val dimension: BudgetDimension) : Admission
    data class Deny(val dimension: BudgetDimension, val detail: String) : Admission
}

/**
 * 预算调度器。
 *
 * - [startCycle] 发放新周期预算；未用完的部分**不累计**（防止「省下来的注意力」变成扩张）；
 * - [charge] 先判后记，超限不扣也不静默截断；
 * - [admitResult] 决定超限结果是投影、转句柄还是直接拒绝。
 */
class BudgetScheduler(
    allocation: BudgetVector = BudgetVector(tokens = 4_000, ms = 30_000, calls = 6),
) {

    private val lock = Any()
    private var allocation = allocation
    private var spent = BudgetVector()

    fun startCycle(next: BudgetVector) = synchronized(lock) {
        allocation = next
        spent = BudgetVector()
    }

    fun allocation(): BudgetVector = synchronized(lock) { allocation }

    fun spent(): BudgetVector = synchronized(lock) { spent }

    fun remaining(): BudgetVector = synchronized(lock) {
        BudgetVector(
            tokens = (allocation.tokens - spent.tokens).coerceAtLeast(0),
            ms = (allocation.ms - spent.ms).coerceAtLeast(0),
            calls = (allocation.calls - spent.calls).coerceAtLeast(0),
            interrupts = (allocation.interrupts - spent.interrupts).coerceAtLeast(0),
            moneyMicros = (allocation.moneyMicros - spent.moneyMicros).coerceAtLeast(0),
        )
    }

    /** 先判后记：不超限才计入。 */
    fun charge(dimension: BudgetDimension, amount: Long): Admission = synchronized(lock) {
        val current = remaining()
        val available = when (dimension) {
            BudgetDimension.TOKENS -> current.tokens.toLong()
            BudgetDimension.MS -> current.ms
            BudgetDimension.CALLS -> current.calls.toLong()
            BudgetDimension.INTERRUPTS -> current.interrupts.toLong()
            BudgetDimension.MONEY -> current.moneyMicros
        }
        if (amount > available) {
            return Admission.Deny(dimension, "剩余 $available，需要 $amount")
        }
        spent = when (dimension) {
            BudgetDimension.TOKENS -> spent.copy(tokens = spent.tokens + amount.toInt())
            BudgetDimension.MS -> spent.copy(ms = spent.ms + amount)
            BudgetDimension.CALLS -> spent.copy(calls = spent.calls + amount.toInt())
            BudgetDimension.INTERRUPTS -> spent.copy(interrupts = spent.interrupts + amount.toInt())
            BudgetDimension.MONEY -> spent.copy(moneyMicros = spent.moneyMicros + amount)
        }
        Admission.Allow
    }

    /**
     * 结果整形决策（A-3 的降级阶梯）。
     *
     * 结果 ≤ 剩余 → 直接注入；否则能投影就投影；再不行转句柄；无法转句柄则拒绝。
     */
    fun admitResult(estimatedTokens: Int, canProject: Boolean, canHandle: Boolean): Admission {
        val left = remaining().tokens
        if (estimatedTokens <= left) return Admission.Allow
        if (canProject) return Admission.Degrade(DegradeLevel.PROJECT, BudgetDimension.TOKENS)
        if (canHandle) return Admission.Degrade(DegradeLevel.HANDLE, BudgetDimension.TOKENS)
        return Admission.Deny(BudgetDimension.TOKENS, "结果约 $estimatedTokens tokens，剩余 $left，且无法转句柄")
    }
}
