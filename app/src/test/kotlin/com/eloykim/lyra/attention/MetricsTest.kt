package com.eloykim.lyra.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsTest {

    private fun sample(tokens: Int, predicted: Double, realized: Double) =
        ReceiptSample(tokens, 0, costMicros = 100, predictedVoi = predicted, realizedVoi = realized)

    @Test
    fun `空回执返回零值`() {
        val metrics = MetricsCalculator.compute(emptyList(), runs = 0, impasses = 0)
        assertEquals(0.0, metrics.voiMae, 0.0001)
        assertEquals(0.0, metrics.wastedAttentionRatio, 0.0001)
        assertFalse(metrics.reliable)
    }

    @Test
    fun `voi mae 按预测与实际之差计算`() {
        val receipts = listOf(
            sample(100, 0.8, 1.0),   // 0.2
            sample(100, 0.5, 0.0),   // 0.5
        )
        val metrics = MetricsCalculator.compute(receipts, runs = 1, impasses = 0)

        assertEquals(0.35, metrics.voiMae, 0.0001)
    }

    @Test
    fun `浪费比例只看低价值步骤的 token`() {
        val receipts = listOf(
            sample(900, 0.9, 1.0),   // 有价值
            sample(100, 0.9, 0.0),   // 浪费
        )
        val metrics = MetricsCalculator.compute(receipts, runs = 1, impasses = 0)

        assertEquals(0.1, metrics.wastedAttentionRatio, 0.0001)
        assertEquals(1_000, metrics.totalTokens)
    }

    @Test
    fun `僵局率按任务平均`() {
        val metrics = MetricsCalculator.compute(listOf(sample(10, 0.5, 1.0)), runs = 4, impasses = 2)
        assertEquals(0.5, metrics.impasseRate, 0.0001)
    }

    @Test
    fun `样本足够才标记为可信`() {
        val few = MetricsCalculator.compute(List(3) { sample(10, 0.5, 1.0) }, 1, 0)
        assertFalse(few.reliable)

        val many = MetricsCalculator.compute(
            List(MetricsCalculator.MIN_SAMPLES) { sample(10, 0.5, 1.0) },
            1,
            0,
        )
        assertTrue(many.reliable)
    }

    @Test
    fun `老回执不参与校准`() {
        val legacy = ReceiptSample(tokensIn = 500, tokensOut = 100, costMicros = 0, predictedVoi = 0.0, realizedVoi = 0.0)
        val fresh = sample(100, 0.8, 1.0)
        val metrics = MetricsCalculator.compute(listOf(legacy, fresh), runs = 1, impasses = 0)

        assertEquals(1, metrics.receipts)
        assertEquals(100, metrics.totalTokens)
        assertEquals(0.2, metrics.voiMae, 0.0001)
        assertEquals(0.0, metrics.wastedAttentionRatio, 0.0001)
    }
}