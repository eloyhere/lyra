package com.eloykim.lyra.amp.protocol.evaluation

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import java.util.Locale

/**
 * `src/evaluation/labels.ts` 的忠实移植：标注导入与裁决。
 *
 * 刻意面向文件：人工标签可能来自表格、CSV 导出或小型 JSONL。任何标签被信任之前，审计
 * 报告覆盖、重复、分歧与两两 Cohen's kappa。
 */

data class Annotation(
    val caseId: String,
    val annotator: String,
    val label: String,
    val note: String? = null,
)

data class LabelCandidate(
    val caseId: String,
    val predicted: Double,
    val uncertainty: Double? = null,
)

data class LabelDuplicate(
    val caseId: String,
    val annotator: String,
    val count: Int,
)

data class LabelDisagreement(
    val caseId: String,
    val labels: Map<String, Int>,
)

data class PairwiseKappa(
    val left: String,
    val right: String,
    val kappa: Double,
    val common: Int,
)

data class LabelAudit(
    val cases: Int,
    val annotations: Int,
    val covered: Int,
    val coverage: Double,
    val missing: List<String>,
    val duplicates: List<LabelDuplicate>,
    val disagreements: List<LabelDisagreement>,
    val annotators: List<String>,
    val pairwiseKappa: List<PairwiseKappa>,
    val adjudicated: Map<String, String?>,
)

private fun fixed3(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

private fun splitCsvRecords(text: String): List<List<String>> {
    val records = ArrayList<List<String>>()
    var record = ArrayList<String>()
    val cell = StringBuilder()
    var quoted = false
    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            char == '"' -> {
                if (quoted && index + 1 < text.length && text[index + 1] == '"') {
                    cell.append('"')
                    index += 1
                } else {
                    quoted = !quoted
                }
            }

            char == ',' && !quoted -> {
                record.add(cell.toString())
                cell.setLength(0)
            }

            (char == '\n' || char == '\r') && !quoted -> {
                if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                    index += 1
                }
                record.add(cell.toString())
                if (record.any { it.trim().isNotEmpty() }) {
                    records.add(record)
                }
                record = ArrayList()
                cell.setLength(0)
            }

            else -> cell.append(char)
        }
        index += 1
    }
    if (cell.isNotEmpty() || record.isNotEmpty()) {
        record.add(cell.toString())
        if (record.any { it.trim().isNotEmpty() }) {
            records.add(record)
        }
    }
    return records
}

/**
 * CSV 表头：`caseId,annotator,label[,note]`，支持带引号的换行。
 */
fun importAnnotationsCsv(text: String): List<Annotation> {
    val records: List<List<String>> = splitCsvRecords(text.removePrefix("\uFEFF"))
    if (records.isEmpty()) {
        return emptyList()
    }
    val header: List<String> = records[0].map { it.trim() }
    fun indexOf(name: String): Int = header.indexOf(name)
    if (indexOf("caseId") < 0 || indexOf("annotator") < 0 || indexOf("label") < 0) {
        throw IllegalArgumentException("CSV header requires caseId, annotator, label")
    }
    return records.drop(1).mapIndexed { row, cells ->
        val caseId: String? = cells.getOrNull(indexOf("caseId"))?.trim()
        val annotator: String? = cells.getOrNull(indexOf("annotator"))?.trim()
        val label: String? = cells.getOrNull(indexOf("label"))?.trim()
        if (caseId == null || annotator == null || label == null) {
            throw IllegalArgumentException("CSV row ${row + 2} is incomplete")
        }
        val note: String? = if (indexOf("note") >= 0) cells.getOrNull(indexOf("note"))?.trim() else null
        Annotation(
            caseId = caseId,
            annotator = annotator,
            label = label,
            note = if (note.isNullOrEmpty()) null else note,
        )
    }
}

/**
 * 主动学习：优先选择预测概率接近 0.5 且不确定性高的候选。
 */
fun selectLabelCandidates(candidates: List<LabelCandidate>, budget: Int): List<LabelCandidate> {
    fun score(candidate: LabelCandidate): Double =
        (1 - Math.abs(candidate.predicted - 0.5) * 2) * 0.6 + (candidate.uncertainty ?: 0.0) * 0.4

    return candidates
        .sortedWith(compareByDescending<LabelCandidate> { score(it) })
        .take(Math.max(0, budget))
}

/** 只填补未解决的平局；已解决的条目保留多数标签。 */
fun resolveUnresolved(audit: LabelAudit, resolutions: Map<String, String>): Map<String, String?> {
    val result: MutableMap<String, String?> = LinkedHashMap(audit.adjudicated)
    for ((caseId, label) in resolutions) {
        if (result[caseId] != null) {
            continue
        }
        result[caseId] = label
    }
    return result
}

/** 在用于校准之前拒绝不合格的标注批次。 */
fun requireLabelCoverage(audit: LabelAudit, minCoverage: Double = 0.9, maxDuplicates: Int = 0) {
    if (audit.coverage < minCoverage) {
        throw IllegalArgumentException("label coverage ${fixed3(audit.coverage)} < $minCoverage")
    }
    if (audit.duplicates.size > maxDuplicates) {
        throw IllegalArgumentException("duplicate annotations: ${audit.duplicates.size}")
    }
}

