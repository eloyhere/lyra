package com.eloykim.lyra.amp.protocol.attention

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialTypesTest {

    @Test
    fun `attention-request 示例往返并符合 social schema`() {
        val example = attentionExampleJson("attention-request.json")
        val request = AttentionRequest.fromJson(example)

        assertEquals("ar.5", request.id)
        assertEquals(InterruptClass.COMMITMENT, request.interruptClass)
        assertEquals(3, request.urgency)
        assertEquals(120_000L, request.cost.ms)
        assertEquals(Fallback.QUEUE, request.fallback)
        assertEquals(example, request.toJson())
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("social", request.toJson()))
    }

    @Test
    fun `other-model 示例往返并符合 social schema`() {
        val example = attentionExampleJson("other-model.json")
        val model = OtherModel.fromJson(example)

        assertEquals("om.u.li", model.id)
        assertEquals(OtherSubjectKind.HUMAN, model.subject.kind)
        assertEquals(2, model.beliefs.size)
        assertEquals(Volatility.HIGH, model.beliefs[0].volatility)
        assertEquals(1, model.goals?.size)
        assertEquals(example, model.toJson())
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("social", model.toJson()))
    }

    @Test
    fun `common-ground 往返并区分假设`() {
        val value = CommonGround(
            id = "cg.1",
            proposition = "双方同意按 T0 假定继续",
            establishedBy = EstablishedBy.CONFIRMATION,
            participants = listOf(attentionRef("a.alpha", RefKind.AGENT), attentionRef("u.li", RefKind.HUMAN)),
            confidence = 0.8,
            establishedAt = ATTENTION_TEST_AT,
        )
        assertEquals(value, CommonGround.fromJson(value.toJson()))
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("social", value.toJson()))
        assertTrue(value.establishedBy.establishesCommonGround)
        assertFalse(EstablishedBy.ASSUMPTION.establishesCommonGround)
    }

    @Test
    fun `SocialObject 按 kind 分派`() {
        assertTrue(SocialObject.fromJson(attentionExampleJson("attention-request.json")) is AttentionRequest)
        assertTrue(SocialObject.fromJson(attentionExampleJson("other-model.json")) is OtherModel)
    }

    @Test
    fun `缺失或越界字段抛 IllegalArgumentException`() {
        val example = attentionExampleJson("attention-request.json") as JsonValue.Obj
        assertThrows(IllegalArgumentException::class.java) {
            AttentionRequest.fromJson(example.without("why_now"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttentionRequest.fromJson(example.copyWith("urgency", jsonNumber(6)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttentionRequest.fromJson(example.copyWith("class", JsonValue.Str("spam")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttentionRequest.fromJson(example.copyWith("fallback", JsonValue.Str("explode")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttentionRequest.fromJson(example.copyWith("amp", JsonValue.Str("0.2")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OtherModel.fromJson(
                (attentionExampleJson("other-model.json") as JsonValue.Obj).copyWith("confidence", jsonNumber(1.5)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SocialObject.fromJson(jsonObj("amp" to JsonValue.Str("0.1"), "kind" to JsonValue.Str("mystery")))
        }
    }

    @Test
    fun `构造时强制 why_now 与 if_ignored`() {
        assertThrows(IllegalArgumentException::class.java) {
            attentionRequestFixture(id = "ar.bad").copy(whyNow = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            attentionRequestFixture(id = "ar.bad").copy(ifIgnored = "")
        }
    }
}
