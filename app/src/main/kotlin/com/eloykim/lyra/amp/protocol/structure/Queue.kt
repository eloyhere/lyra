package com.eloykim.lyra.amp.protocol.structure

/**
 * 数组实现的先进先出队列，移植自 `src/structure/queue.ts`。
 *
 * 值存放在由 head 下标与 count 寻址的环形缓冲区里。逻辑序列两端都通过下标
 * 运算更新：`enqueue` 写当前尾之后的位置，`dequeue` 推进 head，从不调用数组
 * 头部删除。缓冲区不要求长度是 2 的幂；满了翻倍，dequeue 后占用降到 1/4 以下
 * 就减半。所有操作均摊 O(1)。
 */

private const val QueueInitialCapacity: Int = 8

/**
 * 先进先出的队列。
 *
 * 空队列时 [dequeue] 与 [peek] 返回 `null` 而不是抛异常。由于 `null` 也是合法值，
 * 需要区分“存在”与“不存在”时请使用 [size] 或 [isEmpty]。
 *
 * 迭代顺序为队首到队尾。
 */
class Queue<T>(values: Iterable<T>? = null) : Iterable<T> {

    private var items: Array<T?> = arrayOfNulls(QueueInitialCapacity)
    private var head = 0
    private var count = 0

    init {
        values?.forEach { enqueue(it) }
    }

    /** 当前队列中的值数量。 */
    val size: Int get() = count

    /** 队列是否为空。 */
    val isEmpty: Boolean get() = count == 0

    /** 把值加到队尾。 */
    fun enqueue(value: T): Queue<T> {
        if (count == items.size) {
            resize(items.size * 2)
        }
        val tail = (head + count) % items.size
        items[tail] = value
        count += 1
        return this
    }

    /** 移除并返回队首值；空队列返回 `null`。 */
    fun dequeue(): T? {
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

    /** 返回队首值但不移除；空队列返回 `null`。 */
    fun peek(): T? {
        if (count == 0) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return items[head] as T
    }

    /** 移除全部值并把环形缓冲区恢复到初始容量。 */
    fun clear() {
        items = arrayOfNulls(QueueInitialCapacity)
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

    /** dequeue 后占用不超过 1/4 时把过大的环形缓冲区减半。 */
    private fun shrinkIfSparse() {
        if (items.size <= QueueInitialCapacity) {
            return
        }
        if (count > items.size / 4) {
            return
        }
        resize(items.size / 2)
    }
}
