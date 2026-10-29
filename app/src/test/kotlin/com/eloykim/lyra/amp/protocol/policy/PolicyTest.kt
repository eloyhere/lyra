package com.eloykim.lyra.amp.protocol.policy

import com.eloykim.lyra.amp.protocol.types.Provenance
import com.eloykim.lyra.amp.protocol.types.ProvenanceKind
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.warrant.Claim
import com.eloykim.lyra.amp.protocol.warrant.ClaimStatus
import com.eloykim.lyra.amp.protocol.warrant.Inference
import com.eloykim.lyra.amp.protocol.warrant.Warrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `src/policy/policy.ts` 的行为锁定：层级下限、审批绑定、消费与失败关闭。
 */
class PolicyTest {

    private var clock: String = "2026-10-24T10:00:00Z"

    private fun engine(
        approvalEffects: Set<EffectClass> = DefaultApprovalEffects,
        allowReadWithoutClaim: Boolean = true,
        isClaimCurrent: ((Claim) -> Boolean)? = null,
        defaultTtlMs: Long = DEFAULT_TTL_MS,
        maxTtlMs: Long = DEFAULT_MAX_TTL_MS,
    ): PolicyEngine = PolicyEngine(
        PolicyOptions(
            now = { clock },
            defaultTtlMs = defaultTtlMs,
            maxTtlMs = maxTtlMs,
            approvalEffects = approvalEffects,
            allowReadWithoutClaim = allowReadWithoutClaim,
            isClaimCurrent = isClaimCurrent,
        ),
    )

    private fun heldClaim(
        tier: Tier = Tier.T3,
        confidence: Double = 0.9,
        status: ClaimStatus = ClaimStatus.HELD,
    ): Claim = Claim(
        id = "cl.1",
        statement = "deploy caused the spike",
        tier = tier,
        status = status,
        warrant = Warrant(
            evidence = emptyList(),
            inference = Inference.OBSERVATION,
            strength = confidence,
        ),
        provenance = Provenance(source = "test.observation", kind = ProvenanceKind.OBSERVATION),
        created = "2026-10-24T09:00:00Z",
        confidence = confidence,
    )

    private fun readRequest(claim: Claim? = null): ActionRequest =
        ActionRequest(action = "logs.query", effect = EffectClass.READ, claim = claim)

    private fun deployRequest(claim: Claim? = heldClaim()): ActionRequest = ActionRequest(
        action = "deploy.rollback",
        target = Ref(RefKind.TOOL, "mcp.deploy.rollback"),
        effect = EffectClass.DEPLOY,
        claim = claim,
        toolDigest = "sha256:" + "a".repeat(64),
        argsDigest = "sha256:" + "b".repeat(64),
    )

    @Test
    fun `规范最低层级按可逆程度递增`() {
        val policy = engine()
        assertEquals(Tier.T0, policy.minTierFor(EffectClass.READ))
        assertEquals(Tier.T1, policy.minTierFor(EffectClass.WRITE_REVERSIBLE))
        assertEquals(Tier.T2, policy.minTierFor(EffectClass.WRITE_IRREVERSIBLE))
        assertEquals(Tier.T2, policy.minTierFor(EffectClass.SPEND))
        assertEquals(Tier.T2, policy.minTierFor(EffectClass.SEND))
        assertEquals(Tier.T3, policy.minTierFor(EffectClass.DELETE))
        assertEquals(Tier.T3, policy.minTierFor(EffectClass.DEPLOY))
        assertEquals(Tier.T3, policy.minTierFor(EffectClass.PERMISSION))
        assertEquals(Tier.T3, policy.minTierFor(EffectClass.PUBLISH))
        assertEquals(9, EffectClasses.size)
        assertEquals(EffectClasses.toSet(), DefaultApprovalEffects + EffectClass.READ + EffectClass.WRITE_REVERSIBLE)
    }

