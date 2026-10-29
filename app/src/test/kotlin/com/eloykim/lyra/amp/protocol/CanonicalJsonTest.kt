package com.eloykim.lyra.amp.protocol

import com.eloykim.lyra.amp.protocol.json.JsonParseException
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalJsonTest {

    @Test
    fun `对象键递归排序且无空白`() {
        val value = JsonParser.parse("""{"b":1,"a":{"d":2,"c":3}}""")
        assertEquals("""{"a":{"c":3,"d":2},"b":1}""", CanonicalJson.encode(value))
    }

    @Test
    fun `digest 与构造顺序无关`() {
        val first = JsonParser.parse("""{"kind":"frame","id":"f.1"}""")
        val second = JsonParser.parse("""{"id":"f.1","kind":"frame"}""")
        assertEquals(CanonicalJson.digest(first), CanonicalJson.digest(second))
        assertTrue(Sha256.isValidDigest(CanonicalJson.digest(first)))
    }

    @Test
    fun `数字按 ECMAScript 规则格式化`() {
        assertEquals("1", CanonicalJson.formatNumber(1.0))
        assertEquals("0.5", CanonicalJson.formatNumber(0.5))
        assertEquals("1000", CanonicalJson.formatNumber(1000.0))
        assertEquals("123.45", CanonicalJson.formatNumber(123.45))
        assertEquals("0.000001", CanonicalJson.formatNumber(1e-6))
        assertEquals("1e-7", CanonicalJson.formatNumber(1e-7))
        assertEquals("100000000000000000000", CanonicalJson.formatNumber(1e20))
        assertEquals("1e+21", CanonicalJson.formatNumber(1e21))
        assertEquals("0", CanonicalJson.formatNumber(-0.0))
    }

    @Test
    fun `字符串转义与 JSON stringify 对齐`() {
        val value = JsonParser.parse(""""a\"b\\c\n\u0001"""")
        assertEquals(""""a\"b\\c\n\u0001"""", CanonicalJson.encode(value))
    }

    @Test(expected = JsonParseException::class)
    fun `拒绝尾随内容`() {
        JsonParser.parse("""{"a":1} extra""")
    }

    @Test
    fun `协议示例通过 schema 校验`() {
        val frameChain = example("frame-chain.json") as JsonValue.Arr
        assertTrue(frameChain.items.isNotEmpty())
        frameChain.items.forEach { frame ->
            assertEquals(emptyList<String>(), AmpSchemas.validateKind("frame", frame))
        }

        assertEquals(emptyList<String>(), AmpSchemas.validateKind("manifest", example("manifest.json")))
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("receipt", example("receipt.json")))
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("ledger-snapshot", example("ledger-snapshot.json")))
    }

    @Test
    fun `schema 会拒绝缺字段和多字段`() {
        val broken = JsonParser.parse("""{"amp":"0.1","kind":"frame","id":"f.1"}""")
        val problems = AmpSchemas.validateKind("frame", broken)
        assertTrue(problems.any { it.contains("缺少必需字段") })

        val extra = JsonParser.parse(
            """{"amp":"0.1","kind":"receipt","id":"rc.1","op":"act","at":"2026-10-24T07:00:00Z","usage":{},"outcome":"success","bad":1}""",
        )
        assertTrue(AmpSchemas.validateKind("receipt", extra).any { it.contains("不允许的字段 bad") })
    }

    private fun example(name: String): JsonValue {
        val text = javaClass.getResource("/amp/examples/$name")?.readText()
            ?: error("缺少测试资源 $name")
        return JsonParser.parse(text)
    }
}
