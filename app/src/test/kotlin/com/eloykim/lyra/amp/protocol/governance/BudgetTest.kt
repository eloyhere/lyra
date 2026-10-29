package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.types.Budget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetTest {

    private val clock = testClock()

    private fun budget(
        globalTokens: Int? = 1_000,
        reserveTokens: Int? = 100,
        perspectives: List<PerspectiveLedgerConfig> = emptyList(),
        agents: List<AgentLedgerConfig> = emptyList(),
    ): GovernanceBudget = GovernanceBudget(
        GovernanceBudgetOptions(
            global = LedgerConfig(Budget(tokens = globalTokens), Elasticity.LOW),
            reserve = LedgerConfig(Budget(tokens = reserveTokens), Elasticity.LOW),
            perspectives = perspectives,
            agents = agents,
            now = clock,
        ),
    )

    private fun ledger(
        perspective: PerspectiveId,
        tokens: Int,
        elasticity: Elasticity = Elasticity.LOW,
    ): PerspectiveLedgerConfig = PerspectiveLedgerConfig(perspective, Budget(tokens = tokens), elasticity)

    private fun grant(
        subject: ApprovalSubject,
        by: PerspectiveId = PerspectiveId.FINANCE,
        granted: Boolean = true,
    ): ApprovalGrant = ApprovalGrant(
        id = "apr.${subject.wire}",
        subject = subject,
        by = by,
        at = AT_ISSUE,
        granted = granted,
    )

    @Test
    fun `上限内的计费计入使用量`() {
        val meter = budget(perspectives = listOf(ledger(PerspectiveId.CUSTOMER_SERVICE, 10)))
        val result = meter.charge(
            ChargeRequest(
                scope = LedgerScope.PERSPECTIVE,
                perspective = PerspectiveId.CUSTOMER_SERVICE,
                amount = Budget(tokens = 5),
                reason = "turn",
            ),
        )
        assertTrue(result.ok)
        assertEquals(ChargeCode.CHARGED, result.code)
        assertEquals(0.5, result.ratio, 1e-9)
        assertEquals("within cap", result.detail)
        assertEquals(5, result.ledger.used.tokens)
        assertEquals(5, meter.remaining(LedgerScope.PERSPECTIVE, "customer-service").tokens)
        assertEquals(1, meter.history().size)
        assertEquals(LedgerScope.PERSPECTIVE, meter.history()[0].scope)
        assertEquals("customer-service", meter.history()[0].ledgerId)

        val settlement = meter.settle()
        assertTrue(settlement.balanced)
        assertEquals(1, settlement.charges)
        assertTrue(settlement.frozen.isEmpty())
    }

    @Test
    fun `低弹性最多自动追加百分之二十然后冻结`() {
        val meter = budget(perspectives = listOf(ledger(PerspectiveId.CUSTOMER_SERVICE, 10)))

        fun spend(tokens: Int) = meter.charge(
            ChargeRequest(
                scope = LedgerScope.PERSPECTIVE,
                perspective = PerspectiveId.CUSTOMER_SERVICE,
                amount = Budget(tokens = tokens),
                reason = "turn",
            ),
        )

        assertTrue(spend(5).ok)
        val first = spend(6)
        assertTrue(first.ok)
        assertEquals(ChargeCode.CHARGED, first.code)
        assertEquals("within 20% elasticity", first.detail)
        assertTrue(spend(1).ok)
        assertEquals(12, meter.snapshot(LedgerScope.PERSPECTIVE, "customer-service").used.tokens)

        val denied = spend(1)
        assertFalse(denied.ok)
        assertEquals(ChargeCode.EXHAUSTED, denied.code)
        assertTrue(denied.ledger.frozen)
        assertEquals("hard ceiling at 120% of cap", denied.ledger.frozenReason)
        assertEquals(1.3, denied.ratio, 1e-9)

        val frozen = spend(1)
        assertEquals(ChargeCode.FROZEN, frozen.code)
        assertFalse(meter.settle().balanced)
        // 两次超上限提交各写一条 post-audit 记录。
        assertEquals(2, meter.settle().postAudit.size)
    }

    @Test
    fun `中弹性需要财务追加批准`() {
        val meter = budget(perspectives = listOf(ledger(PerspectiveId.TECHNOLOGY, 10, Elasticity.MEDIUM)))
        val request = ChargeRequest(
            scope = LedgerScope.PERSPECTIVE,
            perspective = PerspectiveId.TECHNOLOGY,
            amount = Budget(tokens = 11),
            reason = "deep read",
        )
        val denied = meter.charge(request)
        assertFalse(denied.ok)
        assertEquals(ChargeCode.APPROVAL_REQUIRED, denied.code)
        assertEquals("over cap; finance top-up approval required", denied.detail)
        assertEquals(0, denied.ledger.used.tokens)

        val approved = request.copy(
            approval = grant(ApprovalSubject.BUDGET_TOP_UP),
            requestedBy = PerspectiveId.GENERAL_MANAGER,
        )
        val charged = meter.charge(approved)
        assertTrue(charged.ok)
        assertEquals(ChargeCode.CHARGED_WITH_APPROVAL, charged.code)
        assertEquals("over cap with finance approval", charged.detail)
        assertEquals("apr.budget.top-up", charged.approvalId)
        assertEquals(11, charged.ledger.used.tokens)

        val beyond = meter.charge(
            ChargeRequest(
                scope = LedgerScope.PERSPECTIVE,
                perspective = PerspectiveId.TECHNOLOGY,
                amount = Budget(tokens = 3),
                reason = "more",
                approval = grant(ApprovalSubject.BUDGET_TOP_UP),
                requestedBy = PerspectiveId.GENERAL_MANAGER,
            ),
        )
        assertEquals(ChargeCode.EXHAUSTED, beyond.code)
        assertEquals("hard ceiling at 135% of cap", beyond.ledger.frozenReason)
    }

    @Test
    fun `中弹性的批准必须来自财务且非请求者`() {
        val meter = budget(perspectives = listOf(ledger(PerspectiveId.TECHNOLOGY, 10, Elasticity.MEDIUM)))
        val start = meter.charge(
            ChargeRequest(
                scope = LedgerScope.PERSPECTIVE,
                perspective = PerspectiveId.TECHNOLOGY,
                amount = Budget(tokens = 11),
                reason = "deep read",
                approval = grant(ApprovalSubject.BUDGET_TOP_UP),
                requestedBy = PerspectiveId.GENERAL_MANAGER,
            ),
        )
        assertTrue(start.ok)

        val small = ChargeRequest(
            scope = LedgerScope.PERSPECTIVE,
            perspective = PerspectiveId.TECHNOLOGY,
            amount = Budget(tokens = 1),
            reason = "more",
        )
        // 批准必须来自财务，且不能由请求者自己批准。
        assertEquals(
            ChargeCode.APPROVAL_REQUIRED,
            meter.charge(
                small.copy(
                    approval = grant(ApprovalSubject.BUDGET_RESERVE),
                    requestedBy = PerspectiveId.GENERAL_MANAGER,
                ),
            ).code,
        )
        assertEquals(
            ChargeCode.APPROVAL_REQUIRED,
            meter.charge(
                small.copy(
                    approval = grant(ApprovalSubject.BUDGET_TOP_UP, PerspectiveId.LEGAL),
                    requestedBy = PerspectiveId.GENERAL_MANAGER,
                ),
            ).code,
        )
        assertEquals(
            ChargeCode.APPROVAL_REQUIRED,
            meter.charge(
                small.copy(
                    approval = grant(ApprovalSubject.BUDGET_TOP_UP),
                    requestedBy = PerspectiveId.FINANCE,
                ),
            ).code,
        )
        val charged = meter.charge(
            small.copy(
                approval = grant(ApprovalSubject.BUDGET_TOP_UP),
                requestedBy = PerspectiveId.GENERAL_MANAGER,
            ),
        )
        assertTrue(charged.ok)
        assertEquals(12, charged.ledger.used.tokens)
    }

    @Test
    fun `高弹性自动追加并事后审计`() {
        val meter = budget(perspectives = listOf(ledger(PerspectiveId.MARKETING, 10, Elasticity.HIGH)))
        val result = meter.charge(
            ChargeRequest(
                scope = LedgerScope.PERSPECTIVE,
                perspective = PerspectiveId.MARKETING,
                amount = Budget(tokens = 15),
                reason = "research sweep",
            ),
        )
        assertTrue(result.ok)
        assertEquals(ChargeCode.CHARGED_POST_AUDIT, result.code)
        assertEquals("within 50% elasticity", result.detail)
        assertEquals(2, meter.settle().postAudit.size)

        val beyond = meter.charge(
            ChargeRequest(
                scope = LedgerScope.PERSPECTIVE,
                perspective = PerspectiveId.MARKETING,
                amount = Budget(tokens = 1),
                reason = "one more",
            ),
        )
        assertEquals(ChargeCode.EXHAUSTED, beyond.code)
        assertEquals("hard ceiling at 150% of cap", beyond.ledger.frozenReason)
    }

    @Test
    fun `预备金只能用于升级且大额需要董事会`() {
        val meter = budget(reserveTokens = 20)
        val plain = meter.charge(
            ChargeRequest(scope = LedgerScope.RESERVE, amount = Budget(tokens = 1), reason = "misc"),
        )
        assertEquals(ChargeCode.RESERVE_RESTRICTED, plain.code)
        assertEquals("the reserve ledger only funds escalations", plain.detail)

        val noApproval = meter.charge(
            ChargeRequest(
                scope = LedgerScope.RESERVE,
                amount = Budget(tokens = 1),
                reason = "escalation",
                escalation = true,
            ),
        )
        assertEquals(ChargeCode.APPROVAL_REQUIRED, noApproval.code)

        val approval = grant(ApprovalSubject.BUDGET_RESERVE)
        val charged = meter.charge(
            ChargeRequest(
                scope = LedgerScope.RESERVE,
                amount = Budget(tokens = 1),
                reason = "escalation",
                escalation = true,
                approval = approval,
                requestedBy = PerspectiveId.GENERAL_MANAGER,
            ),
        )
        assertTrue(charged.ok)
        assertEquals(1, charged.ledger.used.tokens)

        assertGovernanceError(GovernanceCode.APPROVAL_REQUIRED) {
            meter.drawReserve(Budget(tokens = 15), approval, "big draw")
        }
        val withBoard = meter.drawReserve(
            Budget(tokens = 10),
            approval,
            "big draw",
            grant(ApprovalSubject.BUDGET_MAJOR_TOP_UP, PerspectiveId.BOARD),
        )
        assertTrue(withBoard.ok)
        assertEquals(11, withBoard.ledger.used.tokens)

        assertGovernanceError(GovernanceCode.APPROVAL_REQUIRED) {
            meter.drawReserve(Budget(tokens = 1), grant(ApprovalSubject.BUDGET_RESERVE, granted = false), "denied")
        }
        assertGovernanceError(GovernanceCode.APPROVAL_REQUIRED) {
            meter.drawReserve(Budget(tokens = 1), grant(ApprovalSubject.BUDGET_TOP_UP), "wrong subject")
        }
    }

    @Test
    fun `重分配守恒且需要财务批准`() {
        val meter = budget(
            perspectives = listOf(
                ledger(PerspectiveId.STRATEGY, 100),
                ledger(PerspectiveId.TECHNOLOGY, 0),
            ),
            agents = listOf(AgentLedgerConfig("agent.worker", Budget(tokens = 0), Elasticity.LOW)),
        )
        val request = ReallocationRequest(
            amount = Budget(tokens = 30),
            approval = grant(ApprovalSubject.BUDGET_ALLOCATION),
            reason = "delegate",
            options = ReallocationOptions(requestedBy = PerspectiveId.GENERAL_MANAGER),
        )
        val result = meter.reallocate(
            BudgetAccount(BudgetAccountScope.PERSPECTIVE, "strategy"),
            BudgetAccount(BudgetAccountScope.PERSPECTIVE, "technology"),
            request,
        )
        assertTrue(result.ok)
        assertEquals(ReallocationCode.REALLOCATED, result.code)
        assertEquals(70, result.from.cap.tokens)
        assertEquals(30, result.to.cap.tokens)
        assertEquals(
            100,
            meter.snapshot(LedgerScope.PERSPECTIVE, "strategy").cap.tokens +
                meter.snapshot(LedgerScope.PERSPECTIVE, "technology").cap.tokens,
        )
        assertEquals(1, meter.transferHistory().size)
        assertEquals("apr.budget.allocation", meter.transferHistory()[0].approvalId)

        val toAgent = meter.reallocate(
            BudgetAccount(BudgetAccountScope.PERSPECTIVE, "strategy"),
            BudgetAccount(BudgetAccountScope.AGENT, "agent.worker"),
            request.copy(amount = Budget(tokens = 20)),
        )
        assertTrue(toAgent.ok)
        assertEquals(20, toAgent.to.cap.tokens)
        assertEquals(2, meter.settle().transfers.size)

        val insufficient = meter.reallocate(
            BudgetAccount(BudgetAccountScope.PERSPECTIVE, "strategy"),
            BudgetAccount(BudgetAccountScope.PERSPECTIVE, "technology"),
            request.copy(amount = Budget(tokens = 100)),
        )
        assertFalse(insufficient.ok)
        assertEquals(ReallocationCode.INSUFFICIENT, insufficient.code)
        assertEquals("tokens: 100 requested, 50 available", insufficient.detail)

        val unapproved = meter.reallocate(
            BudgetAccount(BudgetAccountScope.PERSPECTIVE, "strategy"),
            BudgetAccount(BudgetAccountScope.PERSPECTIVE, "technology"),
            request.copy(options = ReallocationOptions()),
        )
        assertEquals(ReallocationCode.APPROVAL_REQUIRED, unapproved.code)

        val metrics = meter.reallocate(
            BudgetAccount(BudgetAccountScope.PERSPECTIVE, "strategy"),
            BudgetAccount(BudgetAccountScope.PERSPECTIVE, "technology"),
            request.copy(approval = grant(ApprovalSubject.BUDGET_ALLOCATION, PerspectiveId.LEGAL)),
        )
        assertEquals(ReallocationCode.APPROVAL_REQUIRED, metrics.code)
    }

    @Test
    fun `冻结与解冻由财务掌控`() {
        val meter = budget(perspectives = listOf(ledger(PerspectiveId.OPERATIONS, 10)))
        assertGovernanceError(GovernanceCode.APPROVAL_NOT_PERMITTED) {
            meter.freeze(LedgerScope.PERSPECTIVE, "operations", "incident", PerspectiveId.LEGAL)
        }
        val frozen = meter.freeze(LedgerScope.PERSPECTIVE, "operations", "incident")
        assertTrue(frozen.frozen)
        assertEquals("incident", frozen.frozenReason)
        assertEquals(1, meter.settle().frozen.size)

        val denied = meter.charge(
            ChargeRequest(
                scope = LedgerScope.PERSPECTIVE,
                perspective = PerspectiveId.OPERATIONS,
                amount = Budget(tokens = 1),
                reason = "work",
            ),
        )
        assertEquals(ChargeCode.FROZEN, denied.code)
        assertEquals("incident", denied.detail)

        assertGovernanceError(GovernanceCode.APPROVAL_NOT_PERMITTED) {
            meter.unfreeze(LedgerScope.PERSPECTIVE, "operations", grant(ApprovalSubject.BUDGET_FREEZE, PerspectiveId.LEGAL))
        }
        val thawed = meter.unfreeze(
            LedgerScope.PERSPECTIVE,
            "operations",
            grant(ApprovalSubject.BUDGET_FREEZE),
        )
        assertFalse(thawed.frozen)
        assertNull(thawed.frozenReason)
        assertTrue(meter.settle().frozen.isEmpty())
    }

    @Test
    fun `未知账本与重复开户失败关闭`() {
        val meter = budget(perspectives = listOf(ledger(PerspectiveId.OPERATIONS, 10)))
        assertGovernanceError(GovernanceCode.BUDGET_FROZEN) {
            meter.openPerspective(PerspectiveId.OPERATIONS, Budget(tokens = 20))
        }
        assertGovernanceError(GovernanceCode.BUDGET_EXHAUSTED) {
            meter.charge(
                ChargeRequest(
                    scope = LedgerScope.PERSPECTIVE,
                    perspective = PerspectiveId.LEGAL,
                    amount = Budget(tokens = 1),
                    reason = "x",
                ),
            )
        }
        assertGovernanceError(GovernanceCode.BUDGET_EXHAUSTED) {
            meter.charge(
                ChargeRequest(scope = LedgerScope.PERSPECTIVE, amount = Budget(tokens = 1), reason = "x"),
            )
        }
        assertGovernanceError(GovernanceCode.BUDGET_EXHAUSTED) {
            meter.charge(ChargeRequest(scope = LedgerScope.AGENT, amount = Budget(tokens = 1), reason = "x"))
        }

        val agent = meter.openAgent("agent.worker", Budget(tokens = 5))
        assertEquals(5, agent.cap.tokens)
        assertEquals(
            0.0,
            meter.snapshot(LedgerScope.AGENT, "agent.worker").ratio,
            0.0,
        )
        assertGovernanceError(GovernanceCode.BUDGET_FROZEN) {
            meter.openAgent("agent.worker", Budget(tokens = 5))
        }

        val chargedAgent = meter.charge(
            ChargeRequest(
                scope = LedgerScope.AGENT,
                agent = "agent.worker",
                amount = Budget(tokens = 5),
                reason = "work",
            ),
        )
        assertTrue(chargedAgent.ok)
        assertEquals(0, meter.remaining(LedgerScope.AGENT, "agent.worker").tokens)
        assertEquals(0, meter.snapshot(LedgerScope.GLOBAL).used.tokens)
        assertEquals(1_000, meter.snapshot(LedgerScope.GLOBAL).cap.tokens)
    }

    @Test
    fun `快照与结清包含全部账本`() {
        val meter = budget(
            perspectives = listOf(ledger(PerspectiveId.LEGAL, 10), ledger(PerspectiveId.TECHNOLOGY, 20)),
            agents = listOf(AgentLedgerConfig("agent.a", Budget(tokens = 5), Elasticity.LOW)),
        )
        val snapshots = meter.snapshots()
        assertEquals(5, snapshots.size)
        assertEquals(listOf("global", "reserve", "legal", "technology", "agent.a"), snapshots.map { it.id })
        assertEquals(LedgerScope.GLOBAL, snapshots[0].scope)
        assertEquals(LedgerScope.RESERVE, snapshots[1].scope)
        assertEquals(LedgerScope.AGENT, snapshots[4].scope)

        val settlement = meter.settle()
        assertEquals(2, settlement.perspectives.size)
        assertEquals(1, settlement.agents.size)
        assertEquals(0, settlement.charges)
        assertTrue(settlement.transfers.isEmpty())
        assertTrue(settlement.balanced)
        assertNotNull(settlement.global)
    }
}
