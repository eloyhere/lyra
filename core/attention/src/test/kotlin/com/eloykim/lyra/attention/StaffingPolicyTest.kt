package com.eloykim.lyra.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffingPolicyTest {

    @Test
    fun `识别增删人员与分配角色的意图`() {
        assertTrue(StaffingPolicy.isStaffingRequest("加一个数据分析师进来"))
        assertTrue(StaffingPolicy.isStaffingRequest("把乙换成写手"))
        assertTrue(StaffingPolicy.isStaffingRequest("删除丙"))
        assertFalse(StaffingPolicy.isStaffingRequest("今天天气怎么样"))
        assertFalse(StaffingPolicy.isStaffingRequest(""))
    }

    @Test
    fun `总经理由预分配席位决定`() {
        // 成员按 id 升序坐席：1=总经理，2=财务，3=记录者
        assertEquals(1L, StaffingPolicy.generalManager(listOf(1L, 2L, 3L)))
        assertEquals(1L, StaffingPolicy.generalManager(listOf(3L, 1L, 2L)))
    }

    @Test
    fun `空群没有总经理`() {
        assertNull(StaffingPolicy.generalManager(emptyList()))
    }

    @Test
    fun `只有总经理能动人事`() {
        val members = listOf(1L, 2L, 3L)
        assertTrue(StaffingPolicy.mayStaff(1L, members))
        assertFalse(StaffingPolicy.mayStaff(2L, members))
        assertFalse(StaffingPolicy.mayStaff(3L, members))
    }

    @Test
    fun `董事长可以直接要求变更`() {
        assertTrue(StaffingPolicy.mayStaff(null, listOf(1L)))
        assertTrue(StaffingPolicy.mayStaff(null, emptyList()))
    }

    @Test
    fun `服务方法映射到人事动作`() {
        assertEquals(StaffingAction.ADD_MEMBER, StaffingAction.of("add_agent"))
        assertEquals(StaffingAction.REMOVE_MEMBER, StaffingAction.of("remove_agent"))
        assertEquals(StaffingAction.ASSIGN_ROLE, StaffingAction.of("assign_role"))
        assertNull(StaffingAction.of("send"))
    }

    @Test
    fun `总经理职责说明包含发言控制与人事`() {
        val brief = StaffingPolicy.generalManagerBrief()
        assertTrue(brief.contains("控制谁发言"))
        assertTrue(brief.contains("add_agent"))
        assertTrue(brief.contains("董事长"))
    }
}
