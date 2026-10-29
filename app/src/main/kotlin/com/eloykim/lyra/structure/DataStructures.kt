package com.eloykim.lyra.structure

import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** A singly linked node used by [LinearTree]. */
data class LinearNode<T>(var value: T, var next: LinearNode<T>? = null)

/** A binary node. The tree does not impose ordering; [BinaryTree] does. */
data class BinaryNode<T>(var value: T, var left: BinaryNode<T>? = null, var right: BinaryNode<T>? = null)

data class TernaryNode<T>(
    var value: T,
    var left: TernaryNode<T>? = null,
    var middle: TernaryNode<T>? = null,
    var right: TernaryNode<T>? = null,
)

/** A B+ node. Internal nodes use [children]; leaf nodes use [values] and [next]. */
data class BPlusNode<K : Comparable<K>, V>(
    val keys: MutableList<K> = mutableListOf(),
    val values: MutableList<V> = mutableListOf(),
    val children: MutableList<BPlusNode<K, V>> = mutableListOf(),
    var leaf: Boolean = true,
    var next: BPlusNode<K, V>? = null,
)

data class SegmentNode<T>(
    val start: Int,
    val end: Int,
    var value: T,
    var left: SegmentNode<T>? = null,
    var right: SegmentNode<T>? = null,
)

data class TrieNode<V>(
    val children: MutableMap<Char, TrieNode<V>> = sortedMapOf(),
    var value: V? = null,
    var terminal: Boolean = false,
)

data class AstNode<T>(val value: T, val children: MutableList<AstNode<T>> = mutableListOf())

/** Canonical spelling. Kept alongside [HaffmanNode] for source compatibility. */
data class HuffmanNode<T>(
    val value: T?,
    val frequency: Int,
    val left: HuffmanNode<T>? = null,
    val right: HuffmanNode<T>? = null,
)

typealias HaffmanNode<T> = HuffmanNode<T>

class LinearTree<T> {
    var head: LinearNode<T>? = null
        private set
    var size: Int = 0
        private set

    fun add(value: T): LinearNode<T> {
        val node = LinearNode(value)
        if (head == null) head = node else {
            var cursor = head
            while (cursor?.next != null) cursor = cursor.next
            cursor?.next = node
        }
        size++
        return node
    }

    fun addFirst(value: T): LinearNode<T> {
        head = LinearNode(value, head)
        size++
        return head!!
    }

    fun removeFirst(): T? = head?.let { head = it.next; size--; it.value }
    fun toList(): List<T> = buildList { var n = head; while (n != null) { add(n.value); n = n.next } }
    fun clear() { head = null; size = 0 }
}

class BinaryTree<T : Comparable<T>> {
    var root: BinaryNode<T>? = null
        private set
    var size: Int = 0
        private set

    fun insert(value: T): Boolean {
        if (root == null) { root = BinaryNode(value); size++; return true }
        var node = root!!
        while (true) when {
            value == node.value -> return false
            value < node.value -> if (node.left == null) { node.left = BinaryNode(value); size++; return true } else node = node.left!!
            else -> if (node.right == null) { node.right = BinaryNode(value); size++; return true } else node = node.right!!
        }
    }

    fun contains(value: T): Boolean { var n = root; while (n != null) { n = when { value == n.value -> return true; value < n.value -> n.left; else -> n.right } }; return false }
    fun inOrder(): List<T> = buildList { fun visit(n: BinaryNode<T>?) { if (n == null) return; visit(n.left); add(n.value); visit(n.right) }; visit(root) }
    fun remove(value: T): Boolean {
        var removed = false
        fun delete(n: BinaryNode<T>?): BinaryNode<T>? {
            n ?: return null
            when { value < n.value -> n.left = delete(n.left); value > n.value -> n.right = delete(n.right); else -> {
                removed = true
                if (n.left == null) return n.right
                if (n.right == null) return n.left
                var successor = n.right!!; while (successor.left != null) successor = successor.left!!
                n.value = successor.value; n.right = delete(n.right)
            } }
            return n
        }
        root = delete(root); if (removed) size--; return removed
    }
}

