package com.eloykim.lyra.amp.protocol.structure

import java.math.BigInteger
import java.util.ConcurrentModificationException

/**
 * 结构容器共用的 64 位哈希底座，移植自 `src/structure/hash.ts`。
 *
 * 两类哈希：
 *
 * - [hashBytes] / [hashString] 是确定性的内容哈希：相同字节（与种子）在任何进程、
 *   任何平台都产生相同结果。
 * - [hashKey] 把任意键映射到 64 位。原始类型按值哈希（每种类型有独立的域标签），
 *   引用类型按 Kotlin 的 `hashCode` 哈希。JavaScript 版本对对象使用
 *   `WeakMap` 身份哈希；Kotlin 对象本身具有值语义（`equals`/`hashCode`），
 *   因此这里改用 `hashCode`，这样 [sameKey] 与 [hashKey] 的契约在常规类上成立。
 *   需要 JS 式进程内身份哈希时使用 [hashIdentity] 并显式传入 [KeyStrategy]。
 *
 * 所有公开结果都是无符号 64 位 [ULong]。最后一步是 SplitMix64 avalanche，
 * 其低位分布良好；容器正是用低位构造桶下标。
 */

/** 与 TypeScript 的 `Hash = bigint` 对应的无符号 64 位哈希。 */
typealias Hash = ULong

/** 每个 [Hash] 的位宽。 */
const val HashBits: Int = 64

/** 选取低 64 位的掩码。 */
val HashMask: Hash = ULong.MAX_VALUE

private val FnvOffsetBasis: Hash = 0xcbf29ce484222325uL
private val FnvPrime: Hash = 0x100000001b3uL
private val GoldenGamma: Hash = 0x9e3779b97f4a7c15uL
private val MixMultiplier1: Hash = 0xbf58476d1ce4e5b9uL
private val MixMultiplier2: Hash = 0x94d049bb133111ebuL

/** 规范化的 quiet NaN，使所有 NaN 键哈希一致。 */
private val CanonicalNanBits: Hash = 0x7ff8000000000000uL

/** SplitMix64 finalizer：双射的 64 位 avalanche，低位分布极佳。 */
private fun finalize64(value: Hash): Hash {
    var mixed = value and HashMask
    mixed = ((mixed xor (mixed shr 30)) * MixMultiplier1) and HashMask
    mixed = ((mixed xor (mixed shr 27)) * MixMultiplier2) and HashMask
    return (mixed xor (mixed shr 31)) and HashMask
}

/**
 * `finalize64(0x01)` ... `finalize64(0x09)`：随机化的域标签把不同类型家族的
 * 哈希彼此分开。
 */
private val TagNull: Hash = finalize64(0x02uL)
private val TagBoolean: Hash = finalize64(0x03uL)
private val TagNumber: Hash = finalize64(0x04uL)
private val TagBigint: Hash = finalize64(0x05uL)
private val TagString: Hash = finalize64(0x06uL)
private val TagReference: Hash = finalize64(0x09uL)

/**
 * 对原始字节做 FNV-1a 64 位，随后接 SplitMix64 avalanche。
 *
 * 可选种子先折进 FNV offset basis（并掩到 64 位），从而给调用方独立的哈希族。
 * 这是确定性的内容哈希：相同字节序列与种子总是得到相同结果，且不依赖进程状态。
 */
fun hashBytes(bytes: ByteArray, seed: Hash = 0uL): Hash {
    var hash = (FnvOffsetBasis xor (seed and HashMask)) and HashMask
    for (byte in bytes) {
        hash = ((hash xor (byte.toInt() and 0xFF).toULong()) * FnvPrime) and HashMask
    }
    return finalize64(hash)
}

/**
 * 字符串的确定性 UTF-8 哈希，等价于把字符串编码成 UTF-8 后调用 [hashBytes]。
 *
 * 与 JavaScript `TextEncoder` 的差别仅在孤立代理项：Kotlin 的 UTF-8 编码会把它
 * 替换成 `?`，而 `TextEncoder` 使用 U+FFFD。合法 Unicode 文本两边结果一致。
 */
fun hashString(value: String, seed: Hash = 0uL): Hash = hashBytes(value.toByteArray(Charsets.UTF_8), seed)

/**
 * 数值的 IEEE-754 位模式，遵循 SameValueZero：`-0.0` 归一成 `+0.0`，
 * 所有 `NaN` 归一成同一个值。
 */
private fun numberBits(value: Double): Hash {
    if (value.isNaN()) {
        return CanonicalNanBits
    }
    val bits = java.lang.Double.doubleToRawLongBits(if (value == 0.0) 0.0 else value)
    return bits.toULong()
}

/**
 * 按进程内稳定身份哈希对象。
 *
 * 与 JavaScript 的 `WeakMap` 身份 id 不同，这里使用 JVM 的
 * `System.identityHashCode`：同一对象在其生命周期内哈希稳定，值不可持久化、
 * 也不能当作内容地址。默认的 [hashKey] 不使用它（见文件头说明）。
 */
