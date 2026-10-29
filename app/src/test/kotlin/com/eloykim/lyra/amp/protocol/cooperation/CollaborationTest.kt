package com.eloykim.lyra.amp.protocol.cooperation

import com.eloykim.lyra.amp.protocol.delegate.CommitmentRecord
import com.eloykim.lyra.amp.protocol.delegate.TeamManifest
import com.eloykim.lyra.amp.protocol.delegate.TeamObject
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `collaboration.schema.json` / `team.schema.json` 的 wire 锁定。
 *
 * 使用仓库里复制的示例；team 分支由兄弟端口 `delegate/TeamTypes.kt` 承担。
 */
class CollaborationTest {

    private fun resourceText(name: String): String =
        javaClass.getResourceAsStream("/amp/examples/$name")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun array(name: String): List<JsonValue> =
        (JsonParser.parse(resourceText(name)) as JsonValue.Arr).items

    @Test
    fun `collaboration 示例全部 schema 合法且可分派`() {
        val items = array("collaboration.json")
        assertEquals(3, items.size)
        val kinds = ArrayList<String>()
        items.forEach { item ->
            val problems = AmpSchemas.validate("collaboration.schema.json", item)
            assertTrue("schema problems: $problems", problems.isEmpty())
            val parsed = CollaborationObject.fromJson(item)
            kinds += (item as JsonValue.Obj).requireString("kind")
            assertEquals(
                CanonicalJson.encode(item),
                CanonicalJson.encode(parsed.toJson()),
            )
        }
        assertEquals(listOf("delegation", "lease", "result-contract"), kinds)
    }

    @Test
    fun `result contract 保留 claims evidence unknowns artifacts`() {
        val value = array("collaboration.json")
            .first { (it as JsonValue.Obj).requireString("kind") == "result-contract" }
        val contract = (CollaborationObject.fromJson(value) as CollaborationObject.Result).contract
        assertEquals("rc.1", contract.id)
        assertEquals("ls.2", contract.lease)
        assertEquals(ResultStatus.OK, contract.status)
        assertEquals(listOf("claim"), contract.claims.map { it.kind.wire })
        assertEquals(listOf("cl.91"), contract.claims.map { it.id })
        assertEquals(listOf("lane B 未采样"), contract.unknowns)
        assertEquals(listOf("a.1"), contract.artifacts.map { it.id })
        assertEquals(contract, ResultContract.fromJson(contract.toJson()))
    }

    @Test
    fun `result contract 缺字段与未知 kind 被拒绝`() {
        val value = array("collaboration.json")
            .first { (it as JsonValue.Obj).requireString("kind") == "result-contract" } as JsonValue.Obj
        try {
            ResultContract.fromJson(value.without("lease"))
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            CollaborationObject.fromJson(jsonObj("kind" to jsonText("bogus")))
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            ResultArtifact(id = "a.1", digest = "not-a-digest")
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `team 示例全部 schema 合法且绑定到 delegate 端口`() {
        val items = array("team.json")
        assertEquals(2, items.size)
        items.forEach { item ->
            val problems = AmpSchemas.validate("team.schema.json", item)
            assertTrue("schema problems: $problems", problems.isEmpty())
            val parsed = TeamObject.fromJson(item)
            assertEquals(
                CanonicalJson.encode(item),
                CanonicalJson.encode(parsed.toJson()),
            )
        }
        val manifest = TeamObject.fromJson(items[0]) as TeamManifest
        assertEquals("team.incident", manifest.id)
        assertEquals(1, manifest.version)
        assertEquals(1, manifest.maxDepth)
        assertEquals(3, manifest.maxFanout)
        assertEquals(listOf("a.lead", "a.worker"), manifest.members.map { it.id })

        val commitment = TeamObject.fromJson(items[1]) as CommitmentRecord
        assertEquals("cm.1", commitment.id)
        assertEquals(listOf("a.lead", "a.worker"), commitment.parties)
        assertEquals(listOf("a.worker"), commitment.acceptedBy)
    }
}
