package com.eloykim.lyra.amp.protocol.mission

import java.util.PriorityQueue

/**
 * `src/mission/missionQueue.ts` 的忠实移植：就绪进程之上的优先级视图。
 *
 * 内核调度线程；这个队列是 supervisor 层“下一个接纳哪个 mission”的选择。它是内存中的
 * 调度缓存，由 `ProcessRepository.runnable()` 在重建时喂入。
 *
 * 参考实现用 `BinaryHeap` + 惰性删除（`members` 集合）。Kotlin 侧用
 * `java.util.PriorityQueue` 表达同一顺序：`priority` 降序，`enqueuedAt` 升序，`pid`
 * 升序。被 [remove] 的条目留在堆里，[dequeue] 跳过它，与参考实现一致。
 *
 * 参考实现是单线程缓存；这里加锁只是把“顺序保持不变”的承诺延伸到多线程调用，
 * 不改变单线程的可观察行为。
 */
data class MissionEntry(
    val pid: Int,
    val priority: Int,
    /** `Date.now()` 毫秒时间戳。 */
    val enqueuedAt: Long,
)

class MissionQueue(
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val heap: PriorityQueue<MissionEntry> = PriorityQueue(
        Comparator { left, right ->
            when {
                left.priority != right.priority -> right.priority.compareTo(left.priority)
                left.enqueuedAt != right.enqueuedAt -> left.enqueuedAt.compareTo(right.enqueuedAt)
                else -> left.pid.compareTo(right.pid)
            }
        },
    )

    /** 当前仍在队列中的 pid；堆里的过期条目不算。 */
    private val members: MutableSet<Int> = LinkedHashSet()

    /** 已在队列中时返回 false（重复入队被忽略）。 */
    @Synchronized
    fun enqueue(pid: Int, priority: Int = 0): Boolean {
        if (members.contains(pid)) {
            return false
        }
        members.add(pid)
        heap.add(MissionEntry(pid = pid, priority = priority, enqueuedAt = now()))
        return true
    }

    /** 弹出优先级最高的活条目；堆里的过期条目被跳过。 */
    @Synchronized
    fun dequeue(): Int? {
        while (heap.isNotEmpty()) {
            val entry: MissionEntry = heap.poll() ?: return null
            if (members.contains(entry.pid)) {
                members.remove(entry.pid)
                return entry.pid
            }
        }
        return null
    }

    /** 从成员集合移除；返回是否真的移除过。堆里的条目变成过期条目。 */
    @Synchronized
    fun remove(pid: Int): Boolean = members.remove(pid)

    @Synchronized
    fun has(pid: Int): Boolean = members.contains(pid)

    val size: Int
        @Synchronized get() = members.size
}
