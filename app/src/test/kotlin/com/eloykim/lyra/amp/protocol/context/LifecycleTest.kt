package com.eloykim.lyra.amp.protocol.context

import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleTest {

    private val now: () -> String = { "2026-10-29T00:00:00.000Z" }

    private fun frame(
        id: String = "frame.1",
        targets: List<String> = listOf("file.main"),
        allocated: Int = 1000,
        spent: Int = 0,
    ): ContextFrame = ContextFrame(
        id = id,
        agent = AGENT_ID,
        slots = targets.map { target ->
            ContextSlot(Ref(RefKind.ENTITY, target), SlotState.ACTIVE, "inspect $target")
        },
        allocatedTokens = allocated,
        spentTokens = spent,
    )

    private fun graph(store: ContentStore): ContextAst {
        val ast = ContextAst()
        ast.add(
            ContextAstNode(
                id = "project.alpha",
                kind = ContextNodeKind.PROJECT,
                summary = "alpha",
                order = 0,
                salience = 0.0,
            ),
        )
        ast.add(
            ContextAstNode(
                id = "file.main",
                kind = ContextNodeKind.FILE,
                summary = "entry point",
                order = 1,
                parentId = "project.alpha",
                detailRef = store.put("detail of file.main"),
                salience = 1.0,
            ),
        )
        return ast
    }

    @Test
    fun phasesGateExpansionAndMaterialization() {
        val store = MemoryContentStore()
        val ast = graph(store)
        val context = Context.create(frame(), ast, store, now = now)

        assertEquals("ctx.frame.1", context.id)
        assertEquals(ContextPhase.CREATED, context.phase)
        assertThrows(IllegalStateException::class.java) { context.expand("file.main") }

        val view = context.materialize(500)
        assertEquals(ContextPhase.MATERIALIZED, context.phase)
        assertEquals("frame.1", view.frameId)
        assertEquals(500, view.budgetTokens)
        assertTrue(view.nodes.map { it.id }.contains("file.main"))
        assertEquals("detail of file.main", context.expand("file.main"))
        assertNull(context.expand("project.alpha"))

        // 二次物化被拒绝。
        assertThrows(IllegalStateException::class.java) { context.materialize(500) }

        context.activate()
        assertEquals(ContextPhase.ACTIVE, context.phase)
        assertThrows(IllegalStateException::class.java) { context.activate() }
    }

    @Test
    fun settleCompactArchiveProduceSnapshot() {
        val store = MemoryContentStore()
        val context = Context.create(frame(), graph(store), store, now = now)
        context.materialize(500)
        context.activate()

        context.settle("rcpt.1")
        assertEquals(ContextPhase.SETTLED, context.phase)
        val duplicate = assertThrows(IllegalStateException::class.java) { context.settle("rcpt.1") }
        assertTrue(duplicate.message!!.contains("already attached"))

        // 阶段不对时不能跳过中间步骤。
        assertThrows(IllegalStateException::class.java) { context.archive() }
        context.compact()
        assertEquals(ContextPhase.COMPACTED, context.phase)
        context.archive()
        assertEquals(ContextPhase.ARCHIVED, context.phase)
        assertThrows(IllegalStateException::class.java) { context.expand("file.main") }

        val snapshot = context.snapshot()
        assertEquals(ContextPhase.ARCHIVED, snapshot.phase)
        assertEquals(listOf("rcpt.1"), snapshot.receiptIds)
        assertEquals("2026-10-29T00:00:00.000Z", snapshot.createdAt)
        assertNotNull(snapshot.materializedAt)
        assertNotNull(snapshot.settledAt)
        assertNotNull(snapshot.compactedAt)
        assertNotNull(snapshot.archivedAt)
        assertNotNull(snapshot.view)
        assertEquals("frame.1", snapshot.view!!.frameId)
    }

    @Test
    fun missingFrameTargetsAreReportedWithoutAbortingMaterialization() {
        val store = MemoryContentStore()
        val context = Context.create(
            frame(targets = listOf("file.main", "file.gone")),
            graph(store),
            store,
            now = now,
        )
        val view = context.materialize(500)
        assertEquals(1, view.missingRefs.size)
        assertEquals("file.gone", view.missingRefs.single().id)
        assertNull(context.expand("file.gone"))
        assertNull(context.expand("nope"))
    }

    @Test
    fun frameIsCapturedImmutablyFromItsWireForm() {
        val parsed = ContextFrame.fromJson(frameJson(allocated = 800, spent = 120))
        assertEquals("frame.1", parsed.id)
        assertEquals(AGENT_ID, parsed.agent)
        assertEquals(800, parsed.allocatedTokens)
        assertEquals(120, parsed.spentTokens)
        assertEquals(680, parsed.remainingTokens)
        assertEquals(SlotState.ACTIVE, parsed.slots.single().state)

        val store = MemoryContentStore()
        val context = Context.create(parsed, graph(store), store, now = now)
        val captured = context.frame
        assertEquals(680, captured.remainingTokens)
        // 返回的是一份不可变快照，改动它不会影响 Context。
        assertEquals(800, context.frame.allocatedTokens)

        assertThrows(IllegalArgumentException::class.java) {
            ContextFrame.fromJson(frameJson(allocated = 10).copyWith("amp", com.eloykim.lyra.amp.protocol.json.JsonValue.Str("0.2")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContextFrame.fromJson(frameJson(allocated = 10).without("slots"))
        }
    }
}
