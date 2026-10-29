package com.eloykim.lyra.amp.protocol.structure

import java.util.IdentityHashMap

/**
 * 红黑二叉搜索树，移植自 `src/structure/redBlackTree.ts`。
 *
 * 树使用显式 `null` 叶子（无哨兵），每个节点保存父指针。插入与完整 CLRS 风格的
 * 删除修复维护红黑不变式：根是黑色、红节点的子节点是黑色、每条根到 `null` 的
 * 路径包含相同数量的黑节点。这些不变式把高度限制在 `2 * log2(n + 1)` 内，因此
 * `insert`、`has`、`delete`、`min`、`max`、`floor`、`ceil` 都是最坏 O(log n)。
 */

/** 一个红黑节点；`red` 是颜色位，叶子为 `null`。 */
private class RBNode<T>(var value: T) {
    var left: RBNode<T>? = null
    var right: RBNode<T>? = null
    var parent: RBNode<T>? = null
    var red: Boolean = true
}

/**
 * 自平衡的红黑二叉搜索树。
 *
 * 重复值（比较结果为 0）被拒绝。中序迭代按配置的比较器有序。
 */
class RedBlackTree<T>(compare: Comparator<T>? = null) : Iterable<T> {

    private var rootNode: RBNode<T>? = null
    private var count = 0
    private val compareFn: Comparator<T> = compare ?: naturalComparator()

    /** 存储的值数量。 */
    val size: Int get() = count

