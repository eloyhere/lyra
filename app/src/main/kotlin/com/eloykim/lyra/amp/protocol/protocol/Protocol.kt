package com.eloykim.lyra.amp.protocol.protocol

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Signature
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.isTimestamp

/**
 * AMP v0.1 protocol surfaces: the A-surface message envelope and the M-surface
 * model verbs. Mirrors `src/protocol/protocol.ts`, `docs/schemas/message.schema.json`
 * and `docs/schemas/verb.schema.json`.
 */

/** One envelope, seven performatives. */
enum class Performative(val wire: String) {
    INFORM("inform"),
    REQUEST("request"),
    PROPOSE("propose"),
    COMMIT("commit"),
    DECLINE("decline"),
    CANCEL("cancel"),
    RELEASE("release");

    companion object {
        fun fromWire(value: String): Performative =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 performative: $value")

        fun isKnown(value: String): Boolean = entries.any { it.wire == value }
    }
}

/**
 * Closed error enum. `retryable` is a property of the code, not of the message;
 * the reference publishes no retry table, so this port only maps the wire names.
 */
enum class ErrorCode(val wire: String) {
    DENIED("denied"),
    UNAVAILABLE("unavailable"),
    INVALID("invalid"),
    EXPIRED("expired"),
    CONFLICT("conflict"),
    BUDGET_EXCEEDED("budget_exceeded"),
    UNSUPPORTED("unsupported"),
    INTERNAL("internal");

    companion object {
        fun fromWire(value: String): ErrorCode =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 error code: $value")
    }
}

/** Interrupt class carried by `request` and `ask` payloads. */
enum class InterruptClass(val wire: String) {
    SAFETY("safety"),
    COMMITMENT("commitment"),
    OPPORTUNITY("opportunity"),
    CURIOSITY("curiosity");

    companion object {
        fun fromWire(value: String): InterruptClass =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 interrupt class: $value")
    }
}

/* ------------------------------- A-surface ------------------------------- */

/** Type-dependent payload. Every field is optional; absent fields are omitted. */
data class MessagePayload(
    val claim: Ref? = null,
    val warrant: Ref? = null,
    val target: Ref? = null,
    val interruptClass: InterruptClass? = null,
    val whyNow: String? = null,
    val ifIgnored: String? = null,
    val cost: Budget? = null,
    val proposal: String? = null,
    val ref: Ref? = null,
    val request: Id? = null,
    val reason: String? = null,
    val code: ErrorCode? = null,
    val note: String? = null,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "claim" to claim?.toJson(),
        "warrant" to warrant?.toJson(),
        "target" to target?.toJson(),
        "class" to interruptClass?.let { jsonText(it.wire) },
        "why_now" to whyNow?.let { jsonText(it) },
        "if_ignored" to ifIgnored?.let { jsonText(it) },
        "cost" to cost?.toJson(),
        "proposal" to proposal?.let { jsonText(it) },
        "ref" to ref?.toJson(),
        "request" to request?.let { jsonText(it) },
        "reason" to reason?.let { jsonText(it) },
        "code" to code?.let { jsonText(it.wire) },
        "note" to note?.let { jsonText(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): MessagePayload {
            val obj = value.asObject("message.payload")
            return MessagePayload(
                claim = obj.obj("claim")?.let { Ref.fromJson(it) },
                warrant = obj.obj("warrant")?.let { Ref.fromJson(it) },
                target = obj.obj("target")?.let { Ref.fromJson(it) },
                interruptClass = obj.string("class")?.let { InterruptClass.fromWire(it) },
                whyNow = obj.string("why_now"),
                ifIgnored = obj.string("if_ignored"),
                cost = obj.obj("cost")?.let { Budget.fromJson(it) },
                proposal = obj.string("proposal"),
                ref = obj.obj("ref")?.let { Ref.fromJson(it) },
                request = obj.string("request"),
                reason = obj.string("reason"),
                code = obj.string("code")?.let { ErrorCode.fromWire(it) },
                note = obj.string("note"),
            )
        }
    }
}

