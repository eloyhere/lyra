package com.eloykim.lyra.amp.protocol.ipc

import com.eloykim.lyra.amp.protocol.concurrency.IdempotencyStore
import com.eloykim.lyra.amp.protocol.concurrency.once
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.receipt.Outcome
import com.eloykim.lyra.amp.protocol.receipt.Receipt
import com.eloykim.lyra.amp.protocol.receipt.ReceiptOp
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.asObject

/**
 * `src/ipc/mailbox.ts` 的忠实移植：跨进程邮箱。
 *
 * 跨进程流量只有消息：有界队列、一次投递、对象链里的 receipt，以及唤醒阻塞在
 * `mailbox:<pid>` 上的线程的条件通知。发送需要在收件人邮箱上有 `send` capability，
 * 因此两个进程默认不共享任何东西。
 *
 * ## 绑定（参考实现直接依赖未移植的兄弟模块）
 *
 * - `MessageEntity` / `MessageService`（database 层尚未移植）→ [MessageRecord] 与
 *   [MessagePort] 窄端口。`MessageRecord` 与实体行同形（snake_case），`body` 在这里是结构化
 *   JSON；SQL 适配器把 `body` 存成 JSON 文本即可。
 * - `requirePermission`（authz 层尚未移植）→ [MailboxAuth]。
 * - `putObject`（database/store 尚未移植）→ [IpcObjectSink]。
 * - `notifyCondition`（thread 层）→ [ConditionNotifier]；`thread.ThreadStore.notify` 可直接
 *   适配成这个接口。
 * - `once`（幂等）直接复用已移植的 `concurrency.IdempotencyStore`。
 * - `Receipt` 复用已移植的 `receipt.Receipt`。
 */
data class Subject(
    val pid: Int? = null,
    val tid: Int? = null,
)

/** 邮箱资源，用于 capability 检查。 */
data class MailboxResource(
    val id: String,
    val ownerPid: Int,
    val kind: String = "mailbox",
)

interface MailboxAuth {

    /** 无权限时必须抛出异常（参考的 `requirePermission`）。 */
    fun requirePermission(subject: Subject, action: String, resource: MailboxResource, at: String)
}

interface ConditionNotifier {

    fun notifyCondition(condition: String)
}

/** 对象链窄写入端口，对应 `putObject(db, kind, id, value, at)`。 */
interface IpcObjectSink {

    fun putObject(kind: String, id: String, value: JsonValue.Obj, at: String)
}

class MailboxError(
    val code: String,
    val details: List<String> = emptyList(),
) : IllegalArgumentException("$code: ${details.joinToString(", ")}")

enum class MessageKind(val wire: String) {
    REQUEST("request"),
    RESPONSE("response"),
    EVENT("event"),
    SIGNAL("signal"),
    ;

    companion object {
        fun fromWire(value: String): MessageKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 message kind: $value")
    }
}

/** `mailbox:<pid>`：被阻塞线程等待的条件名。 */
fun mailboxCondition(pid: Int): String = "mailbox:$pid"

/** `messages` 表行的 Kotlin 形状；`body` 是结构化 JSON。 */
data class MessageRecord(
    val msgId: Long,
    val id: String,
    val fromPid: Int,
    val fromTid: Int,
    val toPid: Int,
    val toTid: Int? = null,
    val kind: MessageKind,
    val body: JsonValue,
    val correlationId: String? = null,
    val at: String,
    val deliveredAt: String? = null,
    val receiptId: String? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "msg_id" to jsonNumber(msgId),
        "id" to JsonValue.Str(id),
        "from_pid" to jsonNumber(fromPid),
        "from_tid" to jsonNumber(fromTid),
        "to_pid" to jsonNumber(toPid),
        "to_tid" to toTid?.let { jsonNumber(it) },
        "kind" to JsonValue.Str(kind.wire),
        "body" to body,
        "correlation_id" to correlationId?.let { JsonValue.Str(it) },
        "at" to JsonValue.Str(at),
        "delivered_at" to deliveredAt?.let { JsonValue.Str(it) },
        "receipt_id" to receiptId?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): MessageRecord {
            val obj = value.asObject("message")
            val body: JsonValue = obj.fields["body"]
                ?: throw IllegalArgumentException("缺少字段 body")
            return MessageRecord(
                msgId = obj.long("msg_id") ?: throw IllegalArgumentException("缺少整数字段 msg_id"),
                id = obj.requireString("id"),
                fromPid = obj.requireInt("from_pid"),
                fromTid = obj.requireInt("from_tid"),
                toPid = obj.requireInt("to_pid"),
                toTid = obj.int("to_tid"),
                kind = MessageKind.fromWire(obj.requireString("kind")),
                body = body,
                correlationId = obj.string("correlation_id"),
                at = obj.requireString("at"),
                deliveredAt = obj.string("delivered_at"),
                receiptId = obj.string("receipt_id"),
            )
        }
    }
}

