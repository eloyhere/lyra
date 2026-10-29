package com.eloykim.lyra.amp.protocol.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `src/policy/guard.ts` 的行为锁定：滑动限流、连续失败熔断、金额上限。
 */
class GuardTest {

    private var now: Long = 0L

    private fun guard(
        rate: RateLimitOptions? = null,
        breaker: BreakerOptions? = null,
        costCeiling: Double? = null,
    ): Guard = Guard(
        GuardOptions(
            now = { now },
            rate = rate,
            breaker = breaker,
            costCeiling = costCeiling,
        ),
    )

    @Test
    fun `滑动窗口严格拒绝超过 limit 的派发`() {
        val guard = guard(rate = RateLimitOptions(limit = 2, intervalMs = 1_000L))
        guard.beforeDispatch()
        guard.beforeDispatch()

        try {
            guard.beforeDispatch()
            throw AssertionError("expected RateLimited")
        } catch (error: RateLimited) {
            assertEquals("rate_limited", error.code)
            assertEquals(1_000.0, error.retryAfterMs, 0.0)
            assertTrue(error.message!!.contains("retry after 1000ms"))
        }

        now = 500L
        try {
            guard.beforeDispatch()
            throw AssertionError("expected RateLimited")
        } catch (error: RateLimited) {
            assertEquals(500.0, error.retryAfterMs, 0.0)
        }

        now = 1_000L
        guard.beforeDispatch()
        assertEquals(1, guard.snapshot().windowCount)
    }

    @Test
    fun `快照报告窗口计数与重试等待`() {
        val guard = guard(rate = RateLimitOptions(limit = 2, intervalMs = 1_000L))
        guard.beforeDispatch()
        guard.beforeDispatch()
        val snapshot = guard.snapshot()
        assertEquals(2, snapshot.windowCount)
        assertEquals(1_000.0, snapshot.retryAfterMs, 0.0)
        assertFalse(snapshot.open)

        now = 250L
        assertEquals(750.0, guard.snapshot().retryAfterMs, 0.0)
    }

    @Test
    fun `连续失败打开电路并在冷却后放行`() {
        val guard = guard(breaker = BreakerOptions(failures = 2, cooldownMs = 1_000L))
        guard.recordFailure()
        guard.beforeDispatch()
        guard.recordFailure()

        assertTrue(guard.snapshot().open)
        try {
            guard.beforeDispatch()
            throw AssertionError("expected CircuitOpen")
        } catch (error: CircuitOpen) {
            assertEquals("circuit_open", error.code)
            assertEquals(1_000.0, error.retryAfterMs, 0.0)
        }

        now = 1_000L
        guard.beforeDispatch()
        val snapshot = guard.snapshot()
        assertFalse(snapshot.open)
        assertEquals(0, snapshot.failures)
    }

    @Test
    fun `成功关闭电路并清零失败计数`() {
        val guard = guard(breaker = BreakerOptions(failures = 3, cooldownMs = 1_000L))
        guard.recordFailure()
        guard.recordFailure()
        assertEquals(2, guard.snapshot().failures)
        guard.recordSuccess()
        assertEquals(0, guard.snapshot().failures)
        assertFalse(guard.snapshot().open)
        guard.beforeDispatch()
    }

    @Test
    fun `金额上限在派发前检查且失败不记账`() {
        val guard = guard(costCeiling = 5.0)
        guard.beforeDispatch(money = 3.0)
        assertEquals(3.0, guard.snapshot().spent, 0.0)

        try {
            guard.beforeDispatch(money = 3.0)
            throw AssertionError("expected CostCeilingExceeded")
        } catch (error: CostCeilingExceeded) {
            assertEquals("budget_exceeded", error.code)
            assertEquals("money", error.dimension)
            assertEquals(5.0, error.limit, 0.0)
            assertEquals(6.0, error.observed, 0.0)
            assertTrue(error.message!!.contains("observed 6 > limit 5"))
        }
        assertEquals(3.0, guard.snapshot().spent, 0.0)
    }

    @Test
    fun `限流与熔断同时存在时快照取较大等待`() {
        val guard = guard(
            rate = RateLimitOptions(limit = 1, intervalMs = 1_000L),
            breaker = BreakerOptions(failures = 1, cooldownMs = 2_000L),
        )
        guard.beforeDispatch()
        guard.recordFailure()
        // 电路在 now=0 打开，冷却 2000ms；限流窗口 1000ms。
        now = 100L
        val snapshot = guard.snapshot()
        assertEquals(1_900.0, snapshot.retryAfterMs, 0.0)
        assertTrue(snapshot.open)
    }

    @Test
    fun `非法配置在构造期拒绝`() {
        val invalid = listOf(
            { RateLimitOptions(limit = -1, intervalMs = 1_000L) },
            { RateLimitOptions(limit = 1, intervalMs = 0L) },
            { BreakerOptions(failures = 0, cooldownMs = 1L) },
            { BreakerOptions(failures = 1, cooldownMs = -1L) },
            { guard(costCeiling = -0.1) },
        )
        invalid.forEach { action ->
            try {
                action()
                throw AssertionError("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `快照 JSON 往返`() {
        val guard = guard(rate = RateLimitOptions(limit = 3, intervalMs = 100L), costCeiling = 10.0)
        guard.beforeDispatch(money = 1.5)
        val snapshot = guard.snapshot()
        assertEquals(snapshot, GuardSnapshot.fromJson(snapshot.toJson()))
        assertEquals(
            RateLimitOptions(3, 100L),
            RateLimitOptions.fromJson(RateLimitOptions(3, 100L).toJson()),
        )
        assertEquals(
            BreakerOptions(2, 50L),
            BreakerOptions.fromJson(BreakerOptions(2, 50L).toJson()),
        )
    }
}
