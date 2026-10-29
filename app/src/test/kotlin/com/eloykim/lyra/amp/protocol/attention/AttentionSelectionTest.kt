package com.eloykim.lyra.amp.protocol.attention

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionSelectionTest {

    @Test
    fun `显著性单调性与参考取值`() {
        val base = attentionCandidate()
        // 0.792 / 1.4 = 0.5657…，参考实现 round3 后为 0.566。
        assertEquals(0.566, salience(base), 0.0)
        assertTrue(respectsMonotonicity(base))

        val reference = salience(base)
        assertTrue(salience(base.copy(voi = 0.9)) >= reference)
        assertTrue(salience(base.copy(costTokens = 300)) <= reference)
        assertTrue(salience(base.copy(risk = 0.2)) <= reference)
        assertTrue(salience(base.copy(trustCap = 0.2)) <= reference)
    }

    @Test
    fun `焦点选择记录低价值与预算排除`() {
        val high = attentionCandidate(id = "c.high")
        val low = attentionCandidate(id = "c.low", voi = 0.05, goalFit = 0.3, why = "低价值")
        val expensive = attentionCandidate(id = "c.exp", costTokens = 900, why = "太贵")

        val selection = selectFocus(listOf(high, low, expensive), SelectionOptions(budgetTokens = 500))

        assertEquals(listOf("c.high"), selection.slots.map { it.target.id })
        val reasons = selection.ignored.associate { it.target.id to it.reason }
        assertEquals(IgnoredReason.LOW_VOI, reasons["c.low"])
        assertEquals(IgnoredReason.BUDGET, reasons["c.exp"])
        assertTrue("每个排除都要带重新评估条件", selection.ignored.all { it.revisitIf.isNotBlank() })
        assertNull(selection.impasse)

        val slot = selection.slots.single()
        assertEquals(0.566, slot.salience, 0.0)
        assertEquals(Budget(tokens = 100), slot.cost)
        assertEquals(SlotState.ACTIVE, slot.state)
    }

    @Test
    fun `被排除项可写入合法 frame`() {
        val selection = selectFocus(
            listOf(attentionCandidate(id = "c.high"), attentionCandidate(id = "c.low", voi = 0.05, goalFit = 0.3)),
            SelectionOptions(budgetTokens = 500),
        )
        val frame = jsonObj(
            "amp" to JsonValue.Str("0.1"),
            "kind" to JsonValue.Str("frame"),
            "id" to JsonValue.Str("f.1"),
            "agent" to JsonValue.Str("a.alpha"),
            "cycle" to jsonNumber(1),
            "ts" to JsonValue.Str(ATTENTION_TEST_AT),
            "digest" to JsonValue.Str("sha256:" + "a".repeat(64)),
            "mode" to JsonValue.Str("explore"),
            "slots" to JsonValue.Arr(selection.slots.map { it.toJson() }),
            "ignored" to JsonValue.Arr(selection.ignored.map { it.toJson() }),
            "budget" to jsonObj(
                "allocated" to Budget(tokens = 500).toJson(),
                "spent" to Budget(tokens = 100).toJson(),
            ),
        )
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("frame", frame))
    }

    @Test
    fun `容量上限把剩余候选记为 deferred 并给出 TIE`() {
        val candidates = (1..3).map { index -> attentionCandidate(id = "c.$index", costTokens = 0) }
        val selection = selectFocus(candidates, SelectionOptions(budgetTokens = 100, cap = 2))

        assertEquals(2, selection.slots.size)
        assertEquals(ImpasseState.TIE, selection.impasse)
        assertEquals(
            listOf("c.3"),
            selection.ignored.filter { it.reason == IgnoredReason.DEFERRED }.map { it.target.id },
        )
    }

    @Test
    fun `预算耗尽时返回 BUDGET 而非静默空集`() {
        val selection = selectFocus(listOf(attentionCandidate(costTokens = 100)), SelectionOptions(budgetTokens = 0))
        assertTrue(selection.slots.isEmpty())
        assertEquals(ImpasseState.BUDGET, selection.impasse)
        assertEquals(IgnoredReason.BUDGET, selection.ignored.single().reason)
    }

    @Test
    fun `全部低于阈值时返回 STUCK`() {
        val selection = selectFocus(
            listOf(attentionCandidate(voi = 0.01, goalFit = 0.1)),
            SelectionOptions(budgetTokens = 100),
        )
        assertTrue(selection.slots.isEmpty())
        assertEquals(ImpasseState.STUCK, selection.impasse)
        assertEquals(IgnoredReason.LOW_VOI, selection.ignored.single().reason)
    }

    @Test
    fun `注意窗口移动、边界与 pinned 保持可见`() {
        val pinnedItem = AttentionItem(attentionRef("c.pin"), order = 0, weight = 0.5, pinned = true)
        val items = (1..4).map { index -> AttentionItem(attentionRef("c.$index"), order = index, weight = 0.5) }
        val attention = Attention(
            AttentionOptions(
                subject = AttentionSubject(pid = 7),
                capacity = AttentionCapacity(slots = 3, tokens = 1_000),
                items = listOf(pinnedItem) + items,
            ),
        )

        assertEquals(listOf("c.pin", "c.1", "c.2"), attention.focus().map { it.target.id })

        val shift = attention.shift(AttentionShift("act.1", ShiftDirection.RIGHT, "向右移动", steps = 2))
        assertTrue(shift.changed)
        assertFalse(shift.blocked)
        assertEquals(2, attention.cursor)
        assertEquals(listOf("c.pin", "c.3", "c.4"), attention.focus().map { it.target.id })

        val blocked = attention.shift(AttentionShift("act.2", ShiftDirection.RIGHT, "再移动"))
        assertFalse(blocked.changed)
        assertTrue(blocked.blocked)
        assertEquals(2, attention.cursor)
        assertTrue(blocked.reason.contains("blocked at right"))
    }

    @Test
    fun `窗口小于条目数时其余进入 ignored`() {
        val items = (1..3).map { index -> AttentionItem(attentionRef("c.$index"), order = index, weight = 0.5) }
        val attention = Attention(
            AttentionOptions(AttentionSubject(pid = 1), AttentionCapacity(slots = 2, tokens = 100), items),
        )
        assertEquals(2, attention.focus().size)
        assertEquals(listOf("c.3"), attention.snapshot().ignored.map { it.target.id })
    }

    @Test
    fun `力场更新权重且 distraction 需要竞争对象`() {
        val attention = Attention(
            AttentionOptions(AttentionSubject(pid = 7), AttentionCapacity(slots = 2, tokens = 1_000)),
        )
        val a = attentionRef("c.a")
        val b = attentionRef("c.b")
        val from = attentionRef("a.alpha", RefKind.AGENT)

        attention.observe(AttentionForce(AttentionForceKind.ATTRACTION, from, a, strength = 0.5))
        assertEquals(0.5, attention.focus().single().weight, 0.0)

        attention.observe(
            AttentionForce(AttentionForceKind.DISTRACTION, from, b, competesWith = a, strength = 0.4),
        )
        val byId = attention.focus().associateBy { it.target.id }
        assertEquals(0.3, byId.getValue("c.a").weight, 1e-9)
        assertEquals(0.4, byId.getValue("c.b").weight, 1e-9)
        assertEquals(2, attention.snapshot().forces.size)

        assertThrows(IllegalArgumentException::class.java) {
            attention.observe(AttentionForce(AttentionForceKind.DISTRACTION, from, a, strength = 0.2))
        }

        attention.close()
        assertThrows(IllegalStateException::class.java) {
            attention.observe(AttentionForce(AttentionForceKind.ATTRACTION, from, a, strength = 0.2))
        }
    }

    @Test
    fun `移动被建模为 ActionPlan 而不是新执行器`() {
        val plan = toShiftAction(ShiftActionInput(ShiftDirection.LEFT, "回到上一个焦点", steps = 2))
        assertEquals("move the attention window", plan.goal)
        assertEquals("attention.shift", plan.intent)
        assertEquals("attention cursor moves left by 2", plan.expectedEffect)
        assertEquals(ActionRisk.NONE, plan.risk)
    }
}
