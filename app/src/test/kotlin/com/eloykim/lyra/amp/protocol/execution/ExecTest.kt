package com.eloykim.lyra.amp.protocol.execution

import com.eloykim.lyra.amp.protocol.attention.BudgetExceeded
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.module.JsonRpcErrorCode
import com.eloykim.lyra.amp.protocol.module.JsonRpcRequest
import com.eloykim.lyra.amp.protocol.module.JsonRpcResponse
import com.eloykim.lyra.amp.protocol.module.McpClient
import com.eloykim.lyra.amp.protocol.module.McpProtocol
import com.eloykim.lyra.amp.protocol.module.MemoryTransport
import com.eloykim.lyra.amp.protocol.policy.Guard
import com.eloykim.lyra.amp.protocol.policy.GuardOptions
import com.eloykim.lyra.amp.protocol.receipt.Outcome
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Provenance
import com.eloykim.lyra.amp.protocol.types.ProvenanceKind
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.warrant.Claim
import com.eloykim.lyra.amp.protocol.warrant.ClaimStatus
import com.eloykim.lyra.amp.protocol.warrant.Inference
import com.eloykim.lyra.amp.protocol.warrant.Warrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `src/execution/exec.ts` 的行为锁定：目录钉扎、参数校验、审批绑定、护栏与
 * handle 塑形。
 */
class ExecTest {

    private val logsQueryJson =
        """{"name":"logs.query","description":"Query logs",""" +
            """"inputSchema":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]},""" +
            """"annotations":{"readOnlyHint":true}}"""

    private val deployJson =
        """{"name":"deploy.rollback","inputSchema":{"type":"object"},""" +
            """"annotations":{"readOnlyHint":false,"destructiveHint":true}}"""

    private fun obj(text: String): JsonValue.Obj = JsonParser.parse(text) as JsonValue.Obj

    private fun toolJson(text: String): JsonValue.Obj = obj(text)

    private class FakeServer(logsQuery: JsonValue.Obj, deploy: JsonValue.Obj) {
        var tools: List<JsonValue.Obj> = listOf(logsQuery, deploy)
        var resultText: String = "hello"
        var callCount: Int = 0
        var lastCallArgs: JsonValue.Obj? = null

        val transport = MemoryTransport { request: JsonRpcRequest ->
            when (request.method) {
                "server/discover" -> JsonRpcResponse.success(
                    request.id,
                    jsonObj(
                        "supportedVersions" to jsonArr(jsonText(McpProtocol.VERSION)),
                        "ttlMs" to jsonNumber(60_000),
                    ),
                )

                "tools/list" -> JsonRpcResponse.success(
                    request.id,
                    jsonObj(
                        "tools" to JsonValue.Arr(tools.toList()),
                        "ttlMs" to jsonNumber(60_000),
                    ),
                )

                "tools/call" -> {
                    callCount += 1
                    lastCallArgs = request.params?.obj("arguments")
                    JsonRpcResponse.success(
                        request.id,
                        jsonObj(
                            "content" to jsonArr(
                                jsonObj(
                                    "type" to jsonText("text"),
                                    "text" to jsonText(resultText),
                                ),
                            ),
                        ),
                    )
                }

                else -> JsonRpcResponse.failure(request.id, JsonRpcErrorCode.METHOD_NOT_FOUND, "not found")
            }
        }
    }

    private val now: String = "2026-10-24T10:00:00Z"

    private fun claim(tier: Tier = Tier.T3, confidence: Double = 0.9): Claim = Claim(
        id = "cl.1",
        statement = "the rollback is safe",
        tier = tier,
        status = ClaimStatus.HELD,
        warrant = Warrant(
            evidence = emptyList(),
            inference = Inference.OBSERVATION,
            strength = confidence,
        ),
        provenance = Provenance(source = "test.observation", kind = ProvenanceKind.OBSERVATION),
        created = "2026-10-24T09:00:00Z",
        confidence = confidence,
    )

    private fun profile(
        server: FakeServer,
        handles: HandleStore = HandleStore(),
        budget: Budget = Budget(tokens = 20_000, calls = 20, ms = 600_000),
        guard: Guard? = null,
        requireApproval: Boolean? = null,
        maxResultTokens: Int = 1200,
        onReceipt: ((com.eloykim.lyra.amp.protocol.receipt.Receipt) -> Unit)? = null,
    ): ExecProfile = ExecProfile(
        McpClient(server.transport),
        ExecOptions(
            budget = budget,
            handles = handles,
            now = { now },
            maxResultTokens = maxResultTokens,
            requireApproval = requireApproval,
            onReceipt = onReceipt,
            guard = guard,
        ),
    )

