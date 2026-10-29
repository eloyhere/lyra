package com.eloykim.lyra.amp.protocol.structure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `tools/filter-check.ts` 的 Kotlin 版本：Bloom 过滤器与布谷鸟过滤器，
 * 包含确定性随机布谷鸟工作负载。
 */
class FilterCheckTest {

    @Test
    fun bloomFilterValidatesSizingInputs() {
        assertThrows(IllegalArgumentException::class.java) { BloomFilter(expectedItems = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            BloomFilter(expectedItems = 10, falsePositiveRate = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BloomFilter(expectedItems = 10, falsePositiveRate = 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BloomFilter(expectedItems = 1_000_000, falsePositiveRate = 0.0001, maxBits = 1_000L)
        }
        val filter = BloomFilter(expectedItems = 1_000, falsePositiveRate = 0.01)
        assertTrue(filter.bitSize > 0)
        assertTrue(filter.hashCount >= 1)
        assertEquals(0, filter.size)
        assertEquals(0, filter.setBits)
        assertEquals(0.0, filter.fillRatio, 0.0)
    }

    @Test
    fun bloomFilterHasNoFalseNegativesAndRecordsDuplicates() {
        val filter = BloomFilter(expectedItems = 2_000, falsePositiveRate = 0.01)
        for (index in 0 until 2_000) {
            filter.add("key-$index")
        }
        for (index in 0 until 2_000) {
            assertTrue(filter.has("key-$index"))
        }
        assertEquals(2_000, filter.size)
        assertTrue(filter.setBits > 0 && filter.setBits.toLong() < filter.bitSize)
        assertTrue(filter.fillRatio > 0.0 && filter.fillRatio < 1.0)
        assertTrue(filter.estimatedFalsePositiveRate > 0.0)

        val before = filter.setBits
        filter.add("key-0")
        assertEquals(2_001, filter.size)
        assertEquals(before, filter.setBits)
    }

    @Test
    fun bloomFilterFalsePositiveRateIsInTheExpectedRange() {
        val filter = BloomFilter(expectedItems = 1_000, falsePositiveRate = 0.01)
        for (index in 0 until 1_000) {
            filter.add("present-$index")
        }
        var falsePositives = 0
        for (index in 0 until 10_000) {
            if (filter.has("absent-$index")) {
                falsePositives += 1
            }
        }
        assertTrue("false positives: $falsePositives", falsePositives < 300)
    }

    @Test
    fun bloomFilterSupportsByteKeysUnicodeAndClear() {
        val filter = BloomFilter(expectedItems = 100, falsePositiveRate = 0.001)
        val bytes = byteArrayOf(0, 1, 2, 255.toByte())
        filter.add(bytes).add("你好")
        assertTrue(filter.has(byteArrayOf(0, 1, 2, 255.toByte())))
        assertTrue(filter.has("你好"))
        assertFalse(filter.has("hello"))
        filter.clear()
        assertEquals(0, filter.size)
        assertEquals(0, filter.setBits)
        assertFalse(filter.has(bytes))
    }

    @Test
    fun bloomFilterRejectsNonStringNonByteKeys() {
        val filter = BloomFilter(expectedItems = 10)
        assertThrows(IllegalArgumentException::class.java) { assertFilterKey(42) }
        assertThrows(IllegalArgumentException::class.java) { assertFilterKey(null) }
        assertThrows(IllegalArgumentException::class.java) { FilterKey.of(42) }
        assertThrows(IllegalArgumentException::class.java) { FilterKey.of(null) }
        // 合法键可以包装，并通过统一入口使用。
        assertTrue(filter.add(FilterKey.of("wrapped")).has(FilterKey.of("wrapped")))
    }

    @Test
    fun cuckooFilterValidatesParametersAndRoundsCapacityUp() {
        assertThrows(IllegalArgumentException::class.java) { CuckooFilter(requestedCapacity = 0) }
        assertThrows(IllegalArgumentException::class.java) { CuckooFilter(requestedCapacity = 10, bucketSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { CuckooFilter(requestedCapacity = 10, fingerprintBits = 3) }
        assertThrows(IllegalArgumentException::class.java) { CuckooFilter(requestedCapacity = 10, maxKicks = -1) }
        assertThrows(IllegalArgumentException::class.java) { CuckooFilter(requestedCapacity = 10, bucketSize = 33) }
        assertThrows(IllegalArgumentException::class.java) { CuckooFilter(requestedCapacity = 10, fingerprintBits = 31) }
        val filter = CuckooFilter(requestedCapacity = 10, bucketSize = 4, fingerprintBits = 8)
        assertEquals(4, filter.bucketCount)
        assertEquals(16L, filter.capacity)
        assertEquals(0, filter.size)
        assertEquals(0.0, filter.loadFactor, 0.0)
        assertTrue(filter.falsePositiveRate > 0)
    }

    @Test
    fun cuckooFilterHasNoFalseNegativesForAcceptedKeys() {
        val filter = CuckooFilter(requestedCapacity = 2_000, bucketSize = 4, fingerprintBits = 12)
        val accepted = ArrayList<String>()
        for (index in 0 until 1_500) {
            val key = "key-$index"
            if (filter.add(key)) {
                accepted.add(key)
            }
        }
        assertEquals(accepted.size, filter.size)
        for (key in accepted) {
            assertTrue(filter.has(key))
        }
    }

    @Test
    fun cuckooFilterDeleteRemovesOneOccurrenceAndSupportsDuplicates() {
        val filter = CuckooFilter(requestedCapacity = 32, fingerprintBits = 16)
        assertTrue(filter.add("duplicate"))
        assertTrue(filter.add("duplicate"))
        assertEquals(2, filter.size)
        assertTrue(filter.has("duplicate"))
        assertTrue(filter.delete("duplicate"))
        assertTrue(filter.has("duplicate"))
        assertTrue(filter.delete("duplicate"))
        assertEquals(0, filter.size)
        assertFalse(filter.delete("duplicate"))
    }

    @Test
    fun cuckooFilterDeletionKeepsOtherAcceptedKeysFindable() {
        val filter = CuckooFilter(requestedCapacity = 1_200, fingerprintBits = 20)
        val accepted = ArrayList<String>()
        for (index in 0 until 1_000) {
            val key = "key-$index"
            if (filter.add(key)) {
                accepted.add(key)
            }
        }
        var index = 0
        while (index < accepted.size) {
            assertTrue(filter.delete(accepted[index]))
            index += 2
        }
        index = 1
        while (index < accepted.size) {
            assertTrue(filter.has(accepted[index]))
            index += 2
        }
    }

    @Test
    fun cuckooFilterHandlesAFullTableWithoutLosingAcceptedKeys() {
        val filter = CuckooFilter(
            requestedCapacity = 8,
            bucketSize = 1,
            fingerprintBits = 16,
            maxKicks = 12,
            seed = 7uL,
        )
        val accepted = HashSet<String>()
        var failed = 0
        for (index in 0 until 100) {
            val key = "full-$index"
            if (filter.add(key)) {
                accepted.add(key)
            } else {
                failed += 1
            }
        }
        assertTrue("a table with eight slots must eventually reject", failed > 0)
        assertEquals(accepted.size, filter.size)
        for (key in accepted) {
            assertTrue(filter.has(key))
        }
    }

    @Test
    fun cuckooFilterSupportsBytesClearAndIsDeterministicForAFixedSeed() {
        val make = { CuckooFilter(requestedCapacity = 100, fingerprintBits = 12, seed = 42uL) }
        val a = make()
        val b = make()
        for (index in 0 until 80) {
            a.add("same-$index")
            b.add("same-$index")
        }
        assertEquals(a.size, b.size)
        assertEquals(a.loadFactor, b.loadFactor, 0.0)
        for (index in 0 until 80) {
            assertEquals(a.has("same-$index"), b.has("same-$index"))
        }
        val bytes = byteArrayOf(9, 8, 7)
        assertTrue(a.add(bytes))
        assertTrue(a.has(byteArrayOf(9, 8, 7)))
        a.clear()
        assertEquals(0, a.size)
        assertEquals(0.0, a.loadFactor, 0.0)
    }

    @Test
    fun cuckooFilterRejectsInvalidKeys() {
        val filter = CuckooFilter(requestedCapacity = 10)
        assertThrows(IllegalArgumentException::class.java) { assertFilterKey(1) }
        assertThrows(IllegalArgumentException::class.java) { assertFilterKey(mapOf("a" to 1)) }
        assertFalse(filter.has(FilterKey.of("never-added")))
    }

    @Test
    fun deterministicRandomizedCuckooWorkloadKeepsAcceptedKeysFindable() {
        val random = Lcg(0xc0ffee)
        val filter = CuckooFilter(requestedCapacity = 5_000, fingerprintBits = 20, maxKicks = 500)
        val present = LinkedHashSet<String>()
        var inserted = 0
        var deleted = 0
        for (index in 0 until 5_000) {
            val key = "r-${Math.floor(random.next() * 10_000).toInt()}"
            if (filter.add(key)) {
                inserted += 1
                present.add(key)
            }
            if (index % 7 == 0 && present.isNotEmpty()) {
                val victim = present.elementAt(Math.floor(random.next() * present.size).toInt())
                if (filter.delete(victim)) {
                    deleted += 1
                    present.remove(victim)
                }
            }
            if (index % 500 == 0) {
                for (key in present) {
                    assertTrue(filter.has(key))
                }
            }
        }
        assertEquals(inserted - deleted, filter.size)
        assertTrue(filter.size >= present.size)
        for (key in present) {
            assertTrue(filter.has(key))
        }
    }
}
