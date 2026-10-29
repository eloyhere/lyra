package com.eloykim.lyra.data

import android.content.ContentValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 按品牌/模型聚合的用量。 */
data class UsageProviderSummary(
    val providerId: String,
    val model: String,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costMicros: Long = 0,
    val calls: Int = 0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/** 聊天模型用量汇总。 */
data class ChatUsageSummary(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costMicros: Long = 0,
    val calls: Int = 0,
    val sessions: Int = 0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/**
 * 聊天用量：每次模型调用一行，包括工具循环里的每一次请求。
 *
 * 与 Agent 的 runs/steps 用量分开，UsageService 汇总时再把两边加到一起。
 */
class ChatUsageStore internal constructor(private val helper: LyraDatabase) {

    suspend fun record(
        conversationId: Long,
        providerId: String?,
        model: String?,
        inputTokens: Int,
        outputTokens: Int,
        costMicros: Long,
    ) = withContext(Dispatchers.IO) {
        if (inputTokens <= 0 && outputTokens <= 0) return@withContext
        helper.writableDatabase.insertOrThrow(
            "chat_usage",
            null,
            ContentValues().apply {
                put("conversation_id", conversationId)
                put("provider_id", providerId)
                put("model", model)
                put("input_tokens", inputTokens)
                put("output_tokens", outputTokens)
                put("cost_micros", costMicros)
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    suspend fun summary(days: Int = 7): ChatUsageSummary = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - days.coerceIn(1, 30) * 86_400_000L
        helper.readableDatabase.rawQuery(
            """
            SELECT COALESCE(SUM(input_tokens), 0),
                   COALESCE(SUM(output_tokens), 0),
                   COALESCE(SUM(cost_micros), 0),
                   COUNT(*),
                   COUNT(DISTINCT conversation_id)
            FROM chat_usage
            WHERE created_at >= ?
            """.trimIndent(),
            arrayOf(since.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext ChatUsageSummary()
            ChatUsageSummary(
                inputTokens = cursor.getLong(0),
                outputTokens = cursor.getLong(1),
                costMicros = cursor.getLong(2),
                calls = cursor.getInt(3),
                sessions = cursor.getInt(4),
            )
        }
    }

    suspend fun byProvider(days: Int = 7): List<UsageProviderSummary> =
        withContext(Dispatchers.IO) {
            val since = System.currentTimeMillis() - days.coerceIn(1, 30) * 86_400_000L
            val result = ArrayList<UsageProviderSummary>()
            helper.readableDatabase.rawQuery(
                """
                SELECT provider_id, model,
                       COALESCE(SUM(input_tokens), 0),
                       COALESCE(SUM(output_tokens), 0),
                       COALESCE(SUM(cost_micros), 0),
                       COUNT(*)
                FROM chat_usage
                WHERE created_at >= ?
                GROUP BY provider_id, model
                ORDER BY SUM(input_tokens + output_tokens) DESC
                """.trimIndent(),
                arrayOf(since.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result += UsageProviderSummary(
                        providerId = cursor.getString(0) ?: "unknown",
                        model = cursor.getString(1) ?: "—",
                        inputTokens = cursor.getLong(2),
                        outputTokens = cursor.getLong(3),
                        costMicros = cursor.getLong(4),
                        calls = cursor.getInt(5),
                    )
                }
            }
            result
        }

    suspend fun forSession(conversationId: Long): ChatUsageSummary =
        withContext(Dispatchers.IO) {
            helper.readableDatabase.rawQuery(
                """
                SELECT COALESCE(SUM(input_tokens), 0),
                       COALESCE(SUM(output_tokens), 0),
                       COALESCE(SUM(cost_micros), 0),
                       COUNT(*)
                FROM chat_usage
                WHERE conversation_id = ?
                """.trimIndent(),
                arrayOf(conversationId.toString()),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@withContext ChatUsageSummary()
                ChatUsageSummary(
                    inputTokens = cursor.getLong(0),
                    outputTokens = cursor.getLong(1),
                    costMicros = cursor.getLong(2),
                    calls = cursor.getInt(3),
                    sessions = if (cursor.getLong(3) > 0) 1 else 0,
                )
            }
        }
}
