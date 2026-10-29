package com.eloykim.lyra.amp.protocol.bayes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Beta 后验、分级证据、来源折算、特征抽取与三族打分。
 */
class EngineTest {

    private val e6 = 1e-9

    @Test
    fun `beta posterior 的均值、区间与证据计数`() {
        val prior = BetaBelief(2.0, 2.0)
        val evidence = Evidence(
            dimension = Dimension.CORRECTNESS,
            positive = 8.0,
            negative = 2.0,
            reliability = 1.0,
            source = "test",
        )
        val posterior = posteriorOf(prior, listOf(evidence), Dimension.CORRECTNESS)
        assertEquals(10.0, posterior.alpha, e6)
        assertEquals(4.0, posterior.beta, e6)
        assertEquals(10.0 / 14.0, posterior.mean, e6)
        assertEquals(1, posterior.evidenceCount)
        assertEquals(2, posterior.interval.size)
        assertTrue(posterior.intervalLow < posterior.mean)
        assertTrue(posterior.intervalHigh > posterior.mean)
        assertTrue(posterior.intervalHigh <= 1.0)
        assertTrue(posterior.variance > 0.0)
    }

    @Test
    fun `非目标维度的证据被忽略，证据顺序不影响后验`() {
        val prior = BetaBelief(2.0, 2.0)
        val wrongDimension = Evidence(Dimension.SAFETY, 9.0, 9.0, 1.0, "test")
        val ignored = posteriorOf(prior, listOf(wrongDimension), Dimension.CORRECTNESS)
        assertEquals(2.0, ignored.alpha, e6)
        assertEquals(2.0, ignored.beta, e6)
        assertEquals(0, ignored.evidenceCount)

        val a = Evidence(Dimension.CORRECTNESS, 2.0, 0.0, 0.8, "a")
        val b = Evidence(Dimension.CORRECTNESS, 0.0, 3.0, 0.6, "b")
        val ab = posteriorOf(prior, listOf(a, b), Dimension.CORRECTNESS)
        val ba = posteriorOf(prior, listOf(b, a), Dimension.CORRECTNESS)
        assertEquals(ab.alpha, ba.alpha, e6)
        assertEquals(ab.beta, ba.beta, e6)
    }

    @Test
    fun `分级证据按可靠度折算，负计数按零处理`() {
        val prior = BetaBelief(2.0, 2.0)
        val graded = posteriorOf(
            prior,
            listOf(Evidence(Dimension.CORRECTNESS, 2.0, 0.0, 0.5, "weak")),
            Dimension.CORRECTNESS,
        )
        assertEquals(3.0, graded.alpha, e6)
        assertEquals(2.0, graded.beta, e6)

        val clamped = posteriorOf(
            prior,
            listOf(Evidence(Dimension.CORRECTNESS, -5.0, -5.0, 1.0, "noise")),
            Dimension.CORRECTNESS,
        )
        assertEquals(2.0, clamped.alpha, e6)
        assertEquals(2.0, clamped.beta, e6)
    }

    @Test
    fun `来源权重为零时证据不改变后验`() {
        val prior = BetaBelief(2.0, 2.0)
        val evidence = Evidence(Dimension.CORRECTNESS, 5.0, 0.0, 1.0, "text")
        val weighted = posteriorOf(prior, listOf(evidence), Dimension.CORRECTNESS, mapOf("text" to 1.0))
        val muted = posteriorOf(prior, listOf(evidence), Dimension.CORRECTNESS, mapOf("text" to 0.0))
        assertTrue(weighted.mean > muted.mean)
        assertEquals(0.5, muted.mean, e6)
        assertEquals(2.0, muted.alpha, e6)
    }

    @Test
    fun `默认先验是 19 个维度的弱 Beta(2,2)，按三族分组`() {
        assertEquals(19, DefaultPriors.size)
        DefaultPriors.forEach { (_, belief) ->
            assertEquals(2.0, belief.alpha, e6)
            assertEquals(2.0, belief.beta, e6)
        }
        assertEquals(6, UserDimensions.size)
        assertEquals(8, AnswerDimensions.size)
        assertEquals(5, AttentionDimensions.size)
        assertEquals(DimensionFamily.USER, Dimension.CONTEXT_SUFFICIENCY.family)
        assertEquals("contextSufficiency", Dimension.CONTEXT_SUFFICIENCY.wire)
        assertEquals(Dimension.RECOVERY_DEBT, Dimension.fromWire("recoveryDebt"))
    }

    @Test
    fun `用户特征抽取是确定且有界的`() {
        val features = extractUserFeatures("Can you check 3 things by 17:00 because the release is blocked?")
        assertTrue(features.questions > 0)
        assertTrue(features.numbers >= 2)
        assertTrue(features.urgencyHits > 0)
        assertEquals(features, extractUserFeatures("Can you check 3 things by 17:00 because the release is blocked?"))
    }