    @Test
    fun `read 免 claim 只对 read 生效`() {
        val policy = engine()
        val read = policy.decide(readRequest())
        assertEquals(PolicyOutcome.ALLOW, read.outcome)
        assertEquals("read_without_claim", read.reason)
        assertEquals(Tier.T0, read.minTier)

        val write = policy.decide(ActionRequest(action = "file.write", effect = EffectClass.WRITE_REVERSIBLE))
        assertEquals(PolicyOutcome.DENY, write.outcome)
        assertEquals("missing_claim", write.reason)
        assertEquals(Tier.T1, write.minTier)
    }

    @Test
    fun `关闭 read 豁免后 read 也需要 claim`() {
        val policy = engine(allowReadWithoutClaim = false)
        val verdict = policy.decide(readRequest())
        assertEquals(PolicyOutcome.DENY, verdict.outcome)
        assertEquals("missing_claim", verdict.reason)
    }

    @Test
    fun `claim 必须 held 且达到有效层级`() {
        val policy = engine(approvalEffects = emptySet())

        val doubted = policy.decide(
            ActionRequest(
                action = "file.write",
                effect = EffectClass.WRITE_REVERSIBLE,
                claim = heldClaim(tier = Tier.T1, status = ClaimStatus.DOUBTED),
            ),
        )
        assertEquals("claim_not_held", doubted.reason)

        val low = policy.decide(
            ActionRequest(
                action = "deploy.rollback",
                effect = EffectClass.DEPLOY,
                claim = heldClaim(tier = Tier.T2),
            ),
        )
        assertEquals(PolicyOutcome.DENY, low.outcome)
        assertEquals("tier_below_minimum:T3", low.reason)

        val lowConfidence = policy.decide(
            ActionRequest(
                action = "deploy.rollback",
                effect = EffectClass.DEPLOY,
                claim = heldClaim(tier = Tier.T3, confidence = 0.5),
            ),
        )
        assertEquals("claim_low_confidence", lowConfidence.reason)

        val ok = policy.decide(
            ActionRequest(
                action = "deploy.rollback",
                effect = EffectClass.DEPLOY,
                claim = heldClaim(tier = Tier.T3),
            ),
        )
        assertEquals(PolicyOutcome.ALLOW, ok.outcome)
        assertEquals("allowed", ok.reason)
    }

    @Test
    fun `请求只能抬高下限不能降低`() {
        val policy = engine(approvalEffects = emptySet())

        val raised = policy.decide(
            ActionRequest(
                action = "publish.fact",
                effect = EffectClass.PUBLISH,
                claim = heldClaim(tier = Tier.T3),
                minTier = Tier.T4,
            ),
        )
        assertEquals(PolicyOutcome.DENY, raised.outcome)
        assertEquals("tier_below_minimum:T4", raised.reason)

        val satisfied = policy.decide(
            ActionRequest(
                action = "publish.fact",
                effect = EffectClass.PUBLISH,
                claim = heldClaim(tier = Tier.T4),
                minTier = Tier.T4,
            ),
        )
        assertEquals(PolicyOutcome.ALLOW, satisfied.outcome)

        val lowered = policy.decide(
            ActionRequest(
                action = "publish.fact",
                effect = EffectClass.PUBLISH,
                claim = heldClaim(tier = Tier.T3),
                minTier = Tier.T0,
            ),
        )
        assertEquals(PolicyOutcome.ALLOW, lowered.outcome)
        assertEquals(Tier.T3, lowered.minTier)
    }

    @Test
    fun `高风险效果缺审批返回 approval_required 而不是 deny`() {
        val policy = engine()
        val verdict = policy.decide(deployRequest())
        assertEquals(PolicyOutcome.APPROVAL_REQUIRED, verdict.outcome)
        assertEquals("approval_required", verdict.reason)
        assertEquals(Tier.T3, verdict.minTier)
        assertNull(verdict.approvalId)
    }

