package com.eloykim.lyra.amp.protocol.structure

import java.util.IdentityHashMap

/**
 * 通用、与语言无关的抽象语法树，移植自 `src/structure/abstractSyntaxTree.ts`。
 *
 * 本模块不是解析器。它定义普通节点形状，以及供解析器/解释器复用的遍历、查找、
 * 映射、克隆与结构比较原语。节点载荷是不透明值，由调用方拥有；树从不检查或
 * 克隆它们。
 *
 * 节点及其 `children` 数组按设计可变。类按引用保存传入的根，不做深度校验，
 * 因此手工组装的节点也被接受。[AbstractSyntaxTree.leaf] 与
 * [AbstractSyntaxTree.branch] 会强制 `kind` 非空。
 */

/**
 * 节点的半开源码范围。
 */
data class SourceSpan(
    /** 包含式起始偏移。 */
    var start: Int,
    /** 排除式结束偏移。 */
    var end: Int,
    /** 可选的源码标识或文本。 */
    var source: String? = null,
)

/**
 * AST 中的一个节点。
 *
 * `children` 刻意可变；需要不可变副本时使用 [AbstractSyntaxTree.clone] 或
 * [AbstractSyntaxTree.map]。
 *
 * “缺省 value”与“显式 null”在 Kotlin 中都用 `null` 表示。
 *
 * @typeParam T 节点上存储的值载荷。
 */
class AstNode<T>(
    /** 节点类别。辅助构造器要求非空字符串。 */
    var kind: String,
    /** 可选载荷；遍历时忽略。 */
    var value: T? = null,
    /** 直接子节点，按语义顺序。 */
    var children: MutableList<AstNode<T>> = ArrayList(),
    /** 可选源码范围。 */
    var span: SourceSpan? = null,
)

/**
 * [AbstractSyntaxTree.walk] 支持的遍历顺序。
 */
enum class AstOrder(val wire: String) {
    PREORDER("preorder"),
    POSTORDER("postorder"),
    LEVELORDER("levelorder"),
    ;

    companion object {
        fun fromWire(value: String): AstOrder =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown AST traversal order: $value")
    }
}

/**
 * 可变根的树，带非破坏性的遍历与变换辅助方法。
 *
 * 构造函数接受任意节点值并按引用保存，不检查子树；手工构建节点的调用方需自行
 * 维护 [AstNode] 形状。
 *
 * @typeParam T 节点上存储的值载荷。
 */
class AbstractSyntaxTree<T>(private val rootNode: AstNode<T>) : Iterable<AstNode<T>> {

    /** 按引用保存的根节点。 */
    val root: AstNode<T> get() = rootNode

    /** 节点总数，因 children 可变而按需重算。 */
    val size: Int
        get() {
            var count = 0
            for (unused in walk()) {
                count += 1
            }
            return count
        }

    /** 最长根到叶路径上的节点数；叶子高度为 1。 */
    val height: Int
        get() {
            var maximum = 0
            val stack = ArrayList<Pair<AstNode<T>, Int>>()
            stack.add(rootNode to 1)
            while (stack.isNotEmpty()) {
                val (node, depth) = stack.removeAt(stack.size - 1)
                if (depth > maximum) {
                    maximum = depth
                }
                for (child in node.children) {
                    stack.add(child to depth + 1)
                }
            }
            return maximum
        }

    /** 没有子节点的节点数量。 */
    val leaves: Int
        get() {
            var count = 0
            val stack = ArrayList<AstNode<T>>()
            stack.add(rootNode)
            while (stack.isNotEmpty()) {
                val node = stack.removeAt(stack.size - 1)
                if (node.children.isEmpty()) {
                    count += 1
                } else {
                    stack.addAll(node.children)
                }
            }
            return count
        }

    /**
     * 按请求的顺序遍历树。
     *
     * 先序与后序从左到右访问子节点。层序按广度优先，层内同样从左到右。
     *
     * @param order 遍历顺序；默认为 [AstOrder.PREORDER]。
     */
    fun walk(order: AstOrder = AstOrder.PREORDER): Sequence<AstNode<T>> = sequence {
        when (order) {
            AstOrder.PREORDER -> {
                val stack = ArrayList<AstNode<T>>()
                stack.add(rootNode)
                while (stack.isNotEmpty()) {
                    val node = stack.removeAt(stack.size - 1)
                    yield(node)
                    for (index in node.children.indices.reversed()) {
                        stack.add(node.children[index])
                    }
                }
            }

            AstOrder.POSTORDER -> {
                val stack = ArrayList<Pair<AstNode<T>, Boolean>>()
                stack.add(rootNode to false)
                while (stack.isNotEmpty()) {
                    val (node, expanded) = stack.removeAt(stack.size - 1)
                    if (expanded) {
                        yield(node)
                        continue
                    }
                    stack.add(node to true)
                    for (index in node.children.indices.reversed()) {
                        stack.add(node.children[index] to false)
                    }
                }
            }

            AstOrder.LEVELORDER -> {
                val queue = ArrayList<AstNode<T>>()
                queue.add(rootNode)
                var next = 0
                while (next < queue.size) {
                    val node = queue[next]
                    next += 1
                    yield(node)
                    queue.addAll(node.children)
                }
            }
        }
    }

    /** 返回先序中第一个满足 `predicate` 的节点。 */
    fun find(predicate: (AstNode<T>) -> Boolean): AstNode<T>? =
        walk(AstOrder.PREORDER).firstOrNull(predicate)

