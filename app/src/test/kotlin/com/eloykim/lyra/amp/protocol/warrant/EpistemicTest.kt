package com.eloykim.lyra.amp.protocol.warrant

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Provenance
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.Trust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线格式与 schema：claim / hypothesis / unknown 必须能原样往返，且省略可选字段。
 */
class EpistemicTest {

    @Test
    fun `claim 示例通过 schema 且可往返`() {
        val parsed = example("claim.json")
        assertEquals(emptyList<String>(), AmpSchemas.validate("epistemic.schema.json", parsed))

        val claim = Claim.fromJson(parsed)
        assertEquals("cl.91", claim.id)
        assertEquals(Tier.T2, claim.tier)
        assertEquals(ClaimStatus.HELD, claim.status)
        assertEquals(0.72, claim.confidence ?: Double.NaN, 1e-9)
        assertEquals(2, claim.warrant.evidence.size)
        assertEquals(Trust.VERIFIED, claim.warrant.evidence[0].trust)
        assertEquals(Trust.TRUSTED, claim.warrant.evidence[1].trust)
        assertEquals(Inference.ABDUCTION, claim.warrant.inference)
        assertEquals("difference_in_differences", claim.causal?.identificationStrategy)
        assertEquals(AssignmentMechanism.NATURAL_EXPERIMENT, claim.causal?.assignmentMechanism)
        assertEquals("error_rate", (claim.structured?.value as JsonValue.Str).value)
        assertEquals(RefKind.EVENT, claim.structured?.subject?.kind)
        assertEquals(CanonicalJson.encode(parsed), CanonicalJson.encode(claim.toJson()))

        // 与参考实现对齐：来源去重、置信度推导与层级建议
        assertEquals(2, effectiveOrigins(claim.warrant.evidence))
        assertEquals(0.85, deriveConfidence(claim), 1e-9)
        assertEquals(Tier.T4, suggestTier(claim.warrant.evidence))
        assertEquals(emptyList<String>(), validateClaim(claim))
    }

    @Test
    fun `hypothesis 与 unknown 往返且各自只命中 schema 的一个分支`() {
        val hypothesis = Hypothesis(
            id = "h.1",
            question = Ref(RefKind.UNKNOWN, "u.1"),
            statement = "发布导致错误率上升",
            prediction = "回滚后错误率回到基线",
            refutesIf = "回滚后错误率仍是基线三倍",
            status = HypothesisStatus.TESTING,
            created = AT,
            prereg = Prereg(
                hash = preregHashOf("发布导致错误率上升", "5xx_rate", "fixed_n_10m"),
                registeredAt = AT,
                metric = "5xx_rate",
                stopping = "fixed_n_10m",
                looks = 0,
            ),
            labels = listOf(HypothesisLabel.CONFIRMATORY),
        )
        val hypothesisJson = hypothesis.toJson()
        assertEquals(emptyList<String>(), AmpSchemas.validate("epistemic.schema.json", hypothesisJson))
        assertEquals(CanonicalJson.encode(hypothesisJson), CanonicalJson.encode(Hypothesis.fromJson(hypothesisJson).toJson()))

        val unknown = UnknownItem(
            id = "u.1",
            question = "01:04 的发布是否真的导致错误率上升？",
            importance = 0.8,
            blocking = true,
            since = AT,
            attempts = listOf(Ref(RefKind.TOOL, "logs.query")),
            status = UnknownStatus.OPEN,
        )
        val unknownJson = unknown.toJson()
        assertEquals(emptyList<String>(), AmpSchemas.validate("epistemic.schema.json", unknownJson))
        assertEquals(CanonicalJson.encode(unknownJson), CanonicalJson.encode(UnknownItem.fromJson(unknownJson).toJson()))
    }

    @Test
    fun `可选字段缺失时不会写成 null`() {
        val claim = Claim(
            id = "cl.1",
            statement = "日志显示发布后 5xx 上升",
            tier = Tier.T0,
            status = ClaimStatus.CONJECTURE,
            warrant = Warrant(evidence = emptyList(), inference = Inference.OBSERVATION, strength = 0.3),
            provenance = Provenance(source = "a.gamma"),
            created = AT,
        )
        val json = claim.toJson()
        assertEquals("0.1", json.string("amp"))
        assertEquals("claim", json.string("kind"))
        assertFalse(json.has("confidence"))
        assertFalse(json.has("expires"))
        assertFalse(json.has("supersedes"))
        assertFalse(json.has("revision"))
        assertFalse(json.has("causal"))
        assertFalse(json.has("structured"))
        assertEquals(emptyList<String>(), AmpSchemas.validate("epistemic.schema.json", json))

        val labelKeys = Hypothesis(
            id = "h.1",
            question = Ref(RefKind.UNKNOWN, "u.1"),
            statement = "s",
            prediction = "p",
            refutesIf = "r",
            status = HypothesisStatus.PROPOSED,
            created = AT,
        ).toJson()
        assertFalse(labelKeys.has("prereg"))
        assertFalse(labelKeys.has("test"))
        assertFalse(labelKeys.has("labels"))
        assertFalse(labelKeys.has("closed"))
    }

    @Test
    fun `kind 分派到对应对象`() {
        assertTrue(EpistemicObject.fromJson(example("claim.json")) is Claim)
        assertTrue(
            EpistemicObject.fromJson(
                UnknownItem(
                    id = "u.2",
                    question = "q",
                    importance = 0.5,
                    blocking = false,
                    since = AT,
                ).toJson(),
            ) is UnknownItem,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `未知 epistemic kind 被拒绝`() {
        EpistemicObject.fromJson(JsonParser.parse("""{"amp":"0.1","kind":"belief","id":"b.1"}"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `信封版本不匹配被拒绝`() {
        val json = """
            {"amp":"0.2","kind":"claim","id":"cl.1","statement":"x","tier":"T0","status":"conjecture",
             "warrant":{"evidence":[],"inference":"observation","strength":0.3},
             "provenance":{"source":"a.gamma"},"created":"2026-10-24T01:00:00Z"}
        """.trimIndent()
        Claim.fromJson(JsonParser.parse(json))
    }

    private fun example(name: String): JsonValue {
        val text = javaClass.getResource("/amp/examples/$name")?.readText()
            ?: error("缺少测试资源 $name")
        return JsonParser.parse(text)
    }

    private companion object {
        const val AT = "2026-10-24T01:00:00Z"
    }
}
