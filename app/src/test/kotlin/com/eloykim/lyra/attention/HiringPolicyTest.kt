package com.eloykim.lyra.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiringPolicyTest {

    private val ask = HiringAsk(
        positionKey = "FRONTEND",
        department = "工程",
        reason = "登录页改造卡在没人写前端",
        expected = "三天内交出登录页",
    )

    private val facts = HiringFacts(
        memberCount = 4,
        departmentCount = mapOf("工程" to 1),
        openRequests = 0,
        positionTaken = false,
        budgetTokens = 1_000_000,
        spentTokens = 200_000,
    )

    @Test
    fun `编制与预算内自动批准`() {
        val decision = HiringPolicy.evaluate(ask, facts)
        assertTrue(decision is HiringDecision.AutoApprove)
    }

    @Test
    fun `没写理由直接拒绝`() {
        val decision = HiringPolicy.evaluate(ask.copy(reason = ""), facts)
        assertTrue(decision is HiringDecision.Reject)
    }

    @Test
    fun `职位已有人在岗时拒绝`() {
        val decision = HiringPolicy.evaluate(ask, facts.copy(positionTaken = true))
        assertTrue(decision is HiringDecision.Reject)
    }

    @Test
    fun `未知职位拒绝`() {
        val decision = HiringPolicy.evaluate(ask.copy(positionKey = "CTO"), facts)
        assertTrue(decision is HiringDecision.Reject)
    }

    @Test
    fun `未决申请过多时拒绝`() {
        val decision = HiringPolicy.evaluate(ask, facts.copy(openRequests = 2))
        assertTrue(decision is HiringDecision.Reject)
    }

    @Test
    fun `敏感席位升级董事长`() {
        val hr = ask.copy(positionKey = "HR_MINISTER", department = "人力", reason = "需要专职招聘")
        val decision = HiringPolicy.evaluate(hr, facts)
        assertTrue(decision is HiringDecision.NeedChairman)
    }

    @Test
    fun `部门超编升级董事长`() {
        val decision = HiringPolicy.evaluate(ask, facts.copy(departmentCount = mapOf("工程" to 3)))
        assertTrue(decision is HiringDecision.NeedChairman)
    }

    @Test
    fun `预算余量不足升级董事长`() {
        val decision = HiringPolicy.evaluate(
            ask,
            facts.copy(budgetTokens = 500_000, spentTokens = 450_000),
        )
        assertTrue(decision is HiringDecision.NeedChairman)
    }

    @Test
    fun `成员上限升级董事长而不是拒绝`() {
        val decision = HiringPolicy.evaluate(ask, facts.copy(memberCount = 12))
        assertTrue(decision is HiringDecision.NeedChairman)
    }

    @Test
    fun `任务完成后必须经过验收`() {
        assertTrue(TaskFlow.canMove(TaskFlow.DOING, TaskFlow.REVIEW))
        assertTrue(TaskFlow.canMove(TaskFlow.REVIEW, TaskFlow.DONE))
        assertTrue(TaskFlow.needsReview(TaskFlow.DOING, TaskFlow.REVIEW))
        assertFalse(TaskFlow.canMove(TaskFlow.DOING, TaskFlow.DONE))
        assertFalse(TaskFlow.canMove(TaskFlow.DONE, TaskFlow.DOING))
    }

    @Test
    fun `阻塞原因写明缺人才算招聘依据`() {
        assertTrue(TaskFlow.isStaffingGap(TaskFlow.BLOCKED, "缺一个前端，页面没人做"))
        assertFalse(TaskFlow.isStaffingGap(TaskFlow.BLOCKED, "等法务回复"))
        assertFalse(TaskFlow.isStaffingGap(TaskFlow.DOING, "缺人"))
    }

    @Test
    fun `默认称呼按部门人数递增`() {
        assertEquals("前端工程师", HiringPolicy.defaultNickname("FRONTEND", 0))
        assertEquals("前端工程师2", HiringPolicy.defaultNickname("FRONTEND", 1))
    }
}