data class NewMessage(
    val id: String,
    val fromPid: Int,
    val fromTid: Int,
    val toPid: Int,
    val kind: MessageKind,
    val body: JsonValue,
    val correlationId: String? = null,
    val at: String,
)

/** 消息服务窄端口：参考 `MessageService` 的五个方法。 */
interface MessagePort {

    fun processId(pid: Int): String?

    fun countFrom(fromPid: Int): Int

    fun pendingCount(toPid: Int): Int

    fun pending(toPid: Int, limit: Int): List<MessageRecord>

    fun send(message: NewMessage): MessageRecord

    fun markDelivered(msgId: Long, receiptId: String, at: String)
}

data class MailboxSendInput(
    val toPid: Int,
    val kind: MessageKind,
    val body: JsonValue,
    val fromPid: Int? = null,
    val fromTid: Int? = null,
    val correlationId: String? = null,
    val id: String? = null,
)

/**
 * 参考的 `MailboxOptions`。Kotlin 主构造器直接暴露 `capacity`；保留同名类型入口，
 * 让参考调用点的 `MailboxOptions(capacity = n)` 可以原样迁移。
 */
data class MailboxOptions(val capacity: Int = 64)

data class MailboxDelivery(
    val message: MessageRecord,
    val receiptId: String,
)

class Mailbox(
    private val messages: MessagePort,
    private val auth: MailboxAuth,
    private val idempotency: IdempotencyStore,
    private val sink: IpcObjectSink,
    private val notifier: ConditionNotifier,
    val capacity: Int = 64,
) {

    constructor(
        messages: MessagePort,
        auth: MailboxAuth,
        idempotency: IdempotencyStore,
        sink: IpcObjectSink,
        notifier: ConditionNotifier,
        options: MailboxOptions,
    ) : this(messages, auth, idempotency, sink, notifier, options.capacity)

    fun send(subject: Subject, input: MailboxSendInput, at: String): MessageRecord {
        val fromPid: Int = input.fromPid ?: subject.pid
            ?: throw MailboxError("unknown_recipient", listOf("sender has no pid"))
        messages.processId(input.toPid)
            ?: throw MailboxError("unknown_recipient", listOf("pid ${input.toPid}"))
        auth.requirePermission(
            subject,
            "send",
            MailboxResource(id = "${input.toPid}", ownerPid = input.toPid),
            at,
        )
        if (messages.pendingCount(input.toPid) >= capacity) {
            throw MailboxError(
                "mailbox_full",
                listOf("pid ${input.toPid} has $capacity undelivered messages"),
            )
        }

        val send: () -> MessageRecord = {
            val seq: Int = messages.countFrom(fromPid)
            messages.send(
                NewMessage(
                    id = input.id ?: "m.$fromPid.${seq + 1}",
                    fromPid = fromPid,
                    fromTid = input.fromTid ?: subject.tid ?: 0,
                    toPid = input.toPid,
                    kind = input.kind,
                    body = input.body,
                    correlationId = input.correlationId,
                    at = at,
                ),
            )
        }

        val message: MessageRecord = if (input.correlationId == null) {
            send()
        } else {
            once(
                store = idempotency,
                key = "msg:$fromPid:${input.correlationId}",
                at = at,
                encode = { CanonicalJson.encode(it.toJson()) },
                decode = { MessageRecord.fromJson(JsonParser.parse(it)) },
                fn = send,
            ).value
        }
        notifier.notifyCondition(mailboxCondition(input.toPid))
        return message
    }

    fun receive(subject: Subject, at: String): MailboxDelivery? {
        val pid: Int = subject.pid
            ?: throw MailboxError("unknown_recipient", listOf("receiver has no pid"))
        auth.requirePermission(
            subject,
            "receive",
            MailboxResource(id = "$pid", ownerPid = pid),
            at,
        )
        val message: MessageRecord = messages.pending(pid, 1).firstOrNull() ?: return null

        val recipient: String = messages.processId(pid)
            ?: throw MailboxError("unknown_recipient", listOf("pid $pid"))
        val receiptId = "rc.msg.${message.msgId}"
        val receipt = Receipt(
            id = receiptId,
            op = ReceiptOp.READ,
            target = Ref(kind = RefKind.PROCESS, id = recipient),
            at = at,
            usage = Budget(tokens = 0, calls = 0),
            outcome = Outcome.SUCCESS,
            note = "message ${message.id} delivered",
        )
        sink.putObject("receipt", receipt.id, receipt.toJson(), at)
        messages.markDelivered(message.msgId, receiptId, at)
        return MailboxDelivery(message = message, receiptId = receiptId)
    }
}
