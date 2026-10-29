package com.eloykim.lyra.amp.protocol.runtime

/**
 * `src/runtime/escalation.ts` 的移植：安全升级/交接的落点。
 *
 * 运行时不承担危机服务：它只记录一个最小事件（不含完整用户文本）并返回资源清单，
 * 由人类/运维适配器去呼叫。
 */
data class EscalationEvent(
    val id: String,
    val turnId: String,
    val at: String,
    val reason: String,
    val resources: List<String>,
)

/** 参考实现里是 `Omit<EscalationEvent, "id">`；Kotlin 用独立类型表达同一形状。 */
data class EscalationRequest(
    val turnId: String,
    val at: String,
    val reason: String,
    val resources: List<String>,
)

fun interface EscalationSink {
    /** 返回升级事件 id。 */
    fun escalate(event: EscalationRequest): String
}

val CrisisResources: List<String> = listOf(
    "Local emergency services (e.g. 120/911) if there is immediate danger.",
    "A trusted person who can stay with the user.",
    "A local crisis line; in the US, 988.",
)

/** 内存实现：事件按到达顺序保存，id 为自增的 `esc.N`。 */
class MemoryEscalationSink : EscalationSink {

    val events: MutableList<EscalationEvent> = ArrayList()
    private var counter: Int = 0

    override fun escalate(event: EscalationRequest): String {
        counter += 1
        val id = "esc.$counter"
        events.add(
            EscalationEvent(
                id = id,
                turnId = event.turnId,
                at = event.at,
                reason = event.reason,
                resources = event.resources,
            ),
        )
        return id
    }
}
