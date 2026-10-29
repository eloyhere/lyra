package com.eloykim.lyra.amp.protocol.structure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.util.Date

/**
 * `tools/structure-compare-check.ts` 的 Kotlin 版本，并补充 `hash.ts` 的契约检查
 * （参考仓库没有单独的 hash 检查文件）。
 */
class StructureCompareCheckTest {

    private data class Item(val id: Int, val label: String)

    @Test
    fun numericComparisonIsThreeWayAndRejectsNan() {
        assertEquals(-1, compareNumbers(1.0, 2.0))
        assertEquals(0, compareNumbers(2.0, 2.0))
        assertEquals(1, compareNumbers(3.0, 2.0))
        assertThrows(IllegalArgumentException::class.java) { compareNumbers(Double.NaN, 1.0) }
    }

    @Test
    fun stringBigintAndDateComparison() {
        assertEquals(-1, compareStrings("a", "b"))
        assertEquals(0, compareStrings("a", "a"))
        assertEquals(-1, compareBigInts(BigInteger.ONE, BigInteger.TWO))
        assertEquals(0, compareBigInts(BigInteger.TWO, BigInteger.TWO))
        assertEquals(-1, compareDates(Date(0), Date(1)))
        assertEquals(0, compareDates(Date(0), Date(0)))
    }

    @Test
    fun naturalOrderingCoversPrimitivesAndRejectsUnsupportedValues() {
        assertEquals(-1, compareNatural(1, 2))
        assertEquals(-1, compareNatural("a", "b"))
        assertEquals(-1, compareNatural(BigInteger.ONE, BigInteger.TWO))
        assertEquals(-1, compareNatural(false, true))
        assertEquals(0, compareNatural("same", "same"))
        assertThrows(IllegalArgumentException::class.java) { compareNatural(Double.NaN, Double.NaN) }
        val unsupported = Any()
        assertThrows(IllegalArgumentException::class.java) { compareNatural(unsupported, Any()) }
        assertEquals(0, compareNatural(unsupported, unsupported))
    }

    @Test
    fun reversingAComparatorSwapsTheOrdering() {
        val reverse = reverseComparator(Comparator<Int> { a, b -> a - b })
        assertEquals(1, reverse.compare(1, 2))
        assertEquals(-1, reverse.compare(2, 1))
        assertEquals(0, reverse.compare(2, 2))
    }

    @Test
    fun byKeyDerivesAnOrderingFromASelector() {
        val byId = byKey(select = { item: Item -> item.id })
        assertEquals(-1, byId.compare(Item(1, "b"), Item(2, "a")))
        val byLabel = byKey(select = { item: Item -> item.label })
        assertEquals(1, byLabel.compare(Item(1, "b"), Item(2, "a")))
    }

    @Test
    fun signClampsComparatorShapedValues() {
        assertEquals(-1, sign(-10))
        assertEquals(0, sign(0))
        assertEquals(1, sign(10))
    }

    @Test
    fun defaultCompareIsNaturalOrdering() {
        assertEquals(-1, defaultCompare.compare(1, 2))
        assertEquals(1, defaultCompare.compare("b", "a"))
    }

    @Test
    fun contentHashingIsDeterministicAndSeedDependent() {
        val bytes = byteArrayOf(0, 1, 2, 255.toByte())
        assertEquals(hashBytes(bytes), hashBytes(bytes))
        assertNotEquals(hashBytes(bytes), hashBytes(bytes, 1uL))
        assertEquals(hashString("你好"), hashBytes("你好".toByteArray(Charsets.UTF_8)))
        assertNotEquals(hashString("a"), hashString("b"))
        // 空字节串仍然经过 avalanche，不等于偏移基。
        assertNotEquals(0uL, hashBytes(ByteArray(0)))
    }

    @Test
    fun keyHashingSeparatesTypesAndFollowsSameValueZero() {
        assertNotEquals(hashKey(1), hashKey("1"))
        assertNotEquals(hashKey(1), hashKey(true))
        assertNotEquals(hashKey(true), hashKey(false))
        assertNotEquals(hashKey(null), hashKey(0))
        assertEquals(hashKey(0.0), hashKey(-0.0))
        assertEquals(hashKey(Double.NaN), hashKey(Double.NaN))
        assertNotEquals(hashKey(1.0), hashKey(2.0))
    }

    @Test
    fun sameKeyUsesSameValueZero() {
        assertTrue(sameKey(0.0, -0.0))
        assertTrue(sameKey(Double.NaN, Double.NaN))
        assertTrue(sameKey(null, null))
        assertFalse(sameKey(null, 0))
        assertFalse(sameKey(1, 1L))
        assertTrue(sameKey("a", "a"))
        assertTrue(sameKey(Item(1, "x"), Item(1, "x")))
    }

    @Test
    fun hashHexRoundTripsAndRejectsInvalidInput() {
        val values = listOf(0uL, 1uL, 0x1234_5678_9abc_def0uL, ULong.MAX_VALUE)
        for (value in values) {
            assertEquals(value, hashFromHex(hashToHex(value)))
            assertEquals(value, hashFromHex(hashToHex(value, 4)))
        }
        assertEquals("0000", hashToHex(0uL, 4))
        assertEquals(16, hashToHex(1uL).length)
        assertEquals(31uL, hashFromHex("0x1f"))
        assertEquals(31uL, hashFromHex("0X1F"))
        assertEquals(31uL, hashFromHex("00001F"))
        assertThrows(IllegalArgumentException::class.java) { hashToHex(1uL, -1) }
        assertThrows(IllegalArgumentException::class.java) { hashFromHex("") }
        assertThrows(IllegalArgumentException::class.java) { hashFromHex("zz") }
        assertThrows(IllegalArgumentException::class.java) { hashFromHex("0x") }
        assertThrows(IllegalArgumentException::class.java) { hashFromHex("1".repeat(17)) }
    }

    @Test
    fun mixHashIsInjectiveInEachArgumentBitwise() {
        assertNotEquals(mixHash(1uL, 2uL), mixHash(1uL, 3uL))
        assertNotEquals(mixHash(1uL, 2uL), mixHash(2uL, 2uL))
        assertEquals(mixHash(1uL, 2uL), mixHash(1uL, 2uL))
    }

    @Test
    fun identityHashingIsStablePerObject() {
        val first = Any()
        val second = Any()
        assertEquals(hashIdentity(first), hashIdentity(first))
        val probes = List(500) { Any() }
        val distinct = probes.map { hashIdentity(it) }.toSet().size
        assertTrue("identity hashes should almost never collide, got $distinct", distinct >= 495)
        assertNotEquals(hashIdentity(first), hashIdentity(second))
    }

    @Test
    fun deterministicRandomSourcesMatchTheReferenceJavascriptSequences() {
        val mulberry = Mulberry32(0x9e37)
        val mulberryExpected = listOf(
            0.496467053424567,
            0.5835126887541264,
            0.5600962669122964,
            0.9633148957509547,
            0.45075055793859065,
        )
        for (expected in mulberryExpected) {
            assertEquals(expected, mulberry.next(), 0.0)
        }

        val lcg = Lcg(0x5157)
        val lcgExpected = listOf(
            0.30607216292992234,
            0.0030689018312841654,
            0.4998886911198497,
            0.459654240636155,
            0.21096286876127124,
        )
        for (expected in lcgExpected) {
            assertEquals(expected, lcg.next(), 0.0)
        }
    }
}
