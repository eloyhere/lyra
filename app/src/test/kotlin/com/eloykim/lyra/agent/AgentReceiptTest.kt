package com.eloykim.lyra.agent

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.receipt.Receipt
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentReceiptTest {

    @Test
    fun `动作事件携带可解析的协议 Receipt`() = runBlocking {
        val receipts = ArrayList<String>()
        val gateway = object : AutomationGateway {
            override suspend fun observe(): Observation = Observation(
                obsId = 1,
                packageName = "com.example.app",
                elements = listOf(ObservedElement(1, "搜索", clickable = true)),
            )

            override suspend fun tap(nodeId: Int, obsId: Long): ActionResult =
                ActionResult.Ok(observe())

            override suspend fun setText(nodeId: Int, obsId: Long, text: String): ActionResult =
                ActionResult.Ok(observe())

            override suspend fun scroll(direction: Int, obsId: Long): ActionResult =
                ActionResult.Ok(observe())

            override suspend fun launch(packageName: String): ActionResult =
                ActionResult.Ok(observe())

            override suspend fun back(): ActionResult = ActionResult.Ok(observe())
        }
        val backend = ScriptedBackend(
            listOf<ScriptStep>(
                { Verb.Act("tap", nodeId = 1) },
                { Verb.Done },
            ),
        )
        AgentSession(
            gateway = gateway,
            backend = backend,
            events = EventSink { type, payload, _ ->
                if (type == "receipt") receipts += payload
            },
        ).run("测试").toList()

        assertEquals(1, receipts.size)
        val payload = JsonParser.parse(receipts.first()) as com.eloykim.lyra.amp.protocol.json.JsonValue.Obj
        val receiptJson = payload.requireObj("receipt")
        val receipt = Receipt.fromJson(receiptJson)
        assertTrue(receipt.id.startsWith("rc."))
        assertEquals("act", receipt.op.wire)
        assertEquals("success", receipt.outcome.wire)
        assertTrue(receipt.usage.tokens != null)
        assertTrue(receipt.usage.ms != null)
    }
}
