package com.eloykim.lyra.amp.protocol.structure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `tools/segment-ast-check.ts` 的 Kotlin 版本：线段树与通用 AST。
 */
class SegmentAstCheckTest {

    private val sum: (Int, Int) -> Int = { left, right -> left + right }
    private val min: (Int, Int) -> Int = { left, right -> minOf(left, right) }
    private val max: (Int, Int) -> Int = { left, right -> maxOf(left, right) }
    private val concatenate: (String, String) -> String = { left, right -> left + right }

    private val gcd: (Int, Int) -> Int = { left, right ->
        var a = Math.abs(left)
        var b = Math.abs(right)
        while (b != 0) {
            val remainder = a % b
            a = b
            b = remainder
        }
        a
    }

    private fun nodeKinds(nodes: Iterable<AstNode<*>>): List<String> = nodes.map { it.kind }

    private fun sequenceKinds(nodes: Sequence<AstNode<*>>): List<String> = nodes.map { it.kind }.toList()

    private data class MappedCall(val kind: String, val childKinds: List<String>)

    private fun makeNumberTree(): AbstractSyntaxTree<Int> {
        val a = AbstractSyntaxTree.leaf("a", 1, SourceSpan(0, 1, "sample"))
        val b1 = AbstractSyntaxTree.leaf("b1", 2, SourceSpan(3, 4, "sample"))
        val b2 = AbstractSyntaxTree.leaf("b2", 3, SourceSpan(4, 5, "sample"))
        val b = AbstractSyntaxTree.branch("b", listOf(b1.root, b2.root), 0, SourceSpan(2, 5, "sample"))
        val c = AbstractSyntaxTree.leaf("c", 4, SourceSpan(6, 7, "sample"))
        return AbstractSyntaxTree.branch("root", listOf(a.root, b.root, c.root), 0, SourceSpan(0, 8, "sample"))
    }

    @Test
    fun segmentTreeInitializesReadsSetsAndUpdatesSumLeaves() {
        val tree = SegmentTree(size = 5, operation = sum, identity = 0, values = listOf(1, 2, 3, 4, 5))
        assertEquals(5, tree.size)
        assertEquals(listOf(1, 2, 3, 4, 5), tree.toArray())
        assertEquals(1, tree.get(0))
        assertEquals(5, tree.get(4))

        tree.set(2, 10)
        assertEquals(10, tree.get(2))
        assertEquals(22, tree.query(0, 5))

        var observed = -1
        tree.update(4) { previous ->
            observed = previous
            previous + 10
        }
        assertEquals(5, observed)
        assertEquals(15, tree.get(4))
        assertEquals(32, tree.queryAll())
        assertEquals(listOf(1, 2, 10, 4, 15), tree.toArray())

        val identityFilled = SegmentTree(size = 4, operation = sum, identity = 0)
        assertEquals(listOf(0, 0, 0, 0), identityFilled.toArray())
        identityFilled.set(1, 7)
        assertEquals(7, identityFilled.queryAll())
    }

    @Test
    fun segmentTreeSumRangeQueriesIncludeEmptyAndFullRanges() {
        val tree = SegmentTree(size = 6, operation = sum, identity = 0, values = listOf(1, 2, 3, 4, 5, 6))
        assertEquals(21, tree.query(0, 6))
        assertEquals(21, tree.queryAll())
        assertEquals(9, tree.query(1, 4))
        assertEquals(10, tree.query(0, 4))
        assertEquals(18, tree.query(2, 6))
        assertEquals(1, tree.query(0, 1))
        assertEquals(6, tree.query(5, 6))
        assertEquals(0, tree.query(0, 0))
        assertEquals(0, tree.query(2, 2))
        assertEquals(0, tree.query(6, 6))
    }

    @Test
    fun segmentTreeMinQueriesUseTheIdentityForEmptyRanges() {
        val tree = SegmentTree(
            size = 7,
            operation = min,
            identity = Int.MAX_VALUE,
            values = listOf(5, 3, 8, 1, 9, 2, 7),
        )
        assertEquals(1, tree.query(0, 7))
        assertEquals(1, tree.queryAll())
        assertEquals(1, tree.query(0, 4))
        assertEquals(3, tree.query(0, 3))
        assertEquals(2, tree.query(4, 7))
        assertEquals(1, tree.query(3, 4))
        assertEquals(Int.MAX_VALUE, tree.query(2, 2))
        tree.update(3) { previous -> previous + 10 }
        assertEquals(3, tree.query(0, 4))
        assertEquals(11, tree.query(3, 4))
    }

