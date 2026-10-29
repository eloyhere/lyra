package com.eloykim.lyra.amp.protocol.structure

/**
 * 带两个候选桶与紧凑指纹的布谷鸟过滤器，移植自 `src/structure/cuckooFilter.ts`。
 *
 * 与 Bloom 过滤器不同，布谷鸟过滤器支持删除单个键。每个键被归约为一个小指纹与
 * 两个桶下标；插入时把指纹放进任一桶，两个桶都满时把已有指纹踢到它的另一个桶。
 * 有界的踢出链如果找不到空位会被完整回滚，因此失败的 `add` 绝不会静默丢失此前
 * 已接受的键。
 *
 * 假阳性可能存在（不同键可能共享两个桶与指纹）；只要插入、查询与删除使用相同的
 * 键字节，就不会有假阴性。允许重复键。
 */

private const val DefaultBucketSize: Int = 4
private const val DefaultFingerprintBits: Int = 8
private const val DefaultMaxKicks: Int = 500
private val FingerprintSeed: Hash = 0xd1b54a32d192ed03uL
private const val MinFingerprintBits: Int = 4
private const val MaxFingerprintBits: Int = 30

private class KickRecord(val bucket: Int, val slot: Int, val displaced: Int)

/**
 * 面向字符串或 [ByteArray] 键的布谷鸟过滤器。
 *
 * `capacity` 是指纹槽位数量而不是请求值：它等于 `bucketCount * bucketSize`，
 * 由构造函数的 `capacity` 向上取整得到。[loadFactor] 是这些槽位当前被占用的比例。
 *
 * @param requestedCapacity 桶取整之前的请求键容量。
 * @param bucketSize 每个桶的指纹数量，默认 4，取值 `[1, 32]`。
 * @param fingerprintBits 指纹位宽，4..30，默认 8。
 * @param maxKicks 插入失败并回滚之前的最大踢出次数，默认 500。
 * @param seed 键与指纹哈希的域种子，默认 0。
 */
