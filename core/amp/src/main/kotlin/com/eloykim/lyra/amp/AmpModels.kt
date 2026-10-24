package com.eloykim.lyra.amp

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * AMP 0.1 的对象模型（只覆盖 Lyra 用得到的部分）。
 *
 * 与 schema 对齐：`docs/schemas/{frame,receipt,manifest,common}.schema.json`
 * 不变量：A-1 焦点有据 / A-2 忽略显式 / A-3 预算刚性 / X-1 只追加。
 */
object Amp {
    const val VERSION = "0.1"
    const val KIND_FRAME = "frame"
    const val KIND_RECEIPT = "receipt"
    const val KIND_MANIFEST = "manifest"

    fun ts(instant: Instant = Instant.now()): String =
        DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))
}

enum class Tier { T0, T1, T2, T3, T4 }

enum class Mode { explore, exploit, triage, reflect, rest }

enum class SlotRole {
    goal, question, evidence, concept, process, tool, memory, self, other, threat, artifact,
}

enum class SlotState { active, released, evicted, done }

enum class IgnoreReason {
    low_voi, out_of_scope, untrusted, budget, blocked, habituated, deferred,
}

enum class ImpasseState {
    TIE, STUCK, STALLED, CONFLICT, INFEASIBLE, UNKNOWN, UNVERIFIED, DRIFT, BUDGET,
}

enum class ReceiptOp { attend, act, ask, note, plan, reflect, read }

enum class Outcome { success, partial, failure, blocked }

data class Ref(val kind: String, val id: String, val label: String? = null) {
    init {
        AmpIds.require(id, "Ref.id")
    }

    fun toJson(): JVal = obj("kind" to kind, "id" to id, "label" to label)
}

data class Budget(
    val tokens: Int? = null,
    val ms: Int? = null,
    val calls: Int? = null,
    val interrupts: Int? = null,
    val money: Double? = null,
) {
    fun toJson(): JVal = obj(
        "tokens" to tokens,
        "ms" to ms,
        "calls" to calls,
        "interrupts" to interrupts,
        "money" to money,
    )
}

data class Usage(
    val tokens: Int? = null,
    val ms: Int? = null,
    val calls: Int? = null,
    val interrupts: Int? = null,
    val money: Double? = null,
) {
    fun toJson(): JVal = obj(
        "tokens" to tokens,
        "ms" to ms,
        "calls" to calls,
        "interrupts" to interrupts,
        "money" to money,
    )

    fun exceeds(other: Budget): Boolean {
        tokens?.let { v -> other.tokens?.let { if (v > it) return true } }
        ms?.let { v -> other.ms?.let { if (v > it) return true } }
        calls?.let { v -> other.calls?.let { if (v > it) return true } }
        interrupts?.let { v -> other.interrupts?.let { if (v > it) return true } }
        money?.let { v -> other.money?.let { if (v > it) return true } }
        return false
    }
}

data class Slot(
    val target: Ref,
    val role: SlotRole,
    val why: String,
    val state: SlotState = SlotState.active,
    val tier: Tier? = null,
    val salience: Double? = null,
    val voi: Double? = null,
    val cost: Budget? = null,
    val budget: Budget? = null,
    val ttlTurns: Int? = null,
) {
    init {
        require(why.isNotBlank()) { "A-1：slot 必须带 why" }
        require(cost != null) { "A-1：slot 必须带 cost" }
    }

    fun toJson(): JVal = obj(
        "target" to target.toJson(),
        "role" to role.name,
        "tier" to tier?.name,
        "salience" to salience,
        "voi" to voi,
        "cost" to cost?.toJson(),
        "budget" to budget?.toJson(),
        "why" to why,
        "ttl_turns" to ttlTurns,
        "state" to state.name,
    )
}

data class Ignored(
    val target: Ref,
    val reason: IgnoreReason,
    val revisitIf: String?,
) {
    fun toJson(): JVal = obj(
        "target" to target.toJson(),
        "reason" to reason.name,
        "revisit_if" to revisitIf,
    )
}

data class FrameBudget(val allocated: Budget, val spent: Usage) {
    fun toJson(): JVal = obj("allocated" to allocated.toJson(), "spent" to spent.toJson())
}