    @Test
    fun segmentTreeMaxQueriesTrackPointReplacements() {
        val tree = SegmentTree(
            size = 5,
            operation = max,
            identity = Int.MIN_VALUE,
            values = listOf(4, 1, 7, 3, 6),
        )
        assertEquals(7, tree.query(0, 5))
        assertEquals(7, tree.queryAll())
        assertEquals(7, tree.query(1, 4))
        assertEquals(6, tree.query(3, 5))
        assertEquals(Int.MIN_VALUE, tree.query(0, 0))
        tree.set(2, 0)
        assertEquals(4, tree.query(0, 3))
        assertEquals(6, tree.query(2, 5))
    }

    @Test
    fun segmentTreeGcdQueriesCombineValuesAndTrackUpdates() {
        val tree = SegmentTree(
            size = 5,
            operation = gcd,
            identity = 0,
            values = listOf(12, 18, 24, 5, 10),
        )
        assertEquals(6, tree.query(0, 3))
        assertEquals(1, tree.query(2, 5))
        assertEquals(1, tree.query(0, 5))
        assertEquals(1, tree.queryAll())
        assertEquals(18, tree.query(1, 2))
        assertEquals(0, tree.query(3, 3))
        tree.update(3) { 30 }
        assertEquals(6, tree.query(0, 4))
        assertEquals(2, tree.queryAll())
    }

    @Test
    fun segmentTreePreservesLeftToRightOrderForStringConcatenation() {
        val tree = SegmentTree(
            size = 5,
            operation = concatenate,
            identity = "",
            values = listOf("a", "b", "c", "d", "e"),
        )
        assertEquals("abc", tree.query(0, 3))
        assertEquals("bcd", tree.query(1, 4))
        assertEquals("cde", tree.query(2, 5))
        assertEquals("abcde", tree.query(0, 5))
        assertEquals("abcde", tree.queryAll())
        assertEquals("", tree.query(2, 2))
        assertEquals("", tree.query(3, 3))
        assertNotEquals("cba", tree.query(0, 3))

        tree.set(1, "B")
        assertEquals("aBc", tree.query(0, 3))
        tree.update(2) { previous -> "[$previous]" }
        assertEquals("aB[c]d", tree.query(0, 4))
        assertEquals("B", tree.query(1, 2))
    }

    @Test
    fun segmentTreeValidatesLeafIndicesAndQueryBounds() {
        val tree = SegmentTree(size = 4, operation = sum, identity = 0, values = listOf(1, 2, 3, 4))
        val indexError = assertThrows(IndexOutOfBoundsException::class.java) { tree.get(-1) }
        assertTrue(indexError.message!!.contains("index must be an integer"))
        assertThrows(IndexOutOfBoundsException::class.java) { tree.get(4) }
        assertThrows(IndexOutOfBoundsException::class.java) { tree.set(-1, 0) }
        assertThrows(IndexOutOfBoundsException::class.java) { tree.set(4, 0) }
        assertThrows(IndexOutOfBoundsException::class.java) { tree.update(5) { previous -> previous } }
        val rangeError = assertThrows(IndexOutOfBoundsException::class.java) { tree.query(-1, 2) }
        assertTrue(rangeError.message!!.contains("0 <= left <= right <= 4"))
        assertThrows(IndexOutOfBoundsException::class.java) { tree.query(0, 5) }
        assertThrows(IndexOutOfBoundsException::class.java) { tree.query(3, 2) }
        assertThrows(IllegalArgumentException::class.java) {
            SegmentTree(size = -1, operation = sum, identity = 0)
        }
    }

    @Test
    fun segmentTreeRejectsValueCountsThatDoNotMatchSize() {
        val tooFew = assertThrows(IllegalArgumentException::class.java) {
            SegmentTree(size = 3, operation = sum, identity = 0, values = listOf(1, 2))
        }
        assertTrue(tooFew.message!!.contains("values must contain exactly 3 values, received 2"))
        assertThrows(IllegalArgumentException::class.java) {
            SegmentTree(size = 3, operation = sum, identity = 0, values = listOf(1, 2, 3, 4))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SegmentTree(size = 0, operation = sum, identity = 0, values = listOf(1))
        }
        val emptyWithValues = SegmentTree(size = 0, operation = sum, identity = 0, values = emptyList())
        assertEquals(emptyList<Int>(), emptyWithValues.toArray())
    }