/**
 * 解析标注：数组，或 JSONL 文本（模板字符串输入在参考实现里被当成文本）。
 */
fun importAnnotations(input: JsonValue): List<Annotation> {
    val rows: List<JsonValue> = when (input) {
        is JsonValue.Arr -> input.items
        is JsonValue.Str -> input.value
            .split(Regex("\r?\n"))
            .filter { it.trim().isNotEmpty() }
            .map { JsonParser.parse(it) }

        else -> throw IllegalArgumentException("annotations must be an array or JSONL text")
    }
    return rows.mapIndexed { index, row ->
        val value: JsonValue.Obj = row as? JsonValue.Obj
            ?: throw IllegalArgumentException("annotation $index requires caseId, annotator and label")
        val caseId: String = (value.fields["caseId"] as? JsonValue.Str)?.value
            ?: throw IllegalArgumentException("annotation $index requires caseId, annotator and label")
        val annotator: String = (value.fields["annotator"] as? JsonValue.Str)?.value
            ?: throw IllegalArgumentException("annotation $index requires caseId, annotator and label")
        val label: String = (value.fields["label"] as? JsonValue.Str)?.value
            ?: throw IllegalArgumentException("annotation $index requires caseId, annotator and label")
        val note: String? = (value.fields["note"] as? JsonValue.Str)?.value
        Annotation(caseId = caseId, annotator = annotator, label = label, note = note)
    }
}

/** JSONL 便捷入口；非法行由 [JsonParser] 抛出。 */
fun importAnnotationsJsonl(text: String): List<Annotation> =
    importAnnotations(JsonValue.Str(text))

private fun pairKappa(
    caseIds: List<String>,
    annotations: List<Annotation>,
    left: String,
    right: String,
): PairwiseKappa {
    val common = ArrayList<Pair<String, String>>()
    for (caseId in caseIds) {
        val leftLabel: String? = annotations.firstOrNull {
            it.caseId == caseId && it.annotator == left
        }?.label
        val rightLabel: String? = annotations.firstOrNull {
            it.caseId == caseId && it.annotator == right
        }?.label
        if (leftLabel != null && rightLabel != null) {
            common.add(leftLabel to rightLabel)
        }
    }
    if (common.isEmpty()) {
        return PairwiseKappa(left = left, right = right, kappa = 1.0, common = 0)
    }
    return PairwiseKappa(
        left = left,
        right = right,
        kappa = cohenKappa(common.map { it.first }, common.map { it.second }),
        common = common.size,
    )
}

fun auditLabels(caseIds: List<String>, annotations: List<Annotation>): LabelAudit {
    val ids: Set<String> = caseIds.toSet()
    val annotators: List<String> = annotations.map { it.annotator }.distinct().sorted()

    val counts = LinkedHashMap<String, Int>()
    for (annotation in annotations) {
        val key = "${annotation.caseId}\u0000${annotation.annotator}"
        counts[key] = (counts[key] ?: 0) + 1
    }
    val duplicates: List<LabelDuplicate> = counts.entries
        .filter { it.value > 1 }
        .map { entry ->
            LabelDuplicate(
                caseId = entry.key.substringBefore('\u0000'),
                annotator = entry.key.substringAfter('\u0000'),
                count = entry.value,
            )
        }

    val missing: List<String> = caseIds.filter { caseId -> annotations.none { it.caseId == caseId } }
    val disagreements: List<LabelDisagreement> = caseIds.mapNotNull { caseId ->
        val labels = LinkedHashMap<String, Int>()
        for (annotation in annotations.filter { it.caseId == caseId }) {
            labels[annotation.label] = (labels[annotation.label] ?: 0) + 1
        }
        if (labels.size > 1) LabelDisagreement(caseId, labels) else null
    }

    val adjudicated = LinkedHashMap<String, String?>()
    for (caseId in caseIds) {
        val countsForCase = LinkedHashMap<String, Int>()
        for (annotation in annotations.filter { it.caseId == caseId }) {
            countsForCase[annotation.label] = (countsForCase[annotation.label] ?: 0) + 1
        }
        val ranked: List<Map.Entry<String, Int>> = countsForCase.entries
            .sortedByDescending { it.value }
        adjudicated[caseId] = when {
            ranked.isEmpty() -> null
            ranked.size > 1 && ranked[0].value == ranked[1].value -> null
            else -> ranked[0].key
        }
    }

    val pairwise = ArrayList<PairwiseKappa>()
    for (left in annotators.indices) {
        for (right in left + 1 until annotators.size) {
            pairwise.add(
                pairKappa(caseIds, annotations, annotators[left], annotators[right]),
            )
        }
    }

    val covered: Int = caseIds.count { caseId -> annotations.any { it.caseId == caseId } }
    return LabelAudit(
        cases = caseIds.size,
        annotations = annotations.count { it.caseId in ids },
        covered = covered,
        coverage = if (caseIds.isEmpty()) 1.0 else covered.toDouble() / caseIds.size,
        missing = missing,
        duplicates = duplicates,
        disagreements = disagreements,
        annotators = annotators,
        pairwiseKappa = pairwise,
        adjudicated = adjudicated,
    )
}
