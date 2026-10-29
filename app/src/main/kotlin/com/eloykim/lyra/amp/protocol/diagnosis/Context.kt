package com.eloykim.lyra.amp.protocol.diagnosis

import com.eloykim.lyra.amp.protocol.context.ContextAst
import com.eloykim.lyra.amp.protocol.context.ContextAstEdge
import com.eloykim.lyra.amp.protocol.context.ContextAstNode
import com.eloykim.lyra.amp.protocol.context.ContextAstView
import com.eloykim.lyra.amp.protocol.context.ContextEdgeKind
import com.eloykim.lyra.amp.protocol.context.ContextNodeKind
import com.eloykim.lyra.amp.protocol.context.MemoryDetailStore
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import java.util.Locale

/**
 * `src/diagnosis/context.ts` 的忠实移植：把诊断 frame 投影进 context AST。
 *
 * incident -> 层步骤 -> signals/hypotheses。摘要留在 view 里；原始 signal/probe 负载
 * 放在本地 ref 之后。
 *
 * ## 绑定
 *
 * - `ContextAst` / `MemoryDetailStore` / `ContextAstView` 复用已移植的 `context` 包。
 * - 参考实现用 `JSON.stringify` 生成 detail 原始文本（保留属性插入顺序、无空白）。这里
 *   提供私有 [jsonStringify] 复刻该行为；`CanonicalJson.encode` 会排序键，会改变
 *   `detailTokens` 的估算，所以不能替代。
 */

data class DiagnosisContext(
    val ast: ContextAst,
    val details: MemoryDetailStore,
    val view: ContextAstView,
    val top: HypothesisAssessment?,
)

private fun fixed2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

/** `JSON.stringify` 的紧凑编码：保留插入顺序，数字按 JS 规则格式化。 */
private fun jsonStringify(value: JsonValue): String = buildString { appendJson(this, value) }

private fun appendJson(out: StringBuilder, value: JsonValue) {
    when (value) {
        is JsonValue.Null -> out.append("null")
        is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
        is JsonValue.Str -> out.append(CanonicalJson.encode(value))
        is JsonValue.Num -> out.append(CanonicalJson.formatNumber(value.value))
        is JsonValue.Arr -> {
            out.append('[')
            value.items.forEachIndexed { index, item ->
                if (index > 0) {
                    out.append(',')
                }
                appendJson(out, item)
            }
            out.append(']')
        }

        is JsonValue.Obj -> {
            out.append('{')
            var first = true
            for ((key, item) in value.fields) {
                if (!first) {
                    out.append(',')
                }
                first = false
                out.append(CanonicalJson.encode(JsonValue.Str(key)))
                out.append(':')
                appendJson(out, item)
            }
            out.append('}')
        }
    }
}

/** 参考的 `Math.ceil(raw.length / 4)`：`String.length` 与 JS 一样按 UTF-16 码元计。 */
private fun detailTokens(raw: String): Int = (raw.length + 3) / 4

/**
 * 把 DiagnosticFrame 投影成 context AST。
 *
 * 层节点 id 使用 wire 名称（`step.thread_pool`），与参考实现的模板字符串一致。
 */
fun toContextAst(frame: DiagnosticFrame, budgetTokens: Int = 1200): DiagnosisContext {
    val ast = ContextAst()
    val details = MemoryDetailStore()
    var order = 0
    val layersText: String = frame.layers.joinToString(",") { it.wire }
    ast.add(
        ContextAstNode(
            id = "diagnosis",
            kind = ContextNodeKind.GOAL,
            summary = "${frame.incident.summary} | phase=${frame.phase.wire} | layers=$layersText",
            order = order++,
            salience = 1.0,
        ),
    )
    for (step in frame.steps) {
        val layerId = "step.${step.layer.wire}"
        ast.add(
            ContextAstNode(
                id = layerId,
                parentId = "diagnosis",
                kind = ContextNodeKind.FRAME,
                summary = "inspect ${step.layer.wire}: ${step.probe}",
                order = order++,
                salience = 0.8,
            ),
        )
        val signals: List<DiagnosticSignal> =
            (frame.incident.signals ?: emptyList()).filter { it.layer == step.layer }
        for (signal in signals) {
            val id = "signal.${step.layer.wire}.${signal.name}"
            val raw: String = jsonStringify(signal.toJson())
            val ref: String = details.put(id, raw)
            ast.add(
                ContextAstNode(
                    id = id,
                    parentId = layerId,
                    kind = ContextNodeKind.OBSERVATION,
                    summary = "${signal.layer.wire}/${signal.kind.wire}/${signal.name}: ${signal.state.wire}",
                    detailRef = ref,
                    detailTokens = detailTokens(raw),
                    order = order++,
                    salience = if (signal.state == ObservationState.CRITICAL) 0.95 else 0.6,
                ),
            )
        }
    }
    val ranked: List<HypothesisAssessment> = assessHypotheses(frame)
    for (assessment in ranked) {
        val id = "hyp.${assessment.hypothesis.id}"
        val raw: String = jsonStringify(
            jsonObj(
                "cause" to JsonValue.Str(assessment.hypothesis.cause),
                "matched" to JsonValue.Arr(assessment.matched.map { JsonValue.Str(it) }),
                "missing" to JsonValue.Arr(assessment.missing.map { JsonValue.Str(it) }),
                "contradicted" to JsonValue.Arr(assessment.contradicted.map { JsonValue.Str(it) }),
            ),
        )
        val ref: String = details.put(id, raw)
        ast.add(
            ContextAstNode(
                id = id,
                parentId = "diagnosis",
                kind = ContextNodeKind.HYPOTHESIS,
                summary = "${assessment.hypothesis.layer.wire}: ${assessment.hypothesis.cause} " +
                    "(confidence=${fixed2(assessment.confidence)})",
                detailRef = ref,
                detailTokens = detailTokens(raw),
                order = order++,
                salience = assessment.confidence,
            ),
        )
    }
    for (index in 0 until frame.steps.size - 1) {
        ast.link(
            ContextAstEdge(
                from = "step.${frame.steps[index].layer.wire}",
                to = "step.${frame.steps[index + 1].layer.wire}",
                kind = ContextEdgeKind.BEFORE,
            ),
        )
    }
    frame.steps.firstOrNull()?.let { firstStep ->
        ast.link(
            ContextAstEdge(
                from = "diagnosis",
                to = "step.${firstStep.layer.wire}",
                kind = ContextEdgeKind.BEFORE,
            ),
        )
    }
    return DiagnosisContext(
        ast = ast,
        details = details,
        view = ast.view(budgetTokens),
        top = selectHypothesis(frame),
    )
}

fun diagnosisNode(id: String): ContextNodeKind = when {
    id.startsWith("hyp.") -> ContextNodeKind.HYPOTHESIS
    id.startsWith("signal.") -> ContextNodeKind.OBSERVATION
    else -> ContextNodeKind.FRAME
}
