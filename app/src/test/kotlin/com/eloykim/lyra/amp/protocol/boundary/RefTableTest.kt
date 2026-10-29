package com.eloykim.lyra.amp.protocol.boundary

import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RefTableTest {

    @Test
    fun `tool 与 capability 共享命名空间且剥离 mcp 前缀`() {
        val table = RefTable(RefScope.RUN)
        table.add(RefKind.CAPABILITY, "logs.query", "capability")
        assertEquals("capability", table.resolve(Ref(RefKind.TOOL, "logs.query")))
        assertEquals("capability", table.resolve(Ref(RefKind.TOOL, "mcp.logs.query")))
        assertTrue(table.has(Ref(RefKind.TOOL, "mcp.logs.query")))

        val tools = RefTable(RefScope.RUN)
        tools.add(RefKind.TOOL, "mcp.logs.query", "tool")
        assertEquals("tool", tools.resolve(Ref(RefKind.TOOL, "logs.query")))
        // capability 不反向回退到 tool：只有 tool 会尝试 capability 命名空间。
        assertNull(tools.resolve(Ref(RefKind.CAPABILITY, "logs.query")))
        assertFalse(tools.has(Ref(RefKind.CAPABILITY, "mcp.logs.query")))
    }

    @Test
    fun `self 解析到作用域登记的 agent`() {
        val table = RefTable(RefScope.RUN, "a.gamma")
        assertNotNull(table.resolve(Ref(RefKind.SELF, "a.gamma")))
        assertTrue(table.has(Ref(RefKind.SELF, "a.gamma")))
        assertNull(table.resolve(Ref(RefKind.SELF, "a.other")))
        assertEquals(1, table.size)
    }

    @Test
    fun `重复登记与非法 id 都以边界拒绝失败`() {
        val table = RefTable(RefScope.LEDGER)
        table.add(RefKind.CLAIM, "cl.1", "claim")
        val duplicate = assertThrows(BoundaryRejection::class.java) {
            table.add(RefKind.CLAIM, "cl.1", "other")
        }
        assertEquals("duplicate_ref", duplicate.code)
        assertTrue(duplicate.problems.single().contains("claim:cl.1"))
        assertTrue(duplicate.problems.single().contains("scope ledger"))

        val invalid = assertThrows(BoundaryRejection::class.java) {
            table.add(RefKind.CLAIM, "Bad.Id", "claim")
        }
        assertEquals("invalid_id", invalid.code)
        assertTrue(invalid.problems.single().contains("claim:Bad.Id"))
    }

    @Test
    fun `require 为缺失引用给出作用域明确的拒绝`() {
        val table = RefTable(RefScope.BUNDLE)
        table.add(RefKind.CLAIM, "cl.1", "claim")
        val error = assertThrows(BoundaryRejection::class.java) {
            table.require(Ref(RefKind.CLAIM, "cl.404"))
        }
        assertEquals("unresolvable_ref", error.code)
        assertEquals("claim:cl.404 is not present in scope bundle", error.problems.single())
        assertEquals("claim:cl.404 is not present in scope bundle", table.check(Ref(RefKind.CLAIM, "cl.404")))
        assertNull(table.check(Ref(RefKind.CLAIM, "cl.1")))
    }

    @Test
    fun `opaque 种类只要求形状`() {
        val table = RefTable(RefScope.RUN)
        assertNull(table.check(Ref(RefKind.HUMAN, "u.li")))
        assertNull(table.check(Ref(RefKind.ORG, "org.acme")))
        assertNull(table.check(Ref(RefKind.ENTITY, "e.prod")))
        assertNull(table.check(Ref(RefKind.OTHER, "x.1")))
        assertNotNull(table.check(Ref(RefKind.CLAIM, "cl.1")))

        val narrow = RefTable(RefScope.RUN, null, listOf(RefKind.CLAIM))
        assertNull(narrow.check(Ref(RefKind.CLAIM, "cl.1")))
        assertNotNull(narrow.check(Ref(RefKind.HUMAN, "u.li")))
    }
}