fun hashIdentity(value: Any): Hash =
    mixHash(TagReference, finalize64((System.identityHashCode(value).toLong() and 0xFFFF_FFFFL).toULong()))

/**
 * 为哈希容器哈希任意键。
 *
 * 每个类型类别有独立的域标签，因此 `1`、`1L`、`"1"`、`true` 不会共享哈希
 * （`1` 与 `1L` 在 Kotlin 中是不同的键类型，允许哈希相同但相等性为 false）。
 * 数值遵循 SameValueZero：`-0.0` 与 `+0.0` 同哈希，所有 `NaN` 同哈希。
 * 引用类型按 `hashCode` 哈希，与 [sameKey] 的等价判定保持一致。
 */
fun hashKey(value: Any?): Hash {
    if (value == null) {
        return mixHash(TagNull, 0uL)
    }
    return when (value) {
        is Boolean -> mixHash(TagBoolean, if (value) 1uL else 0uL)
        is Double -> mixHash(TagNumber, numberBits(value))
        is Float -> mixHash(TagNumber, numberBits(value.toDouble()))
        is BigInteger -> mixHash(TagBigint, hashString(value.toString(10)))
        is Long -> mixHash(TagNumber, hashString(value.toString(10)))
        is Int -> mixHash(TagNumber, hashString(value.toString(10)))
        is Short -> mixHash(TagNumber, hashString(value.toString(10)))
        is Byte -> mixHash(TagNumber, hashString(value.toString(10)))
        is String -> mixHash(TagString, hashString(value))
        else -> mixHash(TagReference, (value.hashCode().toLong() and 0xFFFF_FFFFL).toULong())
    }
}

/**
 * SameValueZero 键相等：`-0.0` 等于 `+0.0`，`NaN` 等于 `NaN`。
 * 这是本模块所有容器的默认相等判定。
 */
fun sameKey(a: Any?, b: Any?): Boolean {
    if (a == null || b == null) {
        return a == null && b == null
    }
    if (a is Double && b is Double) {
        return a == b || (a.isNaN() && b.isNaN())
    }
    if (a is Float && b is Float) {
        return a == b || (a.isNaN() && b.isNaN())
    }
    return a == b
}

/**
 * 把两个 64 位值组合成一个哈希。
 *
 * 两个输入先掩到 64 位。固定第一个参数时，组合对第二个参数是单射（反之亦然），
 * 因此把域标签与载荷哈希混合本身不会引入额外碰撞。结果再做 avalanche 收敛。
 */
fun mixHash(a: Hash, b: Hash): Hash = finalize64(((a and HashMask) + GoldenGamma) xor (b and HashMask))

/**
 * 把哈希格式化成小写十六进制。
 *
 * 输出至少 `width` 个字符，左侧补零。`width` 是最小宽度而不是截断：
 * 64 位值最多需要 16 位数字，较窄的宽度也不会造成无法回读的表示。
 */
fun hashToHex(value: Hash, width: Int = 16): String {
    require(width >= 0) { "width must be a non-negative integer, received $width" }
    return (value and HashMask).toString(16).padStart(width, '0')
}

/**
 * 从十六进制解析 64 位哈希。
 *
 * 接受大小写数字与可选的 `0x`/`0X` 前缀；允许前导零；超过 `2^64 - 1`
 * 的值会被拒绝而不是静默截断。
 */
fun hashFromHex(hex: String): Hash {
    val match = HEX_PATTERN.matchEntire(hex)
        ?: throw IllegalArgumentException("invalid 64-bit hex hash: \"$hex\"")
    val digits = match.groupValues[1].trimStart('0')
    if (digits.length > 16) {
        throw IllegalArgumentException("hash exceeds 64 bits: \"$hex\"")
    }
    var result = 0uL
    for (digit in digits) {
        val value = Character.digit(digit, 16)
        result = (result shl 4) or value.toULong()
    }
    return result
}

private val HEX_PATTERN = Regex("^(?:0[xX])?([0-9a-fA-F]+)$")

/**
 * 哈希容器的可选哈希/相等策略，对应 TypeScript 的 `KeyStrategy<K>`。
 *
 * 两个成员都可省略，省略时分别回退到 [hashKey] 与 [sameKey]。自定义 `equals`
 * 必须与自定义 `hash` 相容：只要 `equals(a, b)` 为真，`hash(a)` 与 `hash(b)`
 * 就必须相等，否则查找可能漏掉相等的键。自定义 `hash` 会被容器掩到 64 位。
 */
class KeyStrategy<K>(
    val hash: ((K) -> Hash)? = null,
    val equals: ((K, K) -> Boolean)? = null,
)

/**
 * 迭代期间检测到结构性修改时抛出的统一异常。
 *
 * JavaScript 版本使用普通 `Error`；Kotlin 侧使用 JDK 的
 * [ConcurrentModificationException]，与 `java.util` 集合的约定一致
 * （Android 29 可用）。
 */
internal fun concurrentModification(label: String): ConcurrentModificationException =
    ConcurrentModificationException("$label: collection was mutated during iteration")
