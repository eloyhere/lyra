package com.eloykim.lyra.amp.protocol.bayes

import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 来源可靠度学习与 scope 级先验（含向全局池化）。
 */
class ReliabilityTest {

    private val e9 = 1e-9

    @Test
    fun `未见过的来源权重是一`() {
        val table = SourceReliability()
        assertEquals(1.0, table.weight("text"), e9)
        assertTrue(table.weights().isEmpty())
        assertTrue(table.snapshot().isEmpty())
    }

    @Test
    fun `错误观察把质量压到 alpha 侧之外`() {
        val table = SourceReliability()
        table.observe("text", correct = false, weight = 4.0)
        val belief = table.observe("text", correct = false, weight = 4.0)
        assertEquals(2.0, belief.alpha, e9)
        assertEquals(10.0, belief.beta, e9)
        assertEquals(2, belief.observations)
        assertTrue(table.weight("text") < 0.5)
        assertEquals(2.0 / 12.0, table.weights().getValue("text"), e9)
    }

    @Test
    fun `正确观察累加 alpha，负权重被截断但仍计一次观察`() {
        val table = SourceReliability()
        assertEquals(4.0, table.observe("checker", correct = true, weight = 2.0).alpha, e9)
        val ignored = table.observe("checker", correct = true, weight = -5.0)
        assertEquals(4.0, ignored.alpha, e9)
        assertEquals(2, ignored.observations)
        assertEquals(4.0 / 6.0, table.weight("checker"), e9)
    }

    @Test
    fun `自定义先验形状被沿用`() {
        val table = SourceReliability(ReliabilityPrior(alpha = 1.0, beta = 3.0))
        val belief = table.observe("x", correct = true, weight = 1.0)
        assertEquals(2.0, belief.alpha, e9)
        assertEquals(3.0, belief.beta, e9)
    }

    @Test
    fun `快照与加载可往返，JSON 字段与参考实现一致`() {
        val table = SourceReliability()
        table.observe("a", correct = true)
        table.observe("b", correct = false, weight = 2.0)
        val snapshot = table.snapshot()
        val restored = SourceReliability()
        restored.load(snapshot)
        assertEquals(snapshot, restored.snapshot())
        assertEquals(table.weights(), restored.weights())

        val belief = snapshot.getValue("b")
        assertEquals(belief, ReliabilityBelief.fromJson(belief.toJson()))
        assertEquals(
            CanonicalJson.encode(belief.toJson()),
            CanonicalJson.encode(ReliabilityBelief.fromJson(belief.toJson()).toJson()),
        )
        assertTrue(belief.toJson().has("observations"))
        assertEquals(1, snapshot.getValue("b").observations)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `负的计数被拒绝`() {
        ReliabilityBelief(alpha = -1.0, beta = 1.0, observations = 0)
    }

    // -----------------------------------------------------------------------
    // ScopedPriors
    // -----------------------------------------------------------------------

    @Test
    fun `未见 scope 回落到全局 Beta(2,2)`() {
        val priors = ScopedPriors()
        val global = priors.priorsFor("unseen").getValue(Dimension.CORRECTNESS)
        assertEquals(2.0, global.alpha, e9)
        assertEquals(2.0, global.beta, e9)
        assertTrue(priors.scopesKnown().isEmpty())
    }

    @Test
    fun `scope 本地计数推动后验而不影响其他 scope`() {
        val positive = Evidence(Dimension.CORRECTNESS, 20.0, 0.0, 1.0, "label")
        val negative = Evidence(Dimension.CORRECTNESS, 0.0, 20.0, 1.0, "label")
        val priors = ScopedPriors()
        priors.observe("scope.good", listOf(positive))
        priors.observe("scope.bad", listOf(negative))

        val good = priors.priorsFor("scope.good").getValue(Dimension.CORRECTNESS)
        val bad = priors.priorsFor("scope.bad").getValue(Dimension.CORRECTNESS)
        assertEquals(22.0, good.alpha, e9)
        assertEquals(2.0, good.beta, e9)
        assertTrue(good.alpha / (good.alpha + good.beta) > 0.8)
        assertTrue(bad.alpha / (bad.alpha + bad.beta) < 0.2)
        assertEquals(2.0, priors.priorsFor("unseen").getValue(Dimension.CORRECTNESS).alpha, e9)
        assertEquals(listOf("scope.good", "scope.bad"), priors.scopesKnown())

        val score = priors.engineFor("scope.good").scoreAnswer("no evidence")
        assertTrue(score.dimensions.getValue(Dimension.CORRECTNESS).mean > 0.8)
    }

    @Test
    fun `globalWeight 把本地观察按比例并入全局，并被夹到零到一`() {
        val positive = Evidence(Dimension.CORRECTNESS, 1.0, 0.0, 1.0, "label")
        val pooled = ScopedPriors(ScopedPriorsOptions(globalWeight = 0.5))
        pooled.observe("scope.x", listOf(positive))
        assertEquals(2.5, pooled.snapshot().global?.getValue(Dimension.CORRECTNESS)?.alpha ?: 0.0, e9)
        assertEquals(1.0, pooled.snapshot().scopes?.getValue("scope.x")?.getValue(Dimension.CORRECTNESS)?.alpha ?: 0.0, e9)

        val saturated = ScopedPriors(ScopedPriorsOptions(globalWeight = 5.0))
        saturated.observe("scope.y", listOf(positive))
        assertEquals(3.0, saturated.snapshot().global?.getValue(Dimension.CORRECTNESS)?.alpha ?: 0.0, e9)
    }

    @Test
    fun `自定义全局先验覆盖默认值`() {
        val priors = ScopedPriors(
            ScopedPriorsOptions(global = mapOf(Dimension.CORRECTNESS to BetaBelief(5.0, 1.0))),
        )
        assertEquals(5.0, priors.priorsFor("unseen").getValue(Dimension.CORRECTNESS).alpha, e9)
        assertEquals(2.0, priors.priorsFor("unseen").getValue(Dimension.SAFETY).alpha, e9)
    }

    @Test
    fun `scope 快照可 JSON 往返并恢复计数`() {
        val positive = Evidence(Dimension.CORRECTNESS, 3.0, 0.0, 0.5, "label")
        val priors = ScopedPriors(ScopedPriorsOptions(globalWeight = 0.25))
        priors.observe("scope.a", listOf(positive))
        val snapshot = priors.snapshot()
        val json = snapshot.toJson()
        val restored = ScopedPriors()
        restored.load(ScopedPriorsSnapshot.fromJson(json))
        assertEquals(
            CanonicalJson.encode(snapshot.toJson()),
            CanonicalJson.encode(restored.snapshot().toJson()),
        )
        assertEquals(
            priors.priorsFor("scope.a").getValue(Dimension.CORRECTNESS).alpha,
            restored.priorsFor("scope.a").getValue(Dimension.CORRECTNESS).alpha,
            e9,
        )
        // 维度以 wire 名称序列化，与参考实现的 `Record<Dimension, ...>` 键一致。
        assertTrue(json.obj("global")?.has("contextSufficiency") == true)
        assertFalse(json.obj("global")?.has("CONTEXT_SUFFICIENCY") == true)
    }
}
