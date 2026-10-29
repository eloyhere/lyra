package com.eloykim.lyra.amp.protocol.attention

import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptTest {

    private val quietAllDay = AttentionPolicy(quietHours = QuietHours(0, 24), cooldownMinutes = 0)
    private val openPolicy = AttentionPolicy(quietHours = null, cooldownMinutes = 0)

    @Test
    fun `安全类绕过预算与安静时段`() {
        val broker = AttentionBroker({ ATTENTION_TEST_AT }, quietAllDay)

        val safety = broker.submit(attentionRequestFixture(id = "ar.s", interruptClass = InterruptClass.SAFETY))
        assertEquals(Decision.GRANT, safety.decision)
        assertEquals("safety_bypass", safety.reason)
        assertFalse(safety.debtAdded)

        val commitment = broker.submit(
            attentionRequestFixture(
                id = "ar.c",
                target = attentionRef("u.other", RefKind.HUMAN),
                interruptClass = InterruptClass.COMMITMENT,
            ),
        )
        assertEquals(Decision.DEFER, commitment.decision)
        assertEquals("quiet_hours", commitment.reason)
        assertTrue(commitment.debtAdded)
    }

    @Test
    fun `每类每小时预算耗尽后延迟或拒绝`() {
        val policy = openPolicy.withBudgets(mapOf(InterruptClass.COMMITMENT to 1))
        val broker = AttentionBroker({ ATTENTION_TEST_AT }, policy)

        assertEquals(
            Decision.GRANT,
            broker.submit(attentionRequestFixture(id = "ar.1", target = attentionRef("u.a", RefKind.HUMAN))).decision,
        )

        val deferred = broker.submit(attentionRequestFixture(id = "ar.2", target = attentionRef("u.b", RefKind.HUMAN)))
        assertEquals(Decision.DEFER, deferred.decision)
        assertEquals("budget_exhausted", deferred.reason)
        assertTrue(deferred.debtAdded)

        val denied = broker.submit(
            attentionRequestFixture(
                id = "ar.3",
                target = attentionRef("u.c", RefKind.HUMAN),
                fallback = Fallback.DROP,
            ),
        )
        assertEquals(Decision.DENY, denied.decision)
        assertEquals("budget_exhausted", denied.reason)
        assertFalse(denied.debtAdded)
    }

    @Test
    fun `drop 兜底在安静时段变成拒绝`() {
        val broker = AttentionBroker({ ATTENTION_TEST_AT }, quietAllDay)
        val denied = broker.submit(
            attentionRequestFixture(id = "ar.q", fallback = Fallback.DROP, interruptClass = InterruptClass.OPPORTUNITY),
        )
        assertEquals(Decision.DENY, denied.decision)
        assertEquals("quiet_hours:fallback_drop", denied.reason)
    }

    @Test
    fun `curiosity 必须达到期望价值下限`() {
        val broker = AttentionBroker({ ATTENTION_TEST_AT }, openPolicy)

        val denied = broker.submit(
            attentionRequestFixture(
                id = "ar.low",
                target = attentionRef("u.x", RefKind.HUMAN),
                interruptClass = InterruptClass.CURIOSITY,
                expectedValue = 0.5,
            ),
        )
        assertEquals(Decision.DENY, denied.decision)
        assertEquals("curiosity_below_floor", denied.reason)

        val granted = broker.submit(
            attentionRequestFixture(
                id = "ar.high",
                target = attentionRef("u.y", RefKind.HUMAN),
                interruptClass = InterruptClass.CURIOSITY,
                expectedValue = 0.9,
            ),
        )
        assertEquals(Decision.GRANT, granted.decision)
        assertEquals("within_budget", granted.reason)
    }

    @Test
    fun `冷却窗口内对同一目标延迟`() {
        var now = ATTENTION_TEST_AT
        val broker = AttentionBroker({ now }, AttentionPolicy(quietHours = null, cooldownMinutes = 10))
        val target = attentionRef("u.target", RefKind.HUMAN)

        assertEquals(
            Decision.GRANT,
            broker.submit(
                attentionRequestFixture(id = "ar.1", target = target, interruptClass = InterruptClass.OPPORTUNITY),
            ).decision,
        )

        now = "2026-10-24T01:05:00Z"
        val deferred = broker.submit(
            attentionRequestFixture(id = "ar.2", target = target, interruptClass = InterruptClass.OPPORTUNITY),
        )
        assertEquals(Decision.DEFER, deferred.decision)
        assertEquals("cooldown", deferred.reason)

        now = "2026-10-24T01:20:00Z"
        val granted = broker.submit(
            attentionRequestFixture(id = "ar.3", target = target, interruptClass = InterruptClass.OPPORTUNITY),
        )
        assertEquals(Decision.GRANT, granted.decision)
    }

    @Test
    fun `重复请求被拒绝`() {
        val broker = AttentionBroker({ ATTENTION_TEST_AT }, openPolicy)
        broker.submit(attentionRequestFixture(id = "ar.dup"))
        assertThrows(IllegalStateException::class.java) {
            broker.submit(attentionRequestFixture(id = "ar.dup"))
        }
    }

    @Test
    fun `延迟请求累积年龄加权债务`() {
        var now = ATTENTION_TEST_AT
        val broker = AttentionBroker(
            { now },
            openPolicy.withBudgets(mapOf(InterruptClass.OPPORTUNITY to 0)),
        )
        val decision = broker.submit(
            attentionRequestFixture(id = "ar.d", interruptClass = InterruptClass.OPPORTUNITY, urgency = 3),
        )
        assertEquals(Decision.DEFER, decision.decision)
        assertEquals(1, broker.openDebt().size)

        now = "2026-10-24T01:10:00Z"
        assertEquals(30L, broker.debtScore())

        broker.resolve("ar.d")
        assertEquals(0, broker.openDebt().size)
        // 结清后债务不再增长。
        assertEquals(30L, broker.debtScore("2026-10-24T02:00:00Z"))
        assertThrows(IllegalStateException::class.java) { broker.resolve("ar.d") }
    }

    @Test
    fun `账本为每个请求生成 Interrupt 与 Receipt`() {
        val receipts = ArrayList<AttentionReceipt>()
        val ledger = AttentionLedger(
            AttentionLedgerOptions(
                now = { ATTENTION_TEST_AT },
                policy = openPolicy,
                onReceipt = { receipts.add(it) },
            ),
        )

        val submission = ledger.submit(attentionRequestFixture(id = "ar.5"))

        assertEquals("int.1", submission.interrupt.id)
        assertEquals("ar.5", submission.interrupt.request)
        assertEquals(InterruptStatus.GRANTED, submission.interrupt.status)
        assertTrue(submission.interrupt.granted)
        assertEquals("rc.ask.1", submission.interrupt.receipt)
        assertEquals(3.0, submission.interrupt.debtWeight, 0.0)
        assertEquals("within_budget", submission.interrupt.reason)

        assertEquals(ReceiptOp.ASK, submission.receipt.op)
        assertEquals(Outcome.SUCCESS, submission.receipt.outcome)
        assertEquals(Budget(interrupts = 1, ms = 120_000), submission.receipt.usage)
        assertEquals("commitment:granted:within_budget", submission.receipt.note)
        assertEquals(1, receipts.size)

        assertEquals(1, ledger.grantedCount())
        assertEquals(1, ledger.all().size)
        assertEquals(submission.interrupt, ledger.get("ar.5"))

        assertEquals(submission.interrupt, InterruptRecord.fromJson(submission.interrupt.toJson()))
        assertEquals(submission.receipt, AttentionReceipt.fromJson(submission.receipt.toJson()))
        assertEquals("commitment", submission.interrupt.toJson().string("class"))
    }

    @Test
    fun `拒绝的请求生成 blocked receipt 且不产生债务`() {
        val ledger = AttentionLedger(
            AttentionLedgerOptions(now = { ATTENTION_TEST_AT }, policy = quietAllDay),
        )
        val submission = ledger.submit(
            attentionRequestFixture(
                id = "ar.d",
                fallback = Fallback.DROP,
                cost = Budget(ms = 1_000),
            ),
        )

        assertEquals(InterruptStatus.DENIED, submission.interrupt.status)
        assertFalse(submission.interrupt.granted)
        assertNull(submission.interrupt.expires)
        assertEquals(Outcome.BLOCKED, submission.receipt.outcome)
        assertEquals("commitment:denied:quiet_hours:fallback_drop", submission.receipt.note)
        assertEquals(0, submission.receipt.usage.interrupts)
        assertEquals(0, ledger.grantedCount())
        assertTrue(ledger.open().isEmpty())
        assertEquals(0L, ledger.debtScore())
    }

    @Test
    fun `延迟请求在响应窗口后过期且可重复清扫`() {
        val ledger = AttentionLedger(
            AttentionLedgerOptions(
                now = { ATTENTION_TEST_AT },
                policy = quietAllDay,
            ),
        )

        val submission = ledger.submit(
            attentionRequestFixture(id = "ar.q", fallback = Fallback.QUEUE, cost = Budget(ms = 120_000)),
        )
        assertEquals(InterruptStatus.DEFERRED, submission.interrupt.status)
        assertEquals("2026-10-24T01:01:00.000Z", submission.interrupt.expires)
        assertEquals(Outcome.PARTIAL, submission.receipt.outcome)
        assertEquals(0, submission.receipt.usage.interrupts)
        assertEquals(1, ledger.open().size)

        assertTrue(ledger.sweep("2026-10-24T01:00:59Z").isEmpty())

        val expired = ledger.sweep("2026-10-24T01:01:00Z")
        assertEquals(1, expired.size)
        assertEquals(InterruptStatus.EXPIRED, expired.single().status)
        assertTrue(ledger.open().isEmpty())
        assertTrue("过期清扫必须幂等", ledger.sweep("2026-10-24T02:00:00Z").isEmpty())
        assertEquals(InterruptStatus.EXPIRED, ledger.get("ar.q")?.status)
    }

    @Test
    fun `延迟请求可被回答并停止计债`() {
        var now = ATTENTION_TEST_AT
        val ledger = AttentionLedger(
            AttentionLedgerOptions(now = { now }, policy = quietAllDay),
        )
        ledger.submit(attentionRequestFixture(id = "ar.1"))

        now = "2026-10-24T01:02:00Z"
        val resolved = ledger.resolve("ar.1")
        assertEquals(InterruptStatus.ANSWERED, resolved?.status)
        assertTrue(ledger.open().isEmpty())
        // 2 分钟 × 紧急度 3。
        assertEquals(6L, ledger.debtScore("2026-10-24T02:00:00Z"))
        assertNull(ledger.resolve("ar.1"))
        assertThrows(IllegalArgumentException::class.java) {
            ledger.resolve("ar.1", InterruptStatus.GRANTED)
        }
    }

    @Test
    fun `重复提交被拒绝`() {
        val ledger = AttentionLedger(AttentionLedgerOptions(now = { ATTENTION_TEST_AT }, policy = openPolicy))
        ledger.submit(attentionRequestFixture(id = "ar.1"))
        assertThrows(IllegalStateException::class.java) { ledger.submit(attentionRequestFixture(id = "ar.1")) }
    }

    @Test
    fun `外部内容不能占据 goal 槽或超过份额`() {
        assertTrue(admitExternal(ExternalAdmissionInput(role = "web", share = 0.1)).ok)

        val goal = admitExternal(ExternalAdmissionInput(role = "web", share = 0.0, goalSlot = true))
        assertFalse(goal.ok)
        assertEquals("external content cannot occupy the goal slot", goal.reason)

        val tooMuch = admitExternal(ExternalAdmissionInput(role = "web", share = 0.2))
        assertFalse(tooMuch.ok)
        assertTrue(tooMuch.reason.orEmpty().contains("exceeds"))

        val invalid = admitExternal(ExternalAdmissionInput(role = "web", share = Double.NaN))
        assertFalse(invalid.ok)
        assertTrue(invalid.reason.orEmpty().contains("invalid external share"))

        assertTrue(admitExternal(ExternalAdmissionInput(role = "web", share = 0.2, maxShare = 0.3)).ok)
        assertThrows(IllegalArgumentException::class.java) {
            admitExternal(ExternalAdmissionInput(role = "web", share = 0.1, maxShare = 1.5))
        }
    }
}
