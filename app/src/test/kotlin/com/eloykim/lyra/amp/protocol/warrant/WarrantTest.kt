package com.eloykim.lyra.amp.protocol.warrant

import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
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
 * 凭据不变量 W-1..W-5 与置信度推导。
 * 断言的是参考实现 `src/warrant/warrant.ts` 的可观察行为，不是这里的实现细节。
 */
class WarrantTest {

    @Test
    fun `W-1 没有证据的主张不能声称层级`() {
        val empty = Warrant(evidence = emptyList(), inference = Inference.ABDUCTION, strength = 0.5)
        assertEquals(emptyList<String>(), validateClaim(claim(tier = Tier.T0, warrant = empty)))
        assertTrue(
            validateClaim(claim(tier = Tier.T2, warrant = empty))
                .any { it.contains("requires at least one evidence item") },
        )
        assertFalse(canAuthorizeEffect(claim(tier = Tier.T0), Tier.T2))
        assertTrue(canAuthorizeEffect(claim(), Tier.T1))
    }

    @Test
    fun `W-2 假设必须有可证伪条件`() {
        assertEquals(listOf("refutes_if is required"), validateHypothesis(hypothesis(refutesIf = "  ")))
        assertEquals(listOf("prediction is required"), validateHypothesis(hypothesis(prediction = "")))
        assertEquals(emptyList<String>(), validateHypothesis(hypothesis()))
    }

    @Test
    fun `W-3 修订保留旧主张并指向活跃主张`() {
        val previous = claim(id = "cl.1")
        val revised = reviseClaim(previous, "回滚后错误率下降", AT2) { it.copy(status = ClaimStatus.DOUBTED) }

        assertEquals("cl.1.r1", revised.id)
        assertEquals(RefKind.CLAIM, revised.supersedes?.kind)
        assertEquals("cl.1", revised.supersedes?.id)
        assertEquals(ClaimStatus.DOUBTED, revised.status)
        assertEquals(previous.created, revised.created)
        assertEquals("回滚后错误率下降", revised.revision?.reason)
        assertEquals(AT2, revised.revision?.at)
        assertEquals(emptyList<String>(), validateSupersession(previous, revised))

        val second = reviseClaim(revised, "再次修订", AT2)
        assertEquals("cl.1.r1.r2", second.id)
        assertEquals(emptyList<String>(), validateSupersession(revised, second))

        assertTrue(
            validateSupersession(previous.copy(status = ClaimStatus.RETRACTED), revised)
                .any { it.contains("active claim") },
        )
        assertTrue(validateSupersession(null, revised).any { it.contains("unknown claim") })
        assertTrue(
            validateSupersession(previous, revised.copy(supersedes = null))
                .any { it.contains("supersedes") },
        )
    }

    @Test
    fun `W-4 阻塞性未知必须落账`() {
        assertTrue(validateUnknowns(emptyList(), atImpasse = true).any { it.contains("W-4") })

        val blocking = UnknownItem(
            id = "u.1",
            question = "发布是否导致错误率上升",
            importance = 0.8,
            blocking = true,
            since = AT,
            status = UnknownStatus.OPEN,
        )
        assertEquals(emptyList<String>(), validateUnknowns(listOf(blocking), atImpasse = true))
        assertTrue(
            validateUnknowns(listOf(blocking.copy(status = UnknownStatus.RESOLVED)), atImpasse = true).isNotEmpty(),
        )
        assertTrue(validateUnknowns(listOf(blocking, blocking)).any { it.contains("twice") })
        assertEquals(emptyList<String>(), AmpSchemas.validate("epistemic.schema.json", blocking.toJson()))
    }

    @Test
    fun `W-5 tier 不能靠断言提升`() {
        assertTrue(
            validateClaim(claim(tier = Tier.T1, warrant = warrantOf(evidence(entails = false, trust = Trust.TRUSTED))))
                .any { it.contains("entails") },
        )
        assertTrue(
            validateClaim(claim(tier = Tier.T2, warrant = warrantOf(evidence(trust = Trust.EXTERNAL))))
                .any { it.contains("T2") },
        )
        // 两个证据项同源时仍然只有一个来源，因此不能声称 T4
        assertTrue(
            validateClaim(
                claim(
                    tier = Tier.T4,
                    warrant = warrantOf(
                        evidence(id = "ev.1", origin = "a"),
                        evidence(id = "ev.2", origin = "a"),
                    ),
                ),
            ).any { it.contains("T4") },
        )
        assertTrue(
            validateClaim(claim(tier = Tier.T3, causal = CausalClaim(AssignmentMechanism.OBSERVED)))
                .any { it.contains("T3") },
        )
        assertTrue(
            validateClaim(
                claim(
                    tier = Tier.T3,
                    causal = CausalClaim(AssignmentMechanism.OBSERVED),
                    claimScope = ClaimScope.INTERVENTIONAL,
                ),
            ).any { it.contains("observational data") },
        )

        assertEquals(Tier.T2, suggestTier(listOf(evidence(trust = Trust.SELF))))
        assertEquals(
            Tier.T4,
            suggestTier(
                listOf(
                    evidence(id = "ev.1", origin = "a", trust = Trust.VERIFIED),
                    evidence(id = "ev.2", origin = "b", trust = Trust.VERIFIED),
                ),
            ),
        )
        assertEquals(Tier.T0, suggestTier(emptyList()))
    }