    @Test
    fun segmentTreeSupportsSizeZero() {
        val empty = SegmentTree(size = 0, operation = sum, identity = 0)
        assertEquals(0, empty.size)
        assertEquals(emptyList<Int>(), empty.toArray())
        assertEquals(0, empty.query(0, 0))
        assertEquals(0, empty.queryAll())
        empty.clear()
        assertEquals(0, empty.queryAll())
        assertThrows(IndexOutOfBoundsException::class.java) { empty.get(0) }
        assertThrows(IndexOutOfBoundsException::class.java) { empty.set(0, 1) }
        assertThrows(IndexOutOfBoundsException::class.java) { empty.update(0) { previous -> previous + 1 } }
        assertThrows(IndexOutOfBoundsException::class.java) { empty.query(0, 1) }
    }

    @Test
    fun segmentTreeClearResetsAllLeavesAndRemainsUsable() {
        val tree = SegmentTree(size = 6, operation = sum, identity = 0, values = listOf(1, 2, 3, 4, 5, 6))
        tree.clear()
        assertEquals(6, tree.size)
        assertEquals(listOf(0, 0, 0, 0, 0, 0), tree.toArray())
        assertEquals(0, tree.queryAll())
        assertEquals(0, tree.query(0, 6))
        assertEquals(0, tree.get(3))

        tree.set(2, 5)
        assertEquals(5, tree.get(2))
        assertEquals(5, tree.queryAll())
        assertEquals(5, tree.query(0, 3))

        val text = SegmentTree(size = 4, operation = concatenate, identity = "", values = listOf("a", "b", "c", "d"))
        text.clear()
        assertEquals("", text.queryAll())
        text.set(1, "B")
        assertEquals("B", text.query(0, 3))
    }

    @Test
    fun segmentTreeMatchesNaiveSumAndMinOver10000DeterministicOperations() {
        val size = 23
        val random = Lcg(0x5eed)
        val values = IntArray(size) { Math.floor(random.next() * 41).toInt() - 20 }

        val sumTree = SegmentTree(size = size, operation = sum, identity = 0, values = values.toList())
        val minTree = SegmentTree(size = size, operation = min, identity = Int.MAX_VALUE, values = values.toList())

        for (operation in 0 until 10_000) {
            val choice = random.next()
            val index = Math.floor(random.next() * size).toInt()

            if (choice < 0.45) {
                val value = Math.floor(random.next() * 41).toInt() - 20
                values[index] = value
                sumTree.set(index, value)
                minTree.set(index, value)
                continue
            }

            if (choice < 0.75) {
                val delta = Math.floor(random.next() * 11).toInt() - 5
                values[index] += delta
                sumTree.update(index) { previous -> previous + delta }
                minTree.update(index) { previous -> previous + delta }
                continue
            }

            var left = Math.floor(random.next() * (size + 1)).toInt()
            var right = Math.floor(random.next() * (size + 1)).toInt()
            if (left > right) {
                val swap = left
                left = right
                right = swap
            }

            var expectedSum = 0
            var expectedMin = Int.MAX_VALUE
            for (cursor in left until right) {
                expectedSum += values[cursor]
                expectedMin = minOf(expectedMin, values[cursor])
            }

            assertEquals(expectedSum, sumTree.query(left, right))
            assertEquals(expectedMin, minTree.query(left, right))
        }

        assertEquals(values.toList(), sumTree.toArray())
        assertEquals(values.toList(), minTree.toArray())
    }