class TernaryTree<T : Comparable<T>> {
    private val values = java.util.TreeSet<T>()
    var root: TernaryNode<T>? = null
        private set
    val size: Int get() = values.size
    fun insert(value: T): Boolean { if (!values.add(value)) return false; rebuild(); return true }
    fun contains(value: T) = values.contains(value)
    fun remove(value: T): Boolean { if (!values.remove(value)) return false; rebuild(); return true }
    fun inOrder(): List<T> = values.toList()
    private fun rebuild() { root = values.toList().let { build(it, 0, it.lastIndex) } }
    private fun build(a: List<T>, lo: Int, hi: Int): TernaryNode<T>? {
        if (lo > hi) return null
        val third = (hi - lo) / 3
        val i = lo + third
        val j = minOf(hi, i + third + 1)
        val n = TernaryNode(a[i])
        n.left = build(a, lo, i - 1); n.middle = if (j <= hi) build(a, i + 1, j - 1) else null; n.right = build(a, j + 1, hi)
        return n
    }
}

class BPlusTree<K : Comparable<K>, V>(private val order: Int = 32) {
    init { require(order >= 3) }
    private val map = java.util.TreeMap<K, V>()
    val size: Int get() = map.size
    var root: BPlusNode<K, V> = BPlusNode()
        private set
    fun put(key: K, value: V): V? { val old = map.put(key, value); rebuild(); return old }
    operator fun set(key: K, value: V) { put(key, value) }
    operator fun get(key: K): V? = map[key]
    fun remove(key: K): V? { val present = map.containsKey(key); val old = map.remove(key); if (present) rebuild(); return old }
    fun range(from: K, to: K): List<Pair<K, V>> = map.subMap(from, true, to, true).entries.map { it.key to it.value }
    fun entries(): List<Pair<K, V>> = map.entries.map { it.key to it.value }
    private fun rebuild() {
        val entries = map.entries.toList()
        if (entries.size <= order - 1) { root = BPlusNode(leaf = true); entries.forEach { root.keys.add(it.key); root.values.add(it.value) }; return }
        val leaves = entries.chunked(order - 1).map { chunk -> BPlusNode<K, V>().also { n -> chunk.forEach { n.keys.add(it.key); n.values.add(it.value) } } }
        leaves.zipWithNext().forEach { (a, b) -> a.next = b }
        root = BPlusNode<K, V>(leaf = false).also { r -> r.children.addAll(leaves); leaves.drop(1).forEach { r.keys.add(it.keys.first()) } }
    }
}

class SegmentTree(private val values: IntArray, private val combine: (Int, Int) -> Int = Int::plus) {
    private val tree = IntArray(maxOf(1, values.size * 4))
    init { if (values.isNotEmpty()) build(1, 0, values.lastIndex) }
    private fun build(p: Int, l: Int, r: Int) { if (l == r) tree[p] = values[l] else { val m = (l + r) ushr 1; build(p * 2, l, m); build(p * 2 + 1, m + 1, r); tree[p] = combine(tree[p * 2], tree[p * 2 + 1]) } }
    fun query(from: Int, to: Int): Int { require(values.isNotEmpty() && from in values.indices && to in values.indices && from <= to); return query(1, 0, values.lastIndex, from, to) }
    private fun query(p: Int, l: Int, r: Int, ql: Int, qr: Int): Int { if (ql <= l && r <= qr) return tree[p]; val m = (l + r) ushr 1; return when { qr <= m -> query(p * 2, l, m, ql, qr); ql > m -> query(p * 2 + 1, m + 1, r, ql, qr); else -> combine(query(p * 2, l, m, ql, qr), query(p * 2 + 1, m + 1, r, ql, qr)) } }
    fun update(index: Int, value: Int) { require(index in values.indices); values[index] = value; update(1, 0, values.lastIndex, index) }
    private fun update(p: Int, l: Int, r: Int, i: Int) { if (l == r) tree[p] = values[i] else { val m = (l + r) ushr 1; if (i <= m) update(p * 2, l, m, i) else update(p * 2 + 1, m + 1, r, i); tree[p] = combine(tree[p * 2], tree[p * 2 + 1]) } }
}

