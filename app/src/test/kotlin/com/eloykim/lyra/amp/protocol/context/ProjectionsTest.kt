package com.eloykim.lyra.amp.protocol.context

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.types.Budget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionsTest {

    private class FixtureAgent : AgentReader {

        override fun profile(pid: String): AgentProfile =
            AgentProfile("Lyra", "assistant", listOf("honesty"), emptyList())

        override fun narrative(pid: String): SelfNarrative =
            SelfNarrative(3, "I help with careful execution.")

        override fun activeRole(pid: String): RoleAssignment = RoleAssignment(
            AgentRole(
                id = "role.ops",
                label = "Operator",
                obligations = listOf(AgentObligation("confirm before paying")),
                audiences = listOf("user"),
            ),
            salience = 0.7,
        )

        override fun affect(pid: String): AffectState =
            AffectState(0.4, 0.3, 0.2, 0.1, "calm")

        override fun memories(pid: String, limit: Int): List<Memory> = listOf(
            Memory(
                id = "memory.one",
                kind = "episodic",
                content = "The user prefers concise answers. " + "x".repeat(200),
                salience = 0.8,
                at = "2026-10-01T00:00:00Z",
                roleId = "role.ops",
            ),
        )

        override fun background(pid: String, state: String): List<BackgroundProcess> =
            listOf(BackgroundProcess("bg.one", "task", "compile", 0.5))
    }

    private class FixtureSocial : SocialReader {

        override fun relationships(pid: String): List<Relationship> = listOf(
            Relationship("rel.one", "colleague", AGENT_ID, "human.ada", 0.9, 0.6, listOf("be brief")),
        )

        override fun memberships(pid: String): List<Membership> = listOf(
            Membership(Institution("org.alpha", "Alpha", listOf("no spam")), "member", "active"),
        )
    }

    @Test
    fun assemblesSegmentsUnderBudgetAndRecordsOmissions() {
        val context = assembleAgentContext(
            agent = FixtureAgent(),
            social = FixtureSocial(),
            pid = AGENT_ID,
            budget = Budget(tokens = 500),
            options = AgentContextOptions(now = "2026-10-29T00:00:00Z"),
        )
        assertEquals(AgentContextKind.SELF, context.segments.first().kind)
        assertTrue(context.tokens in 1..500)
        assertTrue(context.segments.any { it.kind == AgentContextKind.MEMORY })
        assertTrue(context.segments.any { it.kind == AgentContextKind.ROLE })
        // 份额为 0 的 membership 只有 1 token 上限，必然被记为遗漏。
        assertTrue(context.excluded.any { it.kind == AgentContextKind.MEMBERSHIP })
        assertTrue(context.excluded.all { it.reason == ExcludedReason.BUDGET })
        assertTrue(context.segments.all { it.tokens == estimateTokens(it.text) })
        assertEquals(1, estimateTokens(""))
        assertEquals(1, estimateTokens("abc"))
        assertEquals(1, estimateTokens("abcd"))
        assertEquals(2, estimateTokens("abcde"))
    }

    @Test
    fun projectionKeepsSummariesInTheTreeAndBodiesInTheStore() {
        val context = assembleAgentContext(
            agent = FixtureAgent(),
            social = FixtureSocial(),
            pid = AGENT_ID,
            budget = Budget(tokens = 800),
            options = AgentContextOptions(now = "2026-10-29T00:00:00Z"),
        )
        val projection = agentContextToAst(context)
        val ast = projection.ast

        assertNotNull(ast.node("agent.context"))
        assertEquals(
            listOf("agent.self", "agent.role", "agent.affect", "agent.memory", "agent.relationship", "agent.membership", "agent.background"),
            ast.children("agent.context").map { it.id },
        )
        assertTrue(ast.node("agent.context")!!.summary.contains("memories=1"))

        val memoryNode = ast.node("agent.memory.memory.one")
        assertNotNull(memoryNode)
        assertEquals("memory.one", memoryNode!!.detailRef)
        assertTrue(memoryNode.summary.startsWith("[episodic]"))
        assertTrue(memoryNode.summary.length <= 131)
        assertEquals(context.memories.first().content, projection.details.get("memory.one"))
        // 摘要进入 prompt；正文只在 detail store 里。
        val view = ast.view(8000)
        assertTrue(view.text.contains("[episodic]"))
        assertTrue(!view.text.contains(context.memories.first().content))
        assertEquals(listOf("memory.one"), view.refs)

        // role -> memory 是 support 边，不是包含关系。
        val supports = ast.relations("agent.role", EdgeDirection.OUTGOING, ContextEdgeKind.SUPPORTS)
        assertEquals(1, supports.size)
        assertEquals("agent.memory", supports.single().to)
        assertEquals(6, ast.relations("agent.context", EdgeDirection.OUTGOING, ContextEdgeKind.BEFORE).size)
    }

    @Test
    fun windowProjectionUsesPagesAsDetailRefs() {
        val window = WindowView(
            pages = listOf(
                WindowPage(
                    id = "page.abc",
                    objectKind = "frame",
                    objectId = "frame.1",
                    tokens = 12,
                    content = jsonObj("goal" to JsonValue.Str("ship it"), "n" to jsonNumber(2)),
                ),
            ),
            capacityTokens = 4000,
        )
        val ast = windowToAst(window)
        assertEquals(2, ast.size)
        val page = ast.node("window.page.page.abc")
        assertNotNull(page)
        assertEquals("page.abc", page!!.detailRef)
        assertEquals(12, page.detailTokens)
        assertTrue(page.summary.contains("ship it"))
    }

    @Test
    fun contextIndexCachesVerifiedDetails() {
        val store = MemoryContentStore()
        val index = ContextIndex(store = store)
        val added = index.add(
            ContextAstNode(
                id = "file.main",
                kind = ContextNodeKind.FILE,
                summary = "entry",
                order = 0,
                detailRef = null,
            ),
            detail = "detail body",
        )
        assertNotNull(added.detailRef)
        assertEquals("detail body", index.getDetail("file.main"))
        assertEquals("detail body", index.getDetail("file.main"))
        assertNull(index.getDetail("missing"))
        val stats = index.stats()
        assertEquals(1, stats.nodes)
        assertEquals(1, stats.cachedDetails)
        assertEquals(1, stats.hits)
        assertEquals(1, stats.misses)

        assertThrows(IllegalArgumentException::class.java) {
            index.add(ContextAstNode("file.main", ContextNodeKind.FILE, "dup", 1))
        }
    }
}
