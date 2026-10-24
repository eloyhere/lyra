package com.eloykim.lyra.amp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AmpFrameTest {

    private fun observation() = Observation(
        obsId = 42,
        packageName = "com.android.settings",
        totalNodes = 75,
        interactiveNodes = 14,
        sent = listOf(
            ObservedElement(8, "设置", clickable = true),
            ObservedElement(11, "Settings"),
        ),
        dropped = listOf(
            ObservedElement(20, "隐藏的布局"),
            ObservedElement(21, "重复文本"),
        ),
        tokensEstimate = 320,
    )

    @Test
    fun `frame 满足 A-1 A-2 A-3`() {
        val frame = FrameBuilder().build(
            cycle = 1,
            mode = Mode.exploit,
            observation = observation(),
            allocated = Budget(tokens = 4000, calls = 6),
            spent = Usage(tokens = 320, calls = 1),
            goal = Ref("goal", "g.task1", "打开设置并找到无障碍"),
        )

        assertTrue(frame.violations().isEmpty())
        assertTrue(frame.slots.all { it.why.isNotBlank() })
        assertTrue(frame.slots.all { it.cost != null })
        assertTrue(frame.ignored.all { !it.revisitIf.isNullOrBlank() })
        assertTrue(!frame.budget.spent.exceeds(frame.budget.allocated))
    }

    @Test
    fun `frame 有合法 digest`() {
        val frame = FrameBuilder().build(
            cycle = 2,
            mode = Mode.explore,
            observation = observation(),
            allocated = Budget(tokens = 4000),
            spent = Usage(tokens = 100),
        )
        assertTrue(Digests.isValid(frame.digest!!))
        assertEquals(frame.digest, frame.copy().digest)
    }

    @Test
    fun `slots 不超过 9 个`() {
        val frame = FrameBuilder().build(
            cycle = 3,
            mode = Mode.triage,
            observation = observation(),
            allocated = Budget(tokens = 1000),
            spent = Usage(tokens = 10),
            goal = Ref("goal", "g.x"),
        )
        assertTrue(frame.slots.size <= 9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `slot 缺少 why 会被拒绝`() {
        Slot(
            target = Ref("goal", "g.1"),
            role = SlotRole.goal,
            why = " ",
            cost = Budget(tokens = 1),
        )
    }

    @Test
    fun `ledger 只追加且 head 指向最后一帧`() {
        AmpLedger.resetForTest()
        val builder = FrameBuilder()
        val first = builder.build(1, Mode.explore, observation(), Budget(tokens = 100), Usage(tokens = 1))
        val second = builder.build(2, Mode.exploit, observation(), Budget(tokens = 100), Usage(tokens = 2))
        AmpLedger.append(first)
        AmpLedger.append(second)

        val snapshot = AmpLedger.snapshot()
        assertEquals(2, snapshot.frames.size)
        assertEquals(Ref("frame", second.id), snapshot.head)
    }

    @Test
    fun `manifest 有 digest 与签名`() {
        val signer = Ed25519Signer.createOrNull()
        assertTrue("当前 JDK 不支持 Ed25519，测试环境异常", signer != null)

        val manifest = ManifestFactory.build(signer!!, issued = Instant.parse("2026-10-24T07:00:00Z"))
        val json = manifest.render()

        assertTrue(json.contains("\"kind\":\"manifest\""))
        assertTrue(json.contains("\"signatures\""))
        assertTrue(json.contains("\"capabilities\""))
        assertTrue(json.contains("\"policy_digest\":\"sha256:"))
        assertTrue(Digests.isValid(Regex("\"digest\":\"(sha256:[a-f0-9]{64})\"").find(json)!!.groupValues[1]))
    }
}

class JsonWriterTest {

    @Test
    fun `字符串转义与数字格式`() {
        val json = obj(
            "a" to "say \"hi\"\n",
            "b" to 3,
            "c" to 0.5,
            "d" to null,
        ).render()

        assertEquals("{\"a\":\"say \\\"hi\\\"\\n\",\"b\":3,\"c\":0.5}", json)
    }

    @Test
    fun `id 规则`() {
        assertTrue(AmpIds.isValid("f.101"))
        assertTrue(AmpIds.isValid("a.lyra"))
        assertTrue(!AmpIds.isValid("F.101"))
        assertTrue(!AmpIds.isValid("1.abc"))
        assertEquals("f.abc-def", AmpIds.slug("ABC DEF", "f"))
    }

    @Test
    fun `digest 稳定`() {
        val a = Digests.sha256("hello")
        val b = Digests.sha256("hello")
        assertEquals(a, b)
        assertTrue(Digests.isValid(a))
    }
}
