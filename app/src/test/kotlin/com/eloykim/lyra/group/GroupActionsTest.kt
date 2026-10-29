package com.eloykim.lyra.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupActionsTest {

    @Test
    fun `动作行被拆出来，正文保留`() {
        val reply = """
            工程部现在缺一个前端，登录页没人写。
            <<ACTION {"type":"request_hire","position":"FRONTEND","reason":"登录页卡住","expected":"三天交付"}>>
        """.trimIndent()

        val (text, action) = GroupActions.split(reply)

        assertEquals("工程部现在缺一个前端，登录页没人写。", text)
        assertTrue(action!!.contains("\"request_hire\""))
        assertTrue(action.contains("\"FRONTEND\""))
    }

    @Test
    fun `没有动作行时原文不变`() {
        val reply = "我这边没问题，明天给方案。"
        val (text, action) = GroupActions.split(reply)
        assertEquals(reply, text)
        assertNull(action)
    }

    @Test
    fun `动作行被包在中间也能摘掉`() {
        val reply = "开头\n<<ACTION {\"type\":\"create_task\",\"title\":\"登录页\"}>>\n结尾"
        val (text, action) = GroupActions.split(reply)

        assertTrue(action!!.contains("create_task"))
        assertTrue(text.startsWith("开头"))
        assertTrue(text.endsWith("结尾"))
    }

    @Test
    fun `动作说明里给出了三种动作`() {
        val contract = GroupActions.contract()
        listOf("request_hire", "create_task", "move_task", "claim_task").forEach {
            assertTrue(contract.contains(it))
        }
    }
}
