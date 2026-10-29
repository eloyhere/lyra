package com.eloykim.lyra.data

import android.content.ContentValues
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/** 一条持久化聊天消息。 */
data class ChatMessageRecord(
    val id: Long,
    val conversationId: Long,
    val role: String,
    val content: String,
    val reasoning: String?,
    val reaction: String?,
    val quotedMessageId: Long?,
    val quotedRole: String?,
    val quotedContent: String?,
    val pending: Boolean,
    val error: Boolean,
    val createdAt: Long,
)

/** 一条持久化会话（含消息）。 */
data class ChatConversationRecord(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessageRecord>,
    val providerId: String? = null,
    val model: String? = null,
    val thinkingLevel: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costMicros: Long = 0,
    val messageCount: Int = 0,
    val lastMessageAt: Long? = null,
)

/** 表情被服务层修改后广播给聊天页。 */
data class ReactionChange(
    val conversationId: Long,
    val messageId: Long,
    val reaction: String?,
)

/** 模型查询聊天记录时看到的命中项。 */
data class ChatSearchHit(
    val messageId: Long,
    val conversationId: Long,
    val conversationTitle: String,
    val role: String,
    val content: String,
    val reasoning: String?,
    val quotedRole: String?,
    val quotedContent: String?,
    val createdAt: Long,
)

/**
 * ChatMessage 存储层（SQLite）。
 *
 * - 会话/消息存 SQLite，供聊天页恢复与 ChatMessageService 查询；
 * - 保存采用整表快照（聊天量小，简单可靠）；
 * - 搜索按关键词拆词，对 content 做 LIKE，排除未完成/失败消息；
 * - 版本号防止异步保存乱序覆盖新数据。
 */
class ChatMessageStore internal constructor(private val helper: LyraDatabase) {

    private val saveLock = Any()

    @Volatile
    private var lastSavedVersion: Long = 0L

    private val _reactionChanges = MutableSharedFlow<ReactionChange>(extraBufferCapacity = 16)
    val reactionChanges: SharedFlow<ReactionChange> = _reactionChanges.asSharedFlow()

