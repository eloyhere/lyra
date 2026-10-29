package com.eloykim.lyra.amp.protocol.protocol

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolMessageTest {

    @Test
    fun `message example parses validates and round trips`() {
        val json = example("message.json")
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("message", json))

        val message = Message.fromJson(json)
        assertEquals("m.7f21", message.id)
        assertEquals(Performative.REQUEST, message.type)
        assertEquals("a.gamma", message.from.id)
        assertEquals(RefKind.HUMAN, message.to.kind)
        assertEquals("th.19", message.thread)
        assertEquals("m.7f20", message.replyTo)
        assertEquals(300, message.ttl)
        assertEquals(InterruptClass.COMMITMENT, message.payload.interruptClass)
        assertEquals("u.17", message.payload.target?.id)
        assertEquals(1, message.payload.cost?.interrupts)
        assertEquals(120_000L, message.payload.cost?.ms)

        assertEquals(CanonicalJson.encode(json), CanonicalJson.encode(message.toJson()))
    }

    @Test
    fun `malformed messages throw instead of defaulting`() {
        assertThrows(IllegalArgumentException::class.java) {
            Message.fromJson(JsonParser.parse("""{"amp":"0.1","kind":"message"}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Message.fromJson(
                JsonParser.parse(
                    """{"amp":"0.1","kind":"message","id":"m.1","type":"subscribe","from":{"kind":"agent","id":"a.x"},"to":{"kind":"agent","id":"a.y"},"thread":"th.1","sent":"2026-10-24T10:00:00Z","payload":{}}""",
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MessagePayload.fromJson(jsonObj("code" to jsonText("teapot")))
        }
    }

    @Test
    fun `performatives and error codes map their wire names`() {
        assertEquals(7, Performative.entries.size)
        assertEquals(Performative.CANCEL, Performative.fromWire("cancel"))
        assertEquals(Performative.RELEASE, Performative.fromWire("release"))
        assertTrue(Performative.isKnown("decline"))
        assertEquals(7, AmpProtocol.performatives.size)
        assertTrue(AmpProtocol.isPerformative("propose"))

        ErrorCode.entries.forEach { code ->
            assertEquals(code, ErrorCode.fromWire(code.wire))
            assertEquals(code, AmpProtocol.errorCode(code.wire))
        }
        assertEquals(8, ErrorCode.entries.size)
        assertThrows(IllegalArgumentException::class.java) { ErrorCode.fromWire("teapot") }
        assertThrows(IllegalArgumentException::class.java) { Performative.fromWire("subscribe") }
    }

    @Test
    fun `note verb example parses validates and round trips`() {
        val json = example("verb-note.json")
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("verb", json))

        val call = parseVerbCall(json) as NoteCall
        assertEquals("call.31", call.id)
        assertEquals(NoteAs.HYPOTHESIS, call.asKind)
        assertEquals(Tier.T1, call.tier)
        assertEquals(1, call.evidence?.size)
        assertEquals("ev.1", call.evidence?.single()?.id)
        assertEquals(CanonicalJson.encode(json), CanonicalJson.encode(call.toJson()))
        assertEquals(call, NoteCall.fromJson(json))
    }

    @Test
    fun `M surface calls validate and round trip`() {
        val attend = AttendCall(
            id = "call.1",
            target = Ref(RefKind.AGENT, "a.bob"),
            why = "need a decision",
            budget = Budget(tokens = 5),
        )
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("verb", attend.toJson()))
        assertEquals(attend, AttendCall.fromJson(attend.toJson()))
        assertEquals(VerbName.ATTEND, parseVerbCall(attend.toJson()).verb)

        val ask = AskCall(
            question = "which window?",
            to = Ref(RefKind.HUMAN, "u.li"),
            interruptClass = InterruptClass.SAFETY,
            urgency = 3,
            expires = "2026-10-24T11:00:00Z",
        )
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("verb", ask.toJson()))
        assertEquals(ask, AskCall.fromJson(ask.toJson()))

        val read = ReadCall(handle = "h.1", cursor = 0, limit = 10)
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("verb", read.toJson()))
        assertEquals(read, ReadCall.fromJson(read.toJson()))

        assertThrows(IllegalArgumentException::class.java) {
            AskCall(question = "bad", urgency = 6)
        }
    }

    private fun example(name: String): JsonValue {
        val stream = javaClass.getResourceAsStream("/amp/examples/$name")
            ?: throw IllegalStateException("missing example $name")
        return stream.bufferedReader(Charsets.UTF_8).use { JsonParser.parse(it.readText()) }
    }
}