    @Test
    fun `回答特征识别结构、引用与动作`() {
        val features = extractAnswerFeatures("- run the test\n- check the logs\nSource: https://example.com")
        assertTrue(features.structure)
        assertTrue(features.refs > 0)
        assertTrue(features.actions >= 2)

        val noisy = extractAnswerFeatures("I think it is probably something like 14 or so.")
        assertTrue(noisy.hedges > 0)
        assertTrue(noisy.vague > 0)
    }

    @Test
    fun `具体的问题得到更高的 clarity 与 quality`() {
        val engine = BayesianEngine()
        val specific = engine.scoreUser("How many minutes between 09:14 and 09:52? Answer only the number.")
        val vague = engine.scoreUser("stuff is broken somehow, you know?")
        // clarityPositive = 0.6(问号) + 0.2(>=8 词) + 0.2(无含糊词) = 1.0，可靠度 0.7
        assertEquals(2.7, specific.dimensions.getValue(Dimension.CLARITY).alpha, e6)
        assertEquals(2.0, specific.dimensions.getValue(Dimension.CLARITY).beta, e6)
        assertEquals(2.7 / 4.7, specific.dimensions.getValue(Dimension.CLARITY).mean, e6)
        assertTrue(specific.dimensions.getValue(Dimension.CLARITY).mean > vague.dimensions.getValue(Dimension.CLARITY).mean)
        assertTrue(specific.dimensions.getValue(Dimension.SPECIFICITY).mean > vague.dimensions.getValue(Dimension.SPECIFICITY).mean)
        assertTrue(specific.quality > vague.quality)
        assertEquals(6, specific.evidence.size)
    }

    @Test
    fun `情绪与紧迫被记成状态而不是质量`() {
        val engine = BayesianEngine()
        val intense = engine.scoreUser("I'm overwhelmed and need this now!")
        val calm = engine.scoreUser("No rush, whenever is fine.")
        assertTrue(intense.state.emotionalIntensity > calm.state.emotionalIntensity)
        assertTrue(intense.state.emotionalIntensity > 0.5)
        assertTrue(intense.state.urgency > calm.state.urgency)
    }

    @Test
    fun `每次调用的 reliability 按比例折算证据`() {
        val engine = BayesianEngine()
        val full = engine.scoreUser("How many minutes between 09:14 and 09:52? Answer only the number.")
        val half = engine.scoreUser(
            "How many minutes between 09:14 and 09:52? Answer only the number.",
            ScoreUserMeta(reliability = 0.5),
        )
        assertEquals(2.0 + (full.dimensions.getValue(Dimension.CLARITY).alpha - 2.0) * 0.5,
            half.dimensions.getValue(Dimension.CLARITY).alpha, e6)
    }

    @Test
    fun `回答的对错由检查器证据决定，overclaim 降低校准与简洁`() {
        val engine = BayesianEngine()
        val correct = engine.scoreAnswer("14", AnswerMeta(correct = true, grounded = true, targetTokens = 10))
        val wrong = engine.scoreAnswer("8.3%", AnswerMeta(correct = false, grounded = false, targetTokens = 10))
        assertEquals(7.0 / 9.0, correct.dimensions.getValue(Dimension.CORRECTNESS).mean, e6)
        assertEquals(2.0 / 9.0, wrong.dimensions.getValue(Dimension.CORRECTNESS).mean, e6)
        assertTrue(correct.overall > wrong.overall)

        val grounded = engine.scoreAnswer(
            "According to https://example.com/source, the value is 14.",
            AnswerMeta(correct = true, targetTokens = 60),
        )
        val unsupported = engine.scoreAnswer(
            "I think it is probably something like 14 or so.",
            AnswerMeta(correct = true, targetTokens = 60),
        )
        assertTrue(grounded.dimensions.getValue(Dimension.GROUNDING).mean >
            unsupported.dimensions.getValue(Dimension.GROUNDING).mean)

        val clean = engine.scoreAnswer("14", AnswerMeta(correct = true, targetTokens = 20))
        val overclaim = engine.scoreAnswer(
            "This is definitely, certainly, undoubtedly always 14, guaranteed, obviously proven. ".repeat(6),
            AnswerMeta(correct = true, targetTokens = 20),
        )
        assertTrue(clean.dimensions.getValue(Dimension.CONCISENESS).mean >
            overclaim.dimensions.getValue(Dimension.CONCISENESS).mean)
        assertTrue(clean.dimensions.getValue(Dimension.CALIBRATION).mean >
            overclaim.dimensions.getValue(Dimension.CALIBRATION).mean)
        assertTrue(clean.overall > overclaim.overall)
    }

