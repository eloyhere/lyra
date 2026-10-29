package com.eloykim.lyra.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetSchedulerTest {

    private fun scheduler(
        tokens: Int = 1_000,
        ms: Long = 10_000,
        calls: Int = 3,
        interrupts: Int = 0,
    ) = BudgetScheduler(BudgetVector(tokens, ms, calls, interrupts))

    @Test
    fun `计费不超限时放行并累计`() {
        val budget = scheduler()
        assertEquals(Admission.Allow, budget.charge(BudgetDimension.TOKENS, 400))
        assertEquals(Admission.Allow, budget.charge(BudgetDimension.TOKENS, 400))

        assertEquals(800, budget.spent().tokens)
        assertEquals(200, budget.remaining().tokens)
    }

    @Test
    fun `超限拒绝且不计数`() {
        val budget = scheduler(tokens = 500)
        budget.charge(BudgetDimension.TOKENS, 400)

        val denied = budget.charge(BudgetDimension.TOKENS, 200)
        assertTrue(denied is Admission.Deny)
        assertEquals(BudgetDimension.TOKENS, (denied as Admission.Deny).dimension)
        assertEquals(400, budget.spent().tokens)
    }

    @Test
    fun `调用次数按嵌套累加`() {
        val budget = scheduler(calls = 2)
        assertEquals(Admission.Allow, budget.charge(BudgetDimension.CALLS, 1))
        assertEquals(Admission.Allow, budget.charge(BudgetDimension.CALLS, 1))

        assertTrue(budget.charge(BudgetDimension.CALLS, 1) is Admission.Deny)
        assertEquals(2, budget.spent().calls)
    }

    @Test
    fun `周期重置后已用清零`() {
        val budget = scheduler(tokens = 300)
        budget.charge(BudgetDimension.TOKENS, 300)
        budget.startCycle(BudgetVector(tokens = 300, calls = 3))

        assertEquals(0, budget.spent().tokens)
        assertEquals(300, budget.remaining().tokens)
    }

    @Test
    fun `结果超限先投影`() {
        val budget = scheduler(tokens = 500)
        val admission = budget.admitResult(estimatedTokens = 900, canProject = true, canHandle = true)

        assertTrue(admission is Admission.Degrade)
        assertEquals(DegradeLevel.PROJECT, (admission as Admission.Degrade).level)
    }

    @Test
    fun `不能投影时转句柄`() {
        val budget = scheduler(tokens = 500)
        val admission = budget.admitResult(estimatedTokens = 900, canProject = false, canHandle = true)

        assertEquals(DegradeLevel.HANDLE, (admission as Admission.Degrade).level)
    }

    @Test
    fun `都不可行时拒绝而不是静默截断`() {
        val budget = scheduler(tokens = 500)
        val admission = budget.admitResult(estimatedTokens = 900, canProject = false, canHandle = false)

        assertTrue(admission is Admission.Deny)
    }

    @Test
    fun `结果不超限直接放行`() {
        val budget = scheduler(tokens = 5_000)
        assertEquals(Admission.Allow, budget.admitResult(900, canProject = true, canHandle = true))
    }

    @Test
    fun `时间预算耗尽时拒绝`() {
        val budget = scheduler(ms = 500)
        assertEquals(Admission.Allow, budget.charge(BudgetDimension.MS, 300))

        val denied = budget.charge(BudgetDimension.MS, 400)
        assertTrue(denied is Admission.Deny)
        assertEquals(BudgetDimension.MS, (denied as Admission.Deny).dimension)
    }

    @Test
    fun `金额预算耗尽时拒绝`() {
        val budget = BudgetScheduler(BudgetVector(moneyMicros = 1_000))
        assertEquals(Admission.Allow, budget.charge(BudgetDimension.MONEY, 600))

        val denied = budget.charge(BudgetDimension.MONEY, 500)
        assertTrue(denied is Admission.Deny)
        assertEquals(BudgetDimension.MONEY, (denied as Admission.Deny).dimension)
    }
}