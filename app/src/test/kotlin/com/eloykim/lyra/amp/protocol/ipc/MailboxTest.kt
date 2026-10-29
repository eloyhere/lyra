package com.eloykim.lyra.amp.protocol.ipc

import com.eloykim.lyra.amp.protocol.concurrency.InMemoryIdempotencyStore
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MailboxTest {

    private class FakeMessagePort : MessagePort {

        val processes = LinkedHashMap<Int, String>()
        val messages = ArrayList<MessageRecord>()
        private var nextMsgId = 1L

        override fun processId(pid: Int): String? = processes[pid]

        override fun countFrom(fromPid: Int): Int = messages.count { it.fromPid == fromPid }

        override fun pendingCount(toPid: Int): Int =
            messages.count { it.toPid == toPid && it.deliveredAt == null }

        override fun pending(toPid: Int, limit: Int): List<MessageRecord> =
            messages.filter { it.toPid == toPid && it.deliveredAt == null }
                .sortedBy { it.msgId }
                .take(limit)

        override fun send(message: NewMessage): MessageRecord {
            val record = MessageRecord(
                msgId = nextMsgId++,
                id = message.id,
                fromPid = message.fromPid,
                fromTid = message.fromTid,
                toPid = message.toPid,
                kind = message.kind,
                body = message.body,
                correlationId = message.correlationId,
                at = message.at,
            )
            messages.add(record)
            return record
        }

        override fun markDelivered(msgId: Long, receiptId: String, at: String) {
            val index = messages.indexOfFirst { it.msgId == msgId }
            messages[index] = messages[index].copy(deliveredAt = at, receiptId = receiptId)
        }
    }

    private class RecordingAuth : MailboxAuth {

        val calls = ArrayList<Triple<Subject, String, MailboxResource>>()

        override fun requirePermission(
            subject: Subject,
            action: String,
            resource: MailboxResource,
            at: String,
        ) {
            calls.add(Triple(subject, action, resource))
        }
    }

    private class DenyingAuth : MailboxAuth {

        override fun requirePermission(
            subject: Subject,
            action: String,
            resource: MailboxResource,
            at: String,
        ) {
            throw SecurityException("denied $action on ${resource.id}")
        }
    }

    private class RecordingSink : IpcObjectSink {

        val entries = ArrayList<Pair<String, JsonValue.Obj>>()

        override fun putObject(kind: String, id: String, value: JsonValue.Obj, at: String) {
            entries.add(kind to value)
        }
    }

    private class RecordingNotifier : ConditionNotifier {

        val conditions = ArrayList<String>()

        override fun notifyCondition(condition: String) {
            conditions.add(condition)
        }
    }

    private val at = "2026-10-24T10:00:00Z"
    private val body: JsonValue = JsonParser.parse("""{"question":"why"}""")

    private fun port(): FakeMessagePort = FakeMessagePort().apply {
        processes[1] = "p.sender"
        processes[2] = "p.worker"
    }

    @Test
    fun `发送写入邮箱并通知条件`() {
        val port = port()
        val notifier = RecordingNotifier()
        val mailbox = Mailbox(
            port, RecordingAuth(), InMemoryIdempotencyStore(), RecordingSink(), notifier,
        )
        val sent = mailbox.send(
            Subject(pid = 1, tid = 7),
            MailboxSendInput(toPid = 2, kind = MessageKind.REQUEST, body = body),
            at,
        )
        assertEquals("m.1.1", sent.id)
        assertEquals(1, sent.fromPid)
        assertEquals(7, sent.fromTid)
        assertEquals(2, sent.toPid)
        assertEquals(listOf("mailbox:2"), notifier.conditions)
        assertEquals(1, port.pendingCount(2))
    }

    @Test
    fun `接收生成 receipt 并标记投递`() {
        val port = port()
        val sink = RecordingSink()
        val mailbox = Mailbox(port, RecordingAuth(), InMemoryIdempotencyStore(), sink, RecordingNotifier())
        mailbox.send(Subject(pid = 1), MailboxSendInput(toPid = 2, kind = MessageKind.REQUEST, body = body), at)

        val delivery = mailbox.receive(Subject(pid = 2, tid = 9), at)!!
        assertEquals("rc.msg.1", delivery.receiptId)
        assertEquals("m.1.1", delivery.message.id)
        assertEquals(1, delivery.message.msgId)

        val receipt = sink.entries.single().second
        assertEquals("receipt", receipt.string("kind"))
        assertEquals("rc.msg.1", receipt.string("id"))
        assertEquals("read", receipt.string("op"))
        assertEquals("success", receipt.string("outcome"))
        assertEquals("p.worker", receipt.obj("target")!!.string("id"))
        assertEquals(0, receipt.obj("usage")!!.int("tokens"))
        assertEquals(0, receipt.obj("usage")!!.int("calls"))
        assertTrue(receipt.string("note")!!.contains("delivered"))
        assertEquals(0, port.pendingCount(2))
        assertNull(mailbox.receive(Subject(pid = 2), at))
    }

    @Test
    fun `容量限制在有未投递消息时拒绝发送`() {
        val port = port()
        val mailbox = Mailbox(
            port, RecordingAuth(), InMemoryIdempotencyStore(), RecordingSink(), RecordingNotifier(), capacity = 1,
        )
        mailbox.send(Subject(pid = 1), MailboxSendInput(toPid = 2, kind = MessageKind.EVENT, body = body), at)
        val error = assertThrows(MailboxError::class.java) {
            mailbox.send(Subject(pid = 1), MailboxSendInput(toPid = 2, kind = MessageKind.EVENT, body = body), at)
        }
        assertEquals("mailbox_full", error.code)
    }

    @Test
    fun `相关 id 使发送幂等但仍通知`() {
        val port = port()
        val notifier = RecordingNotifier()
        val mailbox = Mailbox(
            port, RecordingAuth(), InMemoryIdempotencyStore(), RecordingSink(), notifier,
        )
        val first = mailbox.send(
            Subject(pid = 1),
            MailboxSendInput(toPid = 2, kind = MessageKind.REQUEST, body = body, correlationId = "c1"),
            at,
        )
        val second = mailbox.send(
            Subject(pid = 1),
            MailboxSendInput(toPid = 2, kind = MessageKind.REQUEST, body = body, correlationId = "c1"),
            at,
        )
        assertEquals(first.id, second.id)
        assertEquals(1, port.messages.size)
        assertEquals(listOf("mailbox:2", "mailbox:2"), notifier.conditions)
    }

    @Test
    fun `未知收件人缺少 pid 与鉴权失败都被拒绝`() {
        val port = port()
        val auth = RecordingAuth()
        val mailbox = Mailbox(port, auth, InMemoryIdempotencyStore(), RecordingSink(), RecordingNotifier())

        assertEquals(
            "unknown_recipient",
            assertThrows(MailboxError::class.java) {
                mailbox.send(Subject(pid = 1), MailboxSendInput(99, MessageKind.EVENT, body), at)
            }.code,
        )
        assertEquals(
            "unknown_recipient",
            assertThrows(MailboxError::class.java) {
                mailbox.send(Subject(), MailboxSendInput(2, MessageKind.EVENT, body), at)
            }.code,
        )
        assertEquals(
            "unknown_recipient",
            assertThrows(MailboxError::class.java) { mailbox.receive(Subject(), at) }.code,
        )

        val denying = Mailbox(
            port, DenyingAuth(), InMemoryIdempotencyStore(), RecordingSink(), RecordingNotifier(),
        )
        assertThrows(SecurityException::class.java) {
            denying.send(Subject(pid = 1), MailboxSendInput(2, MessageKind.EVENT, body), at)
        }
        assertThrows(SecurityException::class.java) { denying.receive(Subject(pid = 2), at) }
    }

    @Test
    fun `鉴权动作与资源使用 pid 标识`() {
        val port = port()
        val auth = RecordingAuth()
        val mailbox = Mailbox(port, auth, InMemoryIdempotencyStore(), RecordingSink(), RecordingNotifier())
        mailbox.send(Subject(pid = 1), MailboxSendInput(toPid = 2, kind = MessageKind.EVENT, body = body), at)
        mailbox.receive(Subject(pid = 2), at)

        assertEquals(2, auth.calls.size)
        assertEquals("send", auth.calls[0].second)
        assertEquals("2", auth.calls[0].third.id)
        assertEquals(2, auth.calls[0].third.ownerPid)
        assertEquals("mailbox", auth.calls[0].third.kind)
        assertEquals("receive", auth.calls[1].second)
        assertEquals("2", auth.calls[1].third.id)
    }
}