    @Test
    fun `注意力遥测给出负荷后验与健康分`() {
        val engine = BayesianEngine()
        val high = AttentionMetrics(
            utilization = 0.95,
            pressure = 0.9,
            recoveryDebt = 0.8,
            errors = 4,
            switches = 12,
            latencyMs = 5000,
            targetLatencyMs = 1500,
        )
        val low = AttentionMetrics(
            utilization = 0.4,
            pressure = 0.1,
            recoveryDebt = 0.1,
            errors = 0,
            switches = 1,
            latencyMs = 300,
            targetLatencyMs = 1500,
        )
        val highScore = engine.scoreAttention(high)
        val lowScore = engine.scoreAttention(low)
        assertTrue(highScore.dimensions.getValue(Dimension.LOAD).mean > lowScore.dimensions.getValue(Dimension.LOAD).mean)
        assertTrue(highScore.dimensions.getValue(Dimension.STRESS).mean > lowScore.dimensions.getValue(Dimension.STRESS).mean)
        assertTrue(highScore.health < lowScore.health)
    }

    @Test
    fun `交互匹配使用用户状态与模式权重`() {
        val engine = BayesianEngine()
        val emotional = syntheticUser(emotionalIntensity = 0.9, urgency = 0.2, openness = 0.7)
        val urgent = syntheticUser(emotionalIntensity = 0.2, urgency = 0.9, openness = 0.2)
        val affectFit = engine.interactionFit(emotional, InteractionWeights(affect = 1.0))
        val thinkFit = engine.interactionFit(emotional, InteractionWeights(think = 1.0))
        assertEquals(0.82, affectFit.score, e6)
        assertTrue(affectFit.score > thinkFit.score)
        assertEquals("affect 1.00 × emotion 0.90", affectFit.reasons.first())
        assertTrue(engine.interactionFit(urgent, InteractionWeights(converge = 1.0)).score >
            engine.interactionFit(urgent, InteractionWeights(affect = 1.0)).score)
    }

    @Test
    fun `学习是显式的，且不会污染默认先验`() {
        val engine = BayesianEngine()
        assertEquals(2.0, engine.snapshot().getValue(Dimension.CORRECTNESS).alpha, e6)
        engine.learn(listOf(Evidence(Dimension.CORRECTNESS, 2.0, 0.0, 1.0, "checker")))
        assertEquals(4.0, engine.snapshot().getValue(Dimension.CORRECTNESS).alpha, e6)
        engine.learn(listOf(Evidence(Dimension.CORRECTNESS, 2.0, 0.0, 0.1, "self-report")))
        assertEquals(4.2, engine.snapshot().getValue(Dimension.CORRECTNESS).alpha, e6)
        // 参考实现浅拷贝先验会被 learn 改写；这里每个引擎独立，默认表不动。
        assertEquals(2.0, DefaultPriors.getValue(Dimension.CORRECTNESS).alpha, e6)
        assertEquals(2.0, BayesianEngine().snapshot().getValue(Dimension.CORRECTNESS).alpha, e6)
    }

    @Test
    fun `来源权重可更新、按零到一截断并快照`() {
        val engine = BayesianEngine(sourceWeights = mapOf("text" to 0.5))
        assertEquals(0.5, engine.sourceWeightsSnapshot().getValue("text"), e6)
        engine.setSourceWeights(mapOf("text" to 2.0, "self" to -1.0))
        assertEquals(1.0, engine.sourceWeightsSnapshot().getValue("text"), e6)
        assertEquals(0.0, engine.sourceWeightsSnapshot().getValue("self"), e6)
        val snapshot = engine.snapshot()
        assertEquals(19, snapshot.size)
    }

    private fun syntheticUser(
        emotionalIntensity: Double,
        urgency: Double,
        openness: Double,
        clarity: Double = 0.5,
        specificity: Double = 0.5,
    ): UserScore {
        val dimensions = LinkedHashMap<Dimension, Posterior>()
        Dimension.entries.forEach { dimensions[it] = posterior(0.5) }
        dimensions[Dimension.CLARITY] = posterior(clarity)
        dimensions[Dimension.SPECIFICITY] = posterior(specificity)
        dimensions[Dimension.EMOTIONAL_INTENSITY] = posterior(emotionalIntensity)
        dimensions[Dimension.URGENCY] = posterior(urgency)
        dimensions[Dimension.OPENNESS] = posterior(openness)
        return UserScore(
            dimensions = dimensions,
            quality = 0.5,
            state = UserState(emotionalIntensity = emotionalIntensity, urgency = urgency, openness = openness),
            evidence = emptyList(),
        )
    }

    private fun posterior(mean: Double): Posterior = Posterior(
        alpha = mean * 4,
        beta = (1 - mean) * 4,
        mean = mean,
        variance = 0.0,
        intervalLow = 0.0,
        intervalHigh = 1.0,
        evidenceCount = 1,
    )
}
