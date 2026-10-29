package com.eloykim.lyra.amp.protocol.runtime

import com.eloykim.lyra.amp.protocol.context.AffectState
import com.eloykim.lyra.amp.protocol.context.AgentProfile
import com.eloykim.lyra.amp.protocol.context.AgentReader
import com.eloykim.lyra.amp.protocol.context.BackgroundProcess
import com.eloykim.lyra.amp.protocol.context.Memory
import com.eloykim.lyra.amp.protocol.context.Membership
import com.eloykim.lyra.amp.protocol.context.Relationship
import com.eloykim.lyra.amp.protocol.context.RoleAssignment
import com.eloykim.lyra.amp.protocol.context.SelfNarrative
import com.eloykim.lyra.amp.protocol.context.SocialReader
import com.eloykim.lyra.amp.protocol.interaction.InteractionMix
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.module.LlmFinishReason
import com.eloykim.lyra.amp.protocol.module.LlmResponse
import com.eloykim.lyra.amp.protocol.module.LlmToolCall
import com.eloykim.lyra.amp.protocol.module.LlmUsage
import com.eloykim.lyra.amp.protocol.module.RiskToolPolicy
import com.eloykim.lyra.amp.protocol.module.ScriptedLlm
import com.eloykim.lyra.amp.protocol.module.ToolDescriptor
import com.eloykim.lyra.amp.protocol.module.ToolExecutor
import com.eloykim.lyra.amp.protocol.module.ToolRegistry
import com.eloykim.lyra.amp.protocol.module.ToolResult
import com.eloykim.lyra.amp.protocol.module.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `cognitiveRuntime.ts` 的端到端固定测试。
 *
 * 用脚本化 LLM 跑完整循环，钉住：一次成功轮次的 receipt/动作/操作、工具轮的消息
 * 与 operation 记账、审批记录、结果遮蔽与超限信封、截断续写、以及安全升级路径
 * **不调用模型**。
 */
class CognitiveRuntimeTest {

    private class FakeAgent : AgentReader {
        override fun profile(pid: String): AgentProfile =
            AgentProfile(name = "Lyra", lifeChapter = "test chapter")

        override fun narrative(pid: String): SelfNarrative = SelfNarrative(1, "test narrative")

        override fun activeRole(pid: String): RoleAssignment? = null

        override fun affect(pid: String): AffectState? = null

        override fun memories(pid: String, limit: Int): List<Memory> = emptyList()

        override fun background(pid: String, state: String): List<BackgroundProcess> = emptyList()
    }

    private class FakeSocial : SocialReader {
        override fun relationships(pid: String): List<Relationship> = emptyList()

        override fun memberships(pid: String): List<Membership> = emptyList()
    }

    private fun options(
        llm: ScriptedLlm,
        pid: Int = 1,
        sink: ReceiptSink? = null,
        turnStore: TurnStore? = null,
        sessionStore: SessionStore? = null,
        escalationSink: EscalationSink? = null,
        tools: ToolRegistry? = null,
        executor: ToolExecutor? = null,
        policy: com.eloykim.lyra.amp.protocol.module.ToolPolicy? = null,
        maxToolResultChars: Int? = null,
        contextAst: Boolean = false,
        interactionMix: InteractionMix? = null,
    ): CognitiveRuntimeOptions = CognitiveRuntimeOptions(
        pid = pid,
        agent = FakeAgent(),
        social = FakeSocial(),
        llm = llm,
        receiptSink = sink,
        turnStore = turnStore,
        sessionStore = sessionStore,
        escalationSink = escalationSink,
        tools = tools,
        toolExecutor = executor,
        toolPolicy = policy,
        maxToolResultChars = maxToolResultChars,
        contextAst = contextAst,
        interactionMix = interactionMix,
        now = { "2026-01-01T00:00:00.000Z" },
    )