    @Test
    fun `审批签发字段、默认 scope 与 TTL 夹取`() {
        val policy = engine()
        val approval = policy.approve(
            ApproveInput(
                action = "deploy.rollback",
                effect = EffectClass.DEPLOY,
                approvedBy = "a.host",
                toolDigest = "sha256:" + "a".repeat(64),
                argsDigest = "sha256:" + "b".repeat(64),
            ),
        )
        assertTrue(approval.id.startsWith("apr."))
        assertEquals(36, approval.id.length)
        assertEquals(48, approval.nonce.length)
        assertEquals(listOf("deploy.rollback"), approval.scope)
        assertEquals("a.host", approval.approvedBy)
        assertEquals("2026-10-24T10:00:00Z", approval.issued)
        assertEquals("2026-10-24T10:05:00.000Z", approval.expires)
        assertEquals(1, policy.list().size)

        val clamped = policy.approve(
            ApproveInput(
                action = "deploy.rollback",
                effect = EffectClass.DEPLOY,
                approvedBy = "a.host",
                ttlMs = 60 * 60_000L,
            ),
        )
        assertEquals("2026-10-24T10:15:00.000Z", clamped.expires)
    }

    @Test
    fun `审批绑定 action effect 与 digest 并只消费一次`() {
        val policy = engine()
        val request = deployRequest()
        val approval = policy.approve(
            ApproveInput(
                action = request.action,
                effect = request.effect,
                approvedBy = "a.host",
                toolDigest = request.toolDigest,
                argsDigest = request.argsDigest,
            ),
        )

        val first = policy.decide(request, approval)
        assertEquals(PolicyOutcome.ALLOW, first.outcome)
        assertEquals("approved", first.reason)
        assertEquals(approval.id, first.approvalId)

        val replay = policy.decide(request, approval)
        assertEquals(PolicyOutcome.DENY, replay.outcome)
        assertEquals("approval_consumed", replay.reason)

        val otherAction = request.copy(action = "deploy.other")
        val wrongAction = policy.decide(otherAction, approval)
        assertEquals("approval_consumed", wrongAction.reason)
    }

    @Test
    fun `未消费时校验绑定字段`() {
        val policy = engine()
        val request = deployRequest()
        val approval = policy.approve(
            ApproveInput(
                action = request.action,
                effect = request.effect,
                approvedBy = "a.host",
                toolDigest = request.toolDigest,
                argsDigest = request.argsDigest,
            ),
        )

        val wrongAction = policy.checkApproval(approval, request.copy(action = "deploy.other"))
        assertEquals("approval_action_mismatch", wrongAction.reason)

        val wrongEffect = policy.checkApproval(approval, request.copy(effect = EffectClass.DELETE))
        assertEquals("approval_effect_mismatch", wrongEffect.reason)

        val wrongDigest = policy.checkApproval(
            approval,
            request.copy(argsDigest = "sha256:" + "c".repeat(64)),
        )
        assertEquals("approval_digest_mismatch", wrongDigest.reason)

        val tampered = policy.checkApproval(approval.copy(nonce = "tampered"), request)
        assertEquals("approval_invalid", tampered.reason)

        val unknown = policy.checkApproval(approval.copy(id = "apr.unknown"), request)
        assertEquals("approval_unknown", unknown.reason)

        val valid = policy.checkApproval(approval, request)
        assertEquals(PolicyOutcome.ALLOW, valid.outcome)
        assertEquals("approval_valid", valid.reason)

        // checkApproval 不消费：随后的 decide 仍可兑换。
        assertEquals(PolicyOutcome.ALLOW, policy.decide(request, approval).outcome)
    }

    @Test
    fun `过期与吊销 fail closed`() {
        clock = "2026-10-24T10:00:00Z"
        val policy = engine()
        val request = deployRequest()
        val approval = policy.approve(
            ApproveInput(
                action = request.action,
                effect = request.effect,
                approvedBy = "a.host",
                ttlMs = 1_000L,
            ),
        )
        clock = "2026-10-24T10:00:02Z"
        assertEquals("approval_expired", policy.decide(request, approval).reason)

        clock = "2026-10-24T10:00:00Z"
        val revoked = policy.approve(
            ApproveInput(action = request.action, effect = request.effect, approvedBy = "a.host"),
        )
        assertTrue(policy.revoke(revoked.id))
        assertTrue(policy.revoke(revoked.id))
        assertEquals("approval_revoked", policy.decide(request, revoked).reason)
        assertFalse(policy.revoke("apr.unknown"))
    }

