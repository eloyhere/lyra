package com.eloykim.lyra.amp.protocol.diagnosis

import com.eloykim.lyra.amp.protocol.goal.Expectation
import com.eloykim.lyra.amp.protocol.goal.PlanTask
import com.eloykim.lyra.amp.protocol.goal.TaskActor
import com.eloykim.lyra.amp.protocol.goal.TaskStatus
import java.util.Locale

/**
 * `src/diagnosis/frame.ts` 的忠实移植：诊断 frame 的构造、观察与假设评估。
 *
 * ## 绑定
 *
 * - `PlanTask` 复用已移植的 `goal.PlanTask`（参考实现同样把诊断步骤渲染成 PlanTask）。
 * - `ActionPlan` 来自 `src/runtime/action.ts`，该包当前没有对应移植，因此这里定义
 *   [ActionPlan] 作为窄局部模型，只保留参考 `toActionPlan` 实际写入的字段。将来 runtime
 *   包提供 ActionPlan 后，用字段适配替换即可。
 */

/**
 * 窄局部模型：`src/runtime/action.ts#ActionPlan` 的诊断子集。
 * `risk` 在参考里是 `"read"` 字面量，这里保留字符串形式。
 */
data class ActionPlan(
    val goal: String,
    val intent: String,
    val expectedEffect: String,
    val preconditions: List<String>,
    val successCriteria: List<String>,
    val risk: String,
)

/** [selectLayer] 的结果：选中的层与明确理由。 */
data class LayerSelection(
    val layer: DiagnosticLayer?,
    val reason: String,
)

private fun pattern(
    layer: DiagnosticLayer,
    kind: SignalKind? = null,
    name: String? = null,
): SignalPattern = SignalPattern(layer = layer, kind = kind, name = name)

