package com.eloykim.lyra.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreSeatsTest {

    @Test
    fun `建群时预分配总经理、财务、记录者三个席位`() {
        val seats = GroupSeats.preset(listOf(10L, 11L, 12L))

        assertEquals(10L, seats.holder(CoreSeat.GENERAL_MANAGER))
        assertEquals(11L, seats.holder(CoreSeat.FINANCE))
        assertEquals(GroupSeats.SYSTEM_RECORDER, seats.holder(CoreSeat.RECORDER))
    }

    @Test
    fun `成员不足时职位空缺而不是乱分配`() {
        val seats = GroupSeats.preset(listOf(7L))
        assertEquals(7L, seats.holder(CoreSeat.GENERAL_MANAGER))
        assertNull(seats.holder(CoreSeat.FINANCE))
        assertEquals(GroupSeats.SYSTEM_RECORDER, seats.holder(CoreSeat.RECORDER))
    }

    @Test
    fun `总经理能控制发言与人事但动不了预算`() {
        val seats = GroupSeats.preset(listOf(1L, 2L, 3L))
        assertTrue(seats.mayCall(1L, "groupchat.add_agent"))
        assertTrue(seats.mayCall(1L, "groupchat.set_reply_policy")) // 发言控制
        assertFalse(seats.mayCall(1L, "groupchat.set_budget"))
    }

    @Test
    fun `财务只能改预算`() {
        val seats = GroupSeats.preset(listOf(1L, 2L, 3L))
        assertTrue(seats.mayCall(2L, "groupchat.set_budget"))
        assertFalse(seats.mayCall(2L, "groupchat.set_reply_policy")) // 发言控制归总经理
        assertFalse(seats.mayCall(2L, "groupchat.add_agent"))
    }

    @Test
    fun `记录者能清空历史`() {
        val seats = GroupSeats.preset(listOf(1L, 2L, 3L))
        assertTrue(seats.mayCall(GroupSeats.SYSTEM_RECORDER, "groupchat.clear_history"))
        assertFalse(seats.mayCall(GroupSeats.SYSTEM_RECORDER, "groupchat.set_reply_policy"))
    }

    @Test
    fun `普通成员没有任何运营权`() {
        val seats = GroupSeats.preset(listOf(1L, 2L, 3L, 4L))
        assertFalse(seats.mayCall(4L, "groupchat.add_agent"))
        assertFalse(seats.mayCall(4L, "groupchat.set_budget"))
        assertTrue(seats.seatsOf(4L).isEmpty())
    }

    @Test
    fun `董事长（用户本人）不受座位限制`() {
        val seats = GroupSeats.preset(listOf(1L))
        assertTrue(seats.mayCall(null, "groupchat.set_budget"))
    }

    @Test
    fun `移除成员后座位被回收`() {
        val seats = GroupSeats.preset(listOf(1L, 2L, 3L))
        val after = seats.vacate(1L)

        assertNull(after.holder(CoreSeat.GENERAL_MANAGER))
        assertFalse(after.mayCall(1L, "groupchat.add_agent"))
    }

    @Test
    fun `换人只换坐的人不新增权力`() {
        val seats = GroupSeats.preset(listOf(1L, 2L))
        val reassigned = seats.copy(assignments = seats.assignments + (CoreSeat.GENERAL_MANAGER to 99L))

        assertTrue(reassigned.mayCall(99L, "groupchat.add_agent"))
        assertFalse(reassigned.mayCall(1L, "groupchat.add_agent"))
    }
}
