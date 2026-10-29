package com.eloykim.lyra.amp.protocol.diagnosis

import java.util.Locale

/**
 * `src/diagnosis/router.ts` 的忠实移植：工具路由（AST/诊断候选 -> 只读探针）。
 *
 * 这刻意不是执行器：它选择工具并解释理由；运行时仍会把它包进 Action/Operation 与审批
 * 策略。
 */

enum class ToolIntent(val wire: String) {
    INSPECT("inspect"),
    DISCRIMINATE("discriminate"),
    CONFIRM("confirm"),
    REFUTE("refute"),
    ;
}

enum class ToolProduces(val wire: String) {
    METRIC("metric"),
    TRACE("trace"),
    LOG("log"),
    LOCK("lock"),
    PROFILE("profile"),
    ;
}

data class ToolBinding(
    val layer: DiagnosticLayer,
    val tool: String,
    val intent: ToolIntent,
    val readOnly: Boolean = true,
    val produces: ToolProduces,
    val hypothesisId: String? = null,
)

data class ToolRoute(
    val tool: String,
    val layer: DiagnosticLayer,
    val hypothesisId: String? = null,
    val intent: ToolIntent,
    val readOnly: Boolean = true,
    val reason: String,
)

val DefaultToolBindings: List<ToolBinding> = listOf(
    ToolBinding(
        layer = DiagnosticLayer.THREAD_POOL,
        tool = "thread_dump",
        intent = ToolIntent.INSPECT,
        produces = ToolProduces.PROFILE,
    ),
    ToolBinding(
        layer = DiagnosticLayer.CONNECTION_POOL,
        tool = "pool_stats",
        intent = ToolIntent.INSPECT,
        produces = ToolProduces.METRIC,
    ),
    ToolBinding(
        layer = DiagnosticLayer.CONNECT,
        tool = "connect_stats",
        intent = ToolIntent.INSPECT,
        produces = ToolProduces.METRIC,
    ),
    ToolBinding(
        layer = DiagnosticLayer.DATABASE,
        hypothesisId = "db_lock",
        tool = "pg_locks",
        intent = ToolIntent.DISCRIMINATE,
        produces = ToolProduces.LOCK,
    ),
    ToolBinding(
        layer = DiagnosticLayer.DATABASE,
        hypothesisId = "db_slow_query",
        tool = "pg_stat_statements",
        intent = ToolIntent.DISCRIMINATE,
        produces = ToolProduces.TRACE,
    ),
    ToolBinding(
        layer = DiagnosticLayer.DATABASE,
        tool = "pg_stat_statements",
        intent = ToolIntent.INSPECT,
        produces = ToolProduces.TRACE,
    ),
    ToolBinding(
        layer = DiagnosticLayer.QUEUE,
        tool = "queue_lag",
        intent = ToolIntent.INSPECT,
        produces = ToolProduces.METRIC,
    ),
    ToolBinding(
        layer = DiagnosticLayer.APPLICATION,
        tool = "app_profile",
        intent = ToolIntent.INSPECT,
        produces = ToolProduces.PROFILE,
    ),
    ToolBinding(
        layer = DiagnosticLayer.RELEASE,
        tool = "version_diff",
        intent = ToolIntent.INSPECT,
        produces = ToolProduces.LOG,
    ),
)

private fun fixed2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

fun routeTool(
    frame: DiagnosticFrame,
    bindings: List<ToolBinding> = DefaultToolBindings,
    minConfidence: Double = 0.5,
): ToolRoute? {
    val top: HypothesisAssessment? = selectHypothesis(frame)
    if (top != null && top.confidence >= minConfidence) {
        val exact: List<ToolBinding> = bindings.filter {
            it.layer == top.hypothesis.layer && it.hypothesisId == top.hypothesis.id
        }
        val layer: List<ToolBinding> = bindings.filter {
            it.layer == top.hypothesis.layer && it.hypothesisId == null
        }
        val selected: ToolBinding? = exact.firstOrNull() ?: layer.firstOrNull()
        if (selected != null) {
            return ToolRoute(
                tool = selected.tool,
                layer = selected.layer,
                hypothesisId = top.hypothesis.id,
                intent = selected.intent,
                readOnly = true,
                reason = "${top.hypothesis.id} confidence=${fixed2(top.confidence)}; " +
                    "matched=${top.matched.joinToString(",").ifEmpty { "none" }}",
            )
        }
    }
    val fallback: ToolBinding = bindings.firstOrNull { it.layer == DiagnosticLayer.THREAD_POOL }
        ?: bindings.firstOrNull()
        ?: return null
    return ToolRoute(
        tool = fallback.tool,
        layer = fallback.layer,
        intent = fallback.intent,
        readOnly = true,
        reason = if (top == null) {
            "no hypothesis; start with thread dump"
        } else {
            "weak hypothesis confidence=${fixed2(top.confidence)}; start with thread dump"
        },
    )
}