val DefaultHypotheses: List<Hypothesis> = listOf(
    Hypothesis(
        id = "thread_pool_exhaustion",
        cause = "worker threads are exhausted or blocked",
        layer = DiagnosticLayer.THREAD_POOL,
        supports = listOf(
            pattern(DiagnosticLayer.THREAD_POOL, SignalKind.SATURATION, "thread.queue"),
            pattern(DiagnosticLayer.THREAD_POOL, SignalKind.UTILIZATION, "thread.active_max"),
            pattern(DiagnosticLayer.THREAD_POOL, SignalKind.ERRORS, "thread.reject"),
        ),
        contradicts = listOf(pattern(DiagnosticLayer.THREAD_POOL, SignalKind.UTILIZATION, "thread.idle")),
        discriminatingTest = "thread dump: are active threads blocked on a pool, a lock, or CPU?",
        baseConfidence = 0.25,
        rootWeight = 0.0,
    ),
    Hypothesis(
        id = "connection_pool_exhaustion",
        cause = "connection pool is exhausted or leaking",
        layer = DiagnosticLayer.CONNECTION_POOL,
        supports = listOf(
            pattern(DiagnosticLayer.CONNECTION_POOL, SignalKind.SATURATION, "pool.pending"),
            pattern(DiagnosticLayer.CONNECTION_POOL, SignalKind.UTILIZATION, "pool.active_max"),
            pattern(DiagnosticLayer.CONNECTION_POOL, SignalKind.LATENCY, "pool.checkout_wait"),
        ),
        contradicts = listOf(pattern(DiagnosticLayer.CONNECTION_POOL, SignalKind.UTILIZATION, "pool.idle")),
        discriminatingTest = "checkout wait vs active vs held-time: pool exhaustion, leak, or slow owner?",
        baseConfidence = 0.25,
        rootWeight = 0.0,
    ),
    Hypothesis(
        id = "connect_latency",
        cause = "new connections are slow to establish",
        layer = DiagnosticLayer.CONNECT,
        supports = listOf(
            pattern(DiagnosticLayer.CONNECT, SignalKind.LATENCY, "connect.p99"),
            pattern(DiagnosticLayer.CONNECT, SignalKind.ERRORS, "connect.error"),
            pattern(DiagnosticLayer.CONNECT, SignalKind.LATENCY, "tls.handshake"),
        ),
        contradicts = listOf(pattern(DiagnosticLayer.CONNECT, SignalKind.LATENCY, "connect.ok")),
        discriminatingTest = "split connect into TCP RTT, TLS, auth, DNS; is pool wait high while active is low?",
        baseConfidence = 0.25,
        rootWeight = 0.1,
    ),
    Hypothesis(
        id = "db_slow_query",
        cause = "database queries are slow",
        layer = DiagnosticLayer.DATABASE,
        supports = listOf(
            pattern(DiagnosticLayer.DATABASE, SignalKind.LATENCY, "db.query_p99"),
            pattern(DiagnosticLayer.DATABASE, SignalKind.UTILIZATION, "db.cpu"),
            pattern(DiagnosticLayer.DATABASE, SignalKind.SATURATION, "db.temp_spill"),
        ),
        contradicts = listOf(pattern(DiagnosticLayer.DATABASE, SignalKind.LATENCY, "db.query_ok")),
        discriminatingTest = "EXPLAIN ANALYZE: seq scan, temp spill, bad row estimate, or missing index?",
        baseConfidence = 0.25,
        rootWeight = 0.15,
    ),
    Hypothesis(
        id = "db_lock",
        cause = "database locks are blocking progress",
        layer = DiagnosticLayer.DATABASE,
        supports = listOf(
            pattern(DiagnosticLayer.DATABASE, SignalKind.SATURATION, "db.lock_wait"),
            pattern(DiagnosticLayer.DATABASE, SignalKind.SATURATION, "db.blocking_chain"),
        ),
        contradicts = listOf(pattern(DiagnosticLayer.DATABASE, SignalKind.SATURATION, "db.no_lock_wait")),
        discriminatingTest = "pg_locks + pg_blocking_pids: who blocks whom, and for how long?",
        baseConfidence = 0.25,
        rootWeight = 0.15,
    ),
    Hypothesis(
        id = "queue_backlog",
        cause = "consumers cannot keep up with the queue",
        layer = DiagnosticLayer.QUEUE,
        supports = listOf(
            pattern(DiagnosticLayer.QUEUE, SignalKind.SATURATION, "queue.lag"),
            pattern(DiagnosticLayer.QUEUE, SignalKind.LATENCY, "queue.consume_rate"),
            pattern(DiagnosticLayer.QUEUE, SignalKind.ERRORS, "queue.retry"),
        ),
        contradicts = listOf(pattern(DiagnosticLayer.QUEUE, SignalKind.SATURATION, "queue.drained")),
        discriminatingTest = "lag with idle consumers (blocked) vs busy consumers (capacity)?",
        baseConfidence = 0.2,
        rootWeight = 0.1,
    ),
    Hypothesis(
        id = "code_regression",
        cause = "application code or GC is the bottleneck",
        layer = DiagnosticLayer.APPLICATION,
        supports = listOf(
            pattern(DiagnosticLayer.APPLICATION, SignalKind.UTILIZATION, "app.cpu"),
            pattern(DiagnosticLayer.APPLICATION, SignalKind.SATURATION, "app.gc_pause"),
            pattern(DiagnosticLayer.RELEASE, SignalKind.ERRORS, "release.only_new_version"),
        ),
        contradicts = listOf(pattern(DiagnosticLayer.DATABASE, SignalKind.LATENCY, "db.query_p99")),
        discriminatingTest = "DB fast + app CPU/GC high + blocked stack in app code?",
        baseConfidence = 0.2,
        rootWeight = 0.1,
    ),
)

private fun fixed2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

private fun patternMatches(pattern: SignalPattern, signal: DiagnosticSignal): Boolean {
    if (pattern.layer != signal.layer) {
        return false
    }
    if (pattern.kind != null && pattern.kind != signal.kind) {
        return false
    }
    if (pattern.name != null && signal.name != pattern.name && !signal.name.startsWith("${pattern.name}.")) {
        return false
    }
    return true
}

private fun label(pattern: SignalPattern): String =
    listOfNotNull(pattern.layer.wire, pattern.kind?.wire, pattern.name).joinToString(".")

fun assessHypotheses(frame: DiagnosticFrame): List<HypothesisAssessment> =
    frame.hypotheses
        .map { hypothesis ->
            val matched: List<String> = hypothesis.supports
                .filter { pattern -> frame.incident.signals?.any { patternMatches(pattern, it) } == true }
                .map(::label)
            val contradicted: List<String> = (hypothesis.contradicts ?: emptyList())
                .filter { pattern -> frame.incident.signals?.any { patternMatches(pattern, it) } == true }
                .map(::label)
            val missing: List<String> = hypothesis.supports
                .filter { pattern -> !matched.contains(label(pattern)) }
                .map(::label)
            val score: Int = matched.size - contradicted.size * 2
            HypothesisAssessment(
                hypothesis = hypothesis,
                score = score,
                confidence = clamp01(
                    hypothesis.baseConfidence +
                        matched.size * 0.12 +
                        (hypothesis.rootWeight ?: 0.0) -
                        contradicted.size * 0.2,
                ),
                matched = matched,
                missing = missing,
                contradicted = contradicted,
            )
        }
        .sortedWith(
            compareByDescending<HypothesisAssessment> { it.confidence }
                .thenByDescending { it.score },
        )

