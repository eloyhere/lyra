package com.eloykim.lyra.amp.protocol.frame

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Frame 线对象的保真往返：示例来自参考仓库，schema 与 canonical 都必须
 * 一致。
 */
class FrameTest {

    private fun readExample(name: String): JsonValue =
        JsonParser.parse(File("src/test/resources/amp/examples/$name").readText())

    @Test
    fun `frame 示例可解析并保真往返`() {
        val frames = (readExample("frame-chain.json") as JsonValue.Arr).items
        assertEquals(2, frames.size)
        frames.forEach { raw ->
            val frame = Frame.fromJson(raw)
            assertEquals(CanonicalJson.encode(raw), CanonicalJson.encode(frame.toJson()))
            assertEquals(emptyList<String>(), AmpSchemas.validateKind("frame", frame.toJson()))
        }
    }

    @Test
    fun `fromJson 不重算 digest 但要求格式合法`() {
        val raw = (readExample("frame-chain.json") as JsonValue.Arr).items.first()
        val frame = Frame.fromJson(raw)
        assertEquals("sha256:" + "1".repeat(64), frame.digest)
        assertEquals(frame.digest, frame.toJson().string("digest"))
    }

    @Test
    fun `fromJson 拒绝缺失 digest 与非法 mode`() {
        val raw = (readExample("frame-chain.json") as JsonValue.Arr).items.first() as JsonValue.Obj
        val withoutDigest = JsonValue.Obj(
            LinkedHashMap(raw.fields).apply { remove("digest") },
        )
        assertThrows(IllegalArgumentException::class.java) { Frame.fromJson(withoutDigest) }

        val badMode = raw.copyWith("mode", JsonValue.Str("watch"))
        assertThrows(IllegalArgumentException::class.java) { Frame.fromJson(badMode) }
    }

    @Test
    fun `fromJson 拒绝坏 digest 与超过 9 个 slot`() {
        val raw = (readExample("frame-chain.json") as JsonValue.Arr).items.first() as JsonValue.Obj
        val badDigest = raw.copyWith("digest", JsonValue.Str("sha256:xyz"))
        assertThrows(IllegalArgumentException::class.java) { Frame.fromJson(badDigest) }

        val slot = (raw.arr("slots") as JsonValue.Arr).items.first()
        val tooMany = raw.copyWith(
            "slots",
            JsonValue.Arr(List(10) { slot }),
        )
        assertThrows(IllegalArgumentException::class.java) { Frame.fromJson(tooMany) }
    }

    @Test
    fun `可选字段省略时不会写出 null`() {
        val json = jsonObj(
            "amp" to JsonValue.Str("0.1"),
            "kind" to JsonValue.Str("frame"),
            "id" to JsonValue.Str("f.1"),
            "agent" to JsonValue.Str("a.gamma"),
            "cycle" to jsonNumber(1),
            "ts" to JsonValue.Str("2026-10-24T01:00:00Z"),
            "digest" to JsonValue.Str("sha256:" + "0".repeat(64)),
            "mode" to JsonValue.Str("explore"),
            "slots" to JsonValue.Arr(emptyList()),
            "budget" to JsonValue.Obj(
                linkedMapOf(
                    "allocated" to Budget(tokens = 10).toJson(),
                    "spent" to Budget().toJson(),
                ),
            ),
        )
        val frame = Frame.fromJson(json)
        assertNull(frame.parent)
        assertNull(frame.goal)
        assertNull(frame.ignored)
        assertNull(frame.openQuestions)
        assertNull(frame.impasse)
        assertNull(frame.note)
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("frame", frame.toJson()))
        assertTrue(!frame.toJson().has("ignored"))
        assertTrue(!frame.toJson().has("parent"))
    }

    @Test
    fun `impasse 与 tier 的线格式由枚举固定`() {
        assertEquals("UNVERIFIED", ImpasseState.UNVERIFIED.wire)
        assertEquals(ImpasseState.UNVERIFIED, ImpasseState.fromWire("UNVERIFIED"))
        assertEquals(SlotRole.ARTIFACT, SlotRole.fromWire("artifact"))
        assertEquals(SlotState.EVICTED, SlotState.fromWire("evicted"))
        assertEquals(IgnoredReason.HABITUATED, IgnoredReason.fromWire("habituated"))
        assertEquals(FrameMode.REFLECT, FrameMode.fromWire("reflect"))
        assertNotNull(Ref(RefKind.SELF, "a.gamma"))
        assertThrows(IllegalArgumentException::class.java) { ImpasseState.fromWire("TIRED") }
    }
}
