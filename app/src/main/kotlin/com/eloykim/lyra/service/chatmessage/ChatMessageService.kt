package com.eloykim.lyra.service.chatmessage

import com.eloykim.lyra.AppGraph
import com.eloykim.lyra.attention.ChatCondenser
import com.eloykim.lyra.attention.ChatTurn
import com.eloykim.lyra.data.ChatMessageStore
import com.eloykim.lyra.data.ChatSearchHit
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.BreakIterator

/**
 * ChatMessage 服务（只读）：检索本机聊天记录，作为模型回忆/上下文。
 *
 * 数据来自 core:data 的 ChatMessageStore；它与聊天页共用同一份 SQLite，
 * 但不提供写入，避免模型改写用户对话。
 */
class ChatMessageService(private val chat: ChatMessageStore) : BasePhoneService() {

    override val id: String = "chat_message"
    override val title: String = "聊天记录"
    override val description: String =
        "检索本机聊天记录，并可按消息 id 点赞、点踩、用单个 emoji 评论或生成引用。"

    init {
        register(
            ServiceMethod(
                name = "recent",
                summary = "最近的消息记录",
                parameters = Schema.params(
                    "limit" to Schema.integer("数量", 1, 30),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> recent(args.optIntOr("limit", 10)) }

        register(
            ServiceMethod(
                name = "search",
                summary = "按关键词检索聊天记录",
                parameters = Schema.params(
                    "query" to Schema.string("关键词，空格分隔多个词"),
                    "limit" to Schema.integer("数量", 1, 30),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args ->
            search(
                query = args.optStringOr("query"),
                limit = args.optIntOr("limit", 8),
            )
        }

        register(
            ServiceMethod(
                name = "conversations",
                summary = "列出会话（标题/条数/更新时间）",
                parameters = Schema.params(
                    "limit" to Schema.integer("数量", 1, 50),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> conversations(args.optIntOr("limit", 20)) }

        register(
            ServiceMethod(
                name = "conversation",
                summary = "读取单个会话的消息",
                parameters = Schema.params(
                    "conversationId" to Schema.integer("会话 id"),
                    "limit" to Schema.integer("最多返回条数", 1, 100),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args ->
            conversation(
                conversationId = args.reqLong("conversationId"),
                limit = args.optIntOr("limit", 50),
            )
        }

        register(
            ServiceMethod(
                name = "stats",
                summary = "聊天记录统计",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { stats() }

        register(
            ServiceMethod(
                name = "like",
                summary = "给指定消息点赞（👍）",
                parameters = Schema.params(
                    "messageId" to Schema.integer("消息 id"),
                    required = listOf("messageId"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args -> react(args.reqLong("messageId"), "👍") }

        register(
            ServiceMethod(
                name = "dislike",
                summary = "给指定消息点踩（👎）",
                parameters = Schema.params(
                    "messageId" to Schema.integer("消息 id"),
                    required = listOf("messageId"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args -> react(args.reqLong("messageId"), "👎") }

        register(
            ServiceMethod(
                name = "comment",
                summary = "用单个 emoji 评论指定消息（单选，覆盖旧表情）",
                parameters = Schema.params(
                    "messageId" to Schema.integer("消息 id"),
                    "emoji" to Schema.string("单个 emoji，例如 😄 / 😢 / 😠"),
                    required = listOf("messageId", "emoji"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val emoji = args.optStringOr("emoji")
            if (!isSingleEmoji(emoji)) {
                ServiceResult.fail("invalid_emoji", "只能传一个 emoji")
            } else {
                react(args.reqLong("messageId"), emoji)
            }
        }

        register(
            ServiceMethod(
                name = "clear_reaction",
                summary = "取消指定消息的表情",
                parameters = Schema.params(
                    "messageId" to Schema.integer("消息 id"),
                    required = listOf("messageId"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args -> react(args.reqLong("messageId"), null) }

        register(
            ServiceMethod(
                name = "quote",
                summary = "引用指定消息，返回可直接放进回答的 Markdown 引用块",
                parameters = Schema.params(
                    "messageId" to Schema.integer("消息 id"),
                    required = listOf("messageId"),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> quote(args.reqLong("messageId")) }
    }

    private suspend fun quote(messageId: Long): ServiceResult {
        val message = chat.find(messageId)
            ?: return ServiceResult.fail("message_not_found", "没有消息 $messageId")
        if (message.pending) {
            return ServiceResult.fail("message_pending", "消息还在生成中，暂时不能引用")
        }
        val markdown = message.content.lineSequence().joinToString("\n") { "> $it" }
        return ServiceResult.ok(
            "引用消息 #$messageId",
            JSONObject()
                .put("messageId", message.id)
                .put("conversationId", message.conversationId)
                .put("role", message.role)
                .put("content", message.content)
                .put("markdown", markdown),
        )
    }

    private suspend fun react(messageId: Long, reaction: String?): ServiceResult {
        val updated = chat.setReaction(messageId, reaction)
            ?: return ServiceResult.fail("message_not_found", "没有消息 $messageId")
        return ServiceResult.ok(
            if (reaction == null) "reaction_cleared" else "reaction_set",
            JSONObject()
                .put("messageId", updated.id)
                .put("conversationId", updated.conversationId)
                .put("reaction", reaction),
        )
    }

    /** 单个 emoji 可以是多码点（❤️、😄 等）；拒绝文字、数字和多个 emoji。 */
    private fun isSingleEmoji(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var clusters = 0
        var boundary = iterator.next()
        while (boundary != BreakIterator.DONE) {
            clusters += 1
            boundary = iterator.next()
        }
        if (clusters != 1) return false
        return text.codePoints().toArray().any { codePoint ->
            codePoint in 0x1F000..0x1FAFF ||
                codePoint in 0x2600..0x27BF ||
                codePoint in 0x1F1E6..0x1F1FF ||
                codePoint == 0x2B50
        }
    }

    private suspend fun recent(limit: Int): ServiceResult {
        val hits = chat.recent(limit.coerceIn(1, 30))
        return ServiceResult.ok(
            "${hits.size} messages",
            JSONObject()
                .put("messages", hitsJson(hits))
                .put("count", hits.size),
        )
    }

    private suspend fun search(query: String, limit: Int): ServiceResult {
        val hits = chat.search(query, limit.coerceIn(1, 30))
        return ServiceResult.ok(
            "${hits.size} messages",
            JSONObject()
                .put("query", query)
                .put("messages", hitsJson(hits))
                .put("count", hits.size),
        )
    }

    private suspend fun conversations(limit: Int): ServiceResult = withContext(Dispatchers.IO) {
        val all = chat.loadAll()
        val array = JSONArray()
        all.sortedByDescending { it.updatedAt }
            .take(limit.coerceIn(1, 50))
            .forEach { conversation ->
                val last = conversation.messages.lastOrNull()
                array.put(
                    JSONObject()
                        .put("id", conversation.id)
                        .put("title", conversation.title)
                        .put("createdAt", conversation.createdAt)
                        .put("updatedAt", conversation.updatedAt)
                        .put("messageCount", conversation.messages.size)
                        .put("lastRole", last?.role)
                        .put("lastMessage", last?.content?.take(200)),
                )
            }
        ServiceResult.ok(
            "${array.length()} conversations",
            JSONObject().put("conversations", array).put("count", array.length()),
        )
    }

    private suspend fun conversation(conversationId: Long, limit: Int): ServiceResult =
        withContext(Dispatchers.IO) {
            val conversation = chat.loadAll().firstOrNull { it.id == conversationId }
                ?: return@withContext ServiceResult.fail(
                    "not_found",
                    "没有会话 $conversationId",
                )
            val messages = conversation.messages
                .takeLast(limit.coerceIn(1, 100))
                .map { message ->
                    JSONObject()
                        .put("id", message.id)
                        .put("role", message.role)
                        .put("content", message.content.take(2_000))
                        .put("reasoning", message.reasoning?.take(1_000))
                        .put("reaction", message.reaction)
                        .put("quotedRole", message.quotedRole)
                        .put("quotedContent", message.quotedContent?.take(500))
                        .put("pending", message.pending)
                        .put("error", message.error)
                        .put("createdAt", message.createdAt)
                }
            // 条件压缩：长会话只返回「摘要 + 尾部」，被剪掉的消息带 reason 记录
            val turns = messages.map { message ->
                ChatTurn(
                    id = message.optLong("id"),
                    role = message.optString("role"),
                    text = message.optString("content"),
                    at = message.optLong("createdAt"),
                )
            }
            val condensed = ChatCondenser.condense(
                turns = turns,
                keepRecent = 12,
                maxTurns = 40,
                maxChars = 6_000,
            )
            val keptIds = condensed.turns.map { it.id }.toSet()
            val visible = messages.filter { it.optLong("id") in keptIds }

            if (condensed.condensed) {
                runCatching {
                    AppGraph.history.ledger.append(
                        "service",
                        "chat.compressed",
                        JSONObject()
                            .put("conversation", conversation.id)
                            .put("kept", visible.size)
                            .put("dropped", condensed.dropped.size)
                            .put("digest", condensed.digest)
                            .toString(),
                    )
                }
            }

            ServiceResult.ok(
                "${visible.size} messages${if (condensed.condensed) "（已压缩）" else ""}",
                JSONObject()
                    .put("id", conversation.id)
                    .put("title", conversation.title)
                    .put("createdAt", conversation.createdAt)
                    .put("updatedAt", conversation.updatedAt)
                    .put("messages", JSONArray(visible))
                    .put("count", visible.size)
                    .put("sourceCount", messages.size)
                    .put("compressed", condensed.condensed)
                    .put("summary", condensed.summary)
                    .put("digest", condensed.digest)
                    .put("dropped", condensed.dropped.size),
            )
        }

    private suspend fun stats(): ServiceResult = withContext(Dispatchers.IO) {
        val conversations = chat.loadAll()
        val messageCount = conversations.sumOf { it.messages.size }
        val first = conversations.minOfOrNull { it.createdAt }
        val last = conversations.maxOfOrNull { it.updatedAt }
        ServiceResult.ok(
            "ok",
            JSONObject()
                .put("conversations", conversations.size)
                .put("messages", messageCount)
                .put("firstCreatedAt", first ?: 0L)
                .put("lastUpdatedAt", last ?: 0L),
        )
    }

    private fun hitsJson(hits: List<ChatSearchHit>): JSONArray {
        val array = JSONArray()
        hits.forEach { hit ->
            array.put(
                JSONObject()
                    .put("messageId", hit.messageId)
                    .put("conversationId", hit.conversationId)
                    .put("conversationTitle", hit.conversationTitle)
                    .put("role", hit.role)
                    .put("content", hit.content.take(2_000))
                    .put("reasoning", hit.reasoning?.take(1_000))
                    .put("quotedRole", hit.quotedRole)
                    .put("quotedContent", hit.quotedContent?.take(500))
                    .put("createdAt", hit.createdAt),
            )
        }
        return array
    }
}