class CuckooFilter(
    /** 桶取整之前的请求键容量。 */
    val requestedCapacity: Int,
    /** 每个桶的指纹数量。 */
    val bucketSize: Int = DefaultBucketSize,
    /** 指纹位宽。 */
    val fingerprintBits: Int = DefaultFingerprintBits,
    /** 每次插入的最大位移次数。 */
    val maxKicks: Int = DefaultMaxKicks,
    seed: Hash = 0uL,
) {

    /** 桶数量（2 的幂次）。 */
    val bucketCount: Int

    /** 指纹槽位总数：`bucketCount * bucketSize`。 */
    val capacity: Long

    private val buckets: Array<MutableList<Int>>
    private val seed: Hash = seed
    private val indexMask: Int
    private val fingerprintMask: Hash
    private val initialRng: Int
    private var rngState: Int
    private var count = 0

    init {
        require(requestedCapacity > 0) { "capacity must be a positive safe integer" }
        require(bucketSize in 1..32) { "bucketSize must be an integer in [1, 32]" }
        require(fingerprintBits in MinFingerprintBits..MaxFingerprintBits) {
            "fingerprintBits must be an integer in [$MinFingerprintBits, $MaxFingerprintBits]"
        }
        require(maxKicks >= 0) { "maxKicks must be a non-negative safe integer" }

        var computed = 1
        while (computed.toLong() * bucketSize < requestedCapacity) {
            computed *= 2
            if (computed > (1 shl 30)) {
                throw IllegalArgumentException("capacity is too large for a cuckoo filter")
            }
        }

        bucketCount = computed
        capacity = computed.toLong() * bucketSize
        buckets = Array(computed) { ArrayList() }
        indexMask = computed - 1
        fingerprintMask = (1uL shl fingerprintBits) - 1uL
        val seeded = (seed and 0xFFFF_FFFFuL).toLong().toInt()
        initialRng = if (seeded == 0) 0x9e37_79b9.toInt() else seeded
        rngState = initialRng
    }

    /**
     * 插入一个键。
     *
     * @return 指纹被存储返回 `true`；踢出链耗尽返回 `false`。失败的插入会被回滚，
     * 过滤器保持原样。
     */
    fun add(key: FilterKey): Boolean {
        val located = locate(key)
        val first = located.first
        val second = alternate(first, located.second)
        var currentFingerprint = located.second
        var currentIndex = pickBucket(first, second)
        val history = ArrayList<KickRecord>()

        var kick = 0
        while (kick <= maxKicks) {
            val bucket = buckets[currentIndex]
            if (bucket.size < bucketSize) {
                bucket.add(currentFingerprint)
                count += 1
                return true
            }
            if (kick == maxKicks) {
                break
            }

            val slot = randomInt(bucket.size)
            val displaced = bucket[slot]
            history.add(KickRecord(currentIndex, slot, displaced))
            bucket[slot] = currentFingerprint
            currentFingerprint = displaced
            currentIndex = alternate(currentIndex, currentFingerprint)
            kick += 1
        }

        // 撤销每一次位移。最后被踢出的指纹从未被存储，更早的指纹都回到原来的槽位。
        for (index in history.indices.reversed()) {
            val record = history[index]
            buckets[record.bucket][record.slot] = record.displaced
        }
        return false
    }

    fun add(key: String): Boolean = add(FilterKey.Text(key))

    fun add(key: ByteArray): Boolean = add(FilterKey.Bytes(key))

    /** 测试一个键是否可能存在。 */
    fun has(key: FilterKey): Boolean {
        val located = locate(key)
        if (buckets[located.first].contains(located.second)) {
            return true
        }
        val alternate = alternate(located.first, located.second)
        return alternate != located.first && buckets[alternate].contains(located.second)
    }

    fun has(key: String): Boolean = has(FilterKey.Text(key))

    fun has(key: ByteArray): Boolean = has(FilterKey.Bytes(key))

    /**
     * 移除键的一个出现。
     *
     * `false` 只对从未插入过的键精确；不同键可能共享同一指纹，因此对碰撞而言
     * 删除是尽力而为的。
     */
    fun delete(key: FilterKey): Boolean {
        val located = locate(key)
        if (removeFingerprint(located.first, located.second)) {
            count -= 1
            return true
        }
        val alternate = alternate(located.first, located.second)
        if (alternate != located.first && removeFingerprint(alternate, located.second)) {
            count -= 1
            return true
        }
        return false
    }

    fun delete(key: String): Boolean = delete(FilterKey.Text(key))

    fun delete(key: ByteArray): Boolean = delete(FilterKey.Bytes(key))

    /** 移除所有键并重置随机踢出状态。 */
    fun clear() {
        for (bucket in buckets) {
            bucket.clear()
        }
        count = 0
        rngState = initialRng
    }

    /** 已存储的指纹数量。 */
    val size: Int get() = count

    /** 指纹槽位的占用比例。 */
    val loadFactor: Double get() = count.toDouble() / capacity

    /** 每个指纹槽位都被占用时为真。 */
    val isFull: Boolean get() = count.toLong() == capacity

    /** 非满过滤器的近似假阳性概率：`2 * bucketSize / 2^fingerprintBits`。 */
    val falsePositiveRate: Double
        get() = minOf(1.0, (2.0 * bucketSize) / Math.pow(2.0, fingerprintBits.toDouble()))

    /** 对键哈希一次，得到初始桶与紧凑指纹。 */
    private fun locate(key: FilterKey): Pair<Int, Int> {
        val hash = hashBytes(filterKeyBytes(key), seed)
        val index = (hash and indexMask.toULong()).toInt()
        var fingerprint = ((hash shr 32) and fingerprintMask).toInt()
        if (fingerprint == 0) {
            fingerprint = 1
        }
        return index to fingerprint
    }

    /** `index` 中指纹的另一个候选桶。 */
    private fun alternate(index: Int, fingerprint: Int): Int {
        val bytes = byteArrayOf(
            (fingerprint and 0xFF).toByte(),
            ((fingerprint ushr 8) and 0xFF).toByte(),
            ((fingerprint ushr 16) and 0xFF).toByte(),
            ((fingerprint ushr 24) and 0xFF).toByte(),
        )
        val hashed = (hashBytes(bytes, seed xor FingerprintSeed) and indexMask.toULong()).toInt()
        return (index xor hashed) and indexMask
    }

    /** 选择一个初始桶，优先选择还有空间的桶。 */
    private fun pickBucket(first: Int, second: Int): Int {
        if (buckets[first].size < bucketSize) {
            return first
        }
        if (buckets[second].size < bucketSize) {
            return second
        }
        return if (randomInt(2) == 0) first else second
    }

    /** 从一个桶中移除第一个匹配的指纹。 */
    private fun removeFingerprint(index: Int, fingerprint: Int): Boolean {
        val bucket = buckets[index]
        val slot = bucket.indexOf(fingerprint)
        if (slot < 0) {
            return false
        }
        val last = bucket.removeAt(bucket.size - 1)
        if (slot < bucket.size) {
            bucket[slot] = last
        }
        return true
    }

    /** `[0, bound)` 内的 Xorshift32；给定种子与插入顺序时结果确定。 */
    private fun randomInt(bound: Int): Int {
        var value = rngState
        value = value xor (value shl 13)
        value = value xor (value ushr 17)
        value = value xor (value shl 5)
        rngState = value
        return ((rngState.toLong() and 0xFFFF_FFFFL) % bound).toInt()
    }
}
