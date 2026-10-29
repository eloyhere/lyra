package com.eloykim.lyra.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImpasseDetectorTest {

    private val detector = ImpasseDetector(stallThreshold = 2)

    private fun sig(action: String = "tap", node: Int = 1) =
        ActionSignature(action, node, null, null)

    @Test
    fun `连续相同动作且屏幕不变判定为 STALLED`() {
        assertNull(detector.evaluate(sig(), "pkg|3|确认", ok = true, foregroundPackage = "com.x"))
        assertNull(detector.evaluate(sig(), "pkg|3|确认", ok = true, foregroundPackage = "com.x"))
        assertEquals(Impasse.STALLED, detector.evaluate(sig(), "pkg|3|确认", ok = true, foregroundPackage = "com.x"))
    }

    @Test
    fun `动作换了就不算卡住`() {
        detector.evaluate(sig(node = 1), "screen-a", ok = true, foregroundPackage = "com.x")
        detector.evaluate(sig(node = 2), "screen-a", ok = true, foregroundPackage = "com.x")
        assertNull(detector.evaluate(sig(node = 2), "screen-b", ok = true, foregroundPackage = "com.x"))
    }

    @Test
    fun `屏幕变了不判 STALLED`() {
        detector.evaluate(sig(), "screen-a", ok = true, foregroundPackage = "com.x")
        detector.evaluate(sig(), "screen-b", ok = true, foregroundPackage = "com.x")
        assertNull(detector.evaluate(sig(), "screen-b", ok = true, foregroundPackage = "com.x"))
    }

    @Test
    fun `前台变成 Lyra 自身判定为 DRIFT`() {
        assertEquals(
            Impasse.DRIFT,
            detector.evaluate(sig(), "screen", ok = true, foregroundPackage = "com.eloykim.lyra"),
        )
    }

    @Test
    fun `调试模式允许自身操作时不判 DRIFT`() {
        val allowed = ImpasseDetector(selfOperationAllowed = { true })
        assertNull(
            allowed.evaluate(sig(), "screen", ok = true, foregroundPackage = "com.eloykim.lyra"),
        )
    }

    @Test
    fun `动作成功但屏幕不变判定为 UNVERIFIED`() {
        detector.evaluate(ActionSignature("tap", 1, null, null), "same", ok = true, foregroundPackage = "com.x")
        assertEquals(
            Impasse.UNVERIFIED,
            detector.evaluate(ActionSignature("tap", 2, null, null), "same", ok = true, foregroundPackage = "com.x"),
        )
    }

    @Test
    fun `reset 后重新计数`() {
        detector.evaluate(sig(), "s", ok = false, foregroundPackage = "com.x")
        detector.evaluate(sig(), "s", ok = false, foregroundPackage = "com.x")
        detector.reset()
        assertNull(detector.evaluate(sig(), "s", ok = false, foregroundPackage = "com.x"))
    }
}