    /** 返回所有满足 `predicate` 的节点，按先序。 */
    fun findAll(predicate: (AstNode<T>) -> Boolean): List<AstNode<T>> =
        walk(AstOrder.PREORDER).filter(predicate).toList()

    /**
     * 自底向上把每个节点变换成新树。
     *
     * 对每个原节点，`transform` 收到该原节点及其已映射的子节点（保持顺序）。
     * 原树永不被修改。
     *
     * @param transform 后序映射回调。
     * @typeParam U 映射后树的值载荷类型。
     */
    fun <U> map(transform: (AstNode<T>, List<AstNode<U>>) -> AstNode<U>): AbstractSyntaxTree<U> {
        val mapped = IdentityHashMap<AstNode<T>, AstNode<U>>()
        val stack = ArrayList<MappingFrame<T>>()
        stack.add(MappingFrame(rootNode))

        while (stack.isNotEmpty()) {
            val frame = stack[stack.size - 1]
            if (frame.nextChild < frame.node.children.size) {
                val child = frame.node.children[frame.nextChild]
                frame.nextChild += 1
                stack.add(MappingFrame(child))
                continue
            }

            val children = ArrayList<AstNode<U>>(frame.node.children.size)
            for (child in frame.node.children) {
                val mappedChild = mapped[child]
                    ?: throw IllegalStateException("cannot map an AST child before it has been visited")
                children.add(mappedChild)
            }
            mapped[frame.node] = transform(frame.node, children)
            stack.removeAt(stack.size - 1)
        }

        val mappedRoot = mapped[rootNode] ?: throw IllegalStateException("AST mapping produced no root")
        return AbstractSyntaxTree(mappedRoot)
    }

    /**
     * 深拷贝所有节点、children 数组与 span。
     *
     * 载荷值按引用复制。
     */
    fun clone(): AbstractSyntaxTree<T> = AbstractSyntaxTree(cloneNode(rootNode))

    /**
     * 比较 kind、value（`Object.is` 语义）、span 与有序子节点。
     *
     * 该方法与 `Any.equals` 分开命名，避免与引用相等的默认语义混淆。
     */
    fun contentEquals(other: AbstractSyntaxTree<T>): Boolean {
        val stack = ArrayList<Pair<AstNode<T>, AstNode<T>>>()
        stack.add(rootNode to other.rootNode)
        while (stack.isNotEmpty()) {
            val (left, right) = stack.removeAt(stack.size - 1)
            if (left.kind != right.kind) {
                return false
            }
            if (left.value != right.value) {
                return false
            }
            if (left.span != right.span) {
                return false
            }
            if (left.children.size != right.children.size) {
                return false
            }
            for (index in left.children.indices) {
                stack.add(left.children[index] to right.children[index])
            }
        }
        return true
    }

    /**
     * 返回适合序列化的普通深拷贝。
     *
     * 节点与 span 都是新分配的对象；载荷值按引用复制，与 [clone] 一致。
     */
    fun toPlain(): AstNode<T> = cloneNode(rootNode)

    /** 先序遍历；等价于 `walk(AstOrder.PREORDER)`。 */
    override fun iterator(): Iterator<AstNode<T>> = walk(AstOrder.PREORDER).iterator()

    private class MappingFrame<T>(val node: AstNode<T>) {
        var nextChild: Int = 0
    }

    companion object {

        /**
         * 创建只有一个叶节点的树。
         *
         * @throws IllegalArgumentException 当 `kind` 为空字符串。
         */
        fun <T> leaf(kind: String, value: T? = null, span: SourceSpan? = null): AbstractSyntaxTree<T> {
            assertKind(kind)
            return AbstractSyntaxTree(AstNode(kind, value, ArrayList(), span))
        }

        /**
         * 创建只有一个分支节点（带给定子节点）的树。
         *
         * 子节点可迭代对象会被复制进新的可变数组。
         *
         * @throws IllegalArgumentException 当 `kind` 为空字符串。
         */
        fun <T> branch(
            kind: String,
            children: Iterable<AstNode<T>>,
            value: T? = null,
            span: SourceSpan? = null,
        ): AbstractSyntaxTree<T> {
            assertKind(kind)
            return AbstractSyntaxTree(AstNode(kind, value, children.toMutableList(), span))
        }

        private fun assertKind(kind: String) {
            require(kind.isNotEmpty()) { "kind must be a non-empty string" }
        }
    }
}

/**
 * 深拷贝节点与 span，保留值引用。
 *
 * 迭代式且按节点身份记忆化，因此很高的树不会消耗调用栈，共享子树保持共享。
 */
private fun <T> cloneNode(root: AstNode<T>): AstNode<T> {
    val copies = IdentityHashMap<AstNode<T>, AstNode<T>>()
    val originals = ArrayList<AstNode<T>>()
    val pending = ArrayList<AstNode<T>>()
    pending.add(root)

    while (pending.isNotEmpty()) {
        val node = pending.removeAt(pending.size - 1)
        if (copies.containsKey(node)) {
            continue
        }
        val copy = AstNode(node.kind, node.value, ArrayList(), node.span?.let { copySpan(it) })
        copies[node] = copy
        originals.add(node)
        pending.addAll(node.children)
    }

    for (node in originals) {
        val copy = copies[node] ?: continue
        for (child in node.children) {
            copies[child]?.let { copy.children.add(it) }
        }
    }

    return copies[root] ?: throw IllegalStateException("failed to clone the AST root")
}

private fun copySpan(span: SourceSpan): SourceSpan = SourceSpan(span.start, span.end, span.source)
