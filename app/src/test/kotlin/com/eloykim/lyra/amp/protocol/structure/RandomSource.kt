package com.eloykim.lyra.amp.protocol.structure

/**
 * 测试用确定性随机源，与参考检查中的 JavaScript 实现逐位一致。
 *
 * 参考实现使用 32 位无符号算术；Kotlin 侧用掩到 32 位的 [Long] 复现，
 * 因此同一颗种子在两边产生相同序列（见 `RandomSourceTest`）。
 */

/** 与 JavaScript `Math.imul` 相同的 32 位有符号乘法（按位模式等价）。 */
internal fun imul32(a: Long, b: Long): Long = ((a and 0xFFFF_FFFFL) * (b and 0xFFFF_FFFFL)) and 0xFFFF_FFFFL

/** mulberry32：linear / priority 检查使用的生成器。 */
internal class Mulberry32(seed: Int) {

    private var state: Long = seed.toLong() and 0xFFFF_FFFFL

    fun next(): Double {
        state = (state + 0x6d2b79f5L) and 0xFFFF_FFFFL
        var value = state
        value = imul32(value xor (value ushr 15), value or 1L)
        value = value xor ((value + imul32(value xor (value ushr 7), value or 61L)) and 0xFFFF_FFFFL)
        return ((value xor (value ushr 14)) and 0xFFFF_FFFFL).toDouble() / 4294967296.0
    }
}

/** 32 位线性同余生成器：tree / filter / segment 检查使用的生成器。 */
internal class Lcg(seed: Int) {

    private var state: Long = seed.toLong() and 0xFFFF_FFFFL

    fun next(): Double {
        state = (imul32(state, 1664525L) + 1013904223L) and 0xFFFF_FFFFL
        return state.toDouble() / 4294967296.0
    }
}

/** `floor(random() * bound)`，与参考检查的 `randomInt` 对应。 */
internal fun randomInt(random: () -> Double, bound: Int): Int = Math.floor(random() * bound).toInt()

/** 在升序数组中用二分查找插入，保留重复值。 */
internal fun insertSorted(values: MutableList<Int>, value: Int) {
    var low = 0
    var high = values.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (values[middle] < value) {
            low = middle + 1
        } else {
            high = middle
        }
    }
    values.add(low, value)
}

/** 第一个大于等于 `value` 的下标。 */
internal fun lowerBound(values: List<Int>, value: Int): Int {
    var low = 0
    var high = values.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (values[middle] < value) {
            low = middle + 1
        } else {
            high = middle
        }
    }
    return low
}

/** 在升序数组中插入（不保留重复值）。 */
internal fun insertSortedUnique(values: MutableList<Int>, value: Int) {
    val index = lowerBound(values, value)
    if (index == values.size || values[index] != value) {
        values.add(index, value)
    }
}

/** 从升序数组中删除一个值（若存在）。 */
internal fun removeSortedUnique(values: MutableList<Int>, value: Int) {
    val index = lowerBound(values, value)
    if (index < values.size && values[index] == value) {
        values.removeAt(index)
    }
}

internal fun expectedFloor(values: List<Int>, value: Int): Int? {
    val index = lowerBound(values, value)
    if (index < values.size && values[index] == value) {
        return value
    }
    return if (index == 0) null else values[index - 1]
}

internal fun expectedCeil(values: List<Int>, value: Int): Int? {
    val index = lowerBound(values, value)
    return if (index == values.size) null else values[index]
}