    @Test
    fun `按 id 引用未知审批被拒绝`() {
        val policy = engine()
        val verdict = policy.decide(deployRequest(), "apr.missing")
        assertEquals(PolicyOutcome.DENY, verdict.outcome)
        assertEquals("approval_unknown", verdict.reason)
        assertEquals("apr.missing", verdict.approvalId)
    }

    @Test
    fun `consume 显式烧掉不需要审批的能力`() {
        val policy = engine()
        val request = readRequest()
        val approval = policy.approve(
            ApproveInput(action = request.action, effect = request.effect, approvedBy = "a.host"),
        )
        val verdict = policy.consume(approval.id, request)
        assertEquals(PolicyOutcome.ALLOW, verdict.outcome)
        assertEquals("approved", verdict.reason)
        assertEquals("approval_consumed", policy.consume(approval.id, request).reason)
    }

    @Test
    fun `claim 新鲜度投影只允许 true`() {
        var current = false
        val policy = engine(isClaimCurrent = { current })
        val request = ActionRequest(
            action = "file.write",
            effect = EffectClass.WRITE_REVERSIBLE,
            claim = heldClaim(tier = Tier.T1),
        )
        assertEquals("claim_requires_revalidation", policy.decide(request).reason)
        current = true
        assertEquals(PolicyOutcome.ALLOW, policy.decide(request).outcome)

        val throwing = engine(isClaimCurrent = { throw IllegalStateException("store offline") })
        assertEquals("claim_freshness_unavailable", throwing.decide(request).reason)
    }

    @Test
    fun `审批与请求的 JSON 往返保持字段`() {
        val policy = engine()
        val request = deployRequest()
        assertEquals(request, ActionRequest.fromJson(request.toJson()))

        val approval = policy.approve(
            ApproveInput(
                action = request.action,
                effect = request.effect,
                approvedBy = "a.host",
                scope = listOf("deploy.rollback", "deploy.pause"),
                toolDigest = request.toolDigest,
            ),
        )
        assertEquals(approval, Approval.fromJson(approval.toJson()))

        val verdict = policy.checkApproval(approval, request)
        assertEquals(verdict, PolicyVerdict.fromJson(verdict.toJson()))
        assertNotNull(verdict.approvalId)

        val parsed = PolicyOptions(now = { clock })
        assertEquals(DefaultApprovalEffects, parsed.approvalEffects)
        assertEquals(DEFAULT_TTL_MS, parsed.defaultTtlMs)
        assertTrue(parsed.allowReadWithoutClaim)
    }

    @Test
    fun `非法宿主输入抛异常而不是拒绝`() {
        val policy = engine()
        val invalid = listOf(
            { policy.approve(ApproveInput(action = " ", effect = EffectClass.READ, approvedBy = "a.host")) },
            { policy.approve(ApproveInput(action = "x", effect = EffectClass.READ, approvedBy = " ")) },
            { policy.approve(ApproveInput(action = "x", effect = EffectClass.READ, approvedBy = "a.host", ttlMs = 0)) },
            {
                policy.approve(
                    ApproveInput(
                        action = "x",
                        effect = EffectClass.READ,
                        approvedBy = "a.host",
                        toolDigest = " ",
                    ),
                )
            },
            {
                policy.approve(
                    ApproveInput(
                        action = "x",
                        effect = EffectClass.READ,
                        approvedBy = "a.host",
                        scope = listOf(""),
                    ),
                )
            },
        )
        invalid.forEach { action ->
            try {
                action()
                throw AssertionError("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `read 带 claim 时原因不是 read_without_claim`() {
        val policy = engine()
        val verdict = policy.decide(
            ActionRequest(
                action = "logs.query",
                effect = EffectClass.READ,
                claim = heldClaim(tier = Tier.T0),
            ),
        )
        assertEquals(PolicyOutcome.ALLOW, verdict.outcome)
        assertEquals("allowed", verdict.reason)
    }
}
