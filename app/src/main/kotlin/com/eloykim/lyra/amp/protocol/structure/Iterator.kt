package com.eloykim.lyra.amp.protocol.structure

/**
 * 集合结构共用的小型迭代器辅助，移植自 `src/structure/iterator.ts`。
 *
 * TypeScript 版本把快照数组包装成 `Generator`。Kotlin 的对应物是
 * [Sequence]：调用方在构造序列之前先求值快照（例如 `toArray()`），
 * 因此得到的是与后续修改隔离的快照遍历，可直接 `toList()`、`for` 循环
 * 或 `iterator()`。
 */

/**
 * 把快照数组作为 [Sequence] 返回。
 *
 * 数组由调用点在创建序列之前求值，所以传入 `toArray()` 就是快照语义，
 * 不会因之后的结构性修改而改变。
 */
fun <T> generatorFrom(values: List<T>): Sequence<T> = values.asSequence()
