package com.eloykim.lyra.amp

/**
 * 把一次屏幕观察映射成 AMP Frame。
 *
 * Lyra 的映射约定：
 * - `goal` slot：本轮要完成的事（由任务描述生成）
 * - `tool` slot：唯一能读屏的通道 automation.observe（A-5：工具提议，宿主裁决）
 * - `evidence` slot：这一屏本身（节点数 / 可交互数 / 发送给模型的元素数）
 * - `ignored`：被压缩掉的元素（A-2 要求给出理由与重新考虑的条件）
 */
data class ObservedElement(
    val nodeId: Int,
    val label: String,
    val clickable: Boolean = false,
)

data class Observation(
    val obsId: Long,
    val packageName: String,
    val totalNodes: Int,
    val interactiveNodes: Int,
    val sent: List<ObservedElement>,
    val dropped: List<ObservedElement> = emptyList(),
    val tokensEstimate: Int = 0,
)

class FrameBuilder(
    private val agentId: String = "a.lyra",
) {

    fun build(
        cycle: Int,
        mode: Mode,
        observation: Observation,
        allocated: Budget,
        spent: Usage,
        goal: Ref? = null,
        goalWhy: String = "用户请求的当前任务",
        parent: Ref? = null,
        impasse: Pair<ImpasseState, String?>? = null,
    ): Frame {
        val slots = buildList {
            goal?.let {
                add(
                    Slot(
                        target = it,
                        role = SlotRole.goal,
                        why = goalWhy,
                        tier = Tier.T2,
                        salience = 0.9,
                        voi = 0.8,
                        cost = Budget(tokens = 0, calls = 0),
                    ),
                )
            }
            add(
                Slot(
                    target = Ref("tool", "automation.observe", "读屏"),
                    role = SlotRole.tool,
                    why = "当前只有这一条通道能把屏幕变成可判断的证据",
                    tier = Tier.T2,
                    salience = 0.7,
                    voi = 0.6,
                    cost = Budget(calls = 1, ms = 40),
                ),
            )
            add(
                Slot(
                    target = Ref("evidence", "obs.${observation.obsId}", observation.packageName),
                    role = SlotRole.evidence,
                    why = "屏幕 ${observation.packageName}：" +
                        "${observation.totalNodes} 节点 / ${observation.interactiveNodes} 可交互 / " +
                        "送模型 ${observation.sent.size} 个",
                    tier = Tier.T1,
                    salience = 0.6,
                    voi = 0.5,
                    cost = Budget(tokens = observation.tokensEstimate, calls = 1),
                ),
            )
        }

        val ignored = observation.dropped.take(8).map { element ->
            Ignored(
                target = Ref("entity", "ui.${element.nodeId}", element.label.take(40)),
                reason = IgnoreReason.budget,
                revisitIf = "任务卡住或用户点名该控件时",
            )
        }

        return Frame(
            id = AmpIds.slug("f.${observation.obsId}", "f"),
            agent = agentId,
            cycle = cycle,
            mode = mode,
            goal = goal,
            parent = parent,
            slots = slots,
            ignored = ignored,
            budget = FrameBudget(allocated = allocated, spent = spent),
            impasse = impasse,
            note = "pkg=${observation.packageName}",
        ).sealed()
    }
}