    @Test
    fun astLeafAndBranchHelpersBuildTheDocumentedShape() {
        val leafSpan = SourceSpan(2, 5, "input")
        val leaf = AbstractSyntaxTree.leaf("token", "value", leafSpan)
        assertEquals("token", leaf.root.kind)
        assertEquals("value", leaf.root.value)
        assertEquals(emptyList<AstNode<String>>(), leaf.root.children)
        assertEquals(leafSpan, leaf.root.span)
        assertEquals(1, leaf.size)

        val left = AbstractSyntaxTree.leaf("left", 1)
        val right = AbstractSyntaxTree.leaf("right", 2)
        val branch = AbstractSyntaxTree.branch("sum", linkedSetOf(left.root, right.root), 10, SourceSpan(0, 2, "input"))
        assertEquals("sum", branch.root.kind)
        assertEquals(10, branch.root.value)
        assertEquals(listOf(left.root, right.root), branch.root.children)
        assertEquals(SourceSpan(0, 2, "input"), branch.root.span)

        val manual = AstNode<Any?>("manual", children = mutableListOf(AstNode<Any?>("inner")))
        val wrapped = AbstractSyntaxTree(manual)
        assertSame(manual, wrapped.root)
        assertEquals(2, wrapped.size)
    }

    @Test
    fun astSizeHeightAndLeavesDescribeTheTree() {
        val tree = makeNumberTree()
        assertEquals(6, tree.size)
        assertEquals(3, tree.height)
        assertEquals(4, tree.leaves)

        val leaf = AbstractSyntaxTree.leaf<Any?>("only")
        assertEquals(1, leaf.size)
        assertEquals(1, leaf.height)
        assertEquals(1, leaf.leaves)

        val empty = AbstractSyntaxTree.branch<Any?>("empty", emptyList())
        assertEquals(1, empty.size)
        assertEquals(1, empty.height)
        assertEquals(1, empty.leaves)
    }

    @Test
    fun astWalksPreorderPostorderAndLevelOrder() {
        val tree = makeNumberTree()
        assertEquals(listOf("root", "a", "b", "b1", "b2", "c"), sequenceKinds(tree.walk()))
        assertEquals(listOf("root", "a", "b", "b1", "b2", "c"), sequenceKinds(tree.walk(AstOrder.PREORDER)))
        assertEquals(listOf("a", "b1", "b2", "b", "c", "root"), sequenceKinds(tree.walk(AstOrder.POSTORDER)))
        assertEquals(listOf("root", "a", "b", "c", "b1", "b2"), sequenceKinds(tree.walk(AstOrder.LEVELORDER)))
    }

    @Test
    fun astFindAndFindAllRespectPreorder() {
        val tree = makeNumberTree()
        val found = tree.find { node -> node.kind == "b1" }
        assertTrue(found != null)
        assertEquals(2, found!!.value)
        assertNull(tree.find { node -> node.kind == "missing" })
        assertEquals(
            listOf("a", "b", "b1", "b2"),
            nodeKinds(tree.findAll { node -> node.kind == "a" || node.kind.startsWith("b") }),
        )
    }

    @Test
    fun astMapRunsPostOrderWithMappedChildrenAndDoesNotMutateTheOriginal() {
        val tree = makeNumberTree()
        val snapshot = tree.toPlain()
        val calls = ArrayList<MappedCall>()

        val mapped = tree.map<Int> { node, children ->
            calls.add(MappedCall(node.kind, children.map { child -> child.kind }))
            val ownValue = node.value ?: 0
            val childTotal = children.sumOf { child -> child.value ?: 0 }
            val result = AstNode("mapped:${node.kind}", ownValue + childTotal, children.toMutableList())
            node.span?.let { result.span = SourceSpan(it.start, it.end, it.source) }
            result
        }

        assertEquals(
            listOf(
                MappedCall("a", emptyList()),
                MappedCall("b1", emptyList()),
                MappedCall("b2", emptyList()),
                MappedCall("b", listOf("mapped:b1", "mapped:b2")),
                MappedCall("c", emptyList()),
                MappedCall("root", listOf("mapped:a", "mapped:b", "mapped:c")),
            ),
            calls,
        )
        assertEquals("mapped:root", mapped.root.kind)
        assertEquals(10, mapped.root.value)
        assertEquals(6, mapped.size)
        assertEquals(3, mapped.height)
        assertTrue(AbstractSyntaxTree(snapshot).contentEquals(tree))
    }

