package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CredentialTest {

    private val signers = listOf(
        SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_ISSUE),
        SignerInput(SignoffRole.LEGAL, PerspectiveId.LEGAL, AT_ISSUE),
        SignerInput(SignoffRole.FINANCE, PerspectiveId.FINANCE, AT_ISSUE),
    )

    private fun draft(
        from: PerspectiveId = PerspectiveId.GENERAL_MANAGER,
        to: PerspectiveId = PerspectiveId.MARKETING,
        reason: String = "need a market view",
        visibleScope: List<Ref> = listOf(refOf(RefKind.ENTITY, "e.1"), refOf(RefKind.CLAIM, "c.1")),
        payloadRefs: List<Ref> = listOf(refOf(RefKind.CLAIM, "c.1")),
        ttlMs: Long? = 60_000,
        scenario: ScenarioId = ScenarioId.OFFICE,
        manifest: ManifestRef = ManifestRef("amn.1", 1),
        modes: Map<ModeId, Double>? = null,
        notes: List<String>? = null,
    ): CredentialDraft = CredentialDraft(
        from = from,
        to = to,
        reason = reason,
        visibleScope = visibleScope,
        payloadRefs = payloadRefs,
        budget = CredentialBudget(quota = Budget(tokens = 50), elasticity = Elasticity.MEDIUM),
        constraints = constraintsFor(scenario),
        manifest = manifest,
        scenario = scenario,
        modes = modes,
        issuedAt = AT_ISSUE,
        ttlMs = ttlMs,
        notes = notes,
    )

    private fun issue(draft: CredentialDraft = draft()): SwitchCredential = issueCredential(draft, signers)

    private fun request(
        at: String = AT_ISSUE,
        to: PerspectiveId = PerspectiveId.MARKETING,
        manifest: ManifestRef? = null,
        readRefs: List<Ref> = emptyList(),
        revoked: List<CredentialRevocation> = emptyList(),
    ): VerifyCredentialRequest = VerifyCredentialRequest(
        at = at,
        to = to,
        manifest = manifest,
        readRefs = readRefs,
        revoked = revoked,
    )

    @Test
    fun `签发写入过期摘要与三方签署`() {
        val credential = issue()
        assertEquals("switch-credential", credential.kind)
        assertTrue(credential.id.startsWith("crd."))
        assertEquals("2026-10-24T07:01:00.000Z", credential.expires)
        assertEquals(credential.digest, credentialDigest(credential))
        assertEquals(3, credential.signoffs.size)
        assertEquals(
            listOf(SignoffRole.ISSUER, SignoffRole.LEGAL, SignoffRole.FINANCE),
            credential.signoffs.map { it.role },
        )
        assertEquals(
            listOf(PerspectiveId.GENERAL_MANAGER, PerspectiveId.LEGAL, PerspectiveId.FINANCE),
            credential.signoffs.map { it.by },
        )
        assertTrue(credential.signoffs.all { it.digest == credential.digest })
        assertTrue(credential.signoffs.all { it.at == AT_ISSUE })

        // 未给 modes 时采用矩阵默认权重。
        assertEquals(0.1, credential.modes.affect, 1e-9)
        assertEquals(0.3, credential.modes.think, 1e-9)
        assertEquals(0.1, credential.modes.diverge, 1e-9)
        assertEquals(0.5, credential.modes.converge, 1e-9)
        assertEquals(EvidenceFloor.F2, credential.constraints.evidence)
        assertEquals(ManifestRef("amn.1", 1), credential.manifest)
        assertEquals(50, credential.budget.quota.tokens)
        assertEquals(Elasticity.MEDIUM, credential.budget.elasticity)
    }

    @Test
    fun `验证通过并返回可读摘要`() {
        val credential = issue()
        val verdict = verifyCredential(
            credential,
            request(
                manifest = ManifestRef("amn.1", 1),
                readRefs = listOf(refOf(RefKind.CLAIM, "c.1")),
            ),
        )
        assertTrue(verdict.valid)
        assertEquals("valid", verdict.code)
        assertEquals(
            "${credential.id} authorises marketing until 2026-10-24T07:01:00.000Z",
            verdict.detail,
        )
        assertTrue(canRead(credential, refOf(RefKind.CLAIM, "c.1")))
        assertFalse(canRead(credential, refOf(RefKind.CLAIM, "c.2")))
        assertEquals(2, credential.visibleScope.size)
    }

    @Test
    fun `验证拒绝篡改撤销错目标错清单越权读取与时间窗`() {
        val credential = issue()

        val tampered = credential.copy(expires = "2026-10-24T07:02:00.000Z")
        assertEquals("credential-tampered", verifyCredential(tampered, request()).code)

        val revoked = request(
            revoked = listOf(CredentialRevocation(credential.id, PerspectiveId.LEGAL, AT_ISSUE, "leak")),
        )
        val revokedVerdict = verifyCredential(credential, revoked)
        assertFalse(revokedVerdict.valid)
        assertEquals("credential-revoked", revokedVerdict.code)
        assertEquals("revoked by legal: leak", revokedVerdict.detail)

        assertEquals(
            "credential-target",
            verifyCredential(credential, request(to = PerspectiveId.TECHNOLOGY)).code,
        )
        assertEquals(
            "credential-manifest",
            verifyCredential(credential, request(manifest = ManifestRef("amn.1", 2))).code,
        )
        assertEquals(
            "credential-scope",
            verifyCredential(credential, request(readRefs = listOf(refOf(RefKind.CLAIM, "c.2")))).code,
        )
        assertEquals(
            "credential-expired",
            verifyCredential(credential, request(at = AT_TEN_MIN)).code,
        )
        assertEquals(
            "credential-not-yet-valid",
            verifyCredential(credential, request(at = "2026-10-24T06:59:00Z")).code,
        )
        assertEquals(
            "credential-incomplete",
            verifyCredential(credential, request(at = "not-a-time")).code,
        )

        // 载荷引用被移出可见范围后，摘要与可见范围都对不上：失效的伪造凭证按范围拒绝。
        val body = credential.unsigned().copy(payloadRefs = listOf(refOf(RefKind.CLAIM, "c.2")))
        val forgedDigest = credentialDigest(body)
        val forged = body.toCredential(forgedDigest, credential.signoffs.map { it.copy(digest = forgedDigest) })
        assertEquals("credential-scope", verifyCredential(forged, request()).code)
    }

    @Test
    fun `签发拒绝越界载荷空范围空理由与非法方向`() {
        assertGovernanceError(GovernanceCode.CREDENTIAL_SCOPE) {
            issue(draft(payloadRefs = listOf(refOf(RefKind.CLAIM, "c.2"))))
        }
        assertGovernanceError(GovernanceCode.CREDENTIAL_SCOPE) {
            issue(draft(visibleScope = emptyList(), payloadRefs = emptyList()))
        }
        assertGovernanceError(GovernanceCode.CREDENTIAL_INCOMPLETE) {
            issue(draft(reason = "   "))
        }
        assertGovernanceError(GovernanceCode.CREDENTIAL_TARGET) {
            issue(draft(to = PerspectiveId.GENERAL_MANAGER))
        }
        assertGovernanceError(GovernanceCode.CREDENTIAL_TARGET) {
            issue(draft(from = PerspectiveId.LEGAL, to = PerspectiveId.MARKETING))
        }
        assertGovernanceError(GovernanceCode.CREDENTIAL_TARGET) {
            issue(draft(to = PerspectiveId.STRATEGY, from = PerspectiveId.MARKETING))
        }
        assertGovernanceError(GovernanceCode.CREDENTIAL_TARGET) {
            issueCredential(
                draft(to = PerspectiveId.BOARD),
                signers,
            )
        }
    }

    @Test
    fun `战略目标需要董事会或人类授权签署`() {
        // 目标自己不能签署凭证，所以董事会目标由股东大会（human）授权。
        assertGovernanceError(GovernanceCode.CREDENTIAL_TARGET) {
            issueCredential(
                draft(to = PerspectiveId.BOARD),
                signers + SignerInput(SignoffRole.BOARD, PerspectiveId.BOARD, AT_ISSUE),
            )
        }
        val credential = issueCredential(
            draft(to = PerspectiveId.BOARD),
            signers + SignerInput(SignoffRole.HUMAN, PerspectiveId.SHAREHOLDERS, AT_ISSUE),
        )
        assertEquals(4, credential.signoffs.size)
        assertTrue(credential.signoffs.any { it.role == SignoffRole.HUMAN })
        val verdict = verifyCredential(credential, request(to = PerspectiveId.BOARD))
        assertTrue(verdict.valid)
        assertEquals(PerspectiveLayer.STRATEGIC, credentialTargetLayer(credential))
    }

    @Test
    fun `签署者不能是目标且不能重复或冒用角色`() {
        assertGovernanceError(GovernanceCode.CREDENTIAL_TARGET) {
            issueCredential(
                draft(to = PerspectiveId.LEGAL),
                signers,
            )
        }
        assertGovernanceError(GovernanceCode.CREDENTIAL_INCOMPLETE) {
            issueCredential(
                draft(),
                listOf(
                    SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_ISSUE),
                    SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_ISSUE),
                    SignerInput(SignoffRole.LEGAL, PerspectiveId.LEGAL, AT_ISSUE),
                    SignerInput(SignoffRole.FINANCE, PerspectiveId.FINANCE, AT_ISSUE),
                ),
            )
        }
        assertGovernanceError(GovernanceCode.CREDENTIAL_INCOMPLETE) {
            issueCredential(
                draft(),
                listOf(
                    SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_ISSUE),
                    SignerInput(SignoffRole.LEGAL, PerspectiveId.GENERAL_MANAGER, AT_ISSUE),
                    SignerInput(SignoffRole.FINANCE, PerspectiveId.FINANCE, AT_ISSUE),
                ),
            )
        }
        assertGovernanceError(GovernanceCode.CREDENTIAL_INCOMPLETE) {
            issueCredential(
                draft(),
                listOf(
                    SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_ISSUE),
                    SignerInput(SignoffRole.LEGAL, PerspectiveId.LEGAL, AT_ISSUE),
                ),
            )
        }
    }

    @Test
    fun `TTL 夹紧到一秒与三十分钟`() {
        val shortest = issue(draft(ttlMs = 0))
        assertEquals("2026-10-24T07:00:01.000Z", shortest.expires)
        val capped = issue(draft(ttlMs = 10_000_000))
        assertEquals("2026-10-24T07:30:00.000Z", capped.expires)
        val fallback = issue(draft(ttlMs = 60_000))
        assertEquals("2026-10-24T07:01:00.000Z", fallback.expires)
    }

    @Test
    fun `模式覆盖会被归一化`() {
        val credential = issue(draft(modes = mapOf(ModeId.THINK to 1.0)))
        assertEquals(0.0, credential.modes.affect, 0.0)
        assertEquals(1.0, credential.modes.think, 0.0)
        assertEquals(1.0, credential.modes.total, 1e-9)
    }

    @Test
    fun `审计摘要与目标层级`() {
        val credential = issue()
        assertEquals(
            "${credential.id} general-manager->marketing scope=2 expires=2026-10-24T07:01:00.000Z",
            credentialSummary(credential),
        )
        assertEquals(PerspectiveLayer.EXECUTION, credentialTargetLayer(credential))
    }

    @Test
    fun `撤销是事件而非改写`() {
        val credential = issue()
        val before = credential.toJson()
        val revocation = revokeCredential(credential, PerspectiveId.LEGAL, "leak", AT_TEN_MIN)
        assertEquals(credential.id, revocation.credentialId)
        assertEquals(PerspectiveId.LEGAL, revocation.by)
        assertEquals(AT_TEN_MIN, revocation.at)
        assertEquals(before, credential.toJson())
        assertEquals(credential.digest, credentialDigest(credential))

        assertEquals(
            PerspectiveId.BOARD,
            revokeCredential(credential, PerspectiveId.BOARD, "board order", AT_TEN_MIN).by,
        )
        assertEquals(
            PerspectiveId.GENERAL_MANAGER,
            revokeCredential(credential, PerspectiveId.GENERAL_MANAGER, "recall", AT_TEN_MIN).by,
        )
        assertGovernanceError(GovernanceCode.APPROVAL_NOT_PERMITTED) {
            revokeCredential(credential, PerspectiveId.OPERATIONS, "nope", AT_TEN_MIN)
        }
    }

    @Test
    fun `JSON 往返保持摘要`() {
        val credential = issue(draft(notes = listOf("short window")))
        val parsed = SwitchCredential.fromJson(credential.toJson())
        assertEquals(credential, parsed)
        assertEquals(credential.digest, credentialDigest(parsed))
        assertTrue(verifyCredential(parsed, request()).valid)
        assertEquals(listOf("short window"), parsed.notes)
    }

    @Test
    fun `密码学钩子绑定凭证主体`() {
        assumeTrue(Ed25519.isAvailable())
        val credential = issue()
        val governanceSigners = generateGovernanceSigners(listOf("legal", "finance"))
        val keyring = GovernanceKeyring()
        governanceSigners.forEach { keyring.add(it.signer, it.publicKey()!!, kid = it.kid) }
        val bundle = signSwitchCredential(credential, governanceSigners)
        assertEquals(GovernanceSubject.SWITCH_CREDENTIAL, bundle.subject)
        assertEquals(credential.id, bundle.subjectId)
        assertNull(bundle.subjectVersion)
        assertEquals(credential.digest, bundle.subjectDigest)
        assertTrue(verifySwitchCredentialSignatures(credential, bundle, keyring).ok)
    }
}
