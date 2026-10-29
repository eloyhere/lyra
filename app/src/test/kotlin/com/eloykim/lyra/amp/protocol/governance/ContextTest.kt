package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTest {

    private fun strategy(): StrategyView = StrategyView(
        version = 7,
        digest = digestOfText("board-strategy"),
        summary = "hold the line",
        detail = "internal board reasoning that must not leak",
    )

    private fun core(): GlobalCore = GlobalCore(
        goal = "ship v1",
        strategy = strategy(),
        constraints = listOf("no pii"),
        priorities = listOf("correctness"),
        taskState = "intake",
        scenario = ScenarioId.OFFICE,
        modes = ModeWeights(0.1, 0.3, 0.1, 0.5),
        budget = Budget(tokens = 100),
        version = 1,
    )

    private fun layers(): ContextLayers = ContextLayers(core(), now = testClock())

    private fun receiptInput(
        manifest: ManifestRef = ManifestRef("amn.1", 1),
        confidence: Double = 0.75,
        audience: List<PerspectiveId>? = null,
        escalation: EscalationRef? = null,
    ): BoardReceiptInput = BoardReceiptInput(
        conclusion = "the requirement is satisfied",
        evidence = listOf(refOf(RefKind.CLAIM, "c.1")),
        confidence = confidence,
        budget = Budget(tokens = 10),
        manifest = manifest,
        scenario = ScenarioId.OFFICE,
        modes = ModeWeights(0.1, 0.3, 0.1, 0.5),
        openQuestions = listOf("what about v2?"),
        escalation = escalation,
        audience = audience,
    )

    @Test
    fun `默认视图只给策略摘要`() {
        val view = layers().global(PerspectiveId.CUSTOMER_SERVICE)
        assertEquals("hold the line", view.strategySummary)
        assertEquals(7, view.strategyVersion)
        assertFalse(view.toJson().toString().contains("internal board reasoning"))
        val full = layers().expandGlobal(PerspectiveId.CUSTOMER_SERVICE)
        assertEquals("internal board reasoning that must not leak", full.strategy.detail)
    }

    @Test
    fun `只有董事会或人类可以写全局核心`() {
        val layers = layers()
        assertGovernanceError(GovernanceCode.CONTEXT_DENIED) {
            layers.writeGlobal(PerspectiveId.LEGAL.wire, GlobalCorePatch(goal = "hijack"))
        }
        val board = layers.writeGlobal(PerspectiveId.BOARD.wire, GlobalCorePatch(goal = "ship v2"))
        assertEquals(2, board.version)
        assertEquals("ship v2", board.goal)
        assertEquals("internal board reasoning that must not leak", board.strategy.detail)

        val badApproval = ApprovalGrant(
            id = "apv.bad",
            subject = ApprovalSubject.SCENARIO_JUDGEMENT,
            by = PerspectiveId.BOARD,
            at = AT_ISSUE,
            granted = true,
        )
        assertGovernanceError(GovernanceCode.CONTEXT_DENIED) {
            layers.writeGlobal(PerspectiveId.BOARD.wire, GlobalCorePatch(goal = "no"), badApproval)
        }
        val approved = layers.writeGlobal(
            PerspectiveId.BOARD.wire,
            GlobalCorePatch(taskState = "execution"),
            ApprovalGrant(
                id = "apv.board",
                subject = ApprovalSubject.BOARD_WRITE,
                by = PerspectiveId.BOARD,
                at = AT_ISSUE,
                granted = true,
            ),
        )
        assertEquals(3, approved.version)
        assertEquals("execution", approved.taskState)

        val human = layers.writeGlobal(HUMAN_WRITER, GlobalCorePatch(priorities = listOf("safety")))
        assertEquals(4, human.version)
        assertEquals(listOf("safety"), human.priorities)
    }

    @Test
    fun `私有上下文拒绝他人读取并在切换时封闭`() {
        val layers = layers()
        layers.openPerspective(PerspectiveId.CUSTOMER_SERVICE, ManifestRef("amn.1", 1))
        layers.writePrivate(
            PerspectiveId.CUSTOMER_SERVICE,
            PrivateNote(AT_ISSUE, PrivateNoteKind.REASONING, "user is frustrated"),
        )
        assertGovernanceError(GovernanceCode.CONTEXT_DENIED) {
            layers.readPrivate(PerspectiveId.CUSTOMER_SERVICE, PerspectiveId.TECHNOLOGY)
        }
        assertEquals(1, layers.snapshot().privateContexts.size)

        layers.openPerspective(PerspectiveId.TECHNOLOGY, ManifestRef("amn.2", 1))
        val sealed = layers.readPrivate(PerspectiveId.CUSTOMER_SERVICE, PerspectiveId.CUSTOMER_SERVICE)!!
        assertTrue(sealed.sealed)
        assertEquals(1, sealed.notes.size)
        val summary = layers.snapshot().privateContexts.associateBy { it.perspective }
        assertTrue(summary.getValue(PerspectiveId.CUSTOMER_SERVICE).sealed)
        assertFalse(summary.getValue(PerspectiveId.TECHNOLOGY).sealed)

        assertGovernanceError(GovernanceCode.CONTEXT_DENIED) {
            layers.writePrivate(
                PerspectiveId.CUSTOMER_SERVICE,
                PrivateNote(AT_ISSUE, PrivateNoteKind.OBSERVATION, "late note"),
            )
        }
    }

    @Test
    fun `回执必须来自活跃视角且字段合法`() {
        val layers = layers()
        assertGovernanceError(GovernanceCode.CONTEXT_DENIED) {
            layers.writeReceipt(PerspectiveId.CUSTOMER_SERVICE, receiptInput())
        }
        layers.openPerspective(PerspectiveId.CUSTOMER_SERVICE, ManifestRef("amn.1", 1))
        assertGovernanceError(GovernanceCode.CONTEXT_DENIED) {
            layers.writeReceipt(PerspectiveId.TECHNOLOGY, receiptInput())
        }
        assertGovernanceError(GovernanceCode.CONTEXT_DENIED) {
            layers.writeReceipt(PerspectiveId.CUSTOMER_SERVICE, receiptInput(confidence = 1.5))
        }
        assertGovernanceError(GovernanceCode.MANIFEST_VERSION_CONFLICT) {
            layers.writeReceipt(PerspectiveId.CUSTOMER_SERVICE, receiptInput(ManifestRef("amn.1", 2)))
        }
        val receipt = layers.writeReceipt(PerspectiveId.CUSTOMER_SERVICE, receiptInput())
        assertEquals("rcp.1", receipt.id)
        assertEquals(0.75, receipt.confidence, 0.0)
        assertEquals(listOf("what about v2?"), receipt.openQuestions)
        assertEquals(digestOfText("rcp.1").length, receipt.digest.length)
    }

    @Test
    fun `回执按受众过滤且管理视角看全部`() {
        val layers = layers()
        layers.openPerspective(PerspectiveId.CUSTOMER_SERVICE, ManifestRef("amn.1", 1))
        val private = layers.writeReceipt(PerspectiveId.CUSTOMER_SERVICE, receiptInput())
        val addressed = layers.writeReceipt(
            PerspectiveId.CUSTOMER_SERVICE,
            receiptInput(audience = listOf(PerspectiveId.TECHNOLOGY)),
        )
        assertEquals(2, layers.readReceipts(PerspectiveId.CUSTOMER_SERVICE).size)
        assertEquals(listOf(addressed.id), layers.readReceipts(PerspectiveId.TECHNOLOGY).map { it.id })
        assertEquals(0, layers.readReceipts(PerspectiveId.MARKETING).size)
        assertEquals(2, layers.readReceipts(PerspectiveId.GENERAL_MANAGER).size)
        assertGovernanceError(GovernanceCode.CONTEXT_DENIED) {
            layers.readReceipt(private.id, PerspectiveId.MARKETING)
        }
        assertNull(layers.readReceipt("rcp.none", PerspectiveId.GENERAL_MANAGER))
    }

    @Test
    fun `升级回执单独列出`() {
        val layers = layers()
        layers.openPerspective(PerspectiveId.CUSTOMER_SERVICE, ManifestRef("amn.1", 1))
        layers.writeReceipt(PerspectiveId.CUSTOMER_SERVICE, receiptInput())
        val escalated = layers.writeReceipt(
            PerspectiveId.CUSTOMER_SERVICE,
            receiptInput(escalation = EscalationRef(PerspectiveId.SUPERVISORY, "contradiction")),
        )
        assertEquals(listOf(escalated.id), layers.escalations().map { it.id })
    }

    @Test
    fun `快照携带版本与私有上下文摘要`() {
        val layers = layers()
        layers.openPerspective(PerspectiveId.CUSTOMER_SERVICE, ManifestRef("amn.1", 1))
        layers.writePrivate(
            PerspectiveId.CUSTOMER_SERVICE,
            PrivateNote(AT_ISSUE, PrivateNoteKind.TOOL_RESULT, "tool returned 3 rows"),
        )
        val snapshot = layers.snapshot()
        assertEquals(1, snapshot.version)
        assertEquals(1, snapshot.privateContexts.size)
        assertEquals(1, snapshot.privateContexts[0].notes)
        assertFalse(snapshot.privateContexts[0].sealed)
        assertEquals("ship v1", snapshot.core.goal)
    }
}
