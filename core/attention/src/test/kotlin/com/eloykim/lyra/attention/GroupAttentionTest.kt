package com.eloykim.lyra.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAttentionTest {

    private fun member(id: Long, name: String, weight: Double = 0.5, spent: Long = 0, budget: Long = 0) =
        GroupMember(id, name, weight, spent, budget)

    @Test
    fun `被点名的人排最前`() {
        val members = listOf(member(1, "分析师", 0.9), member(2, "写手", 0.5))
        val ranked = GroupAttention.rank(members, mentioned = setOf("写手"))

        assertEquals("写手", ranked.first().member.name)
        assertEquals("被点名", ranked.first().reason)
    }

    @Test
    fun `已发过言的人降权`() {
        val members = listOf(member(1, "甲", 0.5), member(2, "乙", 0.5))
        val ranked = GroupAttention.rank(members, participants = setOf(1, 2))

        assertTrue(ranked.all { it.reason == "已发过言，降权" })
        assertTrue(ranked.all { it.salience < 0.35 })
    }

    @Test
    fun `预算用超八成后显著性下降`() {
        val rich = member(1, "有钱", 0.5, spent = 10, budget = 100)
        val poor = member(2, "快花完", 0.5, spent = 90, budget = 100)

        val ranked = GroupAttention.rank(listOf(rich, poor))
        assertEquals("有钱", ranked.first().member.name)
    }

    @Test
    fun `选择只返回显著性达标的少数人`() {
        val members = (1..6).map { member(it.toLong(), "成员$it", 0.5) }
        val chosen = GroupAttention.select(members, maxSpeakers = 3)

        assertEquals(3, chosen.size)
        assertTrue(chosen.all { it.salience >= 0.35 })
    }

    @Test
    fun `预算均分且总额精确`() {
        val members = listOf(member(1, "甲", 0.9), member(2, "乙", 0.3), member(3, "丙", 0.3))
        val shares = GroupAttention.budgetShares(1_000, members)

        assertEquals(1_000L, shares.values.sum())
        assertEquals(334L, shares[1])
    }

    @Test
    fun `能力切片只暴露被授权前缀`() {
        val catalog = listOf("chat_message.recent", "contacts.search", "notification.list", "device.info")
        val slice = GroupAttention.capabilitySlice(catalog, listOf("chat_message", "device"))

        assertEquals(listOf("chat_message.recent", "device.info"), slice)
    }

    @Test
    fun `没有授权就没有能力`() {
        val catalog = listOf("chat_message.recent")
        assertTrue(GroupAttention.capabilitySlice(catalog, emptyList()).isEmpty())
    }
}
