package com.eloykim.lyra.amp.protocol.protocol

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.types.Budget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelWireTest {

    @Test
    fun `delegation crosses the loopback channel and is recorded`() {
        val channel = LoopbackChannel()
        val doc = delegateOver(channel, delegation())

        assertEquals(1, channel.sent.size)
        assertEquals(GOAL, doc.goalDigest)
        assertEquals("delegation", doc.toJson().string("kind"))
        assertEquals("tool.logs.query", doc.scope.single())
    }

    @Test
    fun `peer rejection fails closed with a reason`() {
        val channel = LoopbackChannel { ChannelVerdict(accepted = false, reason = "budget too wide") }
        val error = assertThrows(IllegalStateException::class.java) {
            delegateOver(channel, delegation())
        }
        assertTrue(error.message!!.contains("budget too wide"))
        assertEquals(1, channel.sent.size)
    }

    @Test
    fun `peer can enforce its own ceiling on the wire document`() {
        val channel = LoopbackChannel { doc ->
            if ((doc.budget.tokens ?: 0) > 50) {
                ChannelVerdict(false, "over peer ceiling")
            } else {
                ChannelVerdict(true)
            }
        }
        val error = assertThrows(IllegalStateException::class.java) {
            delegateOver(channel, delegation(tokens = 100))
        }
        assertTrue(error.message!!.contains("over peer ceiling"))
        assertTrue(delegateOver(channel, delegation(tokens = 40)).goalDigest == GOAL)
    }

    @Test
    fun `delegation wire document round trips and validates`() {
        val runtime = delegation()
        val doc = toDelegationDoc(runtime)

        assertEquals(emptyList<String>(), AmpSchemas.validateKind("collaboration", doc.toJson()))
        assertEquals(GOAL, doc.toJson().string("goal_digest"))
        assertFalse(doc.toJson().bool("may_redelegate")!!)
        assertEquals(0, doc.depth)

        assertEquals(runtime, fromDelegationDoc(doc))
        assertEquals(doc, DelegationDoc.fromJson(doc.toJson()))
        assertNull(fromDelegationDoc(doc).parent)
    }

    @Test
    fun `delegation parent is preserved in both directions`() {
        val doc = toDelegationDoc(delegation(parent = "dl.0"))
        assertEquals("dl.0", doc.toJson().string("parent"))
        assertEquals("dl.0", fromDelegationDoc(doc).parent)
    }

    @Test
    fun `lease wire document round trips and validates`() {
        val lease = Lease(
            id = "ls.2",
            delegation = "dl.1",
            taskId = "t1",
            holder = "a.worker",
            fence = 1,
            goalDigest = GOAL,
            budget = Budget(tokens = 1200, calls = 2),
            deadline = "2026-10-24T11:00:00Z",
            status = LeaseStatus.ACTIVE,
        )
        val doc = toLeaseDoc(lease)

        assertEquals(emptyList<String>(), AmpSchemas.validateKind("collaboration", doc.toJson()))
        assertEquals("lease", doc.toJson().string("kind"))
        assertEquals("active", doc.toJson().string("status"))
        assertEquals("t1", doc.toJson().string("task_id"))

        val decoded = LeaseDoc.fromJson(doc.toJson())
        assertEquals(doc, decoded)
        assertEquals(lease.taskId, decoded.taskId)
        assertEquals(LeaseStatus.ACTIVE, decoded.status)
    }

    @Test
    fun `collaboration example parses into wire documents`() {
        val text = javaClass.getResourceAsStream("/amp/examples/collaboration.json")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val items = (JsonParser.parse(text) as JsonValue.Arr).items

        val delegation = DelegationDoc.fromJson(items[0])
        assertEquals("dl.1", delegation.id)
        assertEquals(4000, delegation.budget.tokens)
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("collaboration", delegation.toJson()))

        val lease = LeaseDoc.fromJson(items[1])
        assertEquals("ls.2", lease.id)
        assertEquals(1, lease.fence)
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("collaboration", lease.toJson()))
    }

    @Test
    fun `channel verdict json is minimal and defaults missing accepted to false`() {
        assertEquals(
            ChannelVerdict(false, "nope"),
            ChannelVerdict.fromJson(JsonParser.parse("""{"reason":"nope"}""")),
        )
        assertEquals(
            ChannelVerdict(true, "ok"),
            ChannelVerdict.fromJson(JsonParser.parse("""{"accepted":true,"reason":"ok"}""")),
        )
    }

    private fun delegation(tokens: Int = 100, parent: String? = null): Delegation = Delegation(
        id = "dl.1",
        parent = parent,
        granter = "a.lead",
        delegatee = "a.worker",
        goalDigest = GOAL,
        scope = listOf("tool.logs.query"),
        budget = Budget(tokens = tokens),
        deadline = "2026-10-24T12:00:00Z",
        mayRedelegate = false,
        depth = 0,
    )

    companion object {
        private val GOAL = "sha256:" + "a".repeat(64)
    }
}