    @Test
    fun `connect 钉扎目录并按名字暴露工具`() {
        val server = FakeServer(toolJson(logsQueryJson), toolJson(deployJson))
        val exec = profile(server)
        val snapshot = exec.connect()
        assertEquals(listOf("logs.query", "deploy.rollback"), snapshot.tools.map { it.name })
        assertEquals(pinTools(snapshot.tools).catalog, snapshot.digest)
        assertEquals(60_000L, snapshot.ttlMs)
        assertEquals(2, exec.tools().size)
        assertNotNull(exec.tool("deploy.rollback"))
        assertNull(exec.tool("missing"))
        assertTrue(exec.verify().ok)
        assertEquals(snapshot.digest, exec.verify().digest)
    }

    @Test
    fun `read 调用无需 claim 且 receipt 可过 schema`() {
        val server = FakeServer(toolJson(logsQueryJson), toolJson(deployJson))
        val handles = HandleStore()
        val receipts = ArrayList<com.eloykim.lyra.amp.protocol.receipt.Receipt>()
        val exec = profile(server, handles = handles, onReceipt = { receipts += it })

        val outcome = exec.call("logs.query", obj("""{"query":"error"}"""))
        assertEquals("hello", outcome.inline)
        assertNull(outcome.handle)
        assertEquals(2, outcome.tokens)
        assertEquals(Outcome.SUCCESS, outcome.receipt.outcome)
        assertEquals("rc.exec.1", outcome.receipt.id)
        assertEquals("mcp.logs.query", outcome.receipt.target!!.id)
        assertEquals(1, outcome.receipt.usage.calls)
        assertEquals(900L, outcome.receipt.usage.ms)
        assertTrue(AmpSchemas.validateKind("receipt", outcome.receipt.toJson()).isEmpty())
        assertEquals(1, receipts.size)
        assertEquals(1, server.callCount)
    }

    @Test
    fun `非只读工具缺审批抛 ApprovalRequired 并带 blocked receipt`() {
        val server = FakeServer(toolJson(logsQueryJson), toolJson(deployJson))
        val receipts = ArrayList<com.eloykim.lyra.amp.protocol.receipt.Receipt>()
        val exec = profile(server, onReceipt = { receipts += it })

        try {
            exec.call("deploy.rollback", obj("{}"), CallOptions(claim = claim()))
            throw AssertionError("expected ApprovalRequired")
        } catch (error: ApprovalRequired) {
            assertEquals("approval_required", error.code)
            assertEquals("deploy.rollback", error.tool)
            assertNotNull(error.receipt)
            assertEquals(Outcome.BLOCKED, error.receipt!!.outcome)
            assertEquals("approval_required:approval_required", error.receipt!!.note)
        }
        assertEquals(0, server.callCount)
        assertEquals(1, receipts.size)
    }

    @Test
    fun `审批绑定工具摘要与参数摘要且只能兑换一次`() {
        val server = FakeServer(toolJson(logsQueryJson), toolJson(deployJson))
        val exec = profile(server)
        exec.connect()

        val args = obj("""{"action":"rollback"}""")
        val approval = exec.approveTool("deploy.rollback", args, approvedBy = "a.host")
        val outcome = exec.call("deploy.rollback", args, CallOptions(claim = claim(), approval = approval))
        assertEquals(Outcome.SUCCESS, outcome.receipt.outcome)
        assertEquals(1, server.callCount)

        try {
            exec.call("deploy.rollback", args, CallOptions(claim = claim(), approval = approval))
            throw AssertionError("expected PolicyDenied")
        } catch (error: PolicyDenied) {
            assertTrue(error.message!!.contains("approval_consumed"))
        }

        val other = exec.approveTool("deploy.rollback", args, approvedBy = "a.host")
        try {
            exec.call("deploy.rollback", obj("""{"action":"destroy"}"""), CallOptions(claim = claim(), approval = other))
            throw AssertionError("expected PolicyDenied")
        } catch (error: PolicyDenied) {
            assertTrue(error.message!!.contains("approval_digest_mismatch"))
        }
    }

