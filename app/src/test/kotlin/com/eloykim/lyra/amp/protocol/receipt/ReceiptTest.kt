package com.eloykim.lyra.amp.protocol.receipt

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReceiptTest {

    private fun readExample(name: String): JsonValue =
        JsonParser.parse(File("src/test/resources/amp/examples/$name").readText())

    @Test
    fun `receipt 示例可解析并保真往返`() {
        val raw = readExample("receipt.json")
        val receipt = Receipt.fromJson(raw)
        assertEquals("rc.881", receipt.id)
        assertEquals(ReceiptOp.ACT, receipt.op)
        assertEquals(Outcome.SUCCESS, receipt.outcome)
        assertEquals(912L, receipt.ms)
        assertEquals("h.2f9", receipt.handle?.id)
        assertEquals(CanonicalJson.encode(raw), CanonicalJson.encode(receipt.toJson()))
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("receipt", receipt.toJson()))
    }

    @Test
    fun `handle 可省略且不会写出 null`() {
        val json = jsonObj(
            "amp" to JsonValue.Str("0.1"),
            "kind" to JsonValue.Str("receipt"),
            "id" to JsonValue.Str("rc.1"),
            "op" to JsonValue.Str("note"),
            "at" to JsonValue.Str("2026-10-24T01:00:00Z"),
            "usage" to Budget(tokens = 5).toJson(),
            "outcome" to JsonValue.Str("partial"),
        )
        val receipt = Receipt.fromJson(json)
        assertNull(receipt.handle)
        assertNull(receipt.target)
        assertNull(receipt.note)
        assertTrue(!receipt.toJson().has("handle"))
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("receipt", receipt.toJson()))
    }

    @Test
    fun `fromJson 拒绝未知 op 与非法 outcome`() {
        val raw = readExample("receipt.json") as JsonValue.Obj
        assertThrows(IllegalArgumentException::class.java) {
            Receipt.fromJson(raw.copyWith("op", JsonValue.Str("launch")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Receipt.fromJson(raw.copyWith("outcome", JsonValue.Str("maybe")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Receipt.fromJson(raw.copyWith("predicted_voi", jsonNumber(1.5)))
        }
    }

    @Test
    fun `event 示例满足 receipt oneOf 的 event 分支`() {
        val event = AmpEvent(
            id = "e.1",
            at = "2026-10-24T01:00:00Z",
            actor = Ref(RefKind.AGENT, "a.gamma"),
            type = "frame.committed",
            causedBy = "f.101",
            refs = listOf(Ref(RefKind.FRAME, "f.101")),
            digest = "sha256:" + "2".repeat(64),
            payload = jsonObj("cycle" to jsonNumber(3)),
        )
        val json = event.toJson()
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("receipt", json))
        assertEquals(CanonicalJson.encode(json), CanonicalJson.encode(AmpEvent.fromJson(json).toJson()))
        assertEquals(event.digest, (SettlementObject.fromJson(json) as AmpEvent).digest)
    }

    @Test
    fun `settlement 可以按 kind 分派且拒绝未知 kind`() {
        val receipt = Receipt.fromJson(readExample("receipt.json"))
        assertTrue(SettlementObject.fromJson(receipt.toJson()) is Receipt)
        val unknown = receipt.toJson().copyWith("kind", JsonValue.Str("invoice"))
        assertThrows(IllegalArgumentException::class.java) {
            SettlementObject.fromJson(unknown)
        }
    }
}