    @Test
    fun `one turn returns a receipt an action and an llm operation`() {
        val llm = ScriptedLlm(listOf(LlmResponse("hello there", LlmUsage(11, 7), LlmFinishReason.STOP)))
        val sink = MemoryReceiptSink()
        val store = MemoryTurnStore()
        val runtime = CognitiveRuntime(options(llm, sink = sink, turnStore = store))

        val turn = runtime.respond("hello")

        assertEquals("rt.1", turn.turnId)
        assertEquals("hello there", turn.answer)
        assertEquals("2026-01-01T00:00:00.000Z", turn.at)
        assertEquals("rc.runtime.1", turn.receiptId)
        assertEquals(1, llm.calls)
        assertEquals(1, sink.receipts.size)
        assertEquals(ReceiptOutcome.SUCCESS, sink.receipts[0].outcome)
        assertEquals("runtime turn", sink.receipts[0].note)
        assertEquals(11, sink.receipts[0].usage.inputTokens)
        assertEquals(1, turn.actions?.size)
        assertEquals(ActionStatus.DONE, turn.actions!![0].status)
        assertEquals(1, turn.operations?.size)
        assertEquals(OperationKind.LLM_COMPLETE, turn.operations!![0].kind)
        assertEquals(OperationStatus.OK, turn.operations!![0].status)
        assertEquals(1, turn.actions!![0].operationIds.size)
        assertNull(turn.toolCalls)
        assertNull(turn.sessionId)
        assertEquals(SafetyAction.ALLOW, turn.safety.action)
        assertNotNull(turn.context)
        assertTrue(turn.answerScore!!.overall > 0.0)
        assertTrue(turn.trace.any { it.startsWith("user quality=") })
        assertTrue(turn.trace.any { it.startsWith("llm input=11") })
        assertEquals(1, store.list(1).size)
        assertEquals(turn.turnId, store.list(1)[0].turnId)
    }

    @Test
    fun `context ast view is only rendered when opted in`() {
        val plain = ScriptedLlm(listOf(LlmResponse("a", LlmUsage(1, 1), LlmFinishReason.STOP)))
        val plainTurn = CognitiveRuntime(options(plain)).respond("hello")
        assertFalse(plainTurn.trace.any { it.startsWith("context ast tokens=") })

        val ast = ScriptedLlm(listOf(LlmResponse("a", LlmUsage(1, 1), LlmFinishReason.STOP)))
        val astTurn = CognitiveRuntime(options(ast, contextAst = true)).respond("hello")
        assertTrue(astTurn.trace.any { it.startsWith("context ast tokens=") })
    }

    @Test
    fun `tool loop records operations approvals and messages`() {
        val llm = ScriptedLlm(
            listOf(
                LlmResponse(
                    text = "",
                    usage = LlmUsage(5, 2),
                    finishReason = LlmFinishReason.TOOL_CALL,
                    toolCalls = listOf(LlmToolCall("call-1", "wipe", """{"path":"/tmp/x"}""")),
                ),
                LlmResponse("done", LlmUsage(6, 3), LlmFinishReason.STOP),
            ),
        )
        val tools = ToolRegistry().register(
            ToolDescriptor("wipe", "wipes a file", jsonObj(), ToolRisk.DESTRUCTIVE),
        )
        val executor = ToolExecutor { ToolResult(true, "wiped") }
        val runtime = CognitiveRuntime(options(llm, tools = tools, executor = executor))

        val turn = runtime.respond("delete the file")

        assertEquals("done", turn.answer)
        assertEquals(2, llm.calls)
        assertEquals(1, turn.toolCalls?.size)
        val call = turn.toolCalls!![0]
        assertEquals("wipe", call.name)
        assertTrue(call.ok)
        assertEquals("wiped", call.content)
        assertEquals(ToolRisk.DESTRUCTIVE, call.risk)
        assertEquals("allowed", call.decision)
        assertEquals(1, turn.approvals?.size)
        assertEquals(ApprovalStatus.ALLOWED, turn.approvals!![0].status)
        assertEquals(SideEffect.DESTRUCTIVE, turn.approvals!![0].risk)
        assertEquals(2, turn.operations?.size)
        assertEquals(OperationKind.TOOL_CALL, turn.operations!![0].kind)
        assertEquals("call-1", turn.operations!![0].idempotencyKey)
        assertEquals(2, turn.actions!![0].operationIds.size)
        assertEquals(11, turn.usage!!.inputTokens)
        assertEquals(5, turn.usage!!.outputTokens)
        assertTrue(turn.trace.any { it.startsWith("tools=wipe:ok") })
        assertEquals(0, turn.toolErrors)
    }

    @Test
    fun `tool policy denial is recorded without executing the tool`() {
        val llm = ScriptedLlm(
            listOf(
                LlmResponse(
                    text = "",
                    usage = LlmUsage(5, 2),
                    finishReason = LlmFinishReason.TOOL_CALL,
                    toolCalls = listOf(LlmToolCall("call-1", "wipe", "{}")),
                ),
                LlmResponse("blocked", LlmUsage(6, 3), LlmFinishReason.STOP),
            ),
        )
        var executed = false
        val tools = ToolRegistry().register(
            ToolDescriptor("wipe", "wipes a file", jsonObj(), ToolRisk.DESTRUCTIVE),
        )
        val executor = ToolExecutor {
            executed = true
            ToolResult(true, "wiped")
        }
        val runtime = CognitiveRuntime(
            options(llm, tools = tools, executor = executor, policy = RiskToolPolicy()),
        )

        val turn = runtime.respond("delete the file")

        assertFalse(executed)
        assertFalse(turn.toolCalls!![0].ok)
        assertEquals("tool wipe requires approval", turn.toolCalls!![0].error)
        assertEquals(ApprovalStatus.DENIED, turn.approvals!![0].status)
        assertEquals(OperationStatus.ERROR, turn.operations!![0].status)
        assertEquals(1, turn.toolErrors)
        assertEquals("blocked", turn.answer)
    }