fun selectHypothesis(frame: DiagnosticFrame): HypothesisAssessment? = assessHypotheses(frame).firstOrNull()

fun buildDiagnosisFrame(incident: IncidentInput): DiagnosticFrame {
    val layers: List<DiagnosticLayer> = incident.layers ?: LayerOrder
    return DiagnosticFrame(
        id = "diag.${incident.id}",
        incident = incident,
        layers = layers.toList(),
        hypotheses = DefaultHypotheses.map { it.copy() },
        steps = stepsFor(layers),
        phase = if ((incident.signals?.size ?: 0) > 0) DiagnosticPhase.OBSERVING else DiagnosticPhase.FRAMING,
    )
}

/** 追加一次观察，但不修改原 frame。 */
fun observe(frame: DiagnosticFrame, signal: DiagnosticSignal): DiagnosticFrame = frame.copy(
    incident = frame.incident.copy(signals = (frame.incident.signals ?: emptyList()) + signal),
    phase = if (frame.phase == DiagnosticPhase.FRAMING) DiagnosticPhase.OBSERVING else frame.phase,
)

fun nextStep(frame: DiagnosticFrame): DiagnosticStep? = frame.steps.firstOrNull()

/**
 * 路由到与当前证据最匹配的步骤。静态层顺序只是兜底：强 DB 假设不能被路由到
 * `thread_pool`，只因为它在检查单里排第一。
 */
fun routeStep(frame: DiagnosticFrame, minConfidence: Double = 0.5): DiagnosticStep? {
    val top: HypothesisAssessment? = selectHypothesis(frame)
    if (top != null && top.confidence >= minConfidence) {
        val matched: DiagnosticStep? = frame.steps.firstOrNull { it.layer == top.hypothesis.layer }
        if (matched != null) {
            return matched
        }
    }
    return nextStep(frame)
}

fun validateDiagnosisFrame(frame: DiagnosticFrame): List<String> {
    val problems = ArrayList<String>()
    if (frame.layers.isEmpty()) {
        problems.add("diagnosis requires at least one layer")
    }
    if (frame.hypotheses.isEmpty()) {
        problems.add("diagnosis requires at least one hypothesis")
    }
    for (hypothesis in frame.hypotheses) {
        if (hypothesis.discriminatingTest.trim().isEmpty()) {
            problems.add("hypothesis ${hypothesis.id} lacks a discriminating test")
        }
    }
    if (frame.steps.any { it.readOnly != true }) {
        problems.add("diagnostic steps must be read-only")
    }
    if (frame.steps.map { it.id }.toSet().size != frame.steps.size) {
        problems.add("duplicate diagnostic step ids")
    }
    return problems
}

/** 把下一个诊断步骤渲染成有界、只读的 ActionPlan。 */
fun toActionPlan(frame: DiagnosticFrame): ActionPlan? {
    val step: DiagnosticStep = routeStep(frame) ?: return null
    return ActionPlan(
        goal = "discriminate the incident cause at ${step.layer.wire}",
        intent = "diagnose:${step.layer.wire}",
        expectedEffect = "evidence for or against ${step.layer.wire} hypotheses",
        preconditions = listOf(
            "diagnostic step is read-only",
            "no production mutation before discrimination",
        ),
        successCriteria = step.successCriteria,
        risk = "read",
    )
}

/** 把诊断步骤渲染成现有的 PlanTask（不可逆性全为 0）。 */
fun toPlanTasks(frame: DiagnosticFrame): List<PlanTask> =
    frame.steps.mapIndexed { index, step ->
        PlanTask(
            id = step.id,
            intent = step.intent,
            actor = TaskActor.TOOL,
            expect = Expectation(
                observation = step.probe,
                check = step.successCriteria.joinToString("; "),
            ),
            irreversibility = 0,
            status = TaskStatus.PENDING,
            pre = if (index == 0) emptyList() else listOf(frame.steps[index - 1].id),
        )
    }

/** 选择最能解释当前证据的层，并给出明确理由。 */
fun selectLayer(frame: DiagnosticFrame): LayerSelection {
    val top: HypothesisAssessment = selectHypothesis(frame) ?: return LayerSelection(null, "no hypotheses")
    val matched: String = top.matched.joinToString(",").ifEmpty { "none" }
    return LayerSelection(
        layer = top.hypothesis.layer,
        reason = "${top.hypothesis.id} confidence=${fixed2(top.confidence)} matched=$matched",
    )
}
