package com.eloykim.lyra.amp

/**
 * 只追加事件日志（AMP X-1 的最小落地）。
 *
 * 每条事件带 `digest` 与 `prev_digest`，形成哈希链：
 * 篡改任意一条的 payload，后续所有 digest 都对不上；删除中间一条，seq 与链都会断。
 *
 * 这里只做纯 Kotlin 的链与校验，持久化由 `core:data` 的 LedgerStore 负责。
 */
data class AmpEvent(
    val seq: Long,
    val at: Long,
    val actor: String,
    val type: String,
    val refs: List<String>,
    val payload: String,
    val digest: String,
    val prevDigest: String?,
)

class EventLog(initial: List<AmpEvent> = emptyList()) {

    private val events = ArrayList<AmpEvent>(initial)

    val size: Int get() = events.size

    fun headDigest(): String? = events.lastOrNull()?.digest

    fun append(
        actor: String,
        type: String,
        payload: String,
        refs: List<String> = emptyList(),
        at: Long = System.currentTimeMillis(),
    ): AmpEvent {
        val seq = (events.lastOrNull()?.seq ?: 0L) + 1
        val prev = events.lastOrNull()?.digest
        val digest = computeDigest(seq, at, actor, type, refs, payload, prev)
        val event = AmpEvent(seq, at, actor, type, refs, payload, digest, prev)
        events += event
        return event
    }

    fun snapshot(): List<AmpEvent> = events.toList()

    /** 校验整条链；返回违规描述，空列表表示通过。 */
    fun verify(): List<String> = verifyChain(events)

    companion object {

        fun computeDigest(
            seq: Long,
            at: Long,
            actor: String,
            type: String,
            refs: List<String>,
            payload: String,
            prevDigest: String?,
        ): String = Digests.sha256(
            buildString {
                append(prevDigest ?: "genesis")
                append('|').append(seq)
                append('|').append(at)
                append('|').append(actor)
                append('|').append(type)
                append('|').append(refs.joinToString(","))
                append('|').append(payload)
            },
        )

        /** 供持久层在加载后校验，也供测试构造被篡改的链。 */
        fun verifyChain(
            events: List<AmpEvent>,
            startSeq: Long = 1L,
            startPrev: String? = null,
        ): List<String> {
            val problems = ArrayList<String>()
            var expectedSeq = startSeq
            var prev: String? = startPrev
            events.forEachIndexed { index, event ->
                if (event.seq != expectedSeq) {
                    problems += "seq 不连续：第 $index 条为 ${event.seq}，期望 $expectedSeq"
                }
                if (event.prevDigest != prev) {
                    problems += "第 $index 条 prev_digest 不匹配：${event.prevDigest} != $prev"
                }
                val recomputed = computeDigest(
                    event.seq, event.at, event.actor, event.type,
                    event.refs, event.payload, event.prevDigest,
                )
                if (recomputed != event.digest) {
                    problems += "第 $index 条 digest 被篡改"
                }
                prev = event.digest
                expectedSeq = event.seq + 1
            }
            return problems
        }
    }
}