    @Test
    fun astCloneCopiesNodesAndSpansDeeplyWhileSharingValues() {
        val tree = makeNumberTree()
        val clone = tree.clone()
        assertNotSame(tree.root, clone.root)
        assertNotSame(tree.root.children, clone.root.children)
        assertNotSame(tree.root.children[0], clone.root.children[0])
        assertNotSame(tree.root.span, clone.root.span)
        assertNotSame(tree.root.children[0].span, clone.root.children[0].span)
        assertTrue(clone.contentEquals(tree))
        assertTrue(AbstractSyntaxTree(clone.toPlain()).contentEquals(tree))

        val cloneSpan = clone.root.span
        val originalSpan = tree.root.span
        assertTrue(cloneSpan != null)
        assertTrue(originalSpan != null)
        cloneSpan!!.start = 99
        clone.root.kind = "mutated"
        clone.root.children.clear()
        assertEquals("root", tree.root.kind)
        assertEquals(0, originalSpan!!.start)
        assertEquals(3, tree.root.children.size)
        assertEquals(6, tree.size)

        val payload = intArrayOf(1)
        val single = AbstractSyntaxTree.leaf("object", payload)
        val payloadClone = single.clone()
        assertSame(payload, payloadClone.root.value)
    }

    @Test
    fun astContentEqualsComparesKindValueSpanAndChildOrder() {
        val tree = makeNumberTree()
        assertTrue(tree.contentEquals(makeNumberTree()))
        assertTrue(tree.contentEquals(tree.clone()))

        val kindClone = tree.clone()
        kindClone.root.kind = "other"
        assertFalse(tree.contentEquals(kindClone))

        val valueClone = tree.clone()
        valueClone.root.value = 1
        assertFalse(tree.contentEquals(valueClone))

        val spanClone = tree.clone()
        spanClone.root.span!!.end += 1
        assertFalse(tree.contentEquals(spanClone))

        val reordered = tree.clone()
        val children = reordered.root.children
        val first = children[0]
        children[0] = children[1]
        children[1] = first
        assertFalse(tree.contentEquals(reordered))

        val fewer = tree.clone()
        fewer.root.children.removeAt(fewer.root.children.size - 1)
        assertFalse(tree.contentEquals(fewer))

        assertTrue(
            AbstractSyntaxTree.leaf("nan", Double.NaN)
                .contentEquals(AbstractSyntaxTree.leaf("nan", Double.NaN)),
        )
        assertFalse(
            AbstractSyntaxTree.leaf("zero", 0.0)
                .contentEquals(AbstractSyntaxTree.leaf("zero", -0.0)),
        )
    }

    @Test
    fun astToPlainReturnsADeepCopyWithPreservedSpans() {
        val tree = makeNumberTree()
        val plain = tree.toPlain()
        assertNotSame(tree.root, plain)
        assertNotSame(tree.root.children, plain.children)
        assertNotSame(tree.root.span, plain.span)
        assertNotSame(tree.root.children[0], plain.children[0])
        assertTrue(AbstractSyntaxTree(plain).contentEquals(tree))
        assertEquals(tree.root.span, plain.span)
        assertEquals("sample", plain.children[1].span?.source)

        plain.children.clear()
        assertEquals(3, tree.root.children.size)
    }

    @Test
    fun astTraversalPreservesEverySourceSpanUnderCloneAndPlain() {
        val tree = makeNumberTree()
        val originalSpans = tree.walk().map { it.span }.toList()
        val clonedSpans = tree.clone().walk().map { it.span }.toList()
        val plainSpans = AbstractSyntaxTree(tree.toPlain()).walk().map { it.span }.toList()
        assertEquals(originalSpans, clonedSpans)
        assertEquals(originalSpans, plainSpans)
    }

    @Test
    fun astHelpersRejectEmptyKindsWhileTheConstructorStaysPermissive() {
        val leafError = assertThrows(IllegalArgumentException::class.java) { AbstractSyntaxTree.leaf<Any?>("") }
        assertTrue(leafError.message!!.contains("kind must be a non-empty string"))
        assertThrows(IllegalArgumentException::class.java) { AbstractSyntaxTree.branch<Any?>("", emptyList()) }
        AbstractSyntaxTree(AstNode<Any?>(""))
        assertEquals(AstOrder.PREORDER, AstOrder.fromWire("preorder"))
        assertThrows(IllegalArgumentException::class.java) { AstOrder.fromWire("sideways") }
    }

    @Test
    fun astIteratorWalksPreorder() {
        val tree = makeNumberTree()
        assertEquals(listOf("root", "a", "b", "b1", "b2", "c"), nodeKinds(tree))
        assertEquals(sequenceKinds(tree.walk(AstOrder.PREORDER)), nodeKinds(tree))
    }
}

