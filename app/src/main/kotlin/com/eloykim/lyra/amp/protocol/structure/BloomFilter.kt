package com.eloykim.lyra.amp.protocol.structure

/**
 * 经典 Bloom 过滤器，移植自 `src/structure/bloomFilter.ts`。
 *
 * 过滤器保存一个位数组，每个键使用 `k` 个双哈希位位置。它没有假阴性：只要
 * `add(key)` 完成，`has(key)` 就保持为真。假阳性可能存在，并由配置的比率约束。
 * 标准 Bloom 过滤器无法删除单个键；需要删除时请重建过滤器或改用 [CuckooFilter]。
 *
 * 默认哈希个数与位数量由 `expectedItems` 与 `falsePositiveRate` 按常用公式
 * `m = -n ln p / (ln 2)^2` 和 `k = (m/n) ln 2` 推导。不支持的尺寸在任何分配之前
 * 就会失败。
 */

private const val DefaultFalsePositiveRate: Double = 0.01
private const val DefaultMaxBits: Long = 1L shl 31
private val SecondHashSeed: Hash = 0x9e3779b97f4a7c15uL
private const val LN2: Double = 0.6931471805599453

/**
 * 面向字符串或 [ByteArray] 键的 Bloom 过滤器。
 *
 * `size` 统计 [add] 的调用次数而不是不同键数量：Bloom 过滤器无法知道键是否已存在。
 * [setBits] 与 [fillRatio] 暴露实际位占用，这是判断饱和的有用信号。
 *
 * @param expectedItems 预期不同键数量，必须是正整数。
 * @param falsePositiveRate 目标假阳性率，位于 `(0, 1)`；默认 0.01。
 * @param seed 两个哈希族的域种子；默认 0。
 * @param maxBits 位数组大小的硬上限；默认 2^31 位（256 MiB）。
 */
class BloomFilter(
    /** 请求的预期键数量。 */
    val expectedItems: Int,
    /** 请求的目标假阳性率。 */
    val falsePositiveRate: Double = DefaultFalsePositiveRate,
    seed: Hash = 0uL,
    maxBits: Long = DefaultMaxBits,
) {

    /** 过滤器中的位数。 */
    val bitSize: Long

    /** 每个键的双哈希探测次数。 */
    val hashCount: Int

    /** 位数组分配的字节数。 */
    val byteSize: Int

    private val bits: ByteArray
    private val seed: Hash = seed
    private var insertions = 0
    private var bitsSet = 0

    init {
        require(expectedItems > 0) { "expectedItems must be a positive safe integer" }
        require(falsePositiveRate.isFinite() && falsePositiveRate > 0 && falsePositiveRate < 1) {
            "falsePositiveRate must be a finite number in (0, 1)"
        }
        require(maxBits > 0) { "maxBits must be a positive safe integer" }

        val idealBits = Math.ceil((-expectedItems * Math.log(falsePositiveRate)) / (LN2 * LN2))
        require(idealBits <= maxBits.toDouble()) {
            "filter would need ${idealBits.toLong()} bits, exceeding maxBits $maxBits"
        }

        bitSize = maxOf(1L, idealBits.toLong())
        hashCount = maxOf(1, minOf(32, Math.round((bitSize.toDouble() / expectedItems) * LN2).toInt()))
        byteSize = ((bitSize + 7) / 8).toInt()
        bits = ByteArray(byteSize)
    }

    /**
     * 插入一个键。
     *
     * 重复插入无害但会增加 [size]；位占用只对之前未设置的位发生变化。
     */
    fun add(key: FilterKey): BloomFilter {
        val bytes = filterKeyBytes(key)
        val primary = (hashBytes(bytes, seed) % bitSize.toULong()).toLong()
        val step = ((hashBytes(bytes, seed xor SecondHashSeed) or 1uL) % bitSize.toULong()).toLong()
        var position = primary
        for (probe in 0 until hashCount) {
            setBit(position.toInt())
            position = (position + step) % bitSize
        }
        insertions += 1
        return this
    }

    fun add(key: String): BloomFilter = add(FilterKey.Text(key))

    fun add(key: ByteArray): BloomFilter = add(FilterKey.Bytes(key))

    /**
     * 测试一个键。
     *
     * `false` 表示该键肯定未被插入（或过滤器已被清空）。`true` 可能是假阳性。
     */
    fun has(key: FilterKey): Boolean {
        val bytes = filterKeyBytes(key)
        val primary = (hashBytes(bytes, seed) % bitSize.toULong()).toLong()
        val step = ((hashBytes(bytes, seed xor SecondHashSeed) or 1uL) % bitSize.toULong()).toLong()
        var position = primary
        for (probe in 0 until hashCount) {
            if (!getBit(position.toInt())) {
                return false
            }
            position = (position + step) % bitSize
        }
        return true
    }

    fun has(key: String): Boolean = has(FilterKey.Text(key))

    fun has(key: ByteArray): Boolean = has(FilterKey.Bytes(key))

    /** 移除所有键并重置全部计数器。 */
    fun clear() {
        bits.fill(0)
        insertions = 0
        bitsSet = 0
    }

    /** [add] 的调用次数，包含重复。 */
    val size: Int get() = insertions

    /** 当前被置为 1 的位数。 */
    val setBits: Int get() = bitsSet

    /** 被置为 1 的位所占比例。 */
    val fillRatio: Double get() = bitsSet.toDouble() / bitSize

    /** 每一位都被设置、过滤器完全饱和时为真。 */
    val isSaturated: Boolean get() = bitsSet.toLong() == bitSize

    /**
     * 按已插入键数量估计当前假阳性概率：`(1 - e^(-k*n/m))^k`。
     */
    val estimatedFalsePositiveRate: Double
        get() {
            if (insertions == 0) {
                return 0.0
            }
            val exponent = (-hashCount.toDouble() * insertions) / bitSize
            return Math.pow(1 - Math.exp(exponent), hashCount.toDouble())
        }

    /** 设置一位，仅在 0 -> 1 变化时更新占用计数。 */
    private fun setBit(index: Int) {
        val byteIndex = index ushr 3
        val mask = 1 shl (index and 7)
        val current = bits[byteIndex].toInt() and 0xFF
        if ((current and mask) == 0) {
            bits[byteIndex] = (current or mask).toByte()
            bitsSet += 1
        }
    }

    /** 读取一位。 */
    private fun getBit(index: Int): Boolean {
        val byteIndex = index ushr 3
        val mask = 1 shl (index and 7)
        return ((bits[byteIndex].toInt() and 0xFF) and mask) != 0
    }
}
