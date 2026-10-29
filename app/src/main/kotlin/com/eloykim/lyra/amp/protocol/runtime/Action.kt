package com.eloykim.lyra.amp.protocol.runtime

import com.eloykim.lyra.amp.protocol.interaction.InteractionPlan
import com.eloykim.lyra.amp.protocol.scenario.ScenarioPlan

/**
 * `src/runtime/action.ts` 的移植：动作是目标导向的意图。
 *
 * 动作不是权限动词，也不是操作。它记录“为什么做、预期什么、如何算成功”，
 * 由 [ActionPlanner] 在模型调用之前产出，由运行时按行为上限截断。
 */

typealias ActionId = String

enum class ActionRisk(val wire: String) {
    NONE("none"),
    READ("read"),
    WRITE("write"),
    DESTRUCTIVE("destructive"),
}

enum class ActionStatus(val wire: String) {
    PLANNED("planned"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),
    ABANDONED("abandoned"),
}

/**
 * 落地的动作记录。
 *
 * `status` 与 `operationIds` 是运行时唯一会原地推进的两个字段（参考实现同样直接
 * 赋值/`push`）；因此它们是 `var`/可变列表，并在快照时用 [frozen] 复制。
 */
data class ActionRecord(
    val id: ActionId,
    val turnId: String,
    val goal: String,
    val intent: String,
    val expectedEffect: String,
    val preconditions: List<String>,
    val successCriteria: List<String>,
    val risk: ActionRisk,
    var status: ActionStatus,
    val operationIds: MutableList<String>,
) {

    /** 持久化/重放用的深拷贝：之后对原记录的推进不影响快照。 */
    fun frozen(): ActionRecord = copy(operationIds = ArrayList(operationIds))
}

/** 规划器产出的动作草案。 */
data class ActionPlan(
    val goal: String,
    val intent: String,
    val expectedEffect: String,
    val preconditions: List<String> = emptyList(),
    val successCriteria: List<String> = emptyList(),
    val risk: ActionRisk? = null,
)

/** 规划器的输入：行为、场景与交互计划都必须先就位。 */
data class ActionPlannerInput(
    val turnId: String,
    val text: String,
    val behavior: BehaviorPlan,
    val scenario: ScenarioPlan,
    val interaction: InteractionPlan,
)

/**
 * 动作规划端口。
 *
 * 参考实现的 `plan` 是异步的；本端口与 `module/Llm.kt` 一样采用同步签名，
 * 线程与超时由宿主决定。
 */
fun interface ActionPlanner {
    fun plan(input: ActionPlannerInput): List<ActionPlan>
}

/**
 * 每轮一个有界动作；后续规划器可以按目标拆分。
 *
 * 与参考实现一致：先看 `acknowledge`，再看 `clarify`，否则给一个默认的“回答”动作。
 */
class SingleActionPlanner : ActionPlanner {

    override fun plan(input: ActionPlannerInput): List<ActionPlan> {
        val preferred = input.behavior.preferredActions
        if (preferred.contains("acknowledge")) {
            return listOf(
                ActionPlan(
                    goal = "acknowledge affect and converge on a next step",
                    intent = "acknowledge_then_converge",
                    expectedEffect = "user is heard and has one concrete next step",
                    successCriteria = listOf("answer acknowledges affect", "answer gives a bounded next step"),
                    risk = ActionRisk.NONE,
                ),
            )
        }
        if (preferred.contains("clarify")) {
            return listOf(
                ActionPlan(
                    goal = "clarify the underspecified request",
                    intent = "clarify",
                    expectedEffect = "the missing constraint is supplied",
                    successCriteria = listOf("one focused question is returned"),
                    risk = ActionRisk.NONE,
                ),
            )
        }
        return listOf(
            ActionPlan(
                goal = "respond to the user's request",
                intent = "respond",
                expectedEffect = "an answer is produced",
                successCriteria = listOf("answer is returned"),
                risk = ActionRisk.READ,
            ),
        )
    }
}