    @Test
    fun `oversized tool result becomes a complete error envelope`() {
        val llm = ScriptedLlm(
            listOf(
                LlmResponse(
                    text = "",
                    usage = LlmUsage(1, 1),
                    finishReason = LlmFinishReason.TOOL_CALL,
                    toolCalls = listOf(LlmToolCall("call-1", "echo", "{}")),
                ),
                LlmResponse("done", LlmUsage(1, 1), LlmFinishReason.STOP),
            ),
        )
        val tools = ToolRegistry().register(ToolDescriptor("echo", "echo", jsonObj(), ToolRisk.READ))
        val executor = ToolExecutor { ToolResult(true, "x".repeat(500)) }
        val runtime = CognitiveRuntime(
            options(llm, tools = tools, executor = executor, maxToolResultChars = 128),
        )

        val turn = runtime.respond("echo something")

        assertFalse(turn.toolCalls!![0].ok)
        assertEquals(
            "tool_result_too_large: request a smaller page or view",
            turn.toolCalls!![0].error,
        )
        assertEquals("", turn.toolCalls!![0].content)
        assertEquals(1, turn.toolErrors)
    }

    @Test
    fun `secrets in tool output are redacted`() {
        val secret = "sk-abcdefghijklmnopqrstuvwxyz012345"
        val llm = ScriptedLlm(
            listOf(
                LlmResponse(
                    text = "",
                    usage = LlmUsage(1, 1),
                    finishReason = LlmFinishReason.TOOL_CALL,
                    toolCalls = listOf(LlmToolCall("call-1", "leak", "{}")),
                ),
                LlmResponse("done", LlmUsage(1, 1), LlmFinishReason.STOP),
            ),
        )
        val tools = ToolRegistry().register(ToolDescriptor("leak", "leak", jsonObj(), ToolRisk.READ))
        val executor = ToolExecutor { ToolResult(true, "token is $secret") }
        val runtime = CognitiveRuntime(options(llm, tools = tools, executor = executor))

        val turn = runtime.respond("leak the token")

        assertTrue(turn.toolCalls!![0].redacted)
        assertTrue(turn.toolCalls!![0].content.contains("[REDACTED]"))
        assertFalse(turn.toolCalls!![0].content.contains(secret))
    }

    @Test
    fun `unknown tool and invalid arguments become operation errors`() {
        val llm = ScriptedLlm(
            listOf(
                LlmResponse(
                    text = "",
                    usage = LlmUsage(1, 1),
                    finishReason = LlmFinishReason.TOOL_CALL,
                    toolCalls = listOf(
                        LlmToolCall("call-1", "missing", "{}"),
                        LlmToolCall("call-2", "echo", "not-json"),
                    ),
                ),
                LlmResponse("done", LlmUsage(1, 1), LlmFinishReason.STOP),
            ),
        )
        val tools = ToolRegistry().register(ToolDescriptor("echo", "echo", jsonObj(), ToolRisk.READ))
        val executor = ToolExecutor { ToolResult(true, "ok") }
        val runtime = CognitiveRuntime(options(llm, tools = tools, executor = executor))

        val turn = runtime.respond("call tools")

        assertEquals("unknown tool missing", turn.toolCalls!![0].error)
        assertTrue(turn.toolCalls!![1].error!!.startsWith("invalid tool arguments:"))
        assertEquals(2, turn.toolErrors)
        assertEquals(OperationStatus.ERROR, turn.operations!![1].status)
    }

    @Test
    fun `length finish reason triggers exactly one continuation`() {
        val llm = ScriptedLlm(
            listOf(
                LlmResponse("part1", LlmUsage(1, 1), LlmFinishReason.LENGTH),
                LlmResponse(" part2", LlmUsage(2, 2), LlmFinishReason.STOP),
            ),
        )
        val sink = MemoryReceiptSink()
        val runtime = CognitiveRuntime(options(llm, sink = sink))

        val turn = runtime.respond("write a long answer")

        assertEquals("part1 part2", turn.answer)
        assertEquals(2, llm.calls)
        assertEquals(3, turn.usage!!.inputTokens)
        assertEquals(3, turn.usage!!.outputTokens)
        assertEquals(ReceiptOutcome.SUCCESS, sink.receipts[0].outcome)
        assertEquals("runtime turn (continued 1x)", sink.receipts[0].note)
        assertTrue(turn.trace.any { it.contains("continuations=1") })
    }

