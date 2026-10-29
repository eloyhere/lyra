package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AttentionManifestTest {

    private val signers = listOf(
        SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_ISSUE),
        SignerInput(SignoffRole.LEGAL, PerspectiveId.LEGAL, AT_ISSUE),
        SignerInput(SignoffRole.FINANCE, PerspectiveId.FINANCE, AT_ISSUE),
    )

    private fun binding(
        perspective: PerspectiveId = PerspectiveId.CUSTOMER_SERVICE,
        tokens: Int = 100,
        elasticity: Elasticity = Elasticity.LOW,
    ): BudgetBinding = BudgetBinding(
        scope = LedgerScope.PERSPECTIVE,
        perspective = perspective,
        cap = Budget(tokens = tokens),
        elasticity = elasticity,
    )

    private fun focusItems(): List<FocusItem> = listOf(
        FocusItem(refOf(RefKind.GOAL, "g.1"), FocusCategory.GOAL, 0.9, 3),
        FocusItem(refOf(RefKind.TASK, "s.1"), FocusCategory.SUBGOAL, 0.8, 2),
        FocusItem(refOf(RefKind.EVIDENCE, "e.1"), FocusCategory.EVIDENCE, 0.7, 2),
        FocusItem(refOf(RefKind.TOOL, "t.1"), FocusCategory.TOOL, 0.6, 1, ttlFrames = 5),
        FocusItem(refOf(RefKind.MEMORY, "m.1"), FocusCategory.MEMORY, 0.5, 1),
    )

    private fun ignoreItem(everyFrames: Int = 5): IgnoreItem = IgnoreItem(
        target = refOf(RefKind.CLAIM, "c.9"),
        reason = "off topic",
        revisitIf = "user-returns",
        everyFrames = everyFrames,
        lastFrame = 0,
    )

    private fun draft(
        perspective: PerspectiveId = PerspectiveId.CUSTOMER_SERVICE,
        scenario: ScenarioId = ScenarioId.CHAT,
        focus: List<FocusItem> = focusItems(),
        ignore: List<IgnoreItem> = emptyList(),
        budget: BudgetBinding = binding(perspective),
        evidenceFloor: EvidenceFloor? = null,
        escalations: List<EscalationTrigger>? = null,
        modes: Map<ModeId, Double>? = null,
        priorityWeights: Map<String, Double> = emptyMap(),
        ttlMs: Long? = null,
    ): AttentionManifestDraft = AttentionManifestDraft(
        id = "amn.1",
        perspective = perspective,
        scenario = scenario,
        modes = modes,
        focus = focus,
        ignore = ignore,
        priorityWeights = priorityWeights,
        budget = budget,
        evidenceFloor = evidenceFloor,
        escalations = escalations,
        constraints = constraintsFor(scenario),
        derivation = ManifestDerivation(7, digestOfText("board-strategy"), AT_ISSUE),
        issuedAt = AT_ISSUE,
        ttlMs = ttlMs,
    )

    private fun manifest(draft: AttentionManifestDraft = draft()): AttentionManifest =
        createAttentionManifest(draft, signers)

    @Test
    fun `推导解析矩阵行并裁剪焦点`() {
        val derived = deriveAttentionManifest(draft())
        assertEquals(ScenarioId.CHAT, derived.plan.scenario)
        assertEquals(EvidenceFloor.F3, derived.evidenceFloor)
        assertEquals(Tier.T1, derived.requiredTier)
        assertEquals(5, derived.reassessEvery)
        assertEquals(Elasticity.LOW, derived.elasticity)
        assertEquals(Sensitivity.LOW, derived.sensitivity)
        assertEquals(4, derived.focus.size)
        assertEquals(refOf(RefKind.GOAL, "g.1"), derived.focus.first().target)
        assertTrue(derived.focus.none { it.target.id == "m.1" })
        assertEquals(3, derived.escalations.size)
        assertTrue(derived.escalations.all { it.sensitivity == Sensitivity.LOW })
        assertEquals(EscalationTarget.ToPerspective(PerspectiveId.LEGAL), derived.escalations[0].to)
        assertTrue(derived.rationale.any { it.startsWith("focus trimmed from 5 to 4") })
    }

    @Test
    fun `忽略项节奏回落到矩阵节奏并夹紧`() {
        val fallback = deriveAttentionManifest(
            draft(ignore = listOf(ignoreItem(everyFrames = 0), ignoreItem(everyFrames = 999).copy(
                target = refOf(RefKind.CLAIM, "c.10"),
            ))),
        )
        assertEquals(5, fallback.ignore[0].everyFrames)
        assertEquals(60, fallback.ignore[1].everyFrames)
    }

    @Test
    fun `升级触发灵敏度不会低于矩阵`() {
        val derived = deriveAttentionManifest(
            draft(
                escalations = listOf(
                    EscalationTrigger("custom", Sensitivity.VERY_LOW, EscalationTarget.Human),
                ),
            ),
        )
        assertEquals(Sensitivity.LOW, derived.escalations[0].sensitivity)
        assertEquals(EscalationTarget.Human, derived.escalations[0].to)
    }

    @Test
    fun `推导失败关闭`() {
        assertGovernanceError(GovernanceCode.MANIFEST_SCOPE) {
            deriveAttentionManifest(draft(focus = emptyList()))
        }
        assertGovernanceError(GovernanceCode.MANIFEST_SCOPE) {
            deriveAttentionManifest(
                draft(
                    budget = BudgetBinding(LedgerScope.RESERVE, null, null, Budget(tokens = 1), Elasticity.LOW),
                ),
            )
        }
        assertGovernanceError(GovernanceCode.MANIFEST_SCOPE) {
            deriveAttentionManifest(
                draft(
                    budget = BudgetBinding(
                        LedgerScope.PERSPECTIVE,
                        PerspectiveId.LEGAL,
                        null,
                        Budget(tokens = 1),
                        Elasticity.LOW,
                    ),
                ),
            )
        }
        assertGovernanceError(GovernanceCode.MANIFEST_SCOPE) {
            deriveAttentionManifest(
                draft(
                    budget = BudgetBinding(LedgerScope.AGENT, null, null, Budget(tokens = 1), Elasticity.LOW),
                ),
            )
        }
        assertGovernanceError(GovernanceCode.MANIFEST_SCOPE) {
            deriveAttentionManifest(draft(priorityWeights = mapOf("goal" to -1.0)))
        }
        // office 矩阵下限 F2，调用方把下限降到 F3 需要法务豁免。
        assertGovernanceError(GovernanceCode.APPROVAL_REQUIRED) {
            deriveAttentionManifest(
                draft(
                    scenario = ScenarioId.OFFICE,
                    perspective = PerspectiveId.CUSTOMER_SERVICE,
                    budget = binding(PerspectiveId.CUSTOMER_SERVICE),
                    evidenceFloor = EvidenceFloor.F3,
                ),
            )
        }
    }

    @Test
    fun `签发写入版本过期与三方签署`() {
        val manifest = manifest()
        assertEquals(1, manifest.version)
        assertEquals("2026-10-24T19:00:00.000Z", manifest.expires)
        assertEquals(AT_ISSUE, manifest.issuedAt)
        assertEquals(3, manifest.signatures.size)
        assertEquals(manifest.digest, manifestDigest(manifest))
        assertTrue(verifyAttentionManifest(manifest).ok)
        assertTrue(verifyAttentionManifest(manifest, AT_ISSUE).ok)
        assertFalse(isExpired(manifest, AT_TEN_MIN))
        assertTrue(isExpired(manifest, AT_MANIFEST_EXPIRY))
        assertEquals(
            "amn.1 v1 chat [0.50/0.20/0.20/0.10] floor=F3 focus=4 ignore=0",
            manifestSummary(manifest),
        )
    }

    @Test
    fun `TTL 夹紧到一秒与七天`() {
        assertEquals("2026-10-24T07:00:01.000Z", manifest(draft(ttlMs = 1)).expires)
        assertEquals("2026-10-31T07:00:00.000Z", manifest(draft(ttlMs = 30L * 24 * 60 * 60 * 1000)).expires)
    }

    @Test
    fun `签发拒绝缺失重复自签的签署`() {
        assertGovernanceError(GovernanceCode.MANIFEST_UNSIGNED) {
            createAttentionManifest(draft(), signers.take(2))
        }
        assertGovernanceError(GovernanceCode.MANIFEST_UNSIGNED) {
            createAttentionManifest(
                draft(),
                listOf(
                    SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_ISSUE),
                    SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_ISSUE),
                ),
            )
        }
        assertGovernanceError(GovernanceCode.MANIFEST_UNSIGNED) {
            createAttentionManifest(
                draft(),
                listOf(
                    SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_ISSUE),
                    SignerInput(SignoffRole.LEGAL, PerspectiveId.BOARD, AT_ISSUE),
                    SignerInput(SignoffRole.FINANCE, PerspectiveId.FINANCE, AT_ISSUE),
                ),
            )
        }
        // 归属方不能自己签发：issue 角色只能由总经理签，先撞上角色归属检查。
        assertGovernanceError(GovernanceCode.MANIFEST_UNSIGNED) {
            createAttentionManifest(
                draft(),
                listOf(
                    SignerInput(SignoffRole.ISSUER, PerspectiveId.CUSTOMER_SERVICE, AT_ISSUE),
                    SignerInput(SignoffRole.LEGAL, PerspectiveId.LEGAL, AT_ISSUE),
                    SignerInput(SignoffRole.FINANCE, PerspectiveId.FINANCE, AT_ISSUE),
                ),
            )
        }
        // 法务归属的清单不能由法务自己会签。
        assertGovernanceError(GovernanceCode.MANIFEST_SELF_SIGNED) {
            createAttentionManifest(
                draft(perspective = PerspectiveId.LEGAL, budget = binding(PerspectiveId.LEGAL)),
                signers,
            )
        }
    }

    @Test
    fun `验证发现摘要被改`() {
        val manifest = manifest()
        val tampered = manifest.copy(expires = AT_TEN_MIN)
        assertNotEquals(manifest.digest, manifestDigest(tampered))
        assertEquals("manifest-tampered", verifyAttentionManifest(tampered).code)

        val resignedElsewhere = manifest.copy(
            signatures = manifest.signatures.map { it.copy(digest = digestOfText("other")) },
        )
        assertEquals("manifest-unsigned", verifyAttentionManifest(resignedElsewhere).code)

        val expired = verifyAttentionManifest(manifest, AT_MANIFEST_EXPIRY)
        assertEquals("manifest-expired", expired.code)
    }

    @Test
    fun `装载检查归属场景与过期`() {
        val manifest = manifest()
        val loaded = loadAttentionManifest(
            manifest,
            LoadManifestRequest(PerspectiveId.CUSTOMER_SERVICE, AT_ISSUE),
        )
        assertEquals(manifest, loaded.manifest)
        assertEquals(4, loaded.focus.size)
        assertTrue(loaded.reason.startsWith("loaded amn.1 v1 for customer-service"))
        assertTrue(loaded.reason.endsWith("4 focus, 0 ignored"))

        assertGovernanceError(GovernanceCode.MANIFEST_SCOPE) {
            loadAttentionManifest(manifest, LoadManifestRequest(PerspectiveId.LEGAL, AT_ISSUE))
        }
        assertGovernanceError(GovernanceCode.MANIFEST_SCOPE) {
            loadAttentionManifest(
                manifest,
                LoadManifestRequest(PerspectiveId.CUSTOMER_SERVICE, AT_ISSUE, ScenarioId.OFFICE),
            )
        }
        assertGovernanceError(GovernanceCode.MANIFEST_EXPIRED) {
            loadAttentionManifest(
                manifest,
                LoadManifestRequest(PerspectiveId.CUSTOMER_SERVICE, AT_MANIFEST_EXPIRY),
            )
        }
    }

    @Test
    fun `忽略项重评估按节奏与信号提升`() {
        val manifest = manifest(draft(ignore = listOf(ignoreItem())))
        assertTrue(manifest.ignore.isNotEmpty())

        val early = reassessIgnored(manifest, frame = 4, signals = listOf("user-returns"))
        assertTrue(early.due.isEmpty())
        assertTrue(early.promoted.isEmpty())
        assertEquals(1, early.updated.ignore.size)

        val dueWithoutSignal = reassessIgnored(manifest, frame = 5, signals = emptyList())
        assertEquals(1, dueWithoutSignal.due.size)
        assertTrue(dueWithoutSignal.promoted.isEmpty())
        assertEquals(1, dueWithoutSignal.retained.size)
        assertEquals(5, dueWithoutSignal.updated.ignore[0].lastFrame)

        val promoted = reassessIgnored(manifest, frame = 5, signals = listOf("user-returns"))
        assertEquals(1, promoted.due.size)
        assertEquals(1, promoted.promoted.size)
        assertTrue(promoted.retained.isEmpty())
        assertTrue(promoted.updated.ignore.isEmpty())
    }

    @Test
    fun `提升忽略项会生成新版本并要求签署`() {
        val manifest = manifest(draft(ignore = listOf(ignoreItem())))
        val item = manifest.ignore[0]
        assertGovernanceError(GovernanceCode.MANIFEST_UNSIGNED) {
            promoteIgnored(manifest, listOf(item), SignerInput(SignoffRole.ISSUER, PerspectiveId.LEGAL, AT_ISSUE), AT_ISSUE)
        }
        assertGovernanceError(GovernanceCode.MANIFEST_UNSIGNED) {
            promoteIgnored(manifest, listOf(item), SignerInput(SignoffRole.LEGAL, PerspectiveId.CUSTOMER_SERVICE, AT_ISSUE), AT_ISSUE)
        }
        assertEquals(
            manifest,
            promoteIgnored(manifest, emptyList(), SignerInput(SignoffRole.ISSUER, PerspectiveId.CUSTOMER_SERVICE, AT_ISSUE), AT_ISSUE),
        )

        val revised = promoteIgnored(
            manifest,
            listOf(item),
            SignerInput(SignoffRole.ISSUER, PerspectiveId.CUSTOMER_SERVICE, AT_ISSUE),
            AT_ISSUE,
        )
        assertEquals(2, revised.version)
        assertEquals(manifest.digest, revised.parent)
        assertEquals(5, revised.focus.size)
        assertTrue(revised.focus.any { it.target == item.target && it.category == FocusCategory.EVIDENCE })
        assertTrue(revised.ignore.isEmpty())
        assertEquals(1, revised.signatures.size)
        assertTrue(verifyAttentionManifest(revised, AT_ISSUE).ok)
    }

    @Test
    fun `更新只允许归属方改装焦点而总经理改更多`() {
        val manifest = manifest()
        val focusPatch = ManifestPatch(focus = listOf(focusItems().first()))

        assertGovernanceError(GovernanceCode.MANIFEST_SCOPE) {
            updateAttentionManifest(
                manifest,
                focusPatch,
                ManifestUpdateContext(
                    by = PerspectiveId.OPERATIONS,
                    at = AT_ISSUE,
                    signoff = SignerInput(SignoffRole.ISSUER, PerspectiveId.OPERATIONS, AT_ISSUE),
                ),
            )
        }

        val revised = updateAttentionManifest(
            manifest,
            focusPatch,
            ManifestUpdateContext(
                by = PerspectiveId.CUSTOMER_SERVICE,
                at = AT_ISSUE,
                signoff = SignerInput(SignoffRole.ISSUER, PerspectiveId.CUSTOMER_SERVICE, AT_ISSUE),
            ),
        )
        assertEquals(2, revised.version)
        assertEquals(1, revised.focus.size)
        assertEquals(manifest.digest, revised.parent)
        assertTrue(verifyAttentionManifest(revised, AT_ISSUE).ok)

        assertGovernanceError(GovernanceCode.MANIFEST_SCOPE) {
            updateAttentionManifest(
                manifest,
                ManifestPatch(modes = mapOf(ModeId.THINK to 0.5)),
                ManifestUpdateContext(
                    by = PerspectiveId.CUSTOMER_SERVICE,
                    at = AT_ISSUE,
                    signoff = SignerInput(SignoffRole.ISSUER, PerspectiveId.CUSTOMER_SERVICE, AT_ISSUE),
                ),
            )
        }

        assertGovernanceError(GovernanceCode.MANIFEST_REDERIVE_REQUIRED) {
            updateAttentionManifest(
                manifest,
                ManifestPatch(scenario = ScenarioId.ENGINEERING),
                ManifestUpdateContext(
                    by = PerspectiveId.GENERAL_MANAGER,
                    at = AT_ISSUE,
                    signoff = SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_ISSUE),
                ),
            )
        }
    }

    @Test
    fun `模式漂移需要总经理批准`() {
        val manifest = manifest()
        val generalManager = ManifestUpdateContext(
            by = PerspectiveId.GENERAL_MANAGER,
            at = AT_ISSUE,
            signoff = SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_ISSUE),
        )
        val smallPatch = ManifestPatch(
            modes = mapOf(
                ModeId.AFFECT to 0.45,
                ModeId.THINK to 0.2,
                ModeId.DIVERGE to 0.25,
                ModeId.CONVERGE to 0.1,
            ),
        )
        val small = updateAttentionManifest(manifest, smallPatch, generalManager)
        assertEquals(2, small.version)
        assertEquals(0.45, small.modes.affect, 1e-9)

        val bigPatch = ManifestPatch(
            modes = mapOf(
                ModeId.AFFECT to 0.2,
                ModeId.THINK to 0.2,
                ModeId.DIVERGE to 0.4,
                ModeId.CONVERGE to 0.2,
            ),
        )
        assertGovernanceError(GovernanceCode.APPROVAL_REQUIRED) {
            updateAttentionManifest(manifest, bigPatch, generalManager)
        }
        assertGovernanceError(GovernanceCode.APPROVAL_REQUIRED) {
            updateAttentionManifest(
                manifest,
                bigPatch,
                generalManager.copy(
                    approvals = ManifestUpdateApprovals(
                        modeWeights = ApprovalGrant(
                            "apr.mw",
                            ApprovalSubject.MODE_WEIGHTS,
                            PerspectiveId.FINANCE,
                            AT_ISSUE,
                            granted = true,
                        ),
                    ),
                ),
            )
        }
        val approved = updateAttentionManifest(
            manifest,
            bigPatch,
            generalManager.copy(
                approvals = ManifestUpdateApprovals(
                    modeWeights = ApprovalGrant(
                        "apr.mw",
                        ApprovalSubject.MODE_WEIGHTS,
                        PerspectiveId.GENERAL_MANAGER,
                        AT_ISSUE,
                        granted = true,
                    ),
                ),
            ),
        )
        assertEquals(2, approved.version)
        assertEquals(0.4, approved.modes.diverge, 1e-9)
        assertTrue(verifyAttentionManifest(approved, AT_ISSUE).ok)
    }

    @Test
    fun `降低证据下限预算与约束各需对应批准`() {
        val raised = manifest(draft(evidenceFloor = EvidenceFloor.F1))
        assertEquals(EvidenceFloor.F1, raised.evidenceFloor)
        val generalManager = ManifestUpdateContext(
            by = PerspectiveId.GENERAL_MANAGER,
            at = AT_ISSUE,
            signoff = SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_ISSUE),
        )

        assertGovernanceError(GovernanceCode.APPROVAL_REQUIRED) {
            updateAttentionManifest(raised, ManifestPatch(evidenceFloor = EvidenceFloor.F3), generalManager)
        }
        val lowered = updateAttentionManifest(
            raised,
            ManifestPatch(evidenceFloor = EvidenceFloor.F3),
            generalManager.copy(
                approvals = ManifestUpdateApprovals(
                    evidenceFloor = ApprovalGrant(
                        "apr.ef",
                        ApprovalSubject.MANIFEST_EVIDENCE_FLOOR,
                        PerspectiveId.LEGAL,
                        AT_ISSUE,
                        granted = true,
                    ),
                ),
            ),
        )
        assertEquals(EvidenceFloor.F3, lowered.evidenceFloor)
        assertEquals(Tier.T1, lowered.requiredTier)

        // 提高下限无需批准。
        val tightened = updateAttentionManifest(
            manifest(),
            ManifestPatch(evidenceFloor = EvidenceFloor.F1),
            generalManager,
        )
        assertEquals(EvidenceFloor.F1, tightened.evidenceFloor)
        assertEquals(Tier.T3, tightened.requiredTier)

        val budgetPatch = ManifestPatch(
            budget = binding(tokens = 500, elasticity = Elasticity.HIGH),
        )
        assertGovernanceError(GovernanceCode.APPROVAL_REQUIRED) {
            updateAttentionManifest(manifest(), budgetPatch, generalManager)
        }
        val budgetApproved = updateAttentionManifest(
            manifest(),
            budgetPatch,
            generalManager.copy(
                approvals = ManifestUpdateApprovals(
                    budget = ApprovalGrant(
                        "apr.b",
                        ApprovalSubject.BUDGET_ELASTICITY,
                        PerspectiveId.FINANCE,
                        AT_ISSUE,
                        granted = true,
                    ),
                ),
            ),
        )
        assertEquals(500, budgetApproved.budget.cap.tokens)

        val constraintsPatch = ManifestPatch(constraints = constraintsFor(ScenarioId.OFFICE))
        assertGovernanceError(GovernanceCode.APPROVAL_REQUIRED) {
            updateAttentionManifest(manifest(), constraintsPatch, generalManager)
        }
        val constraintsApproved = updateAttentionManifest(
            manifest(),
            constraintsPatch,
            generalManager.copy(
                approvals = ManifestUpdateApprovals(
                    constraints = ApprovalGrant(
                        "apr.c",
                        ApprovalSubject.MANIFEST_CONSTRAINTS,
                        PerspectiveId.LEGAL,
                        AT_ISSUE,
                        granted = true,
                    ),
                ),
            ),
        )
        assertEquals(EvidenceFloor.F2, constraintsApproved.constraints.evidence)
    }

    @Test
    fun `修订签署人必须是执行方且用 issuer 角色`() {
        val manifest = manifest()
        assertGovernanceError(GovernanceCode.MANIFEST_UNSIGNED) {
            updateAttentionManifest(
                manifest,
                ManifestPatch(focus = listOf(focusItems().first())),
                ManifestUpdateContext(
                    by = PerspectiveId.GENERAL_MANAGER,
                    at = AT_ISSUE,
                    signoff = SignerInput(SignoffRole.ISSUER, PerspectiveId.LEGAL, AT_ISSUE),
                ),
            )
        }
        assertGovernanceError(GovernanceCode.MANIFEST_UNSIGNED) {
            updateAttentionManifest(
                manifest,
                ManifestPatch(focus = listOf(focusItems().first())),
                ManifestUpdateContext(
                    by = PerspectiveId.GENERAL_MANAGER,
                    at = AT_ISSUE,
                    signoff = SignerInput(SignoffRole.LEGAL, PerspectiveId.GENERAL_MANAGER, AT_ISSUE),
                ),
            )
        }
    }

    @Test
    fun `重新推导递增版本并保留父摘要`() {
        val manifest = manifest()
        // 场景切换但未给 modes 时保留旧权重（参考实现行为），这里显式给出工程权重。
        val engineering = mapOf(
            ModeId.AFFECT to 0.05,
            ModeId.THINK to 0.35,
            ModeId.DIVERGE to 0.1,
            ModeId.CONVERGE to 0.5,
        )
        val revised = rederiveAttentionManifest(
            manifest,
            RederivePatch(
                scenario = ScenarioId.ENGINEERING,
                modes = engineering,
                evidenceFloor = EvidenceFloor.F1,
            ),
            signers,
            AT_ISSUE,
        )
        assertEquals(2, revised.version)
        assertEquals(manifest.digest, revised.parent)
        assertEquals(ScenarioId.ENGINEERING, revised.scenario)
        assertEquals(EvidenceFloor.F1, revised.evidenceFloor)
        assertEquals("re-derived from v1", revised.derivation.note)
        assertEquals(manifest.expires, revised.expires)
        assertEquals(2, revised.focus.size)
        assertTrue(verifyAttentionManifest(revised, AT_ISSUE).ok)

        // 未显式给 modes 时保留旧权重，因此焦点上限仍是 chat 的 4。
        val scenarioOnly = rederiveAttentionManifest(
            manifest,
            RederivePatch(scenario = ScenarioId.ENGINEERING, evidenceFloor = EvidenceFloor.F1),
            signers,
            AT_ISSUE,
        )
        assertEquals(ScenarioId.ENGINEERING, scenarioOnly.scenario)
        assertEquals(4, scenarioOnly.focus.size)

        assertGovernanceError(GovernanceCode.MANIFEST_EXPIRED) {
            rederiveAttentionManifest(
                manifest,
                RederivePatch(scenario = ScenarioId.ENGINEERING, evidenceFloor = EvidenceFloor.F1),
                signers,
                AT_MANIFEST_EXPIRY,
            )
        }
    }

    @Test
    fun `过期操作把过期时间收缩到当前`() {
        val manifest = manifest()
        val expired = expireAttentionManifest(manifest, AT_TEN_MIN)
        assertEquals(AT_TEN_MIN, expired.expires)
        assertTrue(isExpired(expired, AT_TEN_MIN))
        assertNotNull(expired.digest)
    }

    @Test
    fun `JSON 往返保持摘要`() {
        val manifest = manifest()
        val parsed = AttentionManifest.fromJson(manifest.toJson())
        assertEquals(manifest, parsed)
        assertEquals(manifest.digest, manifestDigest(parsed))
        assertTrue(verifyAttentionManifest(parsed).ok)

        val unsigned = parsed.unsigned()
        assertEquals(manifest.digest, manifestDigest(unsigned))
    }

    @Test
    fun `密码学钩子绑定主体与摘要`() {
        assumeTrue(Ed25519.isAvailable())
        val manifest = manifest()
        val governanceSigners = generateGovernanceSigners(listOf("legal", "finance"))
        val keyring = GovernanceKeyring()
        for (signer in governanceSigners) {
            keyring.add(signer.signer, signer.publicKey()!!, kid = signer.kid)
        }
        val bundle = signAttentionManifest(manifest, governanceSigners)
        assertEquals(GovernanceSubject.ATTENTION_MANIFEST, bundle.subject)
        assertEquals(manifest.id, bundle.subjectId)
        assertEquals(manifest.version, bundle.subjectVersion)
        assertEquals(manifest.digest, bundle.subjectDigest)
        assertTrue(
            verifyAttentionManifestSignatures(
                manifest,
                bundle,
                keyring,
                requiredSigners = listOf("legal", "finance"),
            ).ok,
        )
        assertTrue(verifyAttentionManifestSignatures(manifest, bundle, keyring).ok)
    }
}
