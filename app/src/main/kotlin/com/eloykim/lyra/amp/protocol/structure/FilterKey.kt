package com.eloykim.lyra.amp.protocol.structure

/**
 * 概率过滤器共用的键编码，移植自 `src/structure/filterKey.ts`。
 *
 * 过滤器只处理字节。字符串按 UTF-8 编码；[ByteArray] 原样使用（不复制），
 * 因此调用方不要在插入或查询期间修改键缓冲区。
 */

/**
 * [BloomFilter] 与 [CuckooFilter] 接受的键，对应 TypeScript 的
 * `FilterKey = string | Uint8Array`。
 */
sealed interface FilterKey {

    data class Text(val value: String) : FilterKey

    class Bytes(val value: ByteArray) : FilterKey {

        override fun equals(other: Any?): Boolean =
            other is Bytes && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()
    }

    companion object {
        /**
         * 把任意值包装成合法的过滤器键；其他类型立即失败。
         */
        fun of(value: Any?): FilterKey = when (value) {
            is String -> Text(value)
            is ByteArray -> Bytes(value)
            else -> throw IllegalArgumentException("filter keys must be strings or ByteArray values")
        }
    }
}

/**
 * 字符串对应 UTF-8 字节，[ByteArray] 返回原始字节视图（不复制）。
 */
fun filterKeyBytes(key: FilterKey): ByteArray = when (key) {
    is FilterKey.Text -> key.value.toByteArray(Charsets.UTF_8)
    is FilterKey.Bytes -> key.value
}

/**
 * 提前拒绝既不是字符串也不是字节的键，报错信息与 TypeScript 版本一致。
 */
fun assertFilterKey(key: Any?) {
    if (key !is String && key !is ByteArray) {
        throw IllegalArgumentException("filter keys must be strings or ByteArray values")
    }
}
