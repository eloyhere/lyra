package com.eloykim.lyra.data

import com.eloykim.lyra.amp.AmpEvent
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 单张回执的用量：来自 AMP 账本里 type=receipt 的事件。 */
data class UsageReceipt(
    val at: Long,
    val action: String,
    val ok: Boolean,
    val ms: Long,
    val tokensIn: Long,
    val tokensOut: Long,
    val costMicros: Long,
) {
    val tokens: Long get() = tokensIn + tokensOut
}

/** 按动作聚合的用量。 */
data class UsageSlice(
    val label: String,
    val count: Int,
    val tokens: Long,
    val ms: Long,
    val costMicros: Long,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
)

/** 按天聚合的用量；没有记录的天补 0，保证图表连续。 */
data class UsageDay(
    val date: LocalDate,
    val tokens: Long,
    val receipts: Int,
    val costMicros: Long,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
)

/**
 * 用度报表。
 *
 * 全部从本机只追加账本推导：不额外记录、不上传，
 * 只统计 receipt 里的 tokens_in / tokens_out / ms / cost_micros。
 */
data class UsageReport(
    val events: Int,
    val receipts: Int,
    val tokensIn: Long,
    val tokensOut: Long,
    val costMicros: Long,
    val actionMs: Long,
    val byAction: List<UsageSlice>,
    val byDay: List<UsageDay>,
    val recent: List<UsageReceipt>,
    val chainProblems: List<String>,
) {
    val tokens: Long get() = tokensIn + tokensOut
}

internal object UsageAggregator {

    fun aggregate(
        events: List<AmpEvent>,
        chainProblems: List<String>,
        days: Int,
        recentLimit: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis(),
    ): UsageReport {
        val receipts = events.asSequence()
            .filter { it.type == "receipt" }
            .mapNotNull(::parseReceipt)
            .toList()

        val byAction = receipts.groupBy { it.action }
            .map { (action, list) ->
                UsageSlice(
                    label = action,
                    count = list.size,
                    tokens = list.sumOf { it.tokens },
                    ms = list.sumOf { it.ms },
                    costMicros = list.sumOf { it.costMicros },
                    tokensIn = list.sumOf { it.tokensIn },
                    tokensOut = list.sumOf { it.tokensOut },
                )
            }
            .sortedWith(compareByDescending<UsageSlice> { it.tokens }.thenByDescending { it.count })

        val dayCount = days.coerceIn(1, 30)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val buckets = LinkedHashMap<LocalDate, UsageDay>()
        for (offset in dayCount - 1 downTo 0) {
            val date = today.minusDays(offset.toLong())
            buckets[date] = UsageDay(
                date = date,
                tokens = 0,
                receipts = 0,
                costMicros = 0,
                tokensIn = 0,
                tokensOut = 0,
            )
        }
        for (receipt in receipts) {
            val date = Instant.ofEpochMilli(receipt.at).atZone(zone).toLocalDate()
            val bucket = buckets[date] ?: continue
            buckets[date] = bucket.copy(
                tokens = bucket.tokens + receipt.tokens,
                receipts = bucket.receipts + 1,
                costMicros = bucket.costMicros + receipt.costMicros,
                tokensIn = bucket.tokensIn + receipt.tokensIn,
                tokensOut = bucket.tokensOut + receipt.tokensOut,
            )
        }

        return UsageReport(
            events = events.size,
            receipts = receipts.size,
            tokensIn = receipts.sumOf { it.tokensIn },
            tokensOut = receipts.sumOf { it.tokensOut },
            costMicros = receipts.sumOf { it.costMicros },
            actionMs = receipts.sumOf { it.ms },
            byAction = byAction,
            byDay = buckets.values.toList(),
            recent = receipts.takeLast(recentLimit.coerceIn(1, 50)).reversed(),
            chainProblems = chainProblems,
        )
    }

    private fun parseReceipt(event: AmpEvent): UsageReceipt? {
        val payload = runCatching { JSONObject(event.payload) }.getOrNull() ?: return null
        val action = payload.optString("action")
            .ifBlank { payload.optString("op") }
            .ifBlank { "act" }
        return UsageReceipt(
            at = event.at,
            action = action,
            ok = payload.optBoolean("ok", true),
            ms = payload.optLong("ms"),
            tokensIn = payload.optLong("tokens_in"),
            tokensOut = payload.optLong("tokens_out"),
            costMicros = payload.optLong("cost_micros"),
        )
    }
}
