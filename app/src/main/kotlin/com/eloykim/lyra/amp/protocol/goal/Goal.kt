package com.eloykim.lyra.amp.protocol.goal

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Confidence
import com.eloykim.lyra.amp.protocol.types.Criterion
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.isTimestamp
import com.eloykim.lyra.amp.protocol.types.requireConfidence

/**
 * Goal 与它的准入状态机。
 *
 * 对应参考实现 `src/goal/plan.ts`（Goal 线格式）与 `src/goal/goal.ts`（生命周期）。
 * 目标不是「有人说了就算 active」：它按显式状态迁移，`achieved` / `abandoned` 是终态。
 */
enum class GoalHorizon(val wire: String) {
    TASK("task"),
    SESSION("session"),
    LONG("long");

    companion object {
        fun fromWire(value: String): GoalHorizon =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 goal.horizon: $value")
    }
}

enum class GoalState(val wire: String) {
    PROPOSED("proposed"),
    COMMITTED("committed"),
    ACTIVE("active"),
    ACHIEVED("achieved"),
    ABANDONED("abandoned");

    companion object {
        fun fromWire(value: String): GoalState =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 goal state: $value")
    }
}

/** 至少一个可观察的成功判据；没有判据的目标只是愿望。 */
data class Goal(
    val id: Id,
    val statement: String,
    val success: List<Criterion>,
    val value: Confidence,
    val priority: Int,
    val deadline: Timestamp? = null,
    val horizon: GoalHorizon? = null,
) {

    init {
        ProtocolIds.require(id, "goal.id")
        require(statement.isNotBlank()) { "goal.statement 不能为空" }
        require(success.isNotEmpty()) { "goal.success 至少要有一个成功判据" }
        requireConfidence(value, "goal.value")
        require(priority >= 0) { "goal.priority 必须 >= 0" }
        require(deadline == null || isTimestamp(deadline)) { "goal.deadline 不是合法时间: $deadline" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AmpWire.VERSION),
        "kind" to JsonValue.Str("goal"),
        "id" to JsonValue.Str(id),
        "statement" to JsonValue.Str(statement),
        "success" to JsonValue.Arr(success.map { it.toJson() }),
        "value" to jsonNumber(value),
        "priority" to jsonNumber(priority),
        "deadline" to deadline?.let { JsonValue.Str(it) },
        "horizon" to horizon?.let { JsonValue.Str(it.wire) },
    )

    companion object {
        fun fromJson(value: JsonValue): Goal {
            val obj = value.asObject("goal")
            val amp = obj.requireString("amp")
            require(amp == AmpWire.VERSION) { "goal.amp 必须是 ${AmpWire.VERSION}" }
            val kind = obj.requireString("kind")
            require(kind == "goal") { "goal.kind 必须是 goal" }
            return Goal(
                id = obj.requireString("id"),
                statement = obj.requireString("statement"),
                success = obj.requireArr("success").items.map { Criterion.fromJson(it) },
                value = obj.double("value") ?: throw IllegalArgumentException("缺少数字字段 value"),
                priority = obj.requireInt("priority"),
                deadline = obj.string("deadline"),
                horizon = obj.string("horizon")?.let(GoalHorizon::fromWire),
            )
        }
    }
}

/** 目标生命周期：目标本体 + 当前状态 + 进入 active / achieved 的时间。 */
data class GoalLifecycle(
    val goal: Goal,
    val state: GoalState,
    val startedAt: Timestamp? = null,
    val achievedAt: Timestamp? = null,
) {

    init {
        require(startedAt == null || isTimestamp(startedAt)) { "goal.started_at 不是合法时间: $startedAt" }
        require(achievedAt == null || isTimestamp(achievedAt)) { "goal.achieved_at 不是合法时间: $achievedAt" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "goal" to goal.toJson(),
        "state" to JsonValue.Str(state.wire),
        "started_at" to startedAt?.let { JsonValue.Str(it) },
        "achieved_at" to achievedAt?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): GoalLifecycle {
            val obj = value.asObject("goal-lifecycle")
            return GoalLifecycle(
                goal = Goal.fromJson(obj.requireObj("goal")),
                state = GoalState.fromWire(obj.requireString("state")),
                startedAt = obj.string("started_at"),
                achievedAt = obj.string("achieved_at"),
            )
        }
    }
}

private val GOAL_EDGES: Map<GoalState, Set<GoalState>> = mapOf(
    GoalState.PROPOSED to setOf(GoalState.COMMITTED, GoalState.ABANDONED),
    GoalState.COMMITTED to setOf(GoalState.ACTIVE, GoalState.ABANDONED),
    GoalState.ACTIVE to setOf(GoalState.ACHIEVED, GoalState.ABANDONED),
    GoalState.ACHIEVED to emptySet(),
    GoalState.ABANDONED to emptySet(),
)

/** 从 [from] 出发允许到达的状态。终态返回空集合。 */
fun goalEdgesFrom(from: GoalState): Set<GoalState> = GOAL_EDGES.getValue(from)

/** [from] -> [to] 是否是一条合法边。 */
fun isGoalEdge(from: GoalState, to: GoalState): Boolean = to in GOAL_EDGES.getValue(from)

fun createGoalLifecycle(goal: Goal): GoalLifecycle = GoalLifecycle(goal = goal, state = GoalState.PROPOSED)

/**
 * 状态迁移。非法边直接抛错，而不是静默停在原状态（避免「目标已达成」靠断言生效）。
 */
fun transitionGoal(lifecycle: GoalLifecycle, to: GoalState, at: Timestamp): GoalLifecycle {
    if (!isGoalEdge(lifecycle.state, to)) {
        throw IllegalArgumentException(
            "goal ${lifecycle.goal.id}: ${lifecycle.state.wire} -> ${to.wire} is not an edge",
        )
    }
    var next = lifecycle.copy(state = to)
    if (to == GoalState.ACTIVE && next.startedAt == null) {
        next = next.copy(startedAt = at)
    }
    if (to == GoalState.ACHIEVED) {
        next = next.copy(achievedAt = at)
    }
    return next
}