/** One authenticated A-surface message; nothing here encodes sincerity. */
data class Message(
    val id: Id,
    val type: Performative,
    val from: Ref,
    val to: Ref,
    val thread: Id,
    val replyTo: Id? = null,
    val sent: Timestamp,
    val ttl: Int? = null,
    val payload: MessagePayload,
    val signature: Signature? = null,
) {
    init {
        ProtocolIds.require(id, "message.id")
        ProtocolIds.require(thread, "message.thread")
        replyTo?.let { ProtocolIds.require(it, "message.reply_to") }
        require(isTimestamp(sent)) { "message.sent 不是合法时间" }
        require(ttl == null || ttl >= 0) { "message.ttl 必须 >= 0" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to jsonText(AmpWire.VERSION),
        "kind" to jsonText("message"),
        "id" to jsonText(id),
        "type" to jsonText(type.wire),
        "from" to from.toJson(),
        "to" to to.toJson(),
        "thread" to jsonText(thread),
        "reply_to" to replyTo?.let { jsonText(it) },
        "sent" to jsonText(sent),
        "ttl" to ttl?.let { jsonNumber(it) },
        "payload" to payload.toJson(),
        "signature" to signature?.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): Message {
            val obj = value.asObject("message")
            require(obj.requireString("amp") == AmpWire.VERSION) { "message.amp 必须是 ${AmpWire.VERSION}" }
            require(obj.requireString("kind") == "message") { "message.kind 必须是 message" }
            return Message(
                id = obj.requireString("id"),
                type = Performative.fromWire(obj.requireString("type")),
                from = Ref.fromJson(obj.requireObj("from")),
                to = Ref.fromJson(obj.requireObj("to")),
                thread = obj.requireString("thread"),
                replyTo = obj.string("reply_to"),
                sent = obj.requireString("sent"),
                ttl = obj.int("ttl"),
                payload = MessagePayload.fromJson(obj.requireObj("payload")),
                signature = obj.obj("signature")?.let { Signature.fromJson(it) },
            )
        }
    }
}

/* ------------------------------- M-surface ------------------------------- */

/** Model verbs. The call envelope is `amp`, `kind`, `verb`, an optional id, then verb fields. */
enum class VerbName(val wire: String) {
    ATTEND("attend"),
    PLAN("plan"),
    ACT("act"),
    ASK("ask"),
    NOTE("note"),
    REFLECT("reflect"),
    READ("read");

    companion object {
        fun fromWire(value: String): VerbName =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 verb: $value")
    }
}

enum class PlanMode(val wire: String) {
    DRAFT("draft"),
    COMMIT("commit");

    companion object {
        fun fromWire(value: String): PlanMode =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 plan.mode: $value")
    }
}

enum class NoteAs(val wire: String) {
    CLAIM("claim"),
    HYPOTHESIS("hypothesis"),
    UNKNOWN("unknown"),
    CONCEPT("concept");

    companion object {
        fun fromWire(value: String): NoteAs =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 note.as: $value")
    }
}

enum class ReflectScope(val wire: String) {
    TURN("turn"),
    TASK("task"),
    SESSION("session");

    companion object {
        fun fromWire(value: String): ReflectScope =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 reflect.scope: $value")
    }
}

data class ExpectationCall(
    val observation: String,
    val check: String,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "observation" to jsonText(observation),
        "check" to jsonText(check),
    )

    companion object {
        fun fromJson(value: JsonValue): ExpectationCall {
            val obj = value.asObject("expect")
            return ExpectationCall(
                observation = obj.requireString("observation"),
                check = obj.requireString("check"),
            )
        }
    }
}

sealed interface VerbCall {
    val verb: VerbName
    fun toJson(): JsonValue.Obj
}

data class AttendCall(
    val id: Id? = null,
    val target: Ref,
    val why: String,
    val budget: Budget? = null,
) : VerbCall {

    override val verb: VerbName get() = VerbName.ATTEND

    init {
        id?.let { ProtocolIds.require(it, "call.id") }
    }

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to jsonText(AmpWire.VERSION),
        "kind" to jsonText("call"),
        "verb" to jsonText(verb.wire),
        "id" to id?.let { jsonText(it) },
        "target" to target.toJson(),
        "why" to jsonText(why),
        "budget" to budget?.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): AttendCall {
            val obj = callObject(value, VerbName.ATTEND)
            return AttendCall(
                id = obj.string("id"),
                target = Ref.fromJson(obj.requireObj("target")),
                why = obj.requireString("why"),
                budget = obj.obj("budget")?.let { Budget.fromJson(it) },
            )
        }
    }
}

