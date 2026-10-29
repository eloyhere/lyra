package com.eloykim.lyra.amp.protocol.runtime

/**
 * `src/runtime/replay.ts` 的移植：从已持久化的轮次重建 Action/Operation 图，
 * 并验证每个引用都指向同一轮里的记录。
 *
 * 引用完整性失败抛 [IllegalStateException]（与参考实现的 `Error` 对应）；未知轮次
 * 抛 [IllegalArgumentException]。
 */
data class ReplayedAction(
    val id: String,
    val intent: String,
    val status: String,
    val operations: List<String>,
)

data class ReplayedOperation(
    val id: String,
    val kind: String,
    val status: String,
    val actionId: String,
)

data class ReplayedTurn(
    val turnId: String,
    val sessionId: String? = null,
    val pid: Int,
    val input: String,
    val output: String,
    val behaviorIds: List<String>,
    val actions: List<ReplayedAction>,
    val operations: List<ReplayedOperation>,
)

fun replayTurn(turn: RuntimeTurn): ReplayedTurn {
    val actions = turn.actions ?: emptyList()
    val operations = turn.operations ?: emptyList()
    val actionIds = actions.map { it.id }.toSet()
    val operationIds = operations.map { it.id }.toSet()

    for (operation in operations) {
        if (!actionIds.contains(operation.actionId)) {
            throw IllegalStateException(
                "operation ${operation.id} references missing action ${operation.actionId}",
            )
        }
    }
    for (action in actions) {
        for (operationId in action.operationIds) {
            if (!operationIds.contains(operationId)) {
                throw IllegalStateException(
                    "action ${action.id} references missing operation $operationId",
                )
            }
        }
    }
    return ReplayedTurn(
        turnId = turn.turnId,
        sessionId = turn.sessionId,
        pid = turn.pid,
        input = turn.input,
        output = turn.answer,
        behaviorIds = (turn.behavior?.activations ?: emptyList()).map { it.behaviorId },
        actions = actions.map { action ->
            ReplayedAction(
                id = action.id,
                intent = action.intent,
                status = action.status.wire,
                operations = action.operationIds.toList(),
            )
        },
        operations = operations.map { operation ->
            ReplayedOperation(
                id = operation.id,
                kind = operation.kind.wire,
                status = operation.status.wire,
                actionId = operation.actionId,
            )
        },
    )
}

fun replayStoredTurn(store: TurnStore, turnId: String): ReplayedTurn {
    val turn = store.get(turnId) ?: throw IllegalArgumentException("unknown turn $turnId")
    return replayTurn(turn)
}
