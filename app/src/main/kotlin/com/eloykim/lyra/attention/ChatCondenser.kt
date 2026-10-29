package com.eloykim.lyra.attention

import com.eloykim.lyra.amp.protocol.semantic.Sha256

/**
 * 聊天页面的条件压缩与剪枝。
 *
 * 规则与 AMP 的 Context Economy 对齐：
 * - **剪枝**产出的是「视图」，不改原会话；被丢弃的消息带 `reason` 与 `revisit_if`；
 * - **压缩**把较早的消息折叠成一段摘要，并用 `digest` 承诺被折叠的内容（可验证、不可读）；
 * - **有条件的**：只有超过阈值（条数或字符数）才动手，小会话原样返回；
 * - 不退还预算：压缩/剪枝不产生 token 收入。
 */
data class ChatTurn(
    val id: Long,
    val role: String,
    val text: String,
    val quotedId: Long? = null,
    val at: Long = 0L,
)

enum class ChatDropReason {
    /** 寒暄、表情、空消息。 */
    noise,

    /** 与后文完全重复的内容。 */
    superseded,

    /** 被折叠进摘要的较早消息。 */
    compressed,

    /** 超出保留窗口又没有保留价值。 */
    over_budget,
}

data class ChatDrop(
    val id: Long,
    val reason: ChatDropReason,
    val revisitIf: String,
)

data class ChatCondenseResult(
    /** 视图：保留的尾部 + 因引用被拉回的旧消息，按时间升序。 */
    val turns: List<ChatTurn>,
    /** 折叠摘要；未压缩时为 null。 */
    val summary: String?,
    /** 被折叠前缀的承诺。 */
    val digest: String?,
    val dropped: List<ChatDrop>,
    val condensed: Boolean,
    val sourceCount: Int,
)

object ChatCondenser {

    private val NOISE = setOf(
        "好的", "好", "收到", "嗯", "嗯嗯", "在吗", "ok", "okay", "哈哈", "谢谢", "感谢",
        "[图片]", "[表情]", "[语音]", "[视频]", "…", "...",
    )

    /**
     * @param keepRecent 尾部硬保留条数
     * @param maxTurns 不超过这个条数就不压缩
     * @param maxChars 不超过这个字符数就不压缩
     */
    fun condense(
        turns: List<ChatTurn>,
        keepRecent: Int = 12,
        maxTurns: Int = 40,
        maxChars: Int = 3_000,
    ): ChatCondenseResult {
        require(keepRecent >= 1) { "keepRecent 至少为 1" }
        val totalChars = turns.sumOf { it.text.length }
        if (turns.size <= maxTurns && totalChars <= maxChars) {
            return ChatCondenseResult(
                turns = turns,
                summary = null,
                digest = null,
                dropped = emptyList(),
                condensed = false,
                sourceCount = turns.size,
            )
        }

        val tail = turns.takeLast(keepRecent)
        val tailIds = tail.map { it.id }.toSet()
        val older = turns.dropLast(keepRecent)

        // 后文出现过的相同内容：较早那条属于重复
        val tailTexts = tail.map { it.role to it.text.trim() }.toSet()

        val dropped = ArrayList<ChatDrop>()
        val compressed = ArrayList<ChatTurn>()

        older.forEach { turn ->
            val normalized = turn.text.trim()
            when {
                normalized.isEmpty() || normalized.lowercase() in NOISE ->
                    dropped += ChatDrop(turn.id, ChatDropReason.noise, "用户再次提到这段对话时")

                (turn.role to normalized) in tailTexts ->
                    dropped += ChatDrop(turn.id, ChatDropReason.superseded, "同样的内容再次出现时")

                else -> {
                    compressed += turn
                    dropped += ChatDrop(turn.id, ChatDropReason.compressed, "用户问到更早的对话时")
                }
            }
        }

        // 引用闭包：保留下来的消息引用到的旧消息，即使被折叠也要拉回视图
        val keptIds = tailIds.toMutableSet()
        val byId = turns.associateBy { it.id }
        tail.forEach { turn ->
            val quoted = turn.quotedId ?: return@forEach
            if (quoted in byId) keptIds += quoted
        }
        val rescued = compressed.filter { it.id in keptIds }
        val stillCompressed = compressed.filterNot { it.id in keptIds }
        stillCompressed.forEach { turn ->
            dropped.removeAll { it.id == turn.id && it.reason == ChatDropReason.compressed }
            dropped += ChatDrop(turn.id, ChatDropReason.compressed, "用户问到更早的对话时")
        }

        val visible = turns.filter { it.id in keptIds }
        val summary = if (stillCompressed.isEmpty()) {
            null
        } else {
            summarize(stillCompressed, turns)
        }
        val digest = if (stillCompressed.isEmpty()) {
            null
        } else {
            Sha256.digestString(stillCompressed.joinToString("\n") { "${it.id}|${it.role}|${it.text}" })
        }

        return ChatCondenseResult(
            turns = visible,
            summary = summary,
            digest = digest,
            dropped = dropped,
            condensed = true,
            sourceCount = turns.size,
        )
    }

    private fun summarize(compressed: List<ChatTurn>, all: List<ChatTurn>): String {
        val user = compressed.count { it.role == "user" }
        val assistant = compressed.count { it.role == "assistant" }
        val from = compressed.firstOrNull()?.at ?: 0L
        val to = compressed.lastOrNull()?.at ?: 0L
        val head = compressed.firstOrNull()?.text?.take(30)?.replace('\n', ' ')
        val last = compressed.lastOrNull()?.text?.take(30)?.replace('\n', ' ')
        val parts = buildList {
            add("更早的 ${compressed.size} 条消息已压缩（用户 $user / 助手 $assistant）")
            if (from > 0 && to > 0) add("时间 ${formatHour(from)}–${formatHour(to)}")
            if (!head.isNullOrBlank()) add("开头：$head")
            if (!last.isNullOrBlank()) add("结尾：$last")
        }
        return parts.joinToString("；") + "。需要细节时可按 id 回看（共 ${all.size} 条）。"
    }

    private fun formatHour(epochMillis: Long): String {
        val totalMinutes = epochMillis / 60_000
        val hour = (totalMinutes / 60) % 24
        val minute = totalMinutes % 60
        return "%02d:%02d".format(hour, minute)
    }
}
