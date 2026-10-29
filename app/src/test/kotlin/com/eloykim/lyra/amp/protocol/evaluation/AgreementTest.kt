package com.eloykim.lyra.amp.protocol.evaluation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgreementTest {

    @Test
    fun `一致率与空序列`() {
        assertEquals(1.0, agreementRate(listOf("a", "b"), listOf("a", "b")), 0.0)
        assertEquals(2.0 / 3.0, agreementRate(listOf("a", "b", "c"), listOf("a", "b", "d")), 1e-12)
        assertEquals(1.0, agreementRate(emptyList(), emptyList()), 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            agreementRate(listOf("a"), listOf("a", "b"))
        }
    }

    @Test
    fun `cohen kappa 与完全一致特例`() {
        // observed 0.75；expected 0.5；kappa 0.5
        assertEquals(
            0.5,
            cohenKappa(listOf("a", "a", "b", "b"), listOf("a", "b", "b", "b")),
            1e-12,
        )
        // 两边都只用一个类别：expected 为 1，完全一致记为 1。
        assertEquals(1.0, cohenKappa(listOf("x", "x"), listOf("x", "x")), 0.0)
        assertEquals(1.0, cohenKappa(emptyList(), emptyList()), 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            cohenKappa(listOf("a"), emptyList())
        }
    }

    @Test
    fun `fleiss kappa 接受矩形矩阵并拒绝锯齿矩阵`() {
        assertEquals(
            -1.0 / 3.0,
            fleissKappa(listOf(listOf("a", "a", "b"), listOf("a", "b", "b"))),
            1e-12,
        )
        assertEquals(1.0, fleissKappa(emptyList()), 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            fleissKappa(listOf(listOf("a", "b"), listOf("a")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            fleissKappa(listOf(emptyList()))
        }
    }
}