    suspend fun loadAll(): List<ChatConversationRecord> = withContext(Dispatchers.IO) {
        val conversations = ArrayList<ChatConversationRecord>()
        helper.readableDatabase.rawQuery(
            """
            SELECT id, title, created_at, updated_at, provider_id, model, thinking_level,
                   input_tokens, output_tokens, cost_micros, message_count, last_message_at
            FROM chat_conversations
            ORDER BY updated_at DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                conversations += ChatConversationRecord(
                    id = id,
                    title = cursor.getString(1),
                    createdAt = cursor.getLong(2),
                    updatedAt = cursor.getLong(3),
                    messages = loadMessages(id),
                    providerId = cursor.getString(4),
                    model = cursor.getString(5),
                    thinkingLevel = cursor.getString(6),
                    inputTokens = cursor.getLong(7),
                    outputTokens = cursor.getLong(8),
                    costMicros = cursor.getLong(9),
                    messageCount = cursor.getInt(10),
                    lastMessageAt = cursor.getLong(11).takeIf { !cursor.isNull(11) },
                )
            }
        }
        conversations
    }

    suspend fun saveSnapshot(version: Long, conversations: List<ChatConversationRecord>) =
        withContext(Dispatchers.IO) {
            synchronized(saveLock) {
                if (version <= lastSavedVersion) return@withContext
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    db.delete("chat_messages", null, null)
                    db.delete("chat_conversations", null, null)
                    conversations.forEach { conversation ->
                        db.insertOrThrow(
                            "chat_conversations",
                            null,
                            ContentValues().apply {
                                put("id", conversation.id)
                                put("title", conversation.title)
                                put("created_at", conversation.createdAt)
                                put("updated_at", conversation.updatedAt)
                                put("provider_id", conversation.providerId)
                                put("model", conversation.model)
                                put("thinking_level", conversation.thinkingLevel)
                                put("input_tokens", conversation.inputTokens)
                                put("output_tokens", conversation.outputTokens)
                                put("cost_micros", conversation.costMicros)
                                put("message_count", conversation.messageCount)
                                put("last_message_at", conversation.lastMessageAt)
                            },
                        )
                        conversation.messages.forEach { message ->
                            db.insertOrThrow(
                                "chat_messages",
                                null,
                                ContentValues().apply {
                                    put("id", message.id)
                                    put("conversation_id", message.conversationId)
                                    put("role", message.role)
                                    put("content", message.content)
                                    put("reasoning", message.reasoning)
                                    put("reaction", message.reaction)
                                    put("quoted_id", message.quotedMessageId)
                                    put("quoted_role", message.quotedRole)
                                    put("quoted_content", message.quotedContent)
                                    put("pending", if (message.pending) 1 else 0)
                                    put("error", if (message.error) 1 else 0)
                                    put("created_at", message.createdAt)
                                },
                            )
                        }
                    }
                    db.setTransactionSuccessful()
                    lastSavedVersion = version
                } finally {
                    db.endTransaction()
                }
            }
        }

    /**
     * 给一条消息设置单选表情；返回更新后的记录，消息不存在时返回 null。
     * 这是 Agent 操作「特定消息」的写入口。
     */
    suspend fun setReaction(messageId: Long, reaction: String?): ChatMessageRecord? =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply { put("reaction", reaction) }
            val rows = helper.writableDatabase.update(
                "chat_messages",
                values,
                "id = ?",
                arrayOf(messageId.toString()),
            )
            if (rows == 0) return@withContext null
            val record = queryMessage(messageId) ?: return@withContext null
            _reactionChanges.tryEmit(
                ReactionChange(
                    conversationId = record.conversationId,
                    messageId = messageId,
                    reaction = reaction,
                ),
            )
            record
        }

    /** 按 id 读取一条消息；Agent 引用消息时使用。 */
    suspend fun find(messageId: Long): ChatMessageRecord? = withContext(Dispatchers.IO) {
        queryMessage(messageId)
    }

    private fun queryMessage(messageId: Long): ChatMessageRecord? {
        helper.readableDatabase.rawQuery(
            """
            SELECT id, conversation_id, role, content, reasoning, reaction,
                   quoted_id, quoted_role, quoted_content, pending, error, created_at
            FROM chat_messages
            WHERE id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(messageId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return ChatMessageRecord(
                id = cursor.getLong(0),
                conversationId = cursor.getLong(1),
                role = cursor.getString(2),
                content = cursor.getString(3),
                reasoning = cursor.getString(4),
                reaction = cursor.getString(5),
                quotedMessageId = cursor.getLong(6).takeIf { !cursor.isNull(6) },
                quotedRole = cursor.getString(7),
                quotedContent = cursor.getString(8),
                pending = cursor.getInt(9) != 0,
                error = cursor.getInt(10) != 0,
                createdAt = cursor.getLong(11),
            )
        }
    }

    /** 模型查询入口：关键词检索历史对话，返回最近命中。 */
    suspend fun search(query: String, limit: Int = 8): List<ChatSearchHit> =
        withContext(Dispatchers.IO) {
            val terms = query.trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .take(8)
            if (terms.isEmpty()) return@withContext recent(limit)

            val clauses = terms.joinToString(" AND ") {
                "(m.content LIKE ? ESCAPE '\\')"
            }
            val args = ArrayList<String>(terms.size)
            terms.forEach { args += "%${escapeLike(it)}%" }
            queryMessages(
                where = "m.pending = 0 AND m.error = 0 AND $clauses",
                args = args,
                limit = limit,
            )
        }

    /** 最近聊天记录；没有关键词时使用。 */
    suspend fun recent(limit: Int = 10): List<ChatSearchHit> = withContext(Dispatchers.IO) {
        queryMessages(
            where = "m.pending = 0 AND m.error = 0",
            args = emptyList(),
            limit = limit,
        )
    }

    private fun loadMessages(conversationId: Long): List<ChatMessageRecord> {
        val messages = ArrayList<ChatMessageRecord>()
        helper.readableDatabase.rawQuery(
            """
            SELECT id, role, content, reasoning, reaction,
                   quoted_id, quoted_role, quoted_content, pending, error, created_at
            FROM chat_messages
            WHERE conversation_id = ?
            ORDER BY created_at ASC, id ASC
            """.trimIndent(),
            arrayOf(conversationId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                messages += cursor.toMessage(conversationId)
            }
        }
        return messages
    }

    private fun queryMessages(
        where: String,
        args: List<String>,
        limit: Int,
    ): List<ChatSearchHit> {
        val hits = ArrayList<ChatSearchHit>()
        val safeLimit = limit.coerceIn(1, 30)
        val sql = """
            SELECT m.id, m.conversation_id, c.title, m.role, m.content, m.reasoning,
                   m.quoted_role, m.quoted_content, m.created_at
            FROM chat_messages m
            JOIN chat_conversations c ON c.id = m.conversation_id
            WHERE $where
            ORDER BY m.created_at DESC, m.id DESC
            LIMIT $safeLimit
        """.trimIndent()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                hits += ChatSearchHit(
                    messageId = cursor.getLong(0),
                    conversationId = cursor.getLong(1),
                    conversationTitle = cursor.getString(2),
                    role = cursor.getString(3),
                    content = cursor.getString(4),
                    reasoning = cursor.getString(5),
                    quotedRole = cursor.getString(6),
                    quotedContent = cursor.getString(7),
                    createdAt = cursor.getLong(8),
                )
            }
        }
        return hits
    }

    private fun Cursor.toMessage(conversationId: Long): ChatMessageRecord = ChatMessageRecord(
        id = getLong(0),
        conversationId = conversationId,
        role = getString(1),
        content = getString(2),
        reasoning = getString(3),
        reaction = getString(4),
        quotedMessageId = getLong(5).takeIf { !isNull(5) },
        quotedRole = getString(6),
        quotedContent = getString(7),
        pending = getInt(8) != 0,
        error = getInt(9) != 0,
        createdAt = getLong(10),
    )

    private fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
}
