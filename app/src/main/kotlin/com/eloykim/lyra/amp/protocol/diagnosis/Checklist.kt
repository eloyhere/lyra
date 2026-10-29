package com.eloykim.lyra.amp.protocol.diagnosis

/**
 * `src/diagnosis/checklist.ts` 的忠实移植：USE 风格检查单。
 *
 * 每一层都问利用率、饱和与错误；延迟被拆开问，因为“慢”不是一个层。
 */

data class LayerCheck(
    val layer: DiagnosticLayer,
    val question: String,
    val signals: List<SignalKind>,
    val probe: String,
)

val LayerChecks: Map<DiagnosticLayer, LayerCheck> = linkedMapOf(
    DiagnosticLayer.THREAD_POOL to LayerCheck(
        layer = DiagnosticLayer.THREAD_POOL,
        question = "Are worker threads exhausted, blocked, or idle?",
        signals = listOf(SignalKind.UTILIZATION, SignalKind.SATURATION, SignalKind.ERRORS),
        probe = "active/max threads, run queue, reject count; one thread dump with top blocking frames",
    ),
    DiagnosticLayer.CONNECTION_POOL to LayerCheck(
        layer = DiagnosticLayer.CONNECTION_POOL,
        question = "Is the pool exhausted, leaking, or waiting on slow work?",
        signals = listOf(SignalKind.UTILIZATION, SignalKind.SATURATION, SignalKind.LATENCY),
        probe = "active/idle/pending, checkout wait P99, timeout count, held-time distribution",
    ),
    DiagnosticLayer.CONNECT to LayerCheck(
        layer = DiagnosticLayer.CONNECT,
        question = "Is a new connection slow to establish?",
        signals = listOf(SignalKind.LATENCY, SignalKind.ERRORS),
        probe = "TCP RTT, TLS handshake, auth, DNS, connect P99, new-connection/churn rate",
    ),
    DiagnosticLayer.DATABASE to LayerCheck(
        layer = DiagnosticLayer.DATABASE,
        question = "Is the database slow, locked, or out of connections?",
        signals = listOf(
            SignalKind.UTILIZATION,
            SignalKind.SATURATION,
            SignalKind.LATENCY,
            SignalKind.ERRORS,
        ),
        probe = "pg_stat_statements top SQL, EXPLAIN ANALYZE, pg_locks blocking chain, " +
            "wait events, sessions/max_connections",
    ),
    DiagnosticLayer.QUEUE to LayerCheck(
        layer = DiagnosticLayer.QUEUE,
        question = "Is work arriving faster than it is consumed?",
        signals = listOf(SignalKind.SATURATION, SignalKind.LATENCY, SignalKind.ERRORS),
        probe = "consumer lag, consume rate, retry/DLQ, partition skew, active consumers",
    ),
    DiagnosticLayer.APPLICATION to LayerCheck(
        layer = DiagnosticLayer.APPLICATION,
        question = "Is application code or GC burning CPU / holding locks?",
        signals = listOf(SignalKind.UTILIZATION, SignalKind.SATURATION, SignalKind.ERRORS),
        probe = "CPU, GC pauses, thread dump, lock contention, dependency spans",
    ),
    DiagnosticLayer.RELEASE to LayerCheck(
        layer = DiagnosticLayer.RELEASE,
        question = "Is the failure correlated with a specific version?",
        signals = listOf(SignalKind.ERRORS, SignalKind.LATENCY),
        probe = "per-version error/latency split, diff, canary cohort, deploy timeline",
    ),
)

fun stepFor(layer: DiagnosticLayer): DiagnosticStep {
    val check: LayerCheck = LayerChecks.getValue(layer)
    return DiagnosticStep(
        id = "probe.${layer.wire}",
        layer = layer,
        intent = "inspect ${layer.wire}",
        probe = check.probe,
        readOnly = true,
        successCriteria = listOf(
            "${layer.wire} utilization/saturation/error signals are known",
            "the next hypothesis is discriminated or explicitly left open",
        ),
        stopIf = "a higher layer fully explains the symptoms and the lower layer is healthy",
    )
}

fun stepsFor(layers: List<DiagnosticLayer>): List<DiagnosticStep> = layers.map { stepFor(it) }
