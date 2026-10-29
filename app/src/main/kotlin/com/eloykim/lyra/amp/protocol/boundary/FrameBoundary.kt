package com.eloykim.lyra.amp.protocol.boundary

import com.eloykim.lyra.amp.protocol.frame.Frame
import com.eloykim.lyra.amp.protocol.frame.FrameBudget
import com.eloykim.lyra.amp.protocol.frame.FrameMode
import com.eloykim.lyra.amp.protocol.frame.Ignored
import com.eloykim.lyra.amp.protocol.frame.Impasse
import com.eloykim.lyra.amp.protocol.frame.Slot
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.isTimestamp
import com.eloykim.lyra.amp.protocol.types.nowTimestamp

/**
 * `frameFrom` 接受的草稿；`digest` 在这里计算，永远不由调用方提供。
 */
data class FrameDraft(
    val id: Id,
    val agent: Id,
    val cycle: Int,
    val ts: Timestamp? = null,
    val parent: Ref? = null,
    val mode: FrameMode,
    val goal: Ref? = null,
    val slots: List<Slot>,
    val ignored: List<Ignored>? = null,
    val budget: FrameBudget,
    val openQuestions: List<Ref>? = null,
    val impasse: Impasse? = null,
    val note: String? = null,
)

/** 边界选项；`maxSlots` 只能收紧 schema 的 9，不能放宽。 */
data class FrameBoundaryOptions(
    val maxSlots: Int? = null,
    /**
     * 留在帧上的可见排除数量；其余以 `deferred` 返回。默认 50。
     */
    val maxIgnored: Int? = null,
    /** 草稿省略 `ts` 时使用的时钟；宿主应传入自己的账本时钟。 */
    val now: (() -> Timestamp)? = null,
)

/**
 * 边界结果：帧，以及放不下、但仍需让调用方看到的排除项。
 */
data class FrameBoundaryResult(
    val frame: Frame,
    /** 超出 `maxIgnored` 的排除项，保持输入顺序，绝不静默丢弃。 */
    val deferred: List<Ignored>,
)

/** 预算维度，顺序与参考实现一致。 */
private val BUDGET_DIMENSIONS: List<String> = listOf("tokens", "ms", "calls", "interrupts", "money")

private fun budgetDimension(budget: Budget, name: String): Double? = when (name) {
    "tokens" -> budget.tokens?.toDouble()
    "ms" -> budget.ms?.toDouble()
    "calls" -> budget.calls?.toDouble()
    "interrupts" -> budget.interrupts?.toDouble()
    "money" -> budget.money
    else -> null
}

private fun checkRef(ref: Ref, refs: RefTable, at: String, problems: MutableList<String>) {
    refs.check(ref)?.let { problems += "$at $it" }
}

/**
 * 校验草稿并返回它唯一允许的帧（`src/boundary/frame.ts`）。
 *
 * Kotlin 的枚举让 mode/role/state/tier 的非法值无法表示：它们由数据类构造
 * 与 `fromJson` 拒绝。`frameFrom` 仍然拒绝所有可表示的违约——id 语法、
 * cycle、ts、slot 数量、引用闭包、预算一致性与排除上限——并以一个
 * [BoundaryRejection] 携带**全部**问题。
 *
 * @throws BoundaryRejection code 为 `invalid_frame`，附带每个问题。
 */