    /**
     * 以节点计的高度：空树 0，叶子 1。
     *
     * 按需以 O(n) 计算；红黑不变式把它限制在 O(log n)。
     */
    val height: Int
        get() {
            val start = rootNode ?: return 0
            var maximum = 0
            val stack = ArrayList<Pair<RBNode<T>, Int>>()
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
     * 新节点从红色开始，由标准 CLRS 插入修复处理，包括全部 LL/RR/LR/RL 旋转形状。
     *
     * @return 添加了节点返回 `true`，等价重复返回 `false`。
     */
    fun insert(value: T): Boolean {
        var parent: RBNode<T>? = null
        var node = rootNode
        var goLeft = false
        while (node != null) {
            val order = compareFn.compare(value, node.value)
            if (order == 0) {
                return false
            }
            parent = node
            goLeft = order < 0
            node = if (goLeft) node.left else node.right
        }

        val inserted = RBNode(value)
        inserted.parent = parent
        inserted.red = true
        if (parent == null) {
            rootNode = inserted
        } else if (goLeft) {
            parent.left = inserted
        } else {
            parent.right = inserted
        }

        count += 1
        insertFixup(inserted)
        rootNode?.red = false
        return true
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
     * 处理零、一、两个子节点的删除，并运行完整的 CLRS 删除修复，返回前恢复所有
     * 红黑不变式。双子节点由中序后继替换。
     *
     * @return 删除了值返回 `true`，没有等价值返回 `false`。
     */
    fun delete(value: T): Boolean {
        var node = rootNode
        while (node != null) {
            val order = compareFn.compare(value, node.value)
            if (order == 0) {
                break
            }
            node = if (order < 0) node.left else node.right
        }
        val target = node ?: return false

        deleteNode(target)
        count -= 1
        rootNode?.red = false
        return true
    }

    /** 比较器顺序下的最小值；空树返回 `null`。 */
    fun min(): T? = rootNode?.let { minimumNode(it).value }

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

    /** 移除全部值并把根重置为 `null`。 */
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
     * 验证：根是黑色、红节点没有红子节点、每条根到 `null` 路径黑高相同、
     * 中序值严格递增、父链接一致，以及维护的 size 与可达节点数量一致。
     * 空列表表示树合法。
     */
    fun validate(): List<String> {
        val errors = ArrayList<String>()
        val start = rootNode
        if (start == null) {
            if (count != 0) {
                errors.add("size mismatch: expected $count, found an empty tree")
            }
            return errors
        }

        if (start.red) {
            errors.add("root is red")
        }
        if (start.parent != null) {
            errors.add("root has a parent")
        }

        val values = toArray()
        if (values.size != count) {
            errors.add("size mismatch: expected $count, traversed ${values.size}")
        }
        for (index in 1 until values.size) {
            if (compareFn.compare(values[index - 1], values[index]) >= 0) {
                errors.add("ordering violation between in-order positions ${index - 1} and $index")
            }
        }

        val blackHeights = IdentityHashMap<RBNode<T>, Int>()
        val stack = ArrayList<Pair<RBNode<T>, Boolean>>()
        stack.add(start to false)
        var seen = 0
        while (stack.isNotEmpty()) {
            val (node, visited) = stack.removeAt(stack.size - 1)
            if (!visited) {
                stack.add(node to true)
                node.right?.let { stack.add(it to false) }
                node.left?.let { stack.add(it to false) }
                continue
            }

            seen += 1
            if (node.red) {
                if (node.left?.red == true) {
                    errors.add("red node has a red left child")
                }
                if (node.right?.red == true) {
                    errors.add("red node has a red right child")
                }
            }
            node.left?.let { if (it.parent !== node) errors.add("left child has an inconsistent parent link") }
            node.right?.let { if (it.parent !== node) errors.add("right child has an inconsistent parent link") }

            val leftBlack = node.left?.let { blackHeights[it] ?: -1 } ?: 0
            val rightBlack = node.right?.let { blackHeights[it] ?: -1 } ?: 0
            if (leftBlack < 0 || rightBlack < 0) {
                errors.add("black height is missing for a child subtree")
                blackHeights[node] = if (node.red) 0 else 1
                continue
            }
            if (leftBlack != rightBlack) {
                errors.add("black-height mismatch: left $leftBlack, right $rightBlack")
            }
            blackHeights[node] = (if (node.red) 0 else 1) + maxOf(leftBlack, rightBlack)
        }
        if (seen != count) {
            errors.add("size mismatch: expected $count, reached $seen nodes")
        }
        return errors
    }

    /** 插入红色叶子之后恢复红黑不变式。 */
    private fun insertFixup(start: RBNode<T>) {
        var node = start
        while (node.parent != null && node.parent!!.red) {
            val parent = node.parent!!
            val grandparent = parent.parent ?: break
            if (parent === grandparent.left) {
                val uncle = grandparent.right
                if (uncle != null && uncle.red) {
                    parent.red = false
                    uncle.red = false
                    grandparent.red = true
                    node = grandparent
                    continue
                }
                if (node === parent.right) {
                    node = parent
                    rotateLeft(node)
                }
                val nextParent = node.parent
                val upper = nextParent?.parent
                nextParent?.red = false
                if (upper != null) {
                    upper.red = true
                    rotateRight(upper)
                }
            } else {
                val uncle = grandparent.left
                if (uncle != null && uncle.red) {
                    parent.red = false
                    uncle.red = false
                    grandparent.red = true
                    node = grandparent
                    continue
                }
                if (node === parent.left) {
                    node = parent
                    rotateRight(node)
                }
                val nextParent = node.parent
                val upper = nextParent?.parent
                nextParent?.red = false
                if (upper != null) {
                    upper.red = true
                    rotateLeft(upper)
                }
            }
        }
        rootNode?.red = false
    }

    /** 移除一个已知节点，保持树形直到修复运行。 */
    private fun deleteNode(node: RBNode<T>) {
        var moved = node
        var movedWasRed = moved.red
        var replacement: RBNode<T>? = null
        var replacementParent: RBNode<T>? = null

        if (node.left == null) {
            replacement = node.right
            replacementParent = node.parent
            transplant(node, node.right)
        } else if (node.right == null) {
            replacement = node.left
            replacementParent = node.parent
            transplant(node, node.left)
        } else {
            moved = minimumNode(node.right!!)
            movedWasRed = moved.red
            replacement = moved.right
            if (moved.parent === node) {
                replacementParent = moved
                replacement?.parent = moved
            } else {
                replacementParent = moved.parent
                transplant(moved, moved.right)
                moved.right = node.right
                moved.right?.parent = moved
            }
            transplant(node, moved)
            moved.left = node.left
            moved.left?.parent = moved
            moved.red = node.red
        }

        if (!movedWasRed) {
            deleteFixup(replacement, replacementParent)
        }
    }

    /** 用子树 `replacement` 替换以 `target` 为根的子树。 */
    private fun transplant(target: RBNode<T>, replacement: RBNode<T>?) {
        val parent = target.parent
        if (parent == null) {
            rootNode = replacement
        } else if (target === parent.left) {
            parent.left = replacement
        } else {
            parent.right = replacement
        }
        replacement?.parent = parent
    }

    /** 删除黑节点之后恢复红黑不变式。 */
    private fun deleteFixup(start: RBNode<T>?, startParent: RBNode<T>?) {
        var node = start
        var parent = startParent
        while (node !== rootNode && !isRed(node)) {
            val currentParent = parent ?: break
            if (node === currentParent.left) {
                var sibling = currentParent.right
                if (sibling == null) {
                    node = currentParent
                    parent = node.parent
                    continue
                }
                if (sibling.red) {
                    sibling.red = false
                    currentParent.red = true
                    rotateLeft(currentParent)
                    sibling = currentParent.right
                    if (sibling == null) {
                        node = currentParent
                        parent = node.parent
                        continue
                    }
                }
                val leftRed = isRed(sibling.left)
                val rightRed = isRed(sibling.right)
                if (!leftRed && !rightRed) {
                    sibling.red = true
                    node = currentParent
                    parent = node.parent
                    continue
                }
                if (!rightRed) {
                    sibling.left?.red = false
                    sibling.red = true
                    rotateRight(sibling)
                    sibling = currentParent.right
                    if (sibling == null) {
                        node = currentParent
                        parent = node.parent
                        continue
                    }
                }
                sibling.red = currentParent.red
                currentParent.red = false
                sibling.right?.red = false
                rotateLeft(currentParent)
                node = rootNode
                parent = null
            } else {
                var sibling = currentParent.left
                if (sibling == null) {
                    node = currentParent
                    parent = node.parent
                    continue
                }
                if (sibling.red) {
                    sibling.red = false
                    currentParent.red = true
                    rotateRight(currentParent)
                    sibling = currentParent.left
                    if (sibling == null) {
                        node = currentParent
                        parent = node.parent
                        continue
                    }
                }
                val leftRed = isRed(sibling.left)
                val rightRed = isRed(sibling.right)
                if (!leftRed && !rightRed) {
                    sibling.red = true
                    node = currentParent
                    parent = node.parent
                    continue
                }
                if (!leftRed) {
                    sibling.right?.red = false
                    sibling.red = true
                    rotateLeft(sibling)
                    sibling = currentParent.left
                    if (sibling == null) {
                        node = currentParent
                        parent = node.parent
                        continue
                    }
                }
                sibling.red = currentParent.red
                currentParent.red = false
                sibling.left?.red = false
                rotateRight(currentParent)
                node = rootNode
                parent = null
            }
        }
        node?.red = false
    }

    /** 绕 `node` 左旋；必要时更新根。 */
    private fun rotateLeft(node: RBNode<T>) {
        val pivot = node.right ?: return
        node.right = pivot.left
        pivot.left?.parent = node
        val parent = node.parent
        pivot.parent = parent
        if (parent == null) {
            rootNode = pivot
        } else if (parent.left === node) {
            parent.left = pivot
        } else {
            parent.right = pivot
        }
        pivot.left = node
        node.parent = pivot
    }

    /** 绕 `node` 右旋；必要时更新根。 */
    private fun rotateRight(node: RBNode<T>) {
        val pivot = node.left ?: return
        node.left = pivot.right
        pivot.right?.parent = node
        val parent = node.parent
        pivot.parent = parent
        if (parent == null) {
            rootNode = pivot
        } else if (parent.right === node) {
            parent.right = pivot
        } else {
            parent.left = pivot
        }
        pivot.right = node
        node.parent = pivot
    }

    private fun isRed(node: RBNode<T>?): Boolean = node != null && node.red

    private fun minimumNode(node: RBNode<T>): RBNode<T> {
        var current = node
        while (current.left != null) {
            current = current.left!!
        }
        return current
    }

    private fun inorderValues(): Sequence<T> = sequence {
        val stack = ArrayList<RBNode<T>>()
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
