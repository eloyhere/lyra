package com.eloykim.lyra.amp.protocol.structure

/**
 * 数组实现的双端队列，移植自 `src/structure/deque.ts`。
 *
 * 值存放在由 head 下标与 count 寻址的环形缓冲区里，因此逻辑序列的前后两端都能
 * 摊还 O(1) 地更新，不需要移动其他值。缓冲区不要求长度是 2 的幂；满了翻倍，
 * pop 后占用降到 1/4 以下就减半。弹出的槽位会被清空，避免环形缓冲区继续持有
 * 已删除的值。
 */

private const val DequeInitialCapacity: Int = 8

/**
 * 双端队列。
 *
 * 可在任一端添加和移除值。空队列时 [popFront]、[popBack]、[peekFront]、
 * [peekBack] 返回 `null` 而不是抛异常。由于 `null` 也是合法值，需要区分
 * “存在”与“不存在”时请使用 [size] 或 [isEmpty]。
 *
 * 迭代顺序为队首到队尾。
 */
class Deque<T>(values: Iterable<T>? = null) : Iterable<T> {

    private var items: Array<T?> = arrayOfNulls(DequeInitialCapacity)
    private var head = 0
    private var count = 0

    init {
        values?.forEach { pushBack(it) }
    }

    /** 当前队列中的值数量。 */
    val size: Int get() = count

    /** 队列是否为空。 */
    val isEmpty: Boolean get() = count == 0

    /** 在队首添加值。 */
    fun pushFront(value: T): Deque<T> {
        if (count == items.size) {
            resize(items.size * 2)
        }
        head = (head - 1 + items.size) % items.size
        items[head] = value
        count += 1
        return this
    }

    /** 在队尾添加值。 */
    fun pushBack(value: T): Deque<T> {
        if (count == items.size) {
            resize(items.size * 2)
        }
        val tail = (head + count) % items.size
        items[tail] = value
        count += 1
        return this
    }

    /** [pushBack] 的别名。 */
    fun push(value: T): Deque<T> = pushBack(value)

    /** 移除并返回队首值；空队列返回 `null`。 */
    fun popFront(): T? {
        if (count == 0) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        val value = items[head] as T
        items[head] = null
        head = (head + 1) % items.size
        count -= 1
        shrinkIfSparse()
        return value
    }

    /** 移除并返回队尾值；空队列返回 `null`。 */
    fun popBack(): T? {
        if (count == 0) {
            return null
        }
        val tail = (head + count - 1) % items.size
        @Suppress("UNCHECKED_CAST")
        val value = items[tail] as T
        items[tail] = null
        count -= 1
        shrinkIfSparse()
        return value
    }

    /** 返回队首值但不移除；空队列返回 `null`。 */
    fun peekFront(): T? {
        if (count == 0) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return items[head] as T
    }

    /** 返回队尾值但不移除；空队列返回 `null`。 */
    fun peekBack(): T? {
        if (count == 0) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return items[(head + count - 1) % items.size] as T
    }

    /** 移除全部值并把环形缓冲区恢复到初始容量。 */
    fun clear() {
        items = arrayOfNulls(DequeInitialCapacity)
        head = 0
        count = 0
    }

    /** 自队首到队尾快照。 */
    fun toArray(): List<T> {
        val values = ArrayList<T>(count)
        for (index in 0 until count) {
            @Suppress("UNCHECKED_CAST")
            values.add(items[(head + index) % items.size] as T)
        }
        return values
    }

    /** 自队首到队尾遍历创建迭代器时的快照。 */
    override fun iterator(): Iterator<T> = toArray().iterator()

    /** 把存活值按队首到队尾复制进新的环形缓冲区。 */
    private fun resize(capacity: Int) {
        val next: Array<T?> = arrayOfNulls(capacity)
        for (index in 0 until count) {
            next[index] = items[(head + index) % items.size]
        }
        items = next
        head = 0
    }

    /** pop 后占用不超过 1/4 时把过大的环形缓冲区减半。 */
    private fun shrinkIfSparse() {
        if (items.size <= DequeInitialCapacity) {
            return
        }
        if (count > items.size / 4) {
            return
        }
        resize(items.size / 2)
    }
}