    @Test
    fun `参数 schema 拒绝时不触达服务器并记 blocked receipt`() {
        val server = FakeServer(toolJson(logsQueryJson), toolJson(deployJson))
        val exec = profile(server)
        exec.connect()

        try {
            exec.call("logs.query", obj("""{"query":1}"""))
            throw AssertionError("expected PolicyDenied")
        } catch (error: PolicyDenied) {
            assertTrue(error.message!!.contains("invalid arguments for logs.query"))
            assertEquals(Outcome.BLOCKED, error.receipt!!.outcome)
            assertTrue(error.receipt!!.note!!.startsWith("invalid_tool_args:"))
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `调用前重新钉扎并检测目录漂移`() {
        val server = FakeServer(toolJson(logsQueryJson), toolJson(deployJson))
        val exec = profile(server)
        exec.connect()

        server.tools = listOf(
            toolJson(logsQueryJson).copyWith("description", jsonText("Query logs (v2)")),
            toolJson(deployJson),
        )
        try {
            exec.call("logs.query", obj("""{"query":"error"}"""))
            throw AssertionError("expected DriftDetected")
        } catch (error: DriftDetected) {
            assertEquals("security_event", error.code)
            assertTrue(error.message!!.contains("tool changed before call"))
            assertNotNull(error.receipt)
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `超过 token 预算的结果变成 handle 且不截断`() {
        val server = FakeServer(toolJson(logsQueryJson), toolJson(deployJson))
        server.resultText = "x".repeat(5_000)
        val handles = HandleStore()
        val exec = profile(server, handles = handles)

        val outcome = exec.call("logs.query", obj("""{"query":"error"}"""))
        assertNull(outcome.inline)
        assertNotNull(outcome.handle)
        assertEquals(1_250, outcome.tokens)
        assertTrue(handles.has(outcome.handle!!))
        assertEquals(Outcome.SUCCESS, outcome.receipt.outcome)
        assertNotNull(outcome.receipt.handle)
        assertTrue(outcome.receipt.note!!.contains("handle emitted, nothing truncated"))

        val page = handles.read(outcome.handle!!, 0)
        assertEquals(960, page.text.length)
    }

    @Test
    fun `护栏阻断发生在派发之前`() {
        val server = FakeServer(toolJson(logsQueryJson), toolJson(deployJson))
        val guard = Guard(GuardOptions(now = { 0L }, costCeiling = 1.0))
        val exec = profile(server, guard = guard)

        try {
            exec.call("logs.query", obj("""{"query":"error"}"""), CallOptions(money = 2.0))
            throw AssertionError("expected PolicyDenied")
        } catch (error: PolicyDenied) {
            assertTrue(error.message!!.contains("guard blocked logs.query"))
            assertNotNull(error.receipt)
            assertEquals(Outcome.BLOCKED, error.receipt!!.outcome)
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `未知工具与预算超限都留下 blocked receipt`() {
        val server = FakeServer(toolJson(logsQueryJson), toolJson(deployJson))
        val exec = profile(server)
        exec.connect()

        try {
            exec.call("missing.tool", obj("{}"))
            throw AssertionError("expected PolicyDenied")
        } catch (error: PolicyDenied) {
            assertTrue(error.message!!.contains("unknown tool: missing.tool"))
            assertEquals("unknown_tool:missing.tool", error.receipt!!.note)
        }

        val receipts = ArrayList<com.eloykim.lyra.amp.protocol.receipt.Receipt>()
        val broke = profile(server, budget = Budget(calls = 0, ms = 1_000), onReceipt = { receipts += it })
        try {
            broke.call("logs.query", obj("""{"query":"error"}"""))
            throw AssertionError("expected BudgetExceeded")
        } catch (error: BudgetExceeded) {
            assertEquals("budget_exceeded", error.code)
            assertEquals("calls", error.dimension.wire)
        }
        assertEquals(Outcome.BLOCKED, receipts.last().outcome)
        assertEquals("budget_exceeded:calls", receipts.last().note)
    }

    @Test
    fun `requireApproval false 时默认引擎不要求审批`() {
        val server = FakeServer(toolJson(logsQueryJson), toolJson(deployJson))
        val exec = profile(server, requireApproval = false)
        val outcome = exec.call("deploy.rollback", obj("{}"), CallOptions(claim = claim()))
        assertEquals(Outcome.SUCCESS, outcome.receipt.outcome)
        assertEquals(1, server.callCount)
    }

    @Test
    fun `快照与结果的 JSON 往返`() {
        val server = FakeServer(toolJson(logsQueryJson), toolJson(deployJson))
        val exec = profile(server)
        val snapshot = exec.connect()
        assertEquals(snapshot, CatalogSnapshot.fromJson(snapshot.toJson()))
        assertEquals(exec.verify(), VerifyReport.fromJson(exec.verify().toJson()))

        val outcome = exec.call("logs.query", obj("""{"query":"error"}"""))
        assertEquals(outcome, CallOutcome.fromJson(outcome.toJson()))
        assertFalse(outcome.toJson().has("handle"))
    }
}
