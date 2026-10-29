package com.eloykim.lyra.amp.protocol.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextAstTest {

    private fun node(
        id: String,
        order: Int,
        parentId: String? = null,
        kind: ContextNodeKind = ContextNodeKind.FRAME,
        detailRef: String? = null,
        salience: Double? = null,
    ): ContextAstNode = ContextAstNode(
        id = id,
        kind = kind,
        summary = "summary of $id",
        order = order,
        parentId = parentId,
        detailRef = detailRef,
        salience = salience,
    )

    @Test
    fun rejectsDuplicateNodesAndHierarchyCycles() {
        val ast = ContextAst()
        ast.add(node("root", 0))
        assertThrows(IllegalArgumentException::class.java) { ast.add(node("root", 1)) }

        // 父节点可以后到；补上父节点时不能闭合环。
        ast.add(node("x", 1, parentId = "y"))
        val error = assertThrows(IllegalArgumentException::class.java) {
            ast.add(node("y", 2, parentId = "x"))
        }
        assertTrue(error.message!!.contains("cycle"))
    }

    @Test
    fun precedenceOrderIsStableAndCyclesAreRejected() {
        val ast = ContextAst()
        ast.add(node("a", 0))
        ast.add(node("b", 1))
        ast.add(node("c", 2))
        ast.link(ContextAstEdge("a", "b", ContextEdgeKind.BEFORE))

        // Kahn：初始就绪节点按插入顺序，随后按 link 顺序释放。
        assertEquals(listOf("a", "c", "b"), ast.order().map { it.id })

        ast.link(ContextAstEdge("b", "a", ContextEdgeKind.BEFORE))
        assertThrows(IllegalStateException::class.java) { ast.order() }
    }

    @Test
    fun descriptiveRelationCyclesAreAllowed() {
        val ast = ContextAst()
        ast.add(node("file.a", 0, kind = ContextNodeKind.FILE))
        ast.add(node("file.b", 1, kind = ContextNodeKind.FILE))
        ast.link(ContextAstEdge("file.a", "file.b", ContextEdgeKind.IMPORTS))
        ast.link(ContextAstEdge("file.b", "file.a", ContextEdgeKind.IMPORTS))

        assertEquals(2, ast.order().size)
        assertEquals(2, ast.relations("file.a", EdgeDirection.OUTGOING).size + ast.relations("file.a", EdgeDirection.INCOMING).size)
        val view = ast.view(500)
        assertEquals(2, view.nodes.size)
        assertEquals(2, view.relations.size)
    }

    @Test
    fun viewReportsOutsideScopeDependenciesInsteadOfReadingThem() {
        val ast = ContextAst()
        ast.add(node("root", 0, kind = ContextNodeKind.PROJECT, salience = 0.0))
        ast.add(node("file.one", 1, parentId = "root", kind = ContextNodeKind.FILE, detailRef = "detail.one", salience = 1.0))
        ast.add(node("dep.one", 2, kind = ContextNodeKind.FILE, salience = 0.5))
        ast.link(ContextAstEdge("dep.one", "file.one", ContextEdgeKind.DEPENDS))

        val view = ast.view(1000, listOf("file.one"))
        assertEquals(listOf("root", "file.one"), view.nodes.map { it.id })
        assertEquals(listOf("detail.one"), view.refs)
        assertTrue(view.relations.isEmpty())
        assertEquals(1, view.unresolvedDependencies.size)
        assertEquals("dep.one", view.unresolvedDependencies.single().from)
        assertEquals("file.one", view.unresolvedDependencies.single().to)
        assertTrue(view.text.contains("outside_scope"))
        assertFalse(view.text.contains("summary of dep.one"))
    }

    @Test
    fun viewOmitsBundlesThatDoNotFitTheBudget() {
        val ast = ContextAst()
        ast.add(node("file.one", 0, kind = ContextNodeKind.FILE, detailRef = "detail.one", salience = 1.0))

        val view = ast.view(1, listOf("file.one"))
        assertTrue(view.nodes.isEmpty())
        assertTrue(view.excluded.isNotEmpty())
        assertTrue(view.omissions.all { it.reason == OmissionReason.BUDGET })
        assertEquals("file.one", view.omissions.single().id)
    }

    @Test
    fun viewOmitsNodesWhoseAncestorsNeverArrive() {
        val ast = ContextAst()
        ast.add(node("orphan", 0, kind = ContextNodeKind.FILE, parentId = "ghost"))

        val view = ast.view(100)
        assertTrue(view.nodes.isEmpty())
        val omission = view.omissions.single()
        assertEquals(OmissionReason.MISSING_PARENT, omission.reason)
        assertEquals(listOf("ghost"), omission.requiredIds)
    }

    @Test
    fun flatReadsEveryDetailWhileExpandReadsOneNode() {
        val store = MemoryDetailStore()
        val detailRef = store.put("detail.one", "the whole detail body")
        val ast = ContextAst()
        ast.add(node("file.one", 0, kind = ContextNodeKind.FILE, detailRef = detailRef))
        ast.add(node("file.two", 1, kind = ContextNodeKind.FILE))

        assertTrue(ast.flat(store).text.contains("the whole detail body"))
        assertEquals("the whole detail body", ast.expand("file.one", store))
        assertNull(ast.expand("file.two", store))
        assertNull(ast.expand("missing", store))
    }

    @Test
    fun serializeAndRestorePreserveTreeAndEdges() {
        val ast = ContextAst()
        ast.add(node("root", 0))
        ast.add(node("child", 1, parentId = "root"))
        ast.link(ContextAstEdge("root", "child", ContextEdgeKind.BEFORE))

        val restored = ContextAst.restore(ast.serialize())
        assertEquals(listOf("root", "child"), restored.order().map { it.id })
        assertEquals(1, restored.relations("root", EdgeDirection.OUTGOING).size)
        assertEquals(listOf("child"), restored.children("root").map { it.id })
        assertEquals(listOf("root"), restored.ancestors("child").map { it.id })
    }
}
