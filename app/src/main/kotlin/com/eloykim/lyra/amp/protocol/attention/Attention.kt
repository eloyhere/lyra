package com.eloykim.lyra.amp.protocol.attention

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Confidence
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.requireConfidence
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * `src/attention/attention.ts` 的忠实移植：候选、显著性、焦点选择与注意窗口。
 *
 * 绑定说明：本文件里的 [AttentionSlot] / [AttentionIgnored] 是 `frame.Slot` /
 * `frame.Ignored` 的 attention 侧结构等价模型（字段名与 wire 形状一致），并由
 * `toJson()` / `fromJson()` 负责 frame 线格式。集成时用 coordinator 的
 * `com.eloykim.lyra.amp.protocol.frame.Slot` / `Ignored` 替换或做纯转换即可，
 * 选择算法本身不需要改动。
 */

private const val SLOT_STATE_ACTIVE: String = "active"

// ---------------------------------------------------------------------------
// frame 侧枚举与值对象（attention 本地镜像）
// ---------------------------------------------------------------------------

enum class SlotRole(val wire: String) {
    GOAL("goal"),
    QUESTION("question"),
    EVIDENCE("evidence"),
    CONCEPT("concept"),
    PROCESS("process"),
    TOOL("tool"),
    MEMORY("memory"),
    SELF("self"),
    OTHER("other"),
    THREAT("threat"),
    ARTIFACT("artifact"),
    ;

    companion object {
        fun fromWire(value: String): SlotRole =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 slot.role: $value")
    }
}

enum class SlotState(val wire: String) {
    ACTIVE(SLOT_STATE_ACTIVE),
    RELEASED("released"),
    EVICTED("evicted"),
    DONE("done"),
    ;

    companion object {
        fun fromWire(value: String): SlotState =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 slot.state: $value")
    }
}

enum class FrameMode(val wire: String) {
    EXPLORE("explore"),
    EXPLOIT("exploit"),
    TRIAGE("triage"),
    REFLECT("reflect"),
    REST("rest"),
    ;

    companion object {
        fun fromWire(value: String): FrameMode =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 frame.mode: $value")
    }
}

enum class IgnoredReason(val wire: String) {
    LOW_VOI("low_voi"),
    OUT_OF_SCOPE("out_of_scope"),
    UNTRUSTED("untrusted"),
    BUDGET("budget"),
    BLOCKED("blocked"),
    HABITUATED("habituated"),
    DEFERRED("deferred"),
    ;

    companion object {
        fun fromWire(value: String): IgnoredReason =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 ignored.reason: $value")
    }
}

enum class ImpasseState(val wire: String) {
    TIE("TIE"),
    STUCK("STUCK"),
    STALLED("STALLED"),
    CONFLICT("CONFLICT"),
    INFEASIBLE("INFEASIBLE"),
    UNKNOWN("UNKNOWN"),
    UNVERIFIED("UNVERIFIED"),
    DRIFT("DRIFT"),
    BUDGET("BUDGET"),
    ;

    companion object {
        fun fromWire(value: String): ImpasseState =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 impasse: $value")
    }
}