class TrieTree<V> {
    val root = TrieNode<V>()
    var size: Int = 0
        private set
    fun put(key: String, value: V) { var n = root; for (c in key) n = n.children.getOrPut(c) { TrieNode() }; if (!n.terminal) { size++; n.terminal = true }; n.value = value }
    fun get(key: String): V? { var n = root; for (c in key) n = n.children[c] ?: return null; return if (n.terminal) n.value else null }
    fun contains(key: String): Boolean { var n = root; for (c in key) n = n.children[c] ?: return false; return n.terminal }
    fun remove(key: String): V? { val path = ArrayList<Pair<TrieNode<V>, Char>>(); var n = root; for (c in key) { path += n to c; n = n.children[c] ?: return null }; if (!n.terminal) return null; val old = n.value; n.value = null; n.terminal = false; size--; path.asReversed().forEach { (p, c) -> val child = p.children[c] ?: return@forEach; if (!child.terminal && child.children.isEmpty()) p.children.remove(c) }; return old }
    fun keysWithPrefix(prefix: String): List<String> { var n = root; for (c in prefix) n = n.children[c] ?: return emptyList(); val out = mutableListOf<String>(); fun walk(x: TrieNode<V>, suffix: String) { if (x.terminal) out += prefix + suffix; x.children.forEach { (c, child) -> walk(child, suffix + c) } }; walk(n, ""); return out }
}

class AstTree<T>(val root: AstNode<T>) {
    fun preOrder(): List<T> = buildList { fun walk(n: AstNode<T>) { add(n.value); n.children.forEach(::walk) }; walk(root) }
    fun find(predicate: (T) -> Boolean): AstNode<T>? { fun walk(n: AstNode<T>): AstNode<T>? { if (predicate(n.value)) return n; n.children.forEach { walk(it)?.let { return it } }; return null }; return walk(root) }
}

class HuffmanTree<T>(val root: HuffmanNode<T>) {
    fun codes(): Map<T, String> { val out = linkedMapOf<T, String>(); fun walk(n: HuffmanNode<T>, code: String) { n.value?.let { out[it] = code.ifEmpty { "0" } }; n.left?.let { walk(it, code + "0") }; n.right?.let { walk(it, code + "1") } }; walk(root, ""); return out }
}
typealias HaffmanTree<T> = HuffmanTree<T>

/** Thread-safe bounded least-recently-used map and collection variants. */
open class LRUMap<K, V>(val capacity: Int) {
    init { require(capacity > 0) }
    private val lock = ReentrantReadWriteLock()
    private val map = object : LinkedHashMap<K, V>(capacity, .75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<K, V>) = size > capacity }
    fun get(key: K): V? = lock.write { map[key] }
    fun put(key: K, value: V): V? = lock.write { map.put(key, value) }
    fun remove(key: K): V? = lock.write { map.remove(key) }
    fun containsKey(key: K): Boolean = lock.read { map.containsKey(key) }
    fun size(): Int = lock.read { map.size }
    fun clear() = lock.write { map.clear() }
    fun snapshot(): List<Pair<K, V>> = lock.read { map.entries.map { it.key to it.value } }
}
open class LRUList<T>(capacity: Int) {
    private val map = LRUMap<Int, T>(capacity); private var next = 0
    fun add(value: T): Int { val id = next++; map.put(id, value); return id }
    fun get(id: Int): T? = map.get(id); fun remove(id: Int): T? = map.remove(id); fun size() = map.size(); fun values() = map.snapshot().map { it.second }
}
open class LRUSet<T>(capacity: Int) { private val map = LRUMap<T, Unit>(capacity); fun add(v: T) = map.put(v, Unit) == null; fun contains(v: T) = map.containsKey(v); fun remove(v: T) = map.remove(v) != null; fun size() = map.size(); fun values() = map.snapshot().map { it.first } }
class LRUSkipMap<K, V>(capacity: Int) : LRUMap<K, V>(capacity)
class LRUSkipList<T>(capacity: Int) : LRUList<T>(capacity)
class LRUSkipSet<T>(capacity: Int) : LRUSet<T>(capacity)

