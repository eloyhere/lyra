package com.eloykim.lyra.amp.protocol.runtime

/**
 * `src/runtime/turnStore.ts` 的移植：轮次持久化端口。
 *
 * 一轮提交后即不可变；适配器可以把它持久化，用于重放、审计与评测。
 */
interface TurnStore {

    fun save(turn: RuntimeTurn)

    fun get(turnId: String): RuntimeTurn?

    fun list(pid: Int, limit: Int = 100): List<RuntimeTurn>
}

/** 内存实现：按提交顺序保存，`list` 按 `turnId` 倒序取前 `limit` 条。 */
class MemoryTurnStore : TurnStore {

    private val turns: MutableMap<String, RuntimeTurn> = LinkedHashMap()

    override fun save(turn: RuntimeTurn) {
        turns[turn.turnId] = turn
    }

    override fun get(turnId: String): RuntimeTurn? = turns[turnId]

    override fun list(pid: Int, limit: Int): List<RuntimeTurn> = turns.values
        .filter { it.pid == pid }
        .sortedByDescending { it.turnId }
        .take(limit)
}