data class PlanCall(
    val id: Id? = null,
    val goal: Ref,
    val process: Ref? = null,
    val mode: PlanMode? = null,
) : VerbCall {

    override val verb: VerbName get() = VerbName.PLAN

    init {
        id?.let { ProtocolIds.require(it, "call.id") }
    }

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to jsonText(AmpWire.VERSION),
        "kind" to jsonText("call"),
        "verb" to jsonText(verb.wire),
        "id" to id?.let { jsonText(it) },
        "goal" to goal.toJson(),
        "process" to process?.toJson(),
        "mode" to mode?.let { jsonText(it.wire) },
    )

    companion object {
        fun fromJson(value: JsonValue): PlanCall {
            val obj = callObject(value, VerbName.PLAN)
            return PlanCall(
                id = obj.string("id"),
                goal = Ref.fromJson(obj.requireObj("goal")),
                process = obj.obj("process")?.let { Ref.fromJson(it) },
                mode = obj.string("mode")?.let { PlanMode.fromWire(it) },
            )
        }
    }
}

data class ActCall(
    val id: Id? = null,
    val action: String,
    val ref: Ref? = null,
    val args: JsonValue.Obj? = null,
    val expect: ExpectationCall? = null,
    val budget: Budget? = null,
) : VerbCall {

    override val verb: VerbName get() = VerbName.ACT

    init {
        id?.let { ProtocolIds.require(it, "call.id") }
    }

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to jsonText(AmpWire.VERSION),
        "kind" to jsonText("call"),
        "verb" to jsonText(verb.wire),
        "id" to id?.let { jsonText(it) },
        "action" to jsonText(action),
        "ref" to ref?.toJson(),
        "args" to args,
        "expect" to expect?.toJson(),
        "budget" to budget?.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): ActCall {
            val obj = callObject(value, VerbName.ACT)
            return ActCall(
                id = obj.string("id"),
                action = obj.requireString("action"),
                ref = obj.obj("ref")?.let { Ref.fromJson(it) },
                args = obj.obj("args"),
                expect = obj.obj("expect")?.let { ExpectationCall.fromJson(it) },
                budget = obj.obj("budget")?.let { Budget.fromJson(it) },
            )
        }
    }
}

data class AskCall(
    val id: Id? = null,
    val question: String,
    val to: Ref? = null,
    val interruptClass: InterruptClass? = null,
    val urgency: Int? = null,
    val expires: Timestamp? = null,
) : VerbCall {

    override val verb: VerbName get() = VerbName.ASK

    init {
        id?.let { ProtocolIds.require(it, "call.id") }
        require(urgency == null || urgency in 1..5) { "ask.urgency 必须在 1..5" }
        require(expires == null || isTimestamp(expires)) { "ask.expires 不是合法时间" }
    }

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to jsonText(AmpWire.VERSION),
        "kind" to jsonText("call"),
        "verb" to jsonText(verb.wire),
        "id" to id?.let { jsonText(it) },
        "question" to jsonText(question),
        "to" to to?.toJson(),
        "class" to interruptClass?.let { jsonText(it.wire) },
        "urgency" to urgency?.let { jsonNumber(it) },
        "expires" to expires?.let { jsonText(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): AskCall {
            val obj = callObject(value, VerbName.ASK)
            return AskCall(
                id = obj.string("id"),
                question = obj.requireString("question"),
                to = obj.obj("to")?.let { Ref.fromJson(it) },
                interruptClass = obj.string("class")?.let { InterruptClass.fromWire(it) },
                urgency = obj.int("urgency"),
                expires = obj.string("expires"),
            )
        }
    }
}