class BloomFilter<T>(expectedInsertions: Int, falsePositiveProbability: Double = .01) {
    init { require(expectedInsertions > 0); require(falsePositiveProbability in 0.0..1.0 && falsePositiveProbability > 0.0) }
    private val bitCount = maxOf(64, (-expectedInsertions * kotlin.math.ln(falsePositiveProbability) / (kotlin.math.ln(2.0) * kotlin.math.ln(2.0))).toInt())
    private val hashes = maxOf(2, (-kotlin.math.ln(falsePositiveProbability) / kotlin.math.ln(2.0)).toInt())
    private val bits = java.util.BitSet(bitCount)
    private fun indexes(value: T) = (0 until hashes).map { i -> Math.floorMod((value?.hashCode() ?: 0) * 31 + i * -0x61c88647, bitCount) }
    fun add(value: T) { indexes(value).forEach(bits::set) }
    fun mightContain(value: T): Boolean = indexes(value).all(bits::get)
    fun contains(value: T) = mightContain(value)
    fun clear() = bits.clear()
}

class CuckooFilter<T>(private val capacity: Int, private val bucketSize: Int = 4) {
    init { require(capacity > 0 && bucketSize > 0) }
    private val bucketCount = maxOf(2, (capacity + bucketSize - 1) / bucketSize).let { n -> Integer.highestOneBit(n - 1) shl 1 }
    private val buckets = Array(bucketCount) { ArrayDeque<Int>(bucketSize) }
    private var count = 0
    private fun fingerprint(value: T): Int = ((value?.hashCode() ?: 0) * -0x61c88647).ushr(1).coerceAtLeast(1)
    private fun index(value: T) = Math.floorMod(value?.hashCode() ?: 0, bucketCount)
    private fun alternate(index: Int, fp: Int) = Math.floorMod(index xor (fp * -0x61c88647), bucketCount)
    fun add(value: T): Boolean {
        if (count >= capacity) return contains(value)
        val fp = fingerprint(value); val first = index(value); val second = alternate(first, fp)
        if (buckets[first].contains(fp) || buckets[second].contains(fp)) return true
        if (buckets[first].size < bucketSize) { buckets[first].addLast(fp); count++; return true }
        if (buckets[second].size < bucketSize) { buckets[second].addLast(fp); count++; return true }
        var bucket = if ((fp and 1) == 0) first else second
        var current = fp
        repeat(500) {
            val b = buckets[bucket]
            val evicted = b.removeFirstOrNull() ?: return@repeat
            b.addLast(current); current = evicted; bucket = alternate(bucket, current)
            if (buckets[bucket].size < bucketSize) { buckets[bucket].addLast(current); count++; return true }
        }
        return false
    }
    fun contains(value: T): Boolean { val fp = fingerprint(value); val first = index(value); return buckets[first].contains(fp) || buckets[alternate(first, fp)].contains(fp) }
    fun mightContain(value: T) = contains(value)
    fun remove(value: T): Boolean { val fp = fingerprint(value); val first = index(value); val second = alternate(first, fp); val removed = buckets[first].remove(fp) || buckets[second].remove(fp); if (removed) count--; return removed }
    fun size() = count
    fun isFull() = count >= capacity
    fun clear() { buckets.forEach { it.clear() }; count = 0 }
}
