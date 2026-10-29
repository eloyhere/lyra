package com.eloykim.lyra.amp.protocol.evidence

import com.eloykim.lyra.amp.protocol.types.Trust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 证据图：节点/边、原子插入、环检测、多源影响闭包。
 *
 * 参考实现的容器迭代顺序未指定；这里固定为插入顺序，测试按该确定性断言。
 */
class EvidenceGraphTest {

    @Test
    fun `失败的插入是原子的，节点身份不能漂移`() {
        val graph = EvidenceGraph()
        val error = assertThrows(IllegalArgumentException::class.java) {
            graph.addEvidence("orphan", "orphan", "absent")
        }
        assertTrue(error.message!!.contains("unknown"))
        assertEquals(0, graph.size)

        val a = graph.addClaim("a", "a")
        graph.addEvidence("e", "e", "a")
        graph.link("e", "a") // 已存在的边被静默忽略
        assertEquals(listOf("e"), graph.evidenceFor("a"))
        assertTrue(graph.claimsFor("a").isEmpty())

        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { graph.link("a", "e") }
                .message!!.contains("cycle"),
        )
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { graph.link("a", "a") }
                .message!!.contains("itself"),
        )
        assertEquals(EvidenceKind.CLAIM, a.kind)
        assertEquals("a", a.id)
        assertEquals(null, a.trust)
        assertFalse(graph.hasCycle())
    }

    @Test
    fun `多源菱形影响只给一条最短见证，且不含无关节点`() {
        val graph = EvidenceGraph()
        listOf("s", "a", "b", "c", "other").forEach { graph.addClaim(it, it) }
        graph.link("s", "a")
        graph.link("s", "b")
        graph.link("a", "c")
        graph.link("b", "c")

        assertEquals(
            listOf(
                EvidenceImpact("s", null),
                EvidenceImpact("b", null),
                EvidenceImpact("a", "s"),
                EvidenceImpact("c", "b"),
            ),
            graph.impact(listOf("s", "b", "s")),
        )
        assertEquals(setOf("a", "b"), graph.claimsFor("s").toSet())
        assertTrue(graph.claimsFor("c").isEmpty())
        assertEquals(listOf("s"), graph.evidenceFor("a"))
        assertEquals(listOf("a", "b"), graph.evidenceFor("c"))

        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { graph.impact(listOf("s", "missing")) }
                .message!!.contains("unknown"),
        )
        assertTrue(graph.impact(emptyList()).isEmpty())
    }

    @Test
    fun `深链使用迭代遍历，闭链被拒绝`() {
        val graph = EvidenceGraph()
        for (index in 0 until 8000) {
            graph.addClaim("$index", "$index")
            if (index > 0) graph.link("${index - 1}", "$index")
        }
        assertEquals(8000, graph.impact(listOf("0")).size)
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { graph.link("7999", "0") }
                .message!!.contains("cycle"),
        )
        assertFalse(graph.hasCycle())
    }

    @Test
    fun `索引闭包与独立的不动点扫描一致`() {
        val graph = EvidenceGraph()
        val edges = ArrayList<Pair<String, String>>()
        for (index in 0 until 90) graph.addClaim("$index", "$index")
        for (from in 0 until 90) {
            for (to in from + 1 until 90) {
                if ((from * 17 + to * 31) % 19 == 0) {
                    graph.link("$from", "$to")
                    edges += "$from" to "$to"
                }
            }
        }
        for (seed in 0 until 90 step 7) {
            val expected = LinkedHashSet<String>()
            expected += "$seed"
            var changed = true
            while (changed) {
                changed = false
                edges.forEach { (from, to) ->
                    if (expected.contains(from) && !expected.contains(to)) {
                        expected += to
                        changed = true
                    }
                }
            }
            assertEquals(expected, graph.impact(listOf("$seed")).map { it.id }.toSet())
        }
    }

    @Test
    fun `重复节点被拒绝，证据来源默认 trust 为 self`() {
        val graph = EvidenceGraph()
        graph.addClaim("a", "a")
        graph.addSource("src", "src")
        val node = graph.get("src")
        assertEquals(EvidenceKind.EVIDENCE, node?.kind)
        assertEquals(Trust.SELF, node?.trust)
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { graph.addClaim("a", "again") }
                .message!!.contains("duplicate"),
        )
        assertEquals(2, graph.size)
    }

    @Test
    fun `两节点闭链被 link 拒绝`() {
        val graph = EvidenceGraph()
        graph.addClaim("a", "a")
        graph.addClaim("b", "b")
        graph.link("a", "b")
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { graph.link("b", "a") }
                .message!!.contains("cycle"),
        )
        assertFalse(graph.hasCycle())
    }
}