/** `frame.Slot` 的结构等价模型；`toJson()` 产出 frame 线格式的槽位。 */
data class AttentionSlot(
    val target: Ref,
    val role: SlotRole,
    val salience: Confidence,
    val cost: Budget,
    val why: String,
    val state: SlotState,
    val tier: Tier? = null,
    val voi: Confidence? = null,
    val budget: Budget? = null,
    val ttlTurns: Int? = null,
) {

    init {
        requireConfidence(salience, "slot.salience")
        require(why.isNotBlank()) { "slot.why 不能为空" }
        voi?.let { requireConfidence(it, "slot.voi") }
        require(ttlTurns == null || ttlTurns >= 0) { "slot.ttl_turns 必须 >= 0" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "target" to target.toJson(),
        "role" to JsonValue.Str(role.wire),
        "tier" to tier?.let { JsonValue.Str(it.wire) },
        "salience" to jsonNumber(salience),
        "voi" to voi?.let { jsonNumber(it) },
        "cost" to cost.toJson(),
        "budget" to budget?.toJson(),
        "why" to JsonValue.Str(why),
        "ttl_turns" to ttlTurns?.let { jsonNumber(it) },
        "state" to JsonValue.Str(state.wire),
    )

    companion object {
        fun fromJson(value: JsonValue): AttentionSlot {
            val obj = value.asObject("frame.slot")
            return AttentionSlot(
                target = Ref.fromJson(obj.requireObj("target")),
                role = SlotRole.fromWire(obj.requireString("role")),
                salience = obj.requireDouble("salience"),
                cost = Budget.fromJson(obj.requireObj("cost")),
                why = obj.requireString("why"),
                state = SlotState.fromWire(obj.requireString("state")),
                tier = obj.string("tier")?.let(Tier::fromWire),
                voi = obj.double("voi"),
                budget = obj.obj("budget")?.let(Budget::fromJson),
                ttlTurns = obj.int("ttl_turns"),
            )
        }
    }
}

/** `frame.Ignored` 的结构等价模型：被有意排除的目标。 */
data class AttentionIgnored(
    val target: Ref,
    val reason: IgnoredReason,
    val revisitIf: String,
) {

    init {
        require(revisitIf.isNotBlank()) { "ignored.revisit_if 不能为空" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "target" to target.toJson(),
        "reason" to JsonValue.Str(reason.wire),
        "revisit_if" to JsonValue.Str(revisitIf),
    )

    companion object {
        fun fromJson(value: JsonValue): AttentionIgnored {
            val obj = value.asObject("frame.ignored")
            return AttentionIgnored(
                target = Ref.fromJson(obj.requireObj("target")),
                reason = IgnoredReason.fromWire(obj.requireString("reason")),
                revisitIf = obj.requireString("revisit_if"),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 显著性
// ---------------------------------------------------------------------------

/**
 * 打分前的候选，镜像 §5.2 的输入。
 */
data class Candidate(
    val target: Ref,
    val role: SlotRole,
    val voi: Double,
    val goalFit: Double,
    val uncertainty: Double,
    val novelty: Double,
    val costTokens: Int,
    val risk: Double,
    /** 信任上限：外部内容只能降低显著性，不能买到显著性（A-4）。 */
    val trustCap: Double,
    val why: String,
    val tier: Tier? = null,
)

/**
 * 参考显著性函数（§5.2）。规范性的部分只有单调性：voi 上升不降低；cost/risk 上升
 * 不提高；trust 设上限。绝对数值只是起点，靠预测/实现 VOI 校准。
 */
fun salience(candidate: Candidate): Double {
    val value: Double = candidate.voi * candidate.goalFit * (0.5 + candidate.uncertainty) * (1 + 0.5 * candidate.novelty)
    val cost: Double = 1 + 2 * clamp01(candidate.costTokens / 2000.0) + 3 * clamp01(candidate.risk)
    return round3(minOf(clamp01(value / cost), clamp01(candidate.trustCap)))
}

data class SelectionOptions(
    val budgetTokens: Int,
    val cap: Int? = null,
    val threshold: Double? = null,
)

data class Selection(
    val slots: List<AttentionSlot>,
    val ignored: List<AttentionIgnored>,
    val impasse: ImpasseState? = null,
)

/**
 * 在预算内选择焦点集，并记录有意排除（A-2）。
 *
 * `ignored` 是经典架构从不保留的部分：它让“为什么没看”可审计。
 */
fun selectFocus(candidates: List<Candidate>, options: SelectionOptions): Selection {
    val threshold: Double = options.threshold ?: 0.12
    val cap: Int = options.cap ?: 7
    val scored: List<Pair<Candidate, Double>> = candidates
        .map { candidate -> candidate to salience(candidate) }
        .sortedByDescending { entry -> entry.second }

    val ignored = ArrayList<AttentionIgnored>()
    val above = ArrayList<Pair<Candidate, Double>>()
    for (entry in scored) {
        if (entry.second < threshold) {
            ignored.add(AttentionIgnored(entry.first.target, IgnoredReason.LOW_VOI, "VOI 上升或目标改变"))
        } else {
            above.add(entry)
        }
    }

    if (above.isEmpty()) {
        return Selection(emptyList(), ignored, ImpasseState.STUCK)
    }

    val tie: Boolean = above.size > 1 && abs(above[0].second - above[1].second) < 0.02
    var remaining: Int = options.budgetTokens
    val slots = ArrayList<AttentionSlot>()

    for (entry in above) {
        val candidate: Candidate = entry.first
        if (slots.size >= cap) {
            ignored.add(AttentionIgnored(candidate.target, IgnoredReason.DEFERRED, "焦点释放后"))
            continue
        }
        if (candidate.costTokens > remaining) {
            ignored.add(AttentionIgnored(candidate.target, IgnoredReason.BUDGET, "预算恢复后"))
            continue
        }
        remaining -= candidate.costTokens
        slots.add(
            AttentionSlot(
                target = candidate.target,
                role = candidate.role,
                tier = candidate.tier,
                salience = entry.second,
                voi = candidate.voi,
                cost = Budget(tokens = candidate.costTokens),
                budget = Budget(tokens = candidate.costTokens),
                why = candidate.why,
                state = SlotState.ACTIVE,
            ),
        )
    }

    val impasse: ImpasseState? = when {
        slots.isEmpty() -> ImpasseState.BUDGET
        tie -> ImpasseState.TIE
        else -> null
    }

    return Selection(slots, ignored, impasse)
}

// ---------------------------------------------------------------------------
// 注意窗口
// ---------------------------------------------------------------------------

enum class AttentionForceKind(val wire: String) {
    ATTRACTION("attraction"),
    DISTRACTION("distraction"),
    ;

    companion object {
        fun fromWire(value: String): AttentionForceKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 force.kind: $value")
    }
}

data class AttentionCapacity(val slots: Int, val tokens: Int)

data class AttentionForce(
    val kind: AttentionForceKind,
    val from: Ref,
    val target: Ref,
    /** distraction 必填：目标在与谁竞争。 */
    val competesWith: Ref? = null,
    val strength: Double,
    val decay: Double? = null,
    val ttl: Int? = null,
)

data class AttentionItem(
    val target: Ref,
    /** 稳定的左右顺序；权重永远不改变窗口顺序。 */
    val order: Int,
    val weight: Double,
    val pinned: Boolean? = null,
    val source: Ref? = null,
    val enteredAt: Timestamp? = null,
    val ttl: Int? = null,
)

data class AttentionSubject(val pid: Int, val tid: Int? = null)

enum class AttentionStatus(val wire: String) {
    OPEN("open"),
    CLOSED("closed"),
    ;

    companion object {
        fun fromWire(value: String): AttentionStatus =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 attention.status: $value")
    }
}

enum class ShiftDirection(val wire: String) {
    LEFT("left"),
    RIGHT("right"),
    ;

    companion object {
        fun fromWire(value: String): ShiftDirection =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 shift direction: $value")
    }
}

data class AttentionShift(
    val actionId: String,
    val direction: ShiftDirection,
    val reason: String,
    val steps: Int? = null,
)

data class AttentionSnapshot(
    val subject: AttentionSubject,
    val status: AttentionStatus,
    val cursor: Int,
    val capacity: AttentionCapacity,
    val focus: List<AttentionItem>,
    val ignored: List<AttentionItem>,
    val forces: List<AttentionForce>,
)

data class AttentionShiftResult(
    val actionId: String,
    val changed: Boolean,
    val blocked: Boolean,
    val reason: String,
    val snapshot: AttentionSnapshot,
)

data class AttentionOptions(
    val subject: AttentionSubject,
    val capacity: AttentionCapacity,
    val items: List<AttentionItem> = emptyList(),
    val now: (() -> Timestamp)? = null,
)

/**
 * 唯一的注意力状态。
 *
 * 它只拥有有序焦点环、容量与力场；不物化上下文、不修改 Frame、也不执行移动——
 * 一次移动是一个 Action，必须由 Receipt 结算。
 */
class Attention(options: AttentionOptions) {

    val subject: AttentionSubject = options.subject
    val capacity: AttentionCapacity = options.capacity
    var status: AttentionStatus = AttentionStatus.OPEN
    var cursor: Int = 0
    private val items: MutableList<AttentionItem> = options.items.toMutableList()
    private val forces: MutableList<AttentionForce> = ArrayList()
    private val now: () -> Timestamp = options.now ?: { isoMillis(Instant.now()) }

    init {
        require(capacity.slots > 0) { "attention capacity.slots must be positive" }
        require(capacity.tokens > 0) { "attention capacity.tokens must be positive" }
        items.sortBy { it.order }
    }

    /**
     * 施加一次吸引/分心力；是否接纳由 commit 决定。
     */
    fun observe(force: AttentionForce) {
        assertOpen()
        require(force.strength.isFinite() && force.strength >= 0) { "force strength must be non-negative" }
        require(force.kind != AttentionForceKind.DISTRACTION || force.competesWith != null) {
            "distraction requires competesWith"
        }
        val targetIndex: Int = items.indexOfFirst { it.target.id == force.target.id }
        if (targetIndex < 0) {
            items.add(
                AttentionItem(
                    target = force.target,
                    order = nextOrder(),
                    weight = clamp01(force.strength),
                    source = force.from,
                    enteredAt = now(),
                    ttl = force.ttl,
                ),
            )
        } else {
            val target: AttentionItem = items[targetIndex]
            items[targetIndex] = target.copy(weight = clamp01(target.weight + force.strength))
        }
        if (force.kind == AttentionForceKind.DISTRACTION && force.competesWith != null) {
            val competitorIndex: Int = items.indexOfFirst { it.target.id == force.competesWith.id }
            if (competitorIndex >= 0) {
                val competitor: AttentionItem = items[competitorIndex]
                items[competitorIndex] = competitor.copy(weight = clamp01(competitor.weight - force.strength * 0.5))
            }
        }
        forces.add(force)
    }

    /**
     * 稳定的左右窗口；pinned 项始终可见。
     */
    fun focus(): List<AttentionItem> {
        val pinned: List<AttentionItem> = items.filter { it.pinned == true }
        val unpinned: List<AttentionItem> = items.filter { it.pinned != true }
        val needed: Int = maxOf(0, capacity.slots - pinned.size)
        val visible: List<AttentionItem> = unpinned.drop(cursor).take(needed)
        return (pinned + visible).sortedBy { it.order }
    }

    fun snapshot(): AttentionSnapshot {
        val focus: List<AttentionItem> = focus()
        val focusIds: Set<String> = focus.map { it.target.id }.toSet()
        return AttentionSnapshot(
            subject = subject,
            status = status,
            cursor = cursor,
            capacity = capacity,
            focus = focus,
            ignored = items.filter { it.target.id !in focusIds },
            forces = forces.toList(),
        )
    }

    /**
     * 应用一次 `attention.shift`。调用方仍需记录 ActionRecord 与 Receipt；本方法只移动
     * cursor。
     */
    fun shift(action: AttentionShift): AttentionShiftResult {
        assertOpen()
        val steps: Int = maxOf(1, abs(action.steps ?: 1))
        val pinned: Int = items.count { it.pinned == true }
        val needed: Int = maxOf(0, capacity.slots - pinned)
        val unpinned: Int = items.size - pinned
        val maxCursor: Int = maxOf(0, unpinned - needed)
        val delta: Int = if (action.direction == ShiftDirection.LEFT) -steps else steps
        val next: Int = maxOf(0, minOf(maxCursor, cursor + delta))
        val changed: Boolean = next != cursor
        val reason: String = if (changed) {
            "cursor $cursor->$next"
        } else {
            "blocked at ${action.direction.wire} boundary ($cursor)"
        }
        cursor = next
        return AttentionShiftResult(
            actionId = action.actionId,
            changed = changed,
            blocked = !changed,
            reason = reason,
            snapshot = snapshot(),
        )
    }

    fun close() {
        status = AttentionStatus.CLOSED
    }

    private fun nextOrder(): Int = items.maxOfOrNull { it.order }?.plus(1) ?: 0

    private fun assertOpen() {
        check(status == AttentionStatus.OPEN) { "attention is closed" }
    }
}

// ---------------------------------------------------------------------------
// Action 绑定
// ---------------------------------------------------------------------------

enum class ActionRisk(val wire: String) {
    NONE("none"),
    READ("read"),
    WRITE("write"),
    DESTRUCTIVE("destructive"),
}

/**
 * `runtime/action.ts` 中 `ActionPlan` 的结构等价模型。绑定说明：集成时用
 * `com.eloykim.lyra.amp.protocol.runtime.ActionPlan` 替换或做纯转换。
 */
data class AttentionActionPlan(
    val goal: String,
    val intent: String,
    val expectedEffect: String,
    val preconditions: List<String>,
    val successCriteria: List<String>,
    val risk: ActionRisk,
)

data class ShiftActionInput(
    val direction: ShiftDirection,
    val reason: String,
    val steps: Int? = null,
    val actionId: String? = null,
)

/** 把一次移动转成既有 runtime ActionPlan，而不是新的执行器。 */
fun toShiftAction(input: ShiftActionInput): AttentionActionPlan = AttentionActionPlan(
    goal = "move the attention window",
    intent = "attention.shift",
    expectedEffect = "attention cursor moves ${input.direction.wire} by ${input.steps ?: 1}",
    preconditions = listOf("attention is open", "focus is ordered"),
    successCriteria = listOf("cursor changed", "a new Frame is committed", "a Receipt is recorded"),
    risk = ActionRisk.NONE,
)

/**
 * 契约的单调性约束（§5.2），以测试形式固定。
 */
fun respectsMonotonicity(base: Candidate): Boolean {
    val reference: Double = salience(base)
    fun at(over: (Candidate) -> Candidate): Double = salience(over(base))
    return at { it.copy(voi = minOf(1.0, base.voi + 0.1)) } >= reference &&
        at { it.copy(costTokens = base.costTokens + 200) } <= reference &&
        at { it.copy(risk = minOf(1.0, base.risk + 0.1)) } <= reference &&
        at { it.copy(trustCap = minOf(base.trustCap, 0.2)) } <= reference
}

// ---------------------------------------------------------------------------
// 共享数值/时间辅助
// ---------------------------------------------------------------------------

/** 与参考实现的 `Math.max(0, Math.min(1, n))` 一致；NaN 会保持 NaN。 */
internal fun clamp01(value: Double): Double = if (value < 0.0) 0.0 else if (value > 1.0) 1.0 else value

/** 保留三位小数，让 receipt / snapshot 字节稳定。 */
internal fun round3(value: Double): Double =
    if (value.isFinite()) Math.round(value * 1000.0) / 1000.0 else value

internal fun timestampMillis(timestamp: Timestamp): Long = Instant.parse(timestamp).toEpochMilli()

/** 与 JavaScript `Date#toISOString` 一样固定毫秒位（强制 root locale，保证 ASCII 数字）。 */
internal fun isoMillis(instant: Instant): String =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .withZone(ZoneOffset.UTC)
        .format(instant)
