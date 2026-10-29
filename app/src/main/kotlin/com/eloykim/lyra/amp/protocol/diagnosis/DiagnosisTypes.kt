package com.eloykim.lyra.amp.protocol.diagnosis

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj

/**
 * `src/diagnosis/types.ts` 的忠实移植：诊断 frame 是对故障系统的有界、只读调查。
 *
 * 它刻意独立于运行时，可以被渲染成 Plan、Action 或 prompt，但不执行任何东西。
 */

enum class DiagnosticLayer(val wire: String) {
    THREAD_POOL("thread_pool"),
    CONNECTION_POOL("connection_pool"),
    CONNECT("connect"),
    DATABASE("database"),
    QUEUE("queue"),
    APPLICATION("application"),
    RELEASE("release"),
    ;

    companion object {
        fun fromWire(value: String): DiagnosticLayer =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 diagnostic layer: $value")
    }
}

enum class SignalKind(val wire: String) {
    UTILIZATION("utilization"),
    SATURATION("saturation"),
    ERRORS("errors"),
    LATENCY("latency"),
    ;

    companion object {
        fun fromWire(value: String): SignalKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 signal kind: $value")
    }
}

enum class ObservationState(val wire: String) {
    OK("ok"),
    WARN("warn"),
    CRITICAL("critical"),
    ;

    companion object {
        fun fromWire(value: String): ObservationState =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 observation state: $value")
    }
}

data class DiagnosticSignal(
    val layer: DiagnosticLayer,
    val kind: SignalKind,
    val name: String,
    val state: ObservationState,
    val value: Double? = null,
    val note: String? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "layer" to JsonValue.Str(layer.wire),
        "kind" to JsonValue.Str(kind.wire),
        "name" to JsonValue.Str(name),
        "state" to JsonValue.Str(state.wire),
        "value" to value?.let { jsonNumber(it) },
        "note" to note?.let { JsonValue.Str(it) },
    )
}

data class SignalPattern(
    val layer: DiagnosticLayer,
    val kind: SignalKind? = null,
    val name: String? = null,
)

data class Hypothesis(
    val id: String,
    val cause: String,
    val layer: DiagnosticLayer,
    val supports: List<SignalPattern>,
    /** 把本原因与邻居区分开的检查。 */
    val discriminatingTest: String,
    val baseConfidence: Double,
    val contradicts: List<SignalPattern>? = null,
    /** 根因证据比下游饱和症状更有分量。 */
    val rootWeight: Double? = null,
)

data class HypothesisAssessment(
    val hypothesis: Hypothesis,
    val score: Int,
    val confidence: Double,
    val matched: List<String>,
    val missing: List<String>,
    val contradicted: List<String>,
)

data class DiagnosticStep(
    val id: String,
    val layer: DiagnosticLayer,
    val intent: String,
    val probe: String,
    val readOnly: Boolean = true,
    val successCriteria: List<String>,
    val stopIf: String? = null,
)

data class IncidentInput(
    val id: String,
    val summary: String,
    val symptoms: List<String>,
    val signals: List<DiagnosticSignal>? = null,
    val recentChanges: List<String>? = null,
    val constraints: List<String>? = null,
    /** 可选收窄；缺省为全部层。 */
    val layers: List<DiagnosticLayer>? = null,
)

enum class DiagnosticPhase(val wire: String) {
    FRAMING("framing"),
    OBSERVING("observing"),
    DISCRIMINATING("discriminating"),
    DECIDING("deciding"),
    VERIFYING("verifying"),
    ;
}

data class DiagnosticFrame(
    val id: String,
    val incident: IncidentInput,
    val layers: List<DiagnosticLayer>,
    val hypotheses: List<Hypothesis>,
    val steps: List<DiagnosticStep>,
    val phase: DiagnosticPhase,
    val selected: String? = null,
)

/** USE 风格的层顺序；也是 checklist 的默认顺序。 */
val LayerOrder: List<DiagnosticLayer> = DiagnosticLayer.entries.toList()

fun clamp01(value: Double): Double = Math.max(0.0, Math.min(1.0, value))