data class NoteCall(
    val id: Id? = null,
    val assertion: String,
    val asKind: NoteAs,
    val tier: Tier? = null,
    val evidence: List<Ref>? = null,
    val refutesIf: String? = null,
    val label: String? = null,
) : VerbCall {

    override val verb: VerbName get() = VerbName.NOTE

    init {
        id?.let { ProtocolIds.require(it, "call.id") }
    }

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to jsonText(AmpWire.VERSION),
        "kind" to jsonText("call"),
        "verb" to jsonText(verb.wire),
        "id" to id?.let { jsonText(it) },
        "assertion" to jsonText(assertion),
        "as" to jsonText(asKind.wire),
        "tier" to tier?.let { jsonText(it.wire) },
        "evidence" to evidence?.let { refs -> JsonValue.Arr(refs.map { it.toJson() }) },
        "refutes_if" to refutesIf?.let { jsonText(it) },
        "label" to label?.let { jsonText(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): NoteCall {
            val obj = callObject(value, VerbName.NOTE)
            return NoteCall(
                id = obj.string("id"),
                assertion = obj.requireString("assertion"),
                asKind = NoteAs.fromWire(obj.requireString("as")),
                tier = obj.string("tier")?.let { Tier.fromWire(it) },
                evidence = obj.arr("evidence")?.items?.map { Ref.fromJson(it) },
                refutesIf = obj.string("refutes_if"),
                label = obj.string("label"),
            )
        }
    }
}

data class ReflectCall(
    val id: Id? = null,
    val scope: ReflectScope? = null,
) : VerbCall {

    override val verb: VerbName get() = VerbName.REFLECT

    init {
        id?.let { ProtocolIds.require(it, "call.id") }
    }

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to jsonText(AmpWire.VERSION),
        "kind" to jsonText("call"),
        "verb" to jsonText(verb.wire),
        "id" to id?.let { jsonText(it) },
        "scope" to scope?.let { jsonText(it.wire) },
    )

    companion object {
        fun fromJson(value: JsonValue): ReflectCall {
            val obj = callObject(value, VerbName.REFLECT)
            return ReflectCall(
                id = obj.string("id"),
                scope = obj.string("scope")?.let { ReflectScope.fromWire(it) },
            )
        }
    }
}

data class ReadCall(
    val id: Id? = null,
    val handle: String,
    val cursor: Int,
    val limit: Int? = null,
) : VerbCall {

    override val verb: VerbName get() = VerbName.READ

    init {
        id?.let { ProtocolIds.require(it, "call.id") }
        require(cursor >= 0) { "read.cursor 必须 >= 0" }
        require(limit == null || limit >= 0) { "read.limit 必须 >= 0" }
    }

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to jsonText(AmpWire.VERSION),
        "kind" to jsonText("call"),
        "verb" to jsonText(verb.wire),
        "id" to id?.let { jsonText(it) },
        "handle" to jsonText(handle),
        "cursor" to jsonNumber(cursor),
        "limit" to limit?.let { jsonNumber(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): ReadCall {
            val obj = callObject(value, VerbName.READ)
            return ReadCall(
                id = obj.string("id"),
                handle = obj.requireString("handle"),
                cursor = obj.requireInt("cursor"),
                limit = obj.int("limit"),
            )
        }
    }
}

/** Parses one M-surface call by its `verb`, rejecting unknown verbs. */
fun parseVerbCall(value: JsonValue): VerbCall = when (VerbName.fromWire(value.asObject("verb call").requireString("verb"))) {
    VerbName.ATTEND -> AttendCall.fromJson(value)
    VerbName.PLAN -> PlanCall.fromJson(value)
    VerbName.ACT -> ActCall.fromJson(value)
    VerbName.ASK -> AskCall.fromJson(value)
    VerbName.NOTE -> NoteCall.fromJson(value)
    VerbName.REFLECT -> ReflectCall.fromJson(value)
    VerbName.READ -> ReadCall.fromJson(value)
}

private fun callObject(value: JsonValue, expected: VerbName): JsonValue.Obj {
    val obj = value.asObject("${expected.wire} call")
    require(obj.requireString("amp") == AmpWire.VERSION) { "${expected.wire}.amp 必须是 ${AmpWire.VERSION}" }
    require(obj.requireString("kind") == "call") { "${expected.wire}.kind 必须是 call" }
    require(obj.requireString("verb") == expected.wire) { "${expected.wire}.verb 必须是 ${expected.wire}" }
    return obj
}

/** Protocol constants and wire-name lookups used by transport code. */
object AmpProtocol {
    const val VERSION: String = AmpWire.VERSION

    val performatives: List<Performative> get() = Performative.entries

    fun isPerformative(value: String): Boolean = Performative.isKnown(value)

    fun errorCode(value: String): ErrorCode = ErrorCode.fromWire(value)
}
