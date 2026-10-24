package com.eloykim.lyra.attention

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalFitTest {

    private val scorer = GoalFitScorer()

    @Test
    fun `任务与候选相关时得分更高`() = runBlocking {
        val scores = scorer.score(
            task = "帮我点一份麦当劳送到公司",
            candidates = listOf("麦当劳", "微信支付", "系统设置"),
        )

        assertEquals(3, scores.size)
        assertTrue("麦当劳 应高于微信支付", scores[0] > scores[1])
        assertTrue("麦当劳 应高于设置", scores[0] > scores[2])
    }

    @Test
    fun `完全无关的候选得分为 0`() = runBlocking {
        val scores = scorer.score(
            task = "查看天气",
            candidates = listOf("微信支付", "123456"),
        )

        assertTrue(scores.all { it == 0f })
    }

    @Test
    fun `空候选返回空列表`() = runBlocking {
        assertTrue(scorer.score("任意任务", emptyList()).isEmpty())
    }

    @Test
    fun `空任务不产生正分`() = runBlocking {
        val scores = scorer.score("", listOf("确认订单", "微信支付"))
        assertTrue(scores.all { it == 0f })
    }

    @Test
    fun `缓存命中不改变结果`() = runBlocking {
        val first = scorer.score("打开计算器", listOf("计算器", "设置"))
        val second = scorer.score("打开计算器", listOf("计算器", "设置"))
        assertEquals(first, second)
    }

    @Test
    fun `分词对中文连续文本稳定`() = runBlocking {
        val a = scorer.score("确认订单", listOf("确认订单"))
        val b = scorer.score("确认订单", listOf("确认订单"))
        assertEquals(a, b)
        assertEquals(1f, a.single(), 0.001f)
    }
}
