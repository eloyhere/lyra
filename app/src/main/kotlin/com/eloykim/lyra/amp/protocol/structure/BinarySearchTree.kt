package com.eloykim.lyra.amp.protocol.structure

/**
 * 不平衡二叉搜索树，移植自 `src/structure/binarySearchTree.ts`。
 *
 * 值由传入的 [Comparator] 排序，默认为 [compareNatural]。重复值（比较结果为 0）
 * 会被拒绝。树刻意保持不平衡：有序插入序列会把它退化成链表，因此 `insert`、
 * `has`、`delete` 最坏 O(n)，只有在插入顺序恰好让树较浅时才是 O(log n)。
 * 需要最坏情况界时请使用 [AVLTree] 或 [RedBlackTree]。
 */

/** 一个搜索树节点；叶子处链接为 `null`。 */
private class SearchNode<T>(var value: T) {
    var left: SearchNode<T>? = null
    var right: SearchNode<T>? = null
}

/**
 * 不平衡的二叉搜索树。
 *
 * 中序迭代按配置的比较器有序。树对每个比较器等价类只保留一个代表，因此插入
 * 等价值返回 `false`，且已存储的代表不变。
 */
class BinarySearchTree<T>(compare: Comparator<T>? = null) : Iterable<T> {

    private var rootNode: SearchNode<T>? = null
    private var count = 0
    private val compareFn: Comparator<T> = compare ?: naturalComparator()

    /** 存储的值数量。 */
    val size: Int get() = count

    /** 最长根到叶路径上的节点数。空树 0，叶子 1。以 O(n) 计算。 */
    val height: Int
        get() {
            val start = rootNode ?: return 0
            var maximum = 0
            val stack = ArrayList<Pair<SearchNode<T>, Int>>()
            stack.add(start to 1)
            while (stack.isNotEmpty()) {
                val (node, depth) = stack.removeAt(stack.size - 1)
                if (depth > maximum) {
                    maximum = depth
                }
                node.left?.let { stack.add(it to depth + 1) }
                node.right?.let { stack.add(it to depth + 1) }
            }
            return maximum
        }

    /** 树是否不含值。 */
    val isEmpty: Boolean get() = count == 0

    /**
     * 当没有等价值已存储时插入。
     *
     * O(h)，最坏 O(n)。插入空树的第一个值不会调用比较器。
     *
     * @return 添加了节点返回 `true`，等价重复返回 `false`。
     */
    fun insert(value: T): Boolean {
        val start = rootNode
        if (start == null) {
            rootNode = SearchNode(value)
            count = 1
            return true
        }
        var node: SearchNode<T> = start
        while (true) {
            val order = compareFn.compare(value, node.value)
            if (order == 0) {
                return false
            }
            if (order < 0) {
                val left = node.left
                if (left == null) {
                    node.left = SearchNode(value)
                    count += 1
                    return true
                }
                node = left
            } else {
                val right = node.right
                if (right == null) {
                    node.right = SearchNode(value)
                    count += 1
                    return true
                }
                node = right
            }
        }
    }

    /** 是否存在等价的值。 */
    fun has(value: T): Boolean {
        var node = rootNode
        while (node != null) {
            val order = compareFn.compare(value, node.value)
            if (order == 0) {
                return true
            }
            node = if (order < 0) node.left else node.right
        }
        return false
    }

    /**
     * 删除与 `value` 等价的值。
     *
     * 处理零、一、两个子节点的情形；双子节点由中序后继替换。O(h)，最坏 O(n)。
     *
     * @return 删除了值返回 `true`，没有等价值返回 `false`。
     */
    fun delete(value: T): Boolean {
        var node = rootNode
        var parent: SearchNode<T>? = null
        while (node != null) {
            val order = compareFn.compare(value, node.value)
            if (order == 0) {
                break
            }
            parent = node
            node = if (order < 0) node.left else node.right
        }
        val target0 = node ?: return false

        var target = target0
        var targetParent = parent
        if (target.left != null && target.right != null) {
            var successorParent: SearchNode<T> = target
            var successor: SearchNode<T> = target.right!!
            while (successor.left != null) {
                successorParent = successor
                successor = successor.left!!
            }
            target.value = successor.value
            target = successor
            targetParent = successorParent
        }

        val child = if (target.left != null) target.left else target.right
        if (targetParent == null) {
            rootNode = child
        } else if (targetParent.left === target) {
            targetParent.left = child
        } else {
            targetParent.right = child
        }

        count -= 1
        return true
    }

    /** 比较器顺序下的最小值；空树返回 `null`。 */
    fun min(): T? {
        var node = rootNode ?: return null
        while (node.left != null) {
            node = node.left!!
        }
        return node.value
    }

    /** 比较器顺序下的最大值；空树返回 `null`。 */
    fun max(): T? {
        var node = rootNode ?: return null
        while (node.right != null) {
            node = node.right!!
        }
        return node.value
    }

    /** 比较器顺序下小于等于 `value` 的最大已存储值；没有则返回 `null`。 */
    fun floor(value: T): T? {
        var node = rootNode
        var result: T? = null
        while (node != null) {
            val order = compareFn.compare(node.value, value)
            if (order == 0) {
                return node.value
            }
            if (order < 0) {
                result = node.value
                node = node.right
            } else {
                node = node.left
            }
        }
        return result
    }

    /** 比较器顺序下大于等于 `value` 的最小已存储值；没有则返回 `null`。 */
    fun ceil(value: T): T? {
        var node = rootNode
        var result: T? = null
        while (node != null) {
            val order = compareFn.compare(node.value, value)
            if (order == 0) {
                return node.value
            }
            if (order < 0) {
                node = node.right
            } else {
                result = node.value
                node = node.left
            }
        }
        return result
    }

    /** 移除全部值。 */
    fun clear() {
        rootNode = null
        count = 0
    }

    /** 按比较器升序收集值。 */
    fun toArray(): List<T> = inorderValues().toList()

    /** 按比较器升序迭代。迭代期间修改树不受支持。 */
    override fun iterator(): Iterator<T> = inorderValues().iterator()

    /**
     * 检查树的不变式。
     *
     * 验证严格的中序有序性，以及维护的 `size` 与可达节点数量一致。
     * 合法树返回空列表；每个字符串描述一条违规。
     */
    fun validate(): List<String> {
        val errors = ArrayList<String>()
        val values = toArray()
        if (values.size != count) {
            errors.add("size mismatch: expected $count, traversed ${values.size}")
        }
        for (index in 1 until values.size) {
            if (compareFn.compare(values[index - 1], values[index]) >= 0) {
                errors.add("ordering violation between in-order positions ${index - 1} and $index")
            }
        }
        return errors
    }

    private fun inorderValues(): Sequence<T> = sequence {
        val stack = ArrayList<SearchNode<T>>()
        var node = rootNode
        while (node != null || stack.isNotEmpty()) {
            while (node != null) {
                stack.add(node)
                node = node.left
            }
            val current = stack.removeAt(stack.size - 1)
            yield(current.value)
            node = current.right
        }
    }
}
