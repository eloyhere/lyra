package com.eloykim.lyra.amp.protocol.manifest

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manifest model: wire shape, schema validity, optional fields. */
class ManifestTest {

    @Test
    fun `示例 manifest 可解析、schema 合法且可重新编码`() {
        val parsed = Manifest.fromJson(JsonParser.parse(Fixtures.example("manifest.json")))

        assertEquals("a.gamma", parsed.agent.id)
        assertEquals("Gamma", parsed.agent.name)
        assertEquals(2, parsed.capabilities.size)
        assertEquals("logs.query", parsed.capabilities[0].id)
        assertEquals("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", parsed.digest)
        assertEquals("c.pool_exhaustion", parsed.concepts?.first()?.id)

        val json = parsed.toJson()
        val schemaProblems = AmpSchemas.validateKind("manifest", json)
        assertTrue("schema 错误: ${schemaProblems.joinToString("; ")}", schemaProblems.isEmpty())

        val reencoded = Manifest.fromJson(json).toJson()
        assertEquals(CanonicalJson.encode(json), CanonicalJson.encode(reencoded))
    }

    @Test
    fun `完整可选块可通过 schema 并原样往返`() {
        val unsigned = Fixtures.unsigned().copy(
            identity = Identity(
                values = listOf(ValueStatement("v.honesty", "不确定时明确说明", 0.95)),
                constraints = listOf(Constraint("c.no_irreversible", "未经确认不执行不可回滚操作", ConstraintEnforcement.HARD)),
            ),
            concepts = listOf(Ref(RefKind.CONCEPT, "c.pool_exhaustion", "连接池耗尽")),
            commitments = listOf(
                Commitment(
                    id = "cm.441",
                    to = Ref(RefKind.HUMAN, "u.li"),
                    promise = "10:00 前给出初步结论",
                    status = CommitmentStatus.OPEN,
                    due = "2026-10-24T02:00:00Z",
                    created = "2026-10-24T01:00:00Z",
                ),
            ),
            unknowns = listOf(
                ManifestUnknown("u.17", "错误率上升的原因？", 0.9, true, "2026-10-24T01:10:00Z"),
            ),
        )
        val signed = signManifest(unsigned, Fixtures.requireSigner())

        val json = signed.toJson()
        val schemaProblems = AmpSchemas.validateKind("manifest", json)
        assertTrue("schema 错误: ${schemaProblems.joinToString("; ")}", schemaProblems.isEmpty())

        val roundTrip = Manifest.fromJson(json)
        assertEquals(0.95, roundTrip.identity?.values?.first()?.weight ?: 0.0, 0.0)
        assertEquals(ConstraintEnforcement.HARD, roundTrip.identity?.constraints?.first()?.enforcement)
        assertEquals(CommitmentStatus.OPEN, roundTrip.commitments?.first()?.status)
        assertTrue(roundTrip.unknowns?.first()?.blocking == true)
        assertEquals(CanonicalJson.encode(json), CanonicalJson.encode(roundTrip.toJson()))
    }

    @Test
    fun `缺省可选字段不写入空值`() {
        val unsignedJson = Fixtures.unsigned().toJson()
        assertFalse(unsignedJson.has("identity"))
        assertFalse(unsignedJson.has("concepts"))
        assertFalse(unsignedJson.has("commitments"))
        assertFalse(unsignedJson.has("unknowns"))
        assertTrue(unsignedJson.has("capabilities"))

        val signed = signManifest(Fixtures.unsigned(), Fixtures.requireSigner())
        assertNull(signed.identity)
        assertNull(signed.commitments)
        assertNotNull(signed.toJson().fields["digest"])
    }

    @Test
    fun `缺必需字段或错误 kind 时拒绝解析`() {
        val incomplete = JsonParser.parse("""{"amp":"0.1","kind":"manifest"}""")
        val missing = runCatching { Manifest.fromJson(incomplete) }.exceptionOrNull()
        assertTrue("应拒绝缺字段的 manifest", missing is IllegalArgumentException)

        val signed = signManifest(Fixtures.unsigned(), Fixtures.requireSigner())
        val wrongKind = signed.toJson().copyWith("kind", JsonValue.Str("frame"))
        val kindError = runCatching { Manifest.fromJson(wrongKind) }.exceptionOrNull()
        assertTrue("应拒绝错误的 kind", kindError is IllegalArgumentException)

        val withoutDigest = signed.toJson().without("digest")
        val digestError = runCatching { Manifest.fromJson(withoutDigest) }.exceptionOrNull()
        assertTrue("应拒绝缺 digest", digestError is IllegalArgumentException)
    }

    @Test
    fun `contentDigest 只覆盖未签名正文`() {
        val unsigned = Fixtures.unsigned()
        val signed = signManifest(unsigned, Fixtures.requireSigner())

        assertEquals(CanonicalJson.digest(unsigned.toJson()), contentDigest(unsigned))
        assertEquals(contentDigest(unsigned), Manifest.contentDigest(unsigned))
        assertEquals(signed.digest, contentDigest(unsigned))
        assertEquals(CanonicalJson.digest(signed.unsignedJson()), signed.digest)

        assertFalse(signed.unsignedJson().has("digest"))
        assertFalse(signed.unsignedJson().has("signatures"))
        assertFalse(signed.bodyWithDigestJson().has("signatures"))
        assertTrue(signed.bodyWithDigestJson().has("digest"))
    }
}
