package com.eloykim.lyra.attention

import kotlin.math.sqrt

/**
 * 文本向量化接口。
 *
 * 生产实现将来接 `bge-small-zh-v1.5`（ONNX Runtime Mobile）；
 * 现在先给一个**词法**实现作为基线：字符 2-gram 哈希 + L2 归一化 + 余弦。
 * 它的语义能力有限，但零依赖、零下载、可离线，且能把「麦当劳」和「微信支付」分开。
 */
interface TextEmbedder {
    val name: String
    suspend fun embed(text: String): FloatArray
}

class LexicalEmbedder(private val dims: Int = 256) : TextEmbedder {

    override val name: String = "lexical-2gram"

    override suspend fun embed(text: String): FloatArray {
        val vector = FloatArray(dims)
        val normalized = text.lowercase().replace(WHITESPACE, "")
        if (normalized.isEmpty()) return vector

        val grams = if (normalized.length < 2) {
            listOf(normalized)
        } else {
            (0..normalized.length - 2).map { normalized.substring(it, it + 2) }
        }
        grams.forEach { gram ->
            vector[Math.floorMod(gram.hashCode(), dims)] += 1f
        }

        var sum = 0.0
        vector.forEach { sum += it * it }
        val length = sqrt(sum).toFloat()
        if (length > 0f) {
            for (index in vector.indices) vector[index] /= length
        }
        return vector
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/**
 * goalFit 打分：任务描述 vs 候选 UI 文本。
 *
 * - 单线程串行（[LocalModelPool]），不阻塞主线程；
 * - LRU 缓存向量，UI 文案高度重复，命中率预期很高；
 * - 余弦相似度经分段映射压到 [0,1]，避免「0.9 相似但无关」直接进焦点集。
 */
class GoalFitScorer(
    private val embedder: TextEmbedder = LexicalEmbedder(),
    private val cacheSize: Int = 256,
) {

    private val cache = object : LinkedHashMap<String, FloatArray>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
            size > cacheSize
    }

    val source: String get() = embedder.name

    suspend fun score(task: String, candidates: List<String>): List<Float> {
        if (candidates.isEmpty()) return emptyList()
        val taskVector = vectorOf(task)
        return candidates.map { candidate ->
            val candidateVector = vectorOf(candidate)
            calibrate(cosine(taskVector, candidateVector))
        }
    }

    private suspend fun vectorOf(text: String): FloatArray {
        cache[text]?.let { return it }
        val vector = embedder.embed(text)
        cache[text] = vector
        return vector
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (index in a.indices) dot += a[index] * b[index]
        return dot.coerceIn(0f, 1f)
    }

    /**
     * 相似度 → goalFit 的分段映射。
     *
     * 词法相似度普遍偏低（0.1–0.4 才算相关），直接当 goalFit 会让所有候选都进不了
     * 焦点；线性拉伸到 [0,1] 又会让噪声进焦点。这里用三段：
     *   < 0.08  → 0（基本无关）
     *   0.08–0.45 → 线性拉到 0–0.85
     *   > 0.45 → 0.85–1.0
     */
    private fun calibrate(similarity: Float): Float = when {
        similarity < 0.08f -> 0f
        similarity < 0.45f -> (similarity - 0.08f) / 0.37f * 0.85f
        else -> 0.85f + (similarity - 0.45f) / 0.55f * 0.15f
    }.coerceIn(0f, 1f)
}