data class Frame(
    val id: String,
    val agent: String,
    val cycle: Int,
    val mode: Mode,
    val slots: List<Slot>,
    val ignored: List<Ignored>,
    val budget: FrameBudget,
    val ts: String = Amp.ts(),
    val parent: Ref? = null,
    val goal: Ref? = null,
    val digest: String? = null,
    val openQuestions: List<Ref> = emptyList(),
    val impasse: Pair<ImpasseState, String?>? = null,
    val note: String? = null,
) {
    init {
        AmpIds.require(id, "Frame.id")
        AmpIds.require(agent, "Frame.agent")
        require(cycle >= 0) { "cycle >= 0" }
        require(slots.size <= 9) { "slots 最多 9 个（frame.schema.json maxItems）" }
    }

    fun body(): JVal = obj(
        "amp" to Amp.VERSION,
        "kind" to Amp.KIND_FRAME,
        "id" to id,
        "agent" to agent,
        "cycle" to cycle,
        "ts" to ts,
        "parent" to parent?.toJson(),
        "mode" to mode.name,
        "goal" to goal?.toJson(),
        "slots" to arr(slots.map { it.toJson() }),
        "ignored" to arr(ignored.map { it.toJson() }),
        "budget" to budget.toJson(),
        "open_questions" to arr(openQuestions.map { it.toJson() }),
        "impasse" to impasse?.let { (state, detail) ->
            obj("state" to state.name, "detail" to detail)
        },
        "note" to note,
    )

    /** 计算并填入 digest（对不含 digest 的规范 JSON 取 sha256）。 */
    fun sealed(): Frame = copy(digest = Digests.sha256(body().render()))

    fun toJson(): JVal {
        val d = digest ?: return body()
        return obj(
            "amp" to Amp.VERSION,
            "kind" to Amp.KIND_FRAME,
            "id" to id,
            "agent" to agent,
            "cycle" to cycle,
            "ts" to ts,
            "parent" to parent?.toJson(),
            "digest" to d,
            "mode" to mode.name,
            "goal" to goal?.toJson(),
            "slots" to arr(slots.map { it.toJson() }),
            "ignored" to arr(ignored.map { it.toJson() }),
            "budget" to budget.toJson(),
            "open_questions" to arr(openQuestions.map { it.toJson() }),
            "impasse" to impasse?.let { (state, detail) ->
                obj("state" to state.name, "detail" to detail)
            },
            "note" to note,
        )
    }
}

data class Receipt(
    val id: String,
    val op: ReceiptOp,
    val usage: Usage,
    val outcome: Outcome,
    val at: String = Amp.ts(),
    val target: Ref? = null,
    val ms: Int? = null,
    val predictedVoi: Double? = null,
    val realizedVoi: Double? = null,
    val note: String? = null,
) {
    init {
        AmpIds.require(id, "Receipt.id")
        require(!usage.exceeds(Budget(tokens = Int.MAX_VALUE, ms = Int.MAX_VALUE, calls = Int.MAX_VALUE)))
    }

    fun toJson(): JVal = obj(
        "amp" to Amp.VERSION,
        "kind" to Amp.KIND_RECEIPT,
        "id" to id,
        "op" to op.name,
        "target" to target?.toJson(),
        "at" to at,
        "ms" to ms,
        "usage" to usage.toJson(),
        "outcome" to outcome.name,
        "predicted_voi" to predictedVoi,
        "realized_voi" to realizedVoi,
        "note" to note,
    )
}

/**
 * 违反不变量时抛出。用于「写入账本前」的自检，而不是事后审计。
 */
fun Frame.violations(): List<String> = buildList {
    if (slots.any { it.why.isBlank() }) add("A-1：slot 缺少 why")
    if (slots.any { it.cost == null }) add("A-1：slot 缺少 cost")
    if (ignored.any { it.revisitIf.isNullOrBlank() }) add("A-2：ignored 缺少 revisit_if")
    if (budget.spent.exceeds(budget.allocated)) add("A-3：spent 超出 allocated")
    if (digest != null && !Digests.isValid(digest)) add("digest 格式不合法")
}
