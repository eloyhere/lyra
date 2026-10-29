package com.eloykim.lyra.amp.protocol.thread

/**
 * `src/thread/transitions.ts` 的忠实移植：纯线程生命周期谓词；不导入 DB 或领域实现。
 */

enum class ThreadState(val wire: String) {
    READY("ready"),
    RUNNING("running"),
    BLOCKED("blocked"),
    DONE("done"),
    FAILED("failed"),
    ABANDONED("abandoned"),
    ;

    companion object {
        fun fromWire(value: String): ThreadState =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 thread state: $value")
    }
}

val ThreadEdges: Map<ThreadState, List<ThreadState>> = linkedMapOf(
    ThreadState.READY to listOf(
        ThreadState.RUNNING,
        ThreadState.FAILED,
        ThreadState.ABANDONED,
    ),
    ThreadState.RUNNING to listOf(
        ThreadState.READY,
        ThreadState.BLOCKED,
        ThreadState.DONE,
        ThreadState.FAILED,
        ThreadState.ABANDONED,
    ),
    ThreadState.BLOCKED to listOf(
        ThreadState.READY,
        ThreadState.DONE,
        ThreadState.FAILED,
        ThreadState.ABANDONED,
    ),
    ThreadState.DONE to emptyList(),
    ThreadState.FAILED to emptyList(),
    ThreadState.ABANDONED to emptyList(),
)

fun canThreadTransition(from: ThreadState, to: ThreadState): Boolean =
    ThreadEdges.getValue(from).contains(to)
