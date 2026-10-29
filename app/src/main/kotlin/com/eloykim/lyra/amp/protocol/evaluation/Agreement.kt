package com.eloykim.lyra.amp.protocol.evaluation

/**
 * `src/evaluation/agreement.ts` 的忠实移植：标注者间一致性指标。
 *
 * 这些指标用于人工标注评估，不是模型置信度；与 ECE/Brier 分开。
 */

private fun requireSameLength(left: List<String>, right: List<String>) {
    if (left.size != right.size) {
        throw IllegalArgumentException("label arrays must have equal length")
    }
}

/** 两个标注序列在同一批条目上的简单一致率。 */
fun agreementRate(left: List<String>, right: List<String>): Double {
    requireSameLength(left, right)
    if (left.isEmpty()) {
        return 1.0
    }
    var matches = 0
    for (index in left.indices) {
        if (left[index] == right[index]) {
            matches += 1
        }
    }
    return matches.toDouble() / left.size
}

/** 两个评分者对同一批分类标签的 Cohen's kappa。 */
fun cohenKappa(left: List<String>, right: List<String>): Double {
    requireSameLength(left, right)
    if (left.isEmpty()) {
        return 1.0
    }
    val observed: Double = agreementRate(left, right)
    val categories: List<String> = (left + right).distinct()
    var expected = 0.0
    for (category in categories) {
        val leftRate: Double = left.count { it == category }.toDouble() / left.size
        val rightRate: Double = right.count { it == category }.toDouble() / right.size
        expected += leftRate * rightRate
    }
    if (expected == 1.0) {
        return if (observed == 1.0) 1.0 else 0.0
    }
    return (observed - expected) / (1.0 - expected)
}

/** n 个条目 x m 个评分者的 Fleiss' kappa。 */
fun fleissKappa(matrix: List<List<String>>): Double {
    if (matrix.isEmpty()) {
        return 1.0
    }
    val raters: Int = matrix[0].size
    if (raters == 0 || matrix.any { it.size != raters }) {
        throw IllegalArgumentException("ragged rating matrix")
    }
    val categories: List<String> = matrix.flatten().distinct()
    val total: Int = matrix.size * raters
    val counts = LinkedHashMap<String, Int>()
    for (row in matrix) {
        for (label in row) {
            counts[label] = (counts[label] ?: 0) + 1
        }
    }
    val observed: Double = matrix.sumOf { row ->
        var agreements = 0
        for (category in categories) {
            val count: Int = row.count { it == category }
            agreements += count * (count - 1)
        }
        agreements.toDouble() / (raters * (raters - 1)).toDouble()
    } / matrix.size
    val expected: Double = categories.sumOf { category ->
        val rate: Double = (counts[category] ?: 0).toDouble() / total
        rate * rate
    }
    if (expected == 1.0) {
        return if (observed == 1.0) 1.0 else 0.0
    }
    return (observed - expected) / (1.0 - expected)
}