    @Test
    fun `置信度由 tier 与最弱来源共同决定`() {
        val empty = Warrant(evidence = emptyList(), inference = Inference.ABDUCTION, strength = 0.5)
        assertEquals(0.35, deriveConfidence(Tier.T0, empty), 1e-9)
        assertEquals(0.6, deriveConfidence(Tier.T4, empty), 1e-9)
        assertTrue(deriveConfidence(Tier.T1, warrantOf(evidence(trust = Trust.EXTERNAL))) <= 0.5)
        assertEquals(1, effectiveOrigins(warrantOf(evidence(id = "ev.1"), evidence(id = "ev.2")).evidence))
        assertEquals(
            2,
            effectiveOrigins(warrantOf(evidence(id = "ev.1", origin = "a"), evidence(id = "ev.2", origin = "b")).evidence),
        )
    }

    @Test
    fun `争议主张不能授权副作用且压力不改变置信度`() {
        assertFalse(canAuthorizeEffect(claim(status = ClaimStatus.CONTESTED), Tier.T1))
        assertFalse(canAuthorizeEffect(claim(status = ClaimStatus.DOUBTED), Tier.T1))
        assertFalse(canAuthorizeEffect(claim(tier = Tier.T1), Tier.T2))

        assertTrue(pressureBlocked(claim(), emptyList(), true))
        assertFalse(pressureBlocked(claim(), listOf(evidence()), true))
        assertFalse(pressureBlocked(null, emptyList(), true))
        assertFalse(pressureBlocked(claim(), emptyList(), false))
    }

    @Test
    fun `预注册决定 confirmatory 分类`() {
        assertEquals(HypothesisClass.EXPLORATORY, classifyHypothesis(hypothesis()))
        assertEquals(HypothesisClass.CONFIRMATORY, classifyHypothesis(hypothesis(prereg = prereg())))
    }

    private fun evidence(
        id: String = "ev.1",
        trust: Trust = Trust.SELF,
        origin: String? = "a",
        entails: Boolean? = true,
        independence: Double = 0.9,
        relation: EvidenceRelation? = null,
    ): EvidenceItem = EvidenceItem(
        id = id,
        kind = EvidenceKind.OBSERVATION,
        trust = trust,
        independence = independence,
        relation = relation,
        entails = entails,
        origin = origin,
        at = AT,
    )

    private fun warrantOf(vararg items: EvidenceItem): Warrant =
        Warrant(evidence = items.toList(), inference = Inference.ABDUCTION, strength = 0.7)

    private fun claim(
        id: String = "cl.1",
        tier: Tier = Tier.T2,
        status: ClaimStatus = ClaimStatus.HELD,
        warrant: Warrant = warrantOf(evidence()),
        causal: CausalClaim? = null,
        claimScope: ClaimScope? = null,
    ): Claim = Claim(
        id = id,
        statement = "x",
        tier = tier,
        status = status,
        warrant = warrant,
        provenance = Provenance(source = "a.gamma"),
        created = AT,
        causal = causal,
        claimScope = claimScope,
    )

    private fun prereg(): Prereg = Prereg(
        hash = preregHashOf("s", "metric", "stopping"),
        registeredAt = AT,
        metric = "metric",
        stopping = "stopping",
    )

    private fun hypothesis(
        refutesIf: String = "r",
        prediction: String = "p",
        prereg: Prereg? = null,
    ): Hypothesis = Hypothesis(
        id = "h.1",
        question = Ref(RefKind.UNKNOWN, "u.1"),
        statement = "s",
        prediction = prediction,
        refutesIf = refutesIf,
        status = HypothesisStatus.TESTING,
        created = AT,
        prereg = prereg,
    )

    private companion object {
        const val AT = "2026-10-24T01:00:00Z"
        const val AT2 = "2026-10-24T02:00:00Z"
    }
}