fun frameFrom(
    draft: FrameDraft,
    refs: RefTable,
    options: FrameBoundaryOptions = FrameBoundaryOptions(),
): FrameBoundaryResult {
    val problems = ArrayList<String>()

    if (!ProtocolIds.isValid(draft.id)) {
        problems += "id is not a valid AMP id"
    }
    if (!ProtocolIds.isValid(draft.agent)) {
        problems += "agent is not a valid AMP id"
    }
    if (draft.cycle < 0) {
        problems += "cycle must be a non-negative integer"
    }

    val ts: Timestamp = draft.ts ?: options.now?.invoke() ?: nowTimestamp()
    if (!isTimestamp(ts)) {
        problems += "ts is not a date-time"
    }

    val requestedMaxSlots: Int = options.maxSlots ?: Frame.MAX_SLOTS
    if (requestedMaxSlots < 0 || requestedMaxSlots > Frame.MAX_SLOTS) {
        problems += "maxSlots must be an integer in [0,${Frame.MAX_SLOTS}]"
    }
    val maxSlots: Int = requestedMaxSlots.coerceIn(0, Frame.MAX_SLOTS)

    val requestedMaxIgnored: Int = options.maxIgnored ?: DEFAULT_MAX_IGNORED
    if (requestedMaxIgnored < 0) {
        problems += "maxIgnored must be a non-negative integer"
    }
    val maxIgnored: Int = requestedMaxIgnored.coerceAtLeast(0)

    if (draft.slots.size > maxSlots) {
        problems += "slots has ${draft.slots.size} entries, max is $maxSlots"
    }
    draft.slots.forEachIndexed { index, slot ->
        checkRef(slot.target, refs, "slots[$index].target", problems)
        if (slot.why.isBlank()) {
            problems += "slots[$index].why is empty"
        }
        slot.budget?.let { checkBudgetDimensions(it, "slots[$index].budget", problems) }
    }

    val ignored: List<Ignored> = draft.ignored.orEmpty()
    ignored.forEachIndexed { index, item ->
        checkRef(item.target, refs, "ignored[$index].target", problems)
        if (item.revisitIf.isBlank()) {
            problems += "ignored[$index].revisit_if is empty"
        }
    }

    val allocated = draft.budget.allocated
    val spent = draft.budget.spent
    checkBudgetDimensions(allocated, "budget.allocated", problems)
    checkBudgetDimensions(spent, "budget.spent", problems)
    if (allocated.isEmpty) {
        problems += "budget.allocated must set at least one dimension"
    }
    BUDGET_DIMENSIONS.forEach { dimension ->
        val limit = budgetDimension(allocated, dimension)
        val used = budgetDimension(spent, dimension)
        if (limit != null && used != null && used > limit) {
            problems += "budget.spent.$dimension exceeds budget.allocated.$dimension"
        }
    }

    draft.parent?.let { checkRef(it, refs, "parent", problems) }
    draft.goal?.let { checkRef(it, refs, "goal", problems) }
    draft.openQuestions?.forEachIndexed { index, ref ->
        checkRef(ref, refs, "open_questions[$index]", problems)
    }

    if (problems.isNotEmpty()) {
        throw BoundaryRejection(BoundaryCodes.INVALID_FRAME, problems)
    }

    val visible: List<Ignored> = ignored.take(maxIgnored)
    val deferred: List<Ignored> = ignored.drop(maxIgnored)
    val unsigned = Frame(
        amp = AmpWire.VERSION,
        kind = Frame.KIND,
        id = draft.id,
        agent = draft.agent,
        cycle = draft.cycle,
        ts = ts,
        parent = draft.parent,
        digest = "",
        mode = draft.mode,
        goal = draft.goal,
        slots = draft.slots.toList(),
        ignored = visible.ifEmpty { null },
        budget = FrameBudget(allocated = allocated, spent = spent),
        openQuestions = draft.openQuestions?.toList(),
        impasse = draft.impasse,
        note = draft.note,
    )
    val frame = unsigned.copy(digest = CanonicalJson.digest(unsigned.toJson().without("digest")))
    return FrameBoundaryResult(frame = frame, deferred = deferred)
}

/** 默认保留在帧上的可见排除数；其余以 `deferred` 返回。 */
const val DEFAULT_MAX_IGNORED: Int = 50

/**
 * 预算维度必须是非负整数，`money` 除外。数据类已保证非负有限数，这里保留
 * 参考实现的整数检查，防止调用方绕过 `Budget` 直接构造线格式（例如未来
 * 的 JSON 草稿入口）。
 */
private fun checkBudgetDimensions(budget: Budget, at: String, problems: MutableList<String>) {
    // Budget 的构造器与 fromJson 已拒绝负数与非有限数；重复检查字段名，
    // 让错误消息与参考实现的 `checkBudget` 完全一致。
    budget.tokens?.let { if (it < 0) problems += "$at.tokens must be a non-negative finite number" }
    budget.ms?.let { if (it < 0) problems += "$at.ms must be a non-negative finite number" }
    budget.calls?.let { if (it < 0) problems += "$at.calls must be a non-negative finite number" }
    budget.interrupts?.let { if (it < 0) problems += "$at.interrupts must be a non-negative finite number" }
    budget.money?.let { if (it < 0.0) problems += "$at.money must be a non-negative finite number" }
}
