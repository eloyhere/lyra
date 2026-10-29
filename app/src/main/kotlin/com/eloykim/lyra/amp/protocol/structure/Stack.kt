package com.eloykim.lyra.amp.protocol.structure

/**
 * 数组实现的后进先出栈，移植自 `src/structure/stack.ts`。
 *
 * 值存放在原生数组中，`push` / `pop` 均摊 O(1)。迭代从栈顶开始，
 * 使用创建迭代器时的快照，因此之后的 push / pop 不会干扰遍历。
 */

/**
 * 后进先出的栈。
 *
 * 空栈时 [pop] 与 [peek] 返回 `null` 而不是抛异常。由于 `null` 也是合法值，
 * 需要区分“存在”与“不存在”时请使用 [size] 或 [isEmpty]。
 *
 * 迭代顺序为栈顶到栈底。
 */
class Stack<T>(values: Iterable<T>? = null) : Iterable<T> {

    private val items = ArrayList<T>()

    init {
        values?.let { items.addAll(it) }
    }

    /** 当前栈中的值数量。 */
    val size: Int get() = items.size

    /** 栈是否为空。 */
    val isEmpty: Boolean get() = items.isEmpty()

    /** 把值压入栈顶。 */
    fun push(value: T): Stack<T> {
        items.add(value)
        return this
    }

    /** 移除并返回栈顶值；空栈返回 `null`。 */
    fun pop(): T? = if (items.isEmpty()) null else items.removeAt(items.size - 1)

    /** 返回栈顶值但不移除；空栈返回 `null`。 */
    fun peek(): T? = items.lastOrNull()

    /** 移除全部值。 */
    fun clear() = items.clear()

    /** 自顶向下快照。 */
    fun toArray(): List<T> = items.reversed()

    /** 自顶向下遍历创建迭代器时的快照。 */
    override fun iterator(): Iterator<T> = toArray().iterator()
}
