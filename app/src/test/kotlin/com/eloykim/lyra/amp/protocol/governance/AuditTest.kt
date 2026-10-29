package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.storage.AppendLogError
import com.eloykim.lyra.amp.protocol.storage.MemoryAppendSink
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditTest {

    private fun record(minor: String = "issued"): GovernanceAuditRecord = GovernanceAuditRecord(
        category = GovernanceAuditCategory.MANIFEST,
        action = "manifest.$minor",
        actor = PerspectiveId.GENERAL_MANAGER.wire,
        detail = "v1 chat floor=F3",
        subject = "amn.1",
        refs = listOf(digestOfText("amn.1")),
        data = null,
    )

    @Test
    fun `追加记录生成哈希链接`() {
        val sink = MemoryAppendSink()
        val log = GovernanceAuditLog.open(sink, now = testClock())
        val first = log.record(record())
        val second = log.record(record("revised"))
        assertEquals(0, first.seq)
        assertEquals(1, second.seq)
        assertEquals(first.digest, second.prev)
        assertEquals(second.digest, log.tip())
        assertEquals(2, log.count)
        assertTrue(log.verify().ok)
        assertEquals(2, sink.lines.size)
        // 每一行都是一个完整的 JSON 对象，含 seq/kind/payload/prev/hash。
        assertTrue(sink.lines[1].contains("\"payload\""))
        assertTrue(sink.lines[1].contains("\"prev\""))
    }

    @Test
    fun `检查点固定长度与链尾`() {
        val log = GovernanceAuditLog.open(MemoryAppendSink(), now = testClock())
        log.record(record())
        log.record(record("revised"))
        val checkpoint = log.checkpoint()
        assertEquals(2, checkpoint.count)
        assertEquals(log.tip(), checkpoint.tip)
        assertTrue(log.verifyCheckpoint(checkpoint).ok)

        log.record(record("waived"))
        assertTrue("旧检查点仍属于链的前缀", log.verifyCheckpoint(checkpoint).ok)

        val tampered = checkpoint.copy(digest = digestOfText("not-the-checkpoint"))
        assertFalse(log.verifyCheckpoint(tampered).ok)
        assertTrue(log.verifyCheckpoint(tampered).reasons.any { it.contains("digest does not verify") })

        val ahead = checkpoint.copy(count = 99)
        assertFalse(log.verifyCheckpoint(ahead).ok)
        assertTrue(log.verifyCheckpoint(ahead).reasons.any { it.contains("outside the chain") })

        val wrongTip = checkpoint.copy(tip = digestOfText("wrong"))
        assertFalse(log.verifyCheckpoint(wrongTip).ok)
        assertTrue(log.verifyCheckpoint(wrongTip).reasons.any { it.contains("tip does not match") })
    }

    @Test
    fun `编辑记录让整条链失效`() {
        val sink = MemoryAppendSink()
        val log = GovernanceAuditLog.open(sink, now = testClock())
        log.record(record())
        log.record(record("revised"))
        sink.lines[0] = sink.lines[0].replace("manifest.issued", "manifest.revised")
        val error = assertThrowsAppendLog { GovernanceAuditLog.open(sink) }
        assertTrue(error.reasons.any { it.contains("hash does not cover") })
    }

    @Test
    fun `重排记录让链接失效`() {
        val sink = MemoryAppendSink()
        val log = GovernanceAuditLog.open(sink, now = testClock())
        log.record(record())
        log.record(record("revised"))
        val swapped = listOf(sink.lines[1], sink.lines[0])
        sink.lines.clear()
        sink.lines.addAll(swapped)
        val error = assertThrowsAppendLog { GovernanceAuditLog.open(sink) }
        assertTrue(error.reasons.any { it.contains("prev does not match") })
    }

    @Test
    fun `截断的尾部只能靠检查点发现`() {
        val sink = MemoryAppendSink()
        val log = GovernanceAuditLog.open(sink, now = testClock())
        log.record(record())
        log.record(record("revised"))
        val checkpoint = log.checkpoint()
        sink.lines.removeAt(sink.lines.size - 1)

        val truncated = GovernanceAuditLog.open(sink, now = testClock())
        assertEquals(1, truncated.count)
        assertTrue("干净截断产生内部自洽的前缀", truncated.verify().ok)
        assertFalse("检查点必须拒绝被截断的链", truncated.verifyCheckpoint(checkpoint).ok)
    }

    @Test
    fun `空行与坏 JSON 属于崩溃`() {
        val blank = MemoryAppendSink(mutableListOf(""))
        assertTrue(assertThrowsAppendLog { GovernanceAuditLog.open(blank) }.reasons.any { it.contains("empty line") })
        val broken = MemoryAppendSink(mutableListOf("{not json"))
        assertTrue(assertThrowsAppendLog { GovernanceAuditLog.open(broken) }.reasons.any { it.contains("not valid JSON") })
    }

    @Test
    fun `非法记录在进入链之前被拒绝`() {
        val log = GovernanceAuditLog.open(MemoryAppendSink(), now = testClock())
        assertThrowsGovernance(GovernanceCode.REGISTRY_INVALID) {
            log.record(record().copy(action = ""))
        }
        assertThrowsGovernance(GovernanceCode.REGISTRY_INVALID) {
            log.record(record().copy(refs = (1..MAX_AUDIT_REFS + 1).map { "ref.$it" }))
        }
        assertEquals(0, log.count)
    }

    @Test
    fun `按类别主题与序号检索`() {
        val log = GovernanceAuditLog.open(MemoryAppendSink(), now = testClock())
        log.record(record())
        log.record(
            record().copy(
                category = GovernanceAuditCategory.LEGAL,
                action = "legal.freeze",
                subject = "amf.1",
            ),
        )
        log.record(record("revised"))
        assertEquals(2, log.byCategory(GovernanceAuditCategory.MANIFEST).size)
        assertEquals(1, log.byCategory(GovernanceAuditCategory.LEGAL).size)
        assertEquals(1, log.bySubject("amf.1").size)
        assertEquals(2, log.entriesAfter(1).size)
    }

    @Test
    fun `清单与凭据记录使用真实对象字段`() {
        val log = GovernanceAuditLog.open(MemoryAppendSink(), now = testClock())
        val manifest = testManifest()
        val manifestEntry = log.recordManifest(manifest, PerspectiveId.GENERAL_MANAGER.wire)
        assertEquals("manifest.issued", manifestEntry.record.action)
        assertEquals(manifest.id, manifestEntry.record.subject)
        assertEquals(listOf(manifest.digest), manifestEntry.record.refs)
        assertEquals("v1 chat floor=F3", manifestEntry.record.detail)

        val revised = rederiveAttentionManifest(manifest, RederivePatch(note = "rev"), TEST_SIGNERS, AT_TEN_MIN)
        assertEquals("manifest.revised", log.recordManifest(revised, "legal").record.action)

        val credential = testCredential()
        val credentialEntry = log.recordCredential(credential)
        assertEquals("credential.issued", credentialEntry.record.action)
        assertEquals(credential.id, credentialEntry.record.subject)
        assertEquals(
            "general-manager->marketing until ${credential.expires}",
            credentialEntry.record.detail,
        )
        assertEquals(
            "credential.revoked",
            log.recordCredential(credential, CredentialAuditAction.REVOKED).record.action,
        )
        assertEquals("switch.peer", log.recordSwitch(credential, SwitchAuditAction.PEER, "general-manager").record.action)
    }

    @Test
    fun `回执扣费与法律裁决各自成类`() {
        val log = GovernanceAuditLog.open(MemoryAppendSink(), now = testClock())
        val receipt = BoardReceipt(
            id = "rcp.1",
            from = PerspectiveId.CUSTOMER_SERVICE,
            at = AT_ISSUE,
            conclusion = "requirement satisfied",
            evidence = listOf(refOf(RefKind.CLAIM, "c.1")),
            confidence = 0.75,
            budget = Budget(tokens = 10),
            manifest = ManifestRef("amn.1", 1),
            scenario = ScenarioId.CHAT,
            modes = ModeWeights(0.5, 0.2, 0.2, 0.1),
            digest = digestOfText("rcp.1"),
        )
        val receiptEntry = log.recordReceipt(receipt)
        assertEquals(GovernanceAuditCategory.RECEIPT, receiptEntry.record.category)
        assertEquals("confidence=0.75", receiptEntry.record.detail)

        val totals = BudgetTotals(tokens = 10, ms = 0, calls = 1, interrupts = 0, money = 0.0)
        val charge = ChargeResult(
            ok = true,
            code = ChargeCode.CHARGED,
            ledger = LedgerSnapshot(
                scope = LedgerScope.GLOBAL,
                id = "global",
                cap = totals.copy(tokens = 100),
                used = totals,
                ratio = 0.1,
                frozen = false,
                elasticity = Elasticity.LOW,
            ),
            ratio = 0.1,
            detail = "charged",
        )
        val chargeEntry = log.recordCharge(charge, "global", PerspectiveId.FINANCE.wire)
        assertEquals(GovernanceAuditCategory.BUDGET, chargeEntry.record.category)
        assertEquals("budget.charged", chargeEntry.record.action)
        assertEquals("ratio=0.10", chargeEntry.record.detail)

        val verdict = LegalVerdict(
            ok = false,
            action = LegalAction.FREEZE,
            violations = listOf(
                Violation(ViolationCode.BEHAVIOR_FORBIDDEN, ViolationLevel.MODERATE, "forbidden action"),
            ),
            applied = LegalApplied(
                freeze = FreezeOrder(
                    id = "frz.1",
                    perspective = PerspectiveId.TECHNOLOGY,
                    level = ViolationLevel.MODERATE,
                    reason = "forbidden action",
                    issuedAt = AT_ISSUE,
                    active = true,
                ),
            ),
        )
        val legalEntry = log.recordLegal(verdict, PerspectiveId.LEGAL.wire)
        assertEquals(GovernanceAuditCategory.LEGAL, legalEntry.record.category)
        assertEquals("legal.freeze", legalEntry.record.action)
        assertEquals("frz.1", legalEntry.record.subject)
        assertEquals("behavior-forbidden", legalEntry.record.detail)
    }

    @Test
    fun `运行时回合记录清单成本与控制决定`() {
        val log = GovernanceAuditLog.open(MemoryAppendSink(), now = testClock())
        val totals = BudgetTotals(tokens = 10, ms = 0, calls = 1, interrupts = 0, money = 0.0)
        val entry = log.recordTurn(
            TurnAuditInput(
                turnId = "turn.1",
                perspective = PerspectiveId.TECHNOLOGY.wire,
                manifestId = "amn.1",
                manifestVersion = 2,
                manifestDigest = digestOfText("amn.1"),
                tokens = 42,
                charge = ChargeResult(
                    ok = true,
                    code = ChargeCode.CHARGED,
                    ledger = LedgerSnapshot(
                        scope = LedgerScope.PERSPECTIVE,
                        id = "perspective.technology",
                        cap = totals.copy(tokens = 100),
                        used = totals,
                        ratio = 0.42,
                        frozen = false,
                        elasticity = Elasticity.LOW,
                    ),
                    ratio = 0.42,
                    detail = "charged",
                ),
                control = ControlSummary("allow", stop = false, escalate = false, approvalRequired = false),
                receiptId = "rcp.7",
                legalCodes = listOf("behavior-forbidden"),
            ),
        )
        assertEquals("runtime.turn", entry.record.action)
        assertEquals("tokens=42 charge=charged control=allow", entry.record.detail)
        assertEquals(listOf("amn.1", digestOfText("amn.1"), "rcp.7"), entry.record.refs)
        assertEquals(GovernanceAuditCategory.RUNTIME, entry.record.category)
        assertNotEquals(0, entry.digest.length)
    }

    private fun assertThrowsAppendLog(block: () -> Unit): AppendLogError {
        var caught: AppendLogError? = null
        try {
            block()
        } catch (error: AppendLogError) {
            caught = error
        }
        if (caught == null) {
            throw AssertionError("期望 AppendLogError，但没有抛出")
        }
        return caught!!
    }
}
