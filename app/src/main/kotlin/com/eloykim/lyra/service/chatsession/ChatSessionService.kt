package com.eloykim.lyra.service.chatsession

import com.eloykim.lyra.data.HistoryRepository
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqLong
import com.eloykim.lyra.service.reqString
import com.eloykim.lyra.ui.chat.ChatConversation
import com.eloykim.lyra.ui.chat.ChatRole
import com.eloykim.lyra.ui.chat.ChatStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * ChatSession 服务：会话级操作。
 *
 * 与聊天页共用 ChatStore，所以 Agent 新建/切换/改名后 UI 会同步；
 * token 与费用来自 chat_usage 账本。
 */
class ChatSessionService(private val history: HistoryRepository) : BasePhoneService() {

    override val id: String = "chat_session"
    override val title: String = "聊天会话"
    override val description: String =
        "新建、查看、切换、改名或清空聊天会话；返回消息数、模型与 token 用度。"

    init {
        register(
            ServiceMethod(
                name = "new",
                summary = "新建一个聊天会话",
                parameters = Schema.params(
                    "title" to Schema.string("会话标题，可空"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args -> newSession(args.optStringOr("title")) }

        register(
            ServiceMethod(
                name = "information",
                summary = "查看当前或指定会话的详细信息",
                parameters = Schema.params(
                    "id" to Schema.integer("会话 id；不传则当前会话"),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args -> information(args.optIntOr("id", -1).toLong()) }

        register(
            ServiceMethod(
                name = "list",
                summary = "列出聊天会话",
                parameters = Schema.params(
                    "limit" to Schema.integer("数量", 1, 50),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args -> list(args.optIntOr("limit", 20)) }

        register(
            ServiceMethod(
                name = "select",
                summary = "切换当前会话",
                parameters = Schema.params(
                    "id" to Schema.integer("会话 id"),
                    required = listOf("id"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args -> select(args.reqLong("id")) }

        register(
            ServiceMethod(
                name = "rename",
                summary = "重命名会话",
                parameters = Schema.params(
                    "id" to Schema.integer("会话 id"),
                    "title" to Schema.string("新标题"),
                    required = listOf("id", "title"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args -> rename(args.reqLong("id"), args.reqString("title")) }

        register(
            ServiceMethod(
                name = "clear",
                summary = "清空指定会话的消息",
                parameters = Schema.params(
                    "id" to Schema.integer("会话 id；不传则当前会话"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args -> clear(args.optIntOr("id", -1).toLong()) }
    }

    private suspend fun ensureReady() {
        if (!ChatStore.ready.value) ChatStore.restore()
    }

    private suspend fun newSession(title: String?): ServiceResult {
        ensureReady()
        val id = ChatStore.newConversation()
        if (!title.isNullOrBlank()) ChatStore.rename(id, title)
        ChatStore.select(id)
        return ServiceResult.ok("已新建会话", conversationJson(require(id), ChatStore.activeId.value == id))
    }

    private suspend fun information(id: Long): ServiceResult {
        ensureReady()
        val target = if (id >= 0) {
            ChatStore.conversations.value.firstOrNull { it.id == id }
        } else {
            ChatStore.conversations.value.firstOrNull { it.id == ChatStore.activeId.value }
        } ?: return ServiceResult.fail("not_found", "没有会话 $id")
        return ServiceResult.ok(
            "会话信息",
            conversationJson(target, ChatStore.activeId.value == target.id),
        )
    }

    private suspend fun list(limit: Int): ServiceResult {
        ensureReady()
        val array = JSONArray()
        ChatStore.conversations.value
            .take(limit.coerceIn(1, 50))
            .forEach { conversation ->
                array.put(conversationJson(conversation, ChatStore.activeId.value == conversation.id))
            }
        return ServiceResult.ok(
            "${array.length()} 个会话",
            JSONObject().put("sessions", array).put("count", array.length()),
        )
    }

    private fun select(id: Long): ServiceResult {
        if (ChatStore.conversations.value.none { it.id == id }) {
            return ServiceResult.fail("not_found", "没有会话 $id")
        }
        ChatStore.select(id)
        return ServiceResult.ok("已切换会话", JSONObject().put("id", id))
    }

    private fun rename(id: Long, title: String): ServiceResult {
        if (title.isBlank()) return ServiceResult.fail("bad_args", "标题为空")
        if (ChatStore.conversations.value.none { it.id == id }) {
            return ServiceResult.fail("not_found", "没有会话 $id")
        }
        ChatStore.rename(id, title)
        return ServiceResult.ok("已重命名", JSONObject().put("id", id).put("title", title))
    }

    private fun clear(id: Long): ServiceResult {
        val target = if (id >= 0) {
            ChatStore.conversations.value.firstOrNull { it.id == id }
        } else {
            ChatStore.conversations.value.firstOrNull { it.id == ChatStore.activeId.value }
        } ?: return ServiceResult.fail("not_found", "没有会话 $id")
        ChatStore.select(target.id)
        ChatStore.clearActive()
        return ServiceResult.ok("已清空会话", JSONObject().put("id", target.id))
    }

    private fun require(id: Long): ChatConversation =
        ChatStore.conversations.value.first { it.id == id }

    private suspend fun conversationJson(
        conversation: ChatConversation,
        active: Boolean,
    ): JSONObject {
        val usage = runCatching { history.chatUsage.forSession(conversation.id) }.getOrNull()
        return JSONObject()
            .put("id", conversation.id)
            .put("title", conversation.title)
            .put("active", active)
            .put("createdAt", conversation.createdAt)
            .put("updatedAt", conversation.updatedAt)
            .put("messageCount", conversation.messages.size)
            .put("userMessages", conversation.messages.count { it.role == ChatRole.User })
            .put("assistantMessages", conversation.messages.count { it.role == ChatRole.Assistant })
            .put("providerId", conversation.providerId)
            .put("model", conversation.model)
            .put("thinkingLevel", conversation.thinkingLevel)
            .put("inputTokens", usage?.inputTokens ?: conversation.inputTokens)
            .put("outputTokens", usage?.outputTokens ?: conversation.outputTokens)
            .put("totalTokens", usage?.totalTokens ?: (conversation.inputTokens + conversation.outputTokens))
            .put("costMicros", usage?.costMicros ?: conversation.costMicros)
            .put("lastMessageAt", conversation.messages.maxOfOrNull { it.createdAt })
    }
}
