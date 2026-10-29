package com.eloykim.lyra.amp.protocol.execution

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.module.McpTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `src/execution/pin.ts` 的行为锁定。
 *
 * 两个 `digestTool` 期望值由参考实现的 `canonical()` + sha256 计算（Node 侧），
 * 用来锁定“缺失字段写成 null、annotations 缺失写成 {}”这一跨语言细节。
 */
class PinTest {

    private val logsQueryJson =
        """{"name":"logs.query","description":"Query logs",""" +
            """"inputSchema":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]},""" +
            """"annotations":{"readOnlyHint":true}}"""

    private val deployJson =
        """{"name":"deploy.rollback","inputSchema":{"type":"object"},""" +
            """"annotations":{"readOnlyHint":false,"destructiveHint":true}}"""

    private fun obj(text: String): JsonValue.Obj = JsonParser.parse(text) as JsonValue.Obj

    private val logsQuery: McpTool get() = McpTool.fromJson(obj(logsQueryJson))
    private val deploy: McpTool get() = McpTool.fromJson(obj(deployJson))

    @Test
    fun `digestTool 与参考 canonical 的摘要一致`() {
        assertEquals(
            "sha256:7a047fc3d20e46c4359b51e1806d2331a0a78983d4b2fb6ef877ca5da16e46b8",
            digestTool(logsQuery),
        )
        assertEquals(
            "sha256:63e8d98fca1b1815c7f13dccbdc97b3c922c627e51d71858cea68368d94a8172",
            digestTool(deploy),
        )

        // 缺失 title 写成 null；缺失 annotations 写成 {}——两者都影响摘要。
        val noTitle = McpTool.fromJson(obj("""{"name":"x","inputSchema":{"type":"object"}}"""))
        val withNullTitle = McpTool.fromJson(
            obj("""{"name":"x","title":null,"description":null,"inputSchema":{"type":"object"},"annotations":{}}"""),
        )
        // title:null 在 fromJson 里等价于缺失（string() 返回 null），但 annotations 显式为 {}。
        assertEquals(digestTool(noTitle), digestTool(withNullTitle))
        assertTrue(digestTool(noTitle) != digestTool(noTitle.copy(title = "Title")))
    }

    @Test
    fun `目录摘要与工具顺序无关`() {
        val forward = pinTools(listOf(logsQuery, deploy))
        val backward = pinTools(listOf(deploy, logsQuery))
        assertEquals(
            "sha256:6b34b6970b6a8cd31bcdcd936050934d5fadfd7d5adc04f7f3714fe5b7bc131f",
            forward.catalog,
        )
        assertEquals(forward.catalog, backward.catalog)
        assertEquals(setOf("logs.query", "deploy.rollback"), forward.tools.keys)
        assertEquals(digestTool(logsQuery), forward.tools["logs.query"])
    }

    @Test
    fun `重复工具名 fail closed`() {
        try {
            pinTools(listOf(logsQuery, logsQuery))
            throw AssertionError("expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("duplicate tool name"))
        }
    }

    @Test
    fun `verifyToolPins 报告变化新增移除`() {
        val pins = pinTools(listOf(logsQuery, deploy))
        val clean = verifyToolPins(pins, listOf(deploy, logsQuery))
        assertTrue(clean.ok)
        assertTrue(clean.reasons.isEmpty())
        assertTrue(clean.changed.isEmpty())

        val changed = verifyToolPins(pins, listOf(logsQuery.copy(description = "Query logs (v2)"), deploy))
        assertFalse(changed.ok)
        assertEquals(listOf("logs.query"), changed.changed)
        assertTrue(changed.reasons.any { it.startsWith("tool changed: logs.query ") })
        assertTrue(changed.reasons.contains("catalog digest changed"))

        val removed = verifyToolPins(pins, listOf(logsQuery))
        assertFalse(removed.ok)
        assertEquals(listOf("deploy.rollback"), removed.changed)
        assertTrue(removed.reasons.contains("tool removed: deploy.rollback"))
        assertTrue(removed.reasons.contains("catalog digest changed"))

        val added = verifyToolPins(pins, listOf(logsQuery, deploy, McpTool.fromJson(obj("""{"name":"x.y","inputSchema":{"type":"object"}}"""))))
        assertFalse(added.ok)
        assertEquals(listOf("x.y"), added.changed)
        assertTrue(added.reasons.contains("tool added: x.y"))
    }

    @Test
    fun `verifyToolPins 对重复名先报重复`() {
        val pins = pinTools(listOf(logsQuery, deploy))
        val verdict = verifyToolPins(pins, listOf(logsQuery, logsQuery, deploy))
        assertFalse(verdict.ok)
        assertTrue(verdict.reasons.contains("duplicate tool name: logs.query"))
        assertTrue(verdict.changed.contains("logs.query"))
    }

    @Test
    fun `assertToolPinned 缺失即失败`() {
        val pins = pinTools(listOf(logsQuery))
        assertTrue(assertToolPinned(pins, logsQuery).ok)

        val missing = assertToolPinned(pins, deploy)
        assertFalse(missing.ok)
        assertEquals(listOf("deploy.rollback"), missing.changed)
        assertTrue(missing.reasons.contains("tool not pinned: deploy.rollback"))

        val changed = assertToolPinned(pins, logsQuery.copy(title = "New title"))
        assertFalse(changed.ok)
        assertTrue(changed.reasons.single().startsWith("tool changed: logs.query "))
    }

    @Test
    fun `钉扎 JSON 往返`() {
        val pins = pinTools(listOf(logsQuery, deploy))
        assertEquals(pins, ToolPins.fromJson(pins.toJson()))
        val verdict = verifyToolPins(pins, listOf(logsQuery))
        assertEquals(verdict, PinVerdict.fromJson(verdict.toJson()))
    }
}
