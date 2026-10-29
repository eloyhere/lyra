package com.eloykim.lyra.amp.protocol.cooperation

import com.eloykim.lyra.amp.protocol.attention.BudgetExceeded
import com.eloykim.lyra.amp.protocol.attention.CallCost
import com.eloykim.lyra.amp.protocol.attention.Decision
import com.eloykim.lyra.amp.protocol.attention.Fallback
import com.eloykim.lyra.amp.protocol.attention.InterruptClass
import com.eloykim.lyra.amp.protocol.attention.AttentionRequest
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `src/cooperation/unified.ts` 的行为锁定：同一账户同时记算力与注意力。
 */
class UnifiedTest {

    private val at: String = "2026-10-24T10:00:00Z"

    private fun ledger(tokens: Int = 10, calls: Int = 2, ms: Long = 1_000L): UnifiedLedger =
        UnifiedLedger(
            budget = Budget(tokens = tokens, calls = calls, ms = ms),
            now = { at },
        )

    @Test
    fun `spend 先扣费再执行并进入快照`() {
        val ledger = ledger()
        val result = ledger.spend({ "done" }, CallCost(tokens = 5, calls = 1, ms = 100))
        assertEquals("done", result)

        val snapshot = ledger.snapshot()
        assertEquals(5, snapshot.tokens)
        assertEquals(1, snapshot.calls)
        assertEquals(100L, snapshot.ms)
        assertEquals(0, snapshot.interruptsGranted)
        assertEquals(0L, snapshot.debtScore)

        val remaining = ledger.remaining()
        assertEquals(5, remaining.tokens)
        assertEquals(1, remaining.calls)
        assertEquals(900L, remaining.ms)
        assertEquals(snapshot, UnifiedSnapshot.fromJson(snapshot.toJson()))
    }

    @Test
    fun `超预算的工作根本不会开始`() {
        val ledger = ledger(tokens = 10)
        var ran = false
        try {
            ledger.spend({ ran = true }, CallCost(tokens = 100, calls = 1))
            throw AssertionError("expected BudgetExceeded")
        } catch (error: BudgetExceeded) {
            assertEquals("budget_exceeded", error.code)
            assertEquals("tokens", error.dimension.wire)
        }
        assertTrue(ran.not())
        assertEquals(0, ledger.snapshot().tokens)
    }

    @Test
    fun `安全中断绕过预算并计入授予数`() {
        val ledger = ledger()
        val request = AttentionRequest(
            id = "ar.1",
            from = Ref(RefKind.HUMAN, "human.1"),
            to = Ref(RefKind.AGENT, "a.agent"),
            target = Ref(RefKind.EVENT, "ev.incident"),
            interruptClass = InterruptClass.SAFETY,
            urgency = 5,
            whyNow = "错误率仍在上升",
            ifIgnored = "错过了回滚窗口",
            cost = Budget(calls = 0),
            fallback = Fallback.QUEUE,
        )
        val decision = ledger.requestAttention(request)
        assertEquals(Decision.GRANT, decision.decision)
        assertEquals("safety_bypass", decision.reason)
        assertEquals(1, ledger.snapshot().interruptsGranted)
        assertEquals(0L, ledger.snapshot().debtScore)

        val other = request.copy(
            id = "ar.2",
            target = Ref(RefKind.EVENT, "ev.other"),
            interruptClass = InterruptClass.CURIOSITY,
            expectedValue = 0.1,
        )
        assertEquals(Decision.DENY, ledger.requestAttention(other).decision)
        assertEquals(1, ledger.snapshot().interruptsGranted)
    }

    @Test
    fun `统一快照与注意力字段一致`() {
        val ledger = ledger()
        ledger.requestAttention(
            AttentionRequest(
                id = "ar.1",
                from = Ref(RefKind.HUMAN, "human.1"),
                to = Ref(RefKind.AGENT, "a.agent"),
                target = Ref(RefKind.EVENT, "ev.incident"),
                interruptClass = InterruptClass.SAFETY,
                urgency = 3,
                whyNow = "需要立即确认",
                ifIgnored = "继续在错误假设上工作",
                cost = Budget(),
                fallback = Fallback.ESCALATE,
            ),
        )
        ledger.spend({ Unit }, CallCost(tokens = 2, calls = 1, ms = 10))
        val snapshot = ledger.snapshot()
        assertEquals(2, snapshot.tokens)
        assertEquals(1, snapshot.calls)
        assertEquals(10L, snapshot.ms)
        assertEquals(1, snapshot.interruptsGranted)
        assertEquals(0L, snapshot.debtScore)
        assertEquals(ledger.snapshot(), UnifiedSnapshot.fromJson(snapshot.toJson()))
    }
}
