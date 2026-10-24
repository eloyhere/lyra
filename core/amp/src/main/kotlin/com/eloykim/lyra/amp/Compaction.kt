package com.eloykim.lyra.amp

/**
 * 账本压缩（AMP compaction，P-5 / P-6 / P-7）。
 *
 * 只追加的账本不能删除，只能「先承诺、后压缩」：把一段前缀的哈希链再摘要成 `root`，
 * 记下它的最后一条 `tip`，tail 从 `tip` 继续。被压缩的内容不可再读，但可验证。
 *
 * 三条硬规则：
 * - P-5：`root` 覆盖被移除条目的顺序、内容、prev 与 hash；
 * - P-6：`tail[0].prev == checkpoint.tip`，seq 连续；
 * - P-7：tail 里若还引用被移除对象，必须拒绝（避免制造悬空引用）。
 */
data class LedgerCheckpoint(
    val from: Long,
    val to: Long,
    val count: Int,
    val root: String,
    val tip: String,
)

data class CompactionResult(
    val checkpoint: LedgerCheckpoint,
    val tail: List<AmpEvent>,
    val removed: List<AmpEvent>,
)

class CompactionRefused(val danglingRefs: List<String>) :
    IllegalStateException("压缩会埋掉活引用：${danglingRefs.joinToString()}")

object Compaction {

    /**
     * 把前缀压成检查点。
     *
     * @param keepRecent 必须保留的尾部条数（硬下限）
     * @throws CompactionRefused 当 tail 仍引用被移除对象时
     */
    fun compact(events: List<AmpEvent>, keepRecent: Int): CompactionResult {
        require(keepRecent >= 1) { "keepRecent 至少为 1" }
        if (events.size <= keepRecent) {
            return CompactionResult(
                checkpoint = LedgerCheckpoint(
                    from = events.firstOrNull()?.seq ?: 0,
                    to = events.lastOrNull()?.seq ?: 0,
                    count = 0,
                    root = Digests.sha256("empty"),
                    tip = events.lastOrNull()?.digest ?: Digests.sha256("genesis"),
                ),
                tail = events,
                removed = emptyList(),
            )
        }

        val cut = events.size - keepRecent
        val removed = events.take(cut)
        val tail = events.drop(cut)

        val removedIds = removed.flatMap { idsIn(it.payload) }.toSet()
        val dangling = tail.flatMap { idsIn(it.payload) }
            .filter { it in removedIds }
            .distinct()
        if (dangling.isNotEmpty()) throw CompactionRefused(dangling)

        val root = rootOf(removed)

        return CompactionResult(
            checkpoint = LedgerCheckpoint(
                from = removed.first().seq,
                to = removed.last().seq,
                count = removed.size,
                root = root,
                tip = removed.last().digest,
            ),
            tail = tail,
            removed = removed,
        )
    }

    /** 重算 root、核对 seq 连续与 prev 链接；返回违规描述，空表示通过。 */
    fun verify(
        checkpoint: LedgerCheckpoint,
        tail: List<AmpEvent>,
        removed: List<AmpEvent>? = null,
    ): List<String> {
        val problems = ArrayList<String>()

        if (tail.isNotEmpty() && tail.first().prevDigest != checkpoint.tip) {
            problems += "tail 首条 prev 不是 checkpoint.tip"
        }
        var expected = tail.firstOrNull()?.seq
        tail.forEach { event ->
            if (expected != null && event.seq != expected) problems += "tail seq 不连续"
            expected = (event.seq + 1)
        }

        if (removed != null) {
            if (removed.size != checkpoint.count) problems += "removed 数量与 checkpoint.count 不一致"
            if (removed.firstOrNull()?.seq != checkpoint.from) problems += "removed 起点与 from 不一致"
            if (removed.lastOrNull()?.seq != checkpoint.to) problems += "removed 终点与 to 不一致"
            if (removed.lastOrNull()?.digest != checkpoint.tip) problems += "removed 末条 digest 与 tip 不一致"

            val recomputed = rootOf(removed)
            if (recomputed != checkpoint.root) problems += "root 重算不匹配"
        }

        return problems
    }

    /** root 覆盖顺序、内容、prev 与 hash（P-5：先承诺，后删除）。 */
    private fun rootOf(events: List<AmpEvent>): String = Digests.sha256(
        events.joinToString("\n") { event ->
            "${event.seq}|${event.at}|${event.type}|${event.payload}|${event.prevDigest ?: "-"}|${event.digest}"
        },
    )

    /** 从 payload 里粗略提取 id 字段，用于悬空引用检查（不做完整 JSON 解析）。 */
    internal fun idsIn(payload: String): List<String> {
        val regex = Regex(""""(?:id|ref|target)"\s*:\s*"([^"]+)"""")
        return regex.findAll(payload).map { it.groupValues[1] }.toList()
    }
}