    @Test
    fun `crisis input escalates before the model call`() {
        val llm = ScriptedLlm(emptyList())
        val sink = MemoryReceiptSink()
        val escalation = MemoryEscalationSink()
        val runtime = CognitiveRuntime(options(llm, sink = sink, escalationSink = escalation))

        val turn = runtime.respond("I want to kill myself")

        assertEquals(0, llm.calls)
        assertEquals(SafetyAction.ESCALATE, turn.safety.action)
        assertEquals("esc.1", turn.escalationId)
        assertEquals(1, escalation.events.size)
        assertEquals("esc.1", escalation.events[0].id)
        assertEquals(3, escalation.events[0].resources.size)
        assertEquals(ReceiptOutcome.BLOCKED, sink.receipts[0].outcome)
        assertEquals("pre-safety escalation", sink.receipts[0].note)
        assertNull(turn.answerScore)
        assertNull(turn.usage)
        assertEquals(ActionStatus.ABANDONED, turn.actions!![0].status)
        assertTrue(turn.trace.any { it.startsWith("safety=escalate; no LLM call; escalation=esc.1") })
    }

    @Test
    fun `blocked answers after generation are refused by post safety`() {
        val llm = ScriptedLlm(
            listOf(
                LlmResponse(
                    "you have depression and your diagnosis is clear",
                    LlmUsage(3, 3),
                    LlmFinishReason.STOP,
                ),
            ),
        )
        val sink = MemoryReceiptSink()
        val runtime = CognitiveRuntime(
            options(
                llm,
                sink = sink,
                interactionMix = mapOf(com.eloykim.lyra.amp.protocol.interaction.InteractionKind.AFFECT to 1.0),
            ),
        )

        val turn = runtime.respond("I am anxious and feeling low, what is wrong with me")

        assertEquals(SafetyAction.REFUSE, turn.safety.action)
        assertEquals(safetyFallback(SafetyAction.REFUSE), turn.answer)
        assertEquals(ReceiptOutcome.BLOCKED, sink.receipts[0].outcome)
        assertEquals("post-safety refuse", sink.receipts[0].note)
    }

    @Test
    fun `session store persists turns and survives a restart`() {
        val sessions = MemorySessionStore()
        val first = CognitiveRuntime(
            options(
                ScriptedLlm(listOf(LlmResponse("one", LlmUsage(1, 1), LlmFinishReason.STOP))),
                sessionStore = sessions,
            ),
        )
        val firstTurn = first.respond("hello")
        assertEquals("session.1", firstTurn.sessionId)
        assertEquals("rt.1", firstTurn.turnId)
        assertEquals("default:chat", firstTurn.scope)
        assertNotNull(sessions.load(1))

        val second = CognitiveRuntime(
            options(
                ScriptedLlm(listOf(LlmResponse("two", LlmUsage(1, 1), LlmFinishReason.STOP))),
                sessionStore = sessions,
            ),
        )
        val secondTurn = second.respond("again")
        assertEquals("rt.2", secondTurn.turnId)
        assertEquals("session.1", secondTurn.sessionId)

        assertTrue(second.archiveSession("2026-02-01T00:00:00Z"))
        assertEquals("2026-02-01T00:00:00Z", sessions.load(1)?.archivedAt)
        assertTrue(second.deleteSession())
        assertNull(sessions.load(1))
        assertFalse(second.deleteSession())
    }

    @Test
    fun `maxToolResultChars must stay inside the supported range`() {
        assertThrows(IllegalArgumentException::class.java) {
            CognitiveRuntime(options(ScriptedLlm(emptyList()), maxToolResultChars = 127))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CognitiveRuntime(options(ScriptedLlm(emptyList()), maxToolResultChars = 1024 * 1024 + 1))
        }
    }

    @Test
    fun `auto tune applies suggestions recorded by the session`() {
        val llm = ScriptedLlm(
            listOf(
                LlmResponse(
                    "acknowledged",
                    LlmUsage(1, 1),
                    LlmFinishReason.STOP,
                ),
            ),
        )
        val registry = BehaviorRegistry()
        val runtime = CognitiveRuntime(
            CognitiveRuntimeOptions(
                pid = 1,
                agent = FakeAgent(),
                social = FakeSocial(),
                llm = llm,
                sessionStore = MemorySessionStore(),
                behaviors = registry,
                autoTuneBehavior = true,
                now = { "2026-01-01T00:00:00.000Z" },
            ),
        )

        runtime.respond("hello")

        // 单轮冲突计数是 1，低于默认阈值 3，因此不应用任何变更。
        assertEquals(0, registry.tuning())
    }
}
