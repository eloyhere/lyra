package com.eloykim.lyra.amp.protocol.diagnosis

import com.eloykim.lyra.amp.protocol.context.ContextNodeKind
import com.eloykim.lyra.amp.protocol.goal.TaskActor
import com.eloykim.lyra.amp.protocol.goal.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosisTest {

    private val threadQueueSignal = DiagnosticSignal(
        layer = DiagnosticLayer.THREAD_POOL,
        kind = SignalKind.SATURATION,
        name = "thread.queue",
        state = ObservationState.CRITICAL,
        value = 128.0,
    )

    private val dbSlowSignal = DiagnosticSignal(
        layer = DiagnosticLayer.DATABASE,
        kind = SignalKind.LATENCY,
        name = "db.query_p99",
        state = ObservationState.WARN,
        value = 2400.0,
    )

    private fun incident(signals: List<DiagnosticSignal>? = null): IncidentInput = IncidentInput(
        id = "inc.1",
        summary = "checkout latency spike",
        symptoms = listOf("p99 latency 10x"),
        signals = signals,
    )

    @Test
    fun `构建 frame 的默认层与阶段`() {
        val framing = buildDiagnosisFrame(incident())
        assertEquals("diag.inc.1", framing.id)
        assertEquals(7, framing.layers.size)
        assertEquals(7, framing.steps.size)
        assertEquals(DiagnosticPhase.FRAMING, framing.phase)
        assertEquals(DefaultHypotheses.size, framing.hypotheses.size)
        assertTrue(validateDiagnosisFrame(framing).isEmpty())

        val observing = buildDiagnosisFrame(incident(listOf(threadQueueSignal)))
        assertEquals(DiagnosticPhase.OBSERVING, observing.phase)

        val narrowed = buildDiagnosisFrame(incident().copy(layers = listOf(DiagnosticLayer.DATABASE)))
        assertEquals(listOf(DiagnosticLayer.DATABASE), narrowed.layers)
        assertEquals(listOf("probe.database"), narrowed.steps.map { it.id })
    }

    @Test
    fun `观察追加信号且不修改原 frame`() {
        val framing = buildDiagnosisFrame(incident())
        val observed = observe(framing, threadQueueSignal)
        assertNull(framing.incident.signals)
        assertEquals(1, observed.incident.signals?.size)
        assertEquals(DiagnosticPhase.OBSERVING, observed.phase)

        val again = observe(observed, dbSlowSignal)
        assertEquals(2, again.incident.signals?.size)
        assertEquals(DiagnosticPhase.OBSERVING, again.phase)
    }

    @Test
    fun `假设评分使用匹配反向证据与根权重`() {
        val frame = buildDiagnosisFrame(incident(listOf(threadQueueSignal, dbSlowSignal)))
        val assessments = assessHypotheses(frame)
        val thread = assessments.first { it.hypothesis.id == "thread_pool_exhaustion" }
        val database = assessments.first { it.hypothesis.id == "db_slow_query" }

        assertEquals(1, thread.matched.size)
        assertEquals("thread_pool.saturation.thread.queue", thread.matched.single())
        assertEquals(1, thread.score)
        assertEquals(0.37, thread.confidence, 1e-12)
        assertEquals(2, thread.missing.size)

        assertEquals(1, database.matched.size)
        assertEquals(1, database.score)
        assertEquals(0.52, database.confidence, 1e-12)

        // 排序：高分在前；db_slow_query 是新的 top。
        assertEquals("db_slow_query", assessments.first().hypothesis.id)
    }

    @Test
    fun `反向证据扣分并降低置信度`() {
        val contradiction = DiagnosticSignal(
            layer = DiagnosticLayer.THREAD_POOL,
            kind = SignalKind.UTILIZATION,
            name = "thread.idle",
            state = ObservationState.OK,
        )
        val frame = buildDiagnosisFrame(incident(listOf(contradiction)))
        val thread = assessHypotheses(frame).first { it.hypothesis.id == "thread_pool_exhaustion" }
        assertEquals(-2, thread.score)
        assertEquals(0.05, thread.confidence, 1e-12)
        assertEquals(1, thread.contradicted.size)
    }

    @Test
    fun `按证据路由步骤与工具`() {
        val noSignals = buildDiagnosisFrame(incident())
        assertEquals("probe.thread_pool", routeStep(noSignals)?.id)
        assertEquals("thread_dump", routeTool(noSignals)?.tool)

        val databaseFrame = buildDiagnosisFrame(incident(listOf(dbSlowSignal)))
        assertEquals("probe.database", routeStep(databaseFrame)?.id)
        val route = routeTool(databaseFrame)!!
        assertEquals("db_slow_query", route.hypothesisId)
        assertEquals("pg_stat_statements", route.tool)
        assertEquals(ToolIntent.DISCRIMINATE, route.intent)
        assertTrue(route.reason.startsWith("db_slow_query confidence=0.52"))
    }

    @Test
    fun `没有精确绑定时退回层默认工具`() {
        val appSignal = DiagnosticSignal(
            layer = DiagnosticLayer.APPLICATION,
            kind = SignalKind.UTILIZATION,
            name = "app.cpu",
            state = ObservationState.CRITICAL,
        )
        val releaseSignal = DiagnosticSignal(
            layer = DiagnosticLayer.RELEASE,
            kind = SignalKind.ERRORS,
            name = "release.only_new_version",
            state = ObservationState.WARN,
        )
        val frame = buildDiagnosisFrame(incident(listOf(appSignal, releaseSignal)))
        assertEquals(0.54, selectHypothesis(frame)!!.confidence, 1e-12)
        val route = routeTool(frame)!!
        assertEquals("code_regression", route.hypothesisId)
        assertEquals("app_profile", route.tool)
        assertEquals(DiagnosticLayer.APPLICATION, route.layer)
    }

    @Test
    fun `弱假设与空绑定表的兜底`() {
        val frame = buildDiagnosisFrame(incident())
        val weak = routeTool(frame)!!
        assertEquals("thread_dump", weak.tool)
        assertNull(weak.hypothesisId)
        assertTrue(weak.reason.startsWith("weak hypothesis confidence=0.40"))
        assertNull(routeTool(frame, bindings = emptyList()))
    }

    @Test
    fun `校验捕获空层空假设无区分测试非只读与重复 id`() {
        val base = buildDiagnosisFrame(incident())
        val emptyLayers = base.copy(layers = emptyList())
        assertEquals(listOf("diagnosis requires at least one layer"), validateDiagnosisFrame(emptyLayers))

        val emptyHypotheses = base.copy(hypotheses = emptyList())
        assertEquals(
            listOf("diagnosis requires at least one hypothesis"),
            validateDiagnosisFrame(emptyHypotheses),
        )

        val noTest = base.copy(
            hypotheses = listOf(base.hypotheses[0].copy(discriminatingTest = "  ")),
        )
        assertEquals(
            listOf("hypothesis thread_pool_exhaustion lacks a discriminating test"),
            validateDiagnosisFrame(noTest),
        )

        val mutableStep = base.copy(
            steps = listOf(base.steps[0].copy(readOnly = false), base.steps[0]),
        )
        assertEquals(
            listOf("diagnostic steps must be read-only", "duplicate diagnostic step ids"),
            validateDiagnosisFrame(mutableStep),
        )

        val duplicated = base.copy(steps = listOf(base.steps[0], base.steps[0]))
        assertEquals(listOf("duplicate diagnostic step ids"), validateDiagnosisFrame(duplicated))
    }

    @Test
    fun `渲染 action plan 与 plan task`() {
        val frame = buildDiagnosisFrame(incident())
        val action = toActionPlan(frame)!!
        assertEquals("discriminate the incident cause at thread_pool", action.goal)
        assertEquals("diagnose:thread_pool", action.intent)
        assertEquals("read", action.risk)
        assertEquals(2, action.preconditions.size)

        val tasks = toPlanTasks(frame)
        assertEquals(7, tasks.size)
        assertEquals("probe.thread_pool", tasks[0].id)
        assertEquals(TaskActor.TOOL, tasks[0].actor)
        assertEquals(TaskStatus.PENDING, tasks[0].status)
        assertTrue(tasks[0].pre.isEmpty())
        assertEquals(listOf("probe.thread_pool"), tasks[1].pre)
        assertEquals(0, tasks[0].irreversibility)
        assertEquals("thread_pool utilization/saturation/error signals are known; " +
            "the next hypothesis is discriminated or explicitly left open", tasks[0].expect.check)
    }

    @Test
    fun `选择层给出理由`() {
        val databaseFrame = buildDiagnosisFrame(incident(listOf(dbSlowSignal)))
        val selection = selectLayer(databaseFrame)
        assertEquals(DiagnosticLayer.DATABASE, selection.layer)
        assertEquals("db_slow_query confidence=0.52 matched=database.latency.db.query_p99", selection.reason)
        assertEquals("no hypotheses", selectLayer(databaseFrame.copy(hypotheses = emptyList())).reason)
    }

    @Test
    fun `投影到 context AST 保留摘要与细节引用`() {
        val frame = buildDiagnosisFrame(incident(listOf(dbSlowSignal)))
        val context = toContextAst(frame, budgetTokens = 4000)
        assertNotNull(context.ast.node("diagnosis"))
        assertNotNull(context.ast.node("step.database"))
        assertTrue(context.ast.node("hyp.db_slow_query") != null)
        assertEquals(
            "database/latency/db.query_p99: warn",
            context.ast.node("signal.database.db.query_p99")?.summary,
        )
        assertEquals(0.6, context.ast.node("signal.database.db.query_p99")?.salience ?: 0.0, 0.0)
        assertNotNull(context.details.get("hyp.db_slow_query"))
        assertTrue(context.details.get("hyp.db_slow_query")!!.contains("\"matched\":["))
        assertTrue(context.view.nodes.any { it.id == "diagnosis" })
        assertTrue(context.view.tokens > 0)
        assertEquals("db_slow_query", context.top?.hypothesis?.id)

        assertEquals(ContextNodeKind.HYPOTHESIS, diagnosisNode("hyp.db_slow_query"))
        assertEquals(ContextNodeKind.OBSERVATION, diagnosisNode("signal.database.x"))
        assertEquals(ContextNodeKind.FRAME, diagnosisNode("step.database"))
    }
}
