package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.types.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalTest {

    private val clock = testClock()

    private fun gate(registry: BehaviorRegistry = BehaviorRegistry()): LegalGate =
        LegalGate(now = clock, registry = registry)

    private fun approval(
        subject: ApprovalSubject,
        by: PerspectiveId,
        granted: Boolean = true,
    ): ApprovalGrant = ApprovalGrant(
        id = "apr.1",
        subject = subject,
        by = by,
        at = AT_ISSUE,
        granted = granted,
    )

    @Test
    fun `最严下限只升不降`() {
        assertEquals(EvidenceFloor.F1, strictestFloor(EvidenceFloor.F3, EvidenceFloor.F1))
        assertEquals(EvidenceFloor.F1, strictestFloor(EvidenceFloor.F1, EvidenceFloor.F3))
        assertEquals(EvidenceFloor.F2, strictestFloor(EvidenceFloor.F2, EvidenceFloor.F3))
        assertEquals(EvidenceFloor.F2, strictestFloor(EvidenceFloor.F2, EvidenceFloor.F2))
    }

    @Test
    fun `行为约束来自场景码表`() {
        val constraints = constraintsFor(ScenarioId.CHAT)
        assertEquals(EvidenceFloor.F3, constraints.evidence)
        assertTrue(constraints.forbidden.contains("self-sign"))
        assertTrue(constraints.obligations.contains("label-uncertainty"))
        assertTrue(constraints.escalation.contains("self-harm-risk"))
        assertTrue(constraints.allowed.contains("clarify"))
        assertEquals(EvidenceFloor.F2, constraintsFor(ScenarioId.ENGINEERING).evidence)

        val empty = BehaviorRegistry(seed = emptyList())
        assertGovernanceError(GovernanceCode.REGISTRY_INVALID) { constraintsFor(ScenarioId.CHAT, empty) }
        empty.register(DefaultBehaviorCodes.getValue(ScenarioId.CHAT))
        assertEquals(listOf(ScenarioId.CHAT), empty.scenarios())
    }

    @Test
    fun `场景严格度`() {
        val gate = gate()
        assertEquals(GateStrictness.LOOSE, gate.strictness(ScenarioId.CHAT))
        assertEquals(GateStrictness.MEDIUM, gate.strictness(ScenarioId.OFFICE))
        assertEquals(GateStrictness.MEDIUM_STRICT, gate.strictness(ScenarioId.RESEARCH))
        assertEquals(GateStrictness.STRICT, gate.strictness(ScenarioId.ENGINEERING))
    }

    @Test
    fun `工具检查识别禁止动作`() {
        val verdict = gate().checkTool(
            ToolCheckInput.of(
                perspective = PerspectiveId.OPERATIONS,
                scenario = ScenarioId.CHAT,
                action = "exfiltrate",
                effect = EffectClass.READ,
            ),
        )
        assertFalse(verdict.ok)
        assertEquals(LegalAction.WARN, verdict.action)
        assertEquals(1, verdict.violations.size)
        assertEquals(ViolationCode.BEHAVIOR_FORBIDDEN, verdict.violations[0].code)
        assertEquals(ViolationLevel.SEVERE, verdict.violations[0].level)
    }

    @Test
    fun `未知 effect 失败关闭`() {
        val verdict = gate().checkTool(
            ToolCheckInput(
                perspective = PerspectiveId.OPERATIONS,
                scenario = ScenarioId.CHAT,
                action = "teleport",
                effect = "teleport",
            ),
        )
        assertEquals(1, verdict.violations.size)
        assertEquals(ViolationCode.UNKNOWN_EFFECT, verdict.violations[0].code)
        assertEquals(ViolationLevel.MODERATE, verdict.violations[0].level)
    }

    @Test
    fun `高危效果需要法务批准`() {
        val input = ToolCheckInput.of(
            perspective = PerspectiveId.OPERATIONS,
            scenario = ScenarioId.ENGINEERING,
            action = "deploy",
            effect = EffectClass.DEPLOY,
        )
        val denied = gate().checkTool(input)
        assertEquals(ViolationCode.TOOL_UNAPPROVED, denied.violations[0].code)
        assertEquals(ViolationLevel.MODERATE, denied.violations[0].level)

        val allowed = gate().checkTool(
            input.copy(approval = approval(ApprovalSubject.TOOL_HIGH_RISK, PerspectiveId.LEGAL)),
        )
        assertTrue(allowed.ok)
        assertEquals(LegalAction.ALLOW, allowed.action)

        val wrongOwner = gate().checkTool(
            input.copy(approval = approval(ApprovalSubject.TOOL_HIGH_RISK, PerspectiveId.BOARD)),
        )
        assertFalse(wrongOwner.ok)
    }

    @Test
    fun `证据低于下限按场景严格度分级`() {
        val strict = gate().checkTool(
            ToolCheckInput.of(
                perspective = PerspectiveId.TECHNOLOGY,
                scenario = ScenarioId.ENGINEERING,
                action = "patch",
                effect = EffectClass.WRITE_REVERSIBLE,
                evidenceTier = Tier.T1,
                evidenceFloor = EvidenceFloor.F1,
            ),
        )
        assertEquals(ViolationCode.EVIDENCE_BELOW_FLOOR, strict.violations[0].code)
        assertEquals(ViolationLevel.MODERATE, strict.violations[0].level)

        val loose = gate().checkTool(
            ToolCheckInput.of(
                perspective = PerspectiveId.CUSTOMER_SERVICE,
                scenario = ScenarioId.CHAT,
                action = "respond",
                effect = EffectClass.READ,
                evidenceTier = Tier.T0,
                evidenceFloor = EvidenceFloor.F2,
            ),
        )
        assertEquals(ViolationLevel.MINOR, loose.violations[0].level)

        val satisfied = gate().checkTool(
            ToolCheckInput.of(
                perspective = PerspectiveId.TECHNOLOGY,
                scenario = ScenarioId.ENGINEERING,
                action = "test",
                effect = EffectClass.READ,
                evidenceTier = Tier.T3,
                evidenceFloor = EvidenceFloor.F1,
            ),
        )
        assertTrue(satisfied.ok)
    }

    @Test
    fun `董事会写入与升级检查`() {
        val board = gate().checkBoardWrite(
            BoardWriteInput(
                perspective = PerspectiveId.BOARD,
                scenario = ScenarioId.OFFICE,
                missing = listOf("why"),
                globalCore = true,
            ),
        )
        assertEquals(2, board.violations.size)
        assertEquals(ViolationCode.RECEIPT_INCOMPLETE, board.violations[0].code)
        assertEquals(ViolationCode.BOARD_UNAPPROVED, board.violations[1].code)
        assertEquals(ViolationLevel.SEVERE, board.violations[1].level)

        val approvedWrite = gate().checkBoardWrite(
            BoardWriteInput(
                perspective = PerspectiveId.BOARD,
                scenario = ScenarioId.OFFICE,
                missing = emptyList(),
                globalCore = true,
                approval = approval(ApprovalSubject.BOARD_WRITE, PerspectiveId.BOARD),
            ),
        )
        assertTrue(approvedWrite.ok)

        val escalation = gate().checkEscalation(
            EscalationCheckInput(
                perspective = PerspectiveId.GENERAL_MANAGER,
                scenario = ScenarioId.RESEARCH,
                evidenceRefs = 0,
                target = PerspectiveId.BOARD,
                credentialSigned = false,
            ),
        )
        assertEquals(2, escalation.violations.size)
        assertEquals(ViolationCode.ESCALATION_UNSIGNED, escalation.violations[0].code)
        assertEquals(ViolationCode.ESCALATION_UNSIGNED, escalation.violations[1].code)

        val direct = gate().checkEscalation(
            EscalationCheckInput(
                perspective = PerspectiveId.LEGAL,
                scenario = ScenarioId.RESEARCH,
                evidenceRefs = 2,
                target = PerspectiveId.GENERAL_MANAGER,
                credentialSigned = false,
            ),
        )
        assertTrue(direct.ok)
        assertTrue(
            gate().checkReceipt(
                ReceiptCheckInput(PerspectiveId.OPERATIONS, ScenarioId.OFFICE, emptyList()),
            ).ok,
        )
    }

    @Test
    fun `凭证检查要求三方签署`() {
        val verdict = gate().checkCredential(
            CredentialCheckInput(
                from = PerspectiveId.GENERAL_MANAGER,
                to = PerspectiveId.LEGAL,
                signoffs = emptyList(),
                selfSigned = true,
            ),
        )
        assertEquals(4, verdict.violations.size)
        assertEquals(ViolationCode.CREDENTIAL_SELF_SIGNED, verdict.violations[0].code)
        assertTrue(
            verdict.violations.drop(1).all { it.code == ViolationCode.CREDENTIAL_MISSING_SIGNOFF },
        )

        assertTrue(
            gate().checkCredential(
                CredentialCheckInput(
                    from = PerspectiveId.GENERAL_MANAGER,
                    to = PerspectiveId.LEGAL,
                    signoffs = listOf("issuer", "legal", "finance"),
                    selfSigned = false,
                ),
            ).ok,
        )
    }

    @Test
    fun `严重违规冻结并降级`() {
        val gate = gate()
        val verdict = gate.enforceTool(
            ToolCheckInput.of(
                perspective = PerspectiveId.OPERATIONS,
                scenario = ScenarioId.CHAT,
                action = "exfiltrate",
                effect = EffectClass.READ,
            ),
        )
        assertFalse(verdict.ok)
        assertEquals(LegalAction.DOWNGRADE, verdict.action)
        assertEquals(ViolationLevel.SEVERE, verdict.violations[0].level)
        val freeze = verdict.applied.freeze
        assertNotNull(freeze)
        assertEquals("frz.1", freeze!!.id)
        assertEquals("legal", freeze.issuedBy)
        assertEquals(AT_ISSUE, freeze.issuedAt)
        assertTrue(gate.isFrozen(PerspectiveId.OPERATIONS))
        assertEquals(1, gate.activeFreezes().size)

        val downgrade = verdict.applied.downgrade
        assertNotNull(downgrade)
        assertEquals("dwn.2", downgrade!!.id)
        assertEquals("restricted", downgrade.to)
        assertEquals("board", downgrade.escalatedTo)
        assertTrue(downgrade.reason.startsWith("severe: "))
        assertEquals(1, gate.downgradesFor(PerspectiveId.OPERATIONS).size)

        // 已有活动冻结单时不再新建。
        val second = gate.enforceTool(
            ToolCheckInput.of(
                perspective = PerspectiveId.OPERATIONS,
                scenario = ScenarioId.CHAT,
                action = "exfiltrate",
                effect = EffectClass.READ,
            ),
        )
        assertEquals("frz.1", second.applied.freeze!!.id)
        assertEquals(1, gate.activeFreezes().size)
        assertEquals(2, gate.downgradesFor(PerspectiveId.OPERATIONS).size)
    }

    @Test
    fun `三次中等违规升级为严重`() {
        val gate = gate()
        val input = ToolCheckInput.of(
            perspective = PerspectiveId.OPERATIONS,
            scenario = ScenarioId.CHAT,
            action = "delete",
            effect = EffectClass.DELETE,
        )
        val first = gate.enforceTool(input)
        assertEquals(LegalAction.FREEZE, first.action)
        assertEquals(ViolationLevel.MODERATE, first.violations[0].level)
        assertEquals(1, first.violations.size)

        gate.enforceTool(input)
        val third = gate.enforceTool(input)
        assertEquals(LegalAction.DOWNGRADE, third.action)
        assertFalse(third.ok)
        assertEquals(2, third.violations.size)
        assertTrue(third.violations.all { it.level == ViolationLevel.SEVERE })
        assertEquals(ViolationCode.REPEAT_VIOLATION, third.violations[1].code)
        assertEquals(
            "operations crossed a boundary 3 times",
            third.violations[1].detail,
        )
        assertTrue(third.violations[0].detail.endsWith("(repeated boundary crossing)"))
        assertNotNull(third.applied.downgrade)
        // 审计保留原始中等条目，加上新增的 repeat-violation。
        assertEquals(4, gate.auditTrail().violations.size)
    }

    @Test
    fun `只有董事会或人类能解冻`() {
        val gate = gate()
        gate.enforceTool(
            ToolCheckInput.of(
                perspective = PerspectiveId.OPERATIONS,
                scenario = ScenarioId.CHAT,
                action = "exfiltrate",
                effect = EffectClass.READ,
            ),
        )
        assertGovernanceError(GovernanceCode.APPROVAL_NOT_PERMITTED) {
            gate.appeal("frz.1", PerspectiveId.LEGAL, "why")
        }
        val appeal = gate.appeal("frz.1", PerspectiveId.GENERAL_MANAGER, "why")
        assertEquals("frz.1", appeal.orderId)
        assertEquals(AT_ISSUE, appeal.at)
        assertEquals(1, gate.appealsFor("frz.1").size)
        assertGovernanceError(GovernanceCode.LEGAL_FROZEN) {
            gate.appeal("frz.99", PerspectiveId.GENERAL_MANAGER, "why")
        }

        val lifted = gate.lift("frz.1", FreezeAuthority.BOARD)
        assertFalse(lifted.active)
        assertEquals(FreezeAuthority.BOARD, lifted.liftedBy)
        assertEquals(AT_ISSUE, lifted.liftedAt)
        assertFalse(gate.isFrozen(PerspectiveId.OPERATIONS))
        assertGovernanceError(GovernanceCode.LEGAL_FROZEN) {
            gate.lift("frz.99", FreezeAuthority.HUMAN)
        }
        assertEquals(1, gate.auditTrail().appeals.size)
        assertEquals(1, gate.auditTrail().orders.size)
    }

    @Test
    fun `熔断器只能由董事会或人类复位`() {
        val gate = gate()
        assertGovernanceError(GovernanceCode.LEGAL_BREAKER) { gate.resetBreaker(FreezeAuthority.BOARD) }
        assertNull(gate.breakerState())

        val tripped = gate.tripBreaker("injection")
        assertTrue(tripped.active)
        assertTrue(gate.isBlocked())
        assertEquals(tripped, gate.tripBreaker("again"))

        val reset = gate.resetBreaker(FreezeAuthority.HUMAN)
        assertFalse(reset.active)
        assertEquals(FreezeAuthority.HUMAN, reset.resetBy)
        assertEquals(AT_ISSUE, reset.resetAt)
        assertFalse(gate.isBlocked())

        val emergency = gate.reportEmergency(
            PerspectiveId.TECHNOLOGY,
            "malicious tool return",
            ViolationCode.MALICIOUS_TOOL,
        )
        assertFalse(emergency.ok)
        assertEquals(LegalAction.BREAKER, emergency.action)
        assertEquals(ViolationLevel.EMERGENCY, emergency.violations[0].level)
        assertNotNull(emergency.applied.freeze)
        assertNotNull(emergency.applied.downgrade)
        assertNotNull(emergency.applied.breaker)
        assertTrue(gate.isBlocked())
    }

    @Test
    fun `无违规则放行且不产生订单`() {
        val gate = gate()
        val verdict = gate.enforceReceipt(
            ReceiptCheckInput(PerspectiveId.OPERATIONS, ScenarioId.OFFICE, emptyList()),
        )
        assertTrue(verdict.ok)
        assertEquals(LegalAction.ALLOW, verdict.action)
        assertTrue(verdict.violations.isEmpty())
        assertNull(verdict.applied.freeze)
        assertTrue(gate.auditTrail().orders.isEmpty())
        assertFalse(gate.isFrozen(PerspectiveId.OPERATIONS))
    }
}
