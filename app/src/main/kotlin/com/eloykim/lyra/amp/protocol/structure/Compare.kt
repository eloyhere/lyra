package com.eloykim.lyra.amp.protocol.structure

/**
 * 有序结构共用的排序原语，移植自 `src/structure/compare.ts`。
 *
 * 比较器返回负数表示 `a` 排在 `b` 之前，返回 0 表示两者在该结构中等价，
 * 返回正数表示 `a` 排在 `b` 之后。结构本身只要求比较器是全预序；
 * 等价的值由容器自行决定如何取舍（例如搜索树只保留一个代表）。
 */

/**
 * 与 TypeScript 的 `Comparator<T>` 对应。Kotlin 侧直接复用
 * `kotlin.Comparator`（即 `java.util.Comparator`），因此可以用
 * `Comparator<Int> { a, b -> a - b }` 这样的 SAM 转换构造。
 */

/**
 * 数值三路比较；NaN 不参与排序，直接拒绝。
 *
 * TypeScript 版本抛出 `TypeError`，Kotlin 对应 [IllegalArgumentException]。
 */
fun compareNumbers(a: Double, b: Double): Int {
    if (a.isNaN() || b.isNaN()) {
        throw IllegalArgumentException("cannot order NaN")
    }
    return if (a < b) -1 else if (a > b) 1 else 0
}

/**
 * UTF-16 字典序比较，与 JavaScript 的 `<` 一致。
 *
 * Kotlin 的 `String.compareTo` 同样按 UTF-16 码元比较，语义完全一致。
 */
fun compareStrings(a: String, b: String): Int = a.compareTo(b)

/**
 * BigInt 比较，适用于 number 范围之外的值。
 */
fun compareBigInts(a: java.math.BigInteger, b: java.math.BigInteger): Int = a.compareTo(b)

/**
 * Date 的时间顺序比较。
 *
 * JavaScript 的 `Date` 可能是 Invalid Date，TypeScript 版本会拒绝它；
 * Java/Kotlin 的 [java.util.Date] 没有“无效”状态，因此这里不做额外校验。
 */
fun compareDates(a: java.util.Date, b: java.util.Date): Int = compareNumbers(a.time.toDouble(), b.time.toDouble())

/**
 * 本库支持的原始类型的自然顺序。
 *
 * 与 TypeScript 版本一样，不支持的值不会被强制转成字符串，而是抛出
 * [IllegalArgumentException]（对应 `TypeError`）。数值相同则返回 0；
 * `-0.0` 与 `0.0` 等价；`NaN` 被拒绝。
 */
fun compareNatural(a: Any?, b: Any?): Int {
    if (a is Double && b is Double) {
        return compareNumbers(a, b)
    }
    if (a is Float && b is Float) {
        return compareNumbers(a.toDouble(), b.toDouble())
    }
    if (a is Double && b is Float) {
        return compareNumbers(a, b.toDouble())
    }
    if (a is Float && b is Double) {
        return compareNumbers(a.toDouble(), b)
    }
    if (a is String && b is String) {
        return compareStrings(a, b)
    }
    if (a is java.math.BigInteger && b is java.math.BigInteger) {
        return compareBigInts(a, b)
    }
    if (a is Boolean && b is Boolean) {
        return if (a == b) 0 else if (a) 1 else -1
    }
    if (a is java.util.Date && b is java.util.Date) {
        return compareDates(a, b)
    }
    if (a is Int && b is Int) {
        return a.compareTo(b)
    }
    if (a is Long && b is Long) {
        return a.compareTo(b)
    }
    if (a is Short && b is Short) {
        return a.compareTo(b)
    }
    if (a is Byte && b is Byte) {
        return a.compareTo(b)
    }
    if (a is Number && b is Number && !isFloating(a) && !isFloating(b)) {
        // 混用 Int/Long/Short/Byte 时按数值比较；数组下标类场景足够安全。
        return a.toLong().compareTo(b.toLong())
    }
    if (a === b) {
        return 0
    }
    val type = a?.let { it::class.java.simpleName } ?: "null"
    throw IllegalArgumentException("no natural ordering for $type; supply a Comparator<T>")
}

private fun isFloating(value: Number): Boolean = value is Double || value is Float

/**
 * 反转比较器；用于在 min-heap 的机制上构造 max-heap。
 */
fun <T> reverseComparator(compare: Comparator<T>): Comparator<T> = Comparator { a, b ->
    val result = compare.compare(a, b)
    if (result == 0) 0 else -result
}

/**
 * 由“取值函数 + 键比较器”派生出 `T` 的比较器。
 *
 * 选择器在每次比较时各求值一次；需要缓存的调用方应在热路径之外自行构建。
 */
fun <T, K> byKey(select: (T) -> K, compare: Comparator<K>? = null): Comparator<T> {
    val keyCompare = compare ?: naturalComparator<K>()
    return Comparator { a, b -> keyCompare.compare(select(a), select(b)) }
}

/**
 * 把比较器结果收敛到文档约定的 -1/0/1 形状。
 */
fun sign(value: Int): Int = if (value < 0) -1 else if (value > 0) 1 else 0

/**
 * 结构在未显式提供比较器时使用的默认比较器（自然顺序）。
 */
@Suppress("UNCHECKED_CAST")
fun <T> naturalComparator(): Comparator<T> = Comparator { a, b -> compareNatural(a, b) }

val defaultCompare: Comparator<Any?> = Comparator { a, b -> compareNatural(a, b) }
