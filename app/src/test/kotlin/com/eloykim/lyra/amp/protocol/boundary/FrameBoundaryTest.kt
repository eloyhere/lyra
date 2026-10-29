package com.eloykim.lyra.amp.protocol.boundary

import com.eloykim.lyra.amp.protocol.frame.FrameBudget
import com.eloykim.lyra.amp.protocol.frame.FrameMode
import com.eloykim.lyra.amp.protocol.frame.Ignored
import com.eloykim.lyra.amp.protocol.frame.IgnoredReason
import com.eloykim.lyra.amp.protocol.frame.Impasse
import com.eloykim.lyra.amp.protocol.frame.ImpasseState
import com.eloykim.lyra.amp.protocol.frame.Slot
import com.eloykim.lyra.amp.protocol.frame.SlotRole
import com.eloykim.lyra.amp.protocol.frame.SlotState
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBoundaryTest {

    private fun refs(): RefTable = RefTable(RefScope.RUN, "a.gamma")
        .add(RefKind.TOOL, "logs.query", "tool")
        .add(RefKind.GOAL, "g.7", "goal")
        .add(RefKind.FRAME, "f.900", "frame")
        .add(RefKind.UNKNOWN, "u.17", "unknown")
        .add(RefKind.HYPOTHESIS, "h.12", "hypothesis")
        .add(RefKind.CLAIM, "cl.1", "claim")

    private fun slot(
        target: Ref = Ref(RefKind.TOOL, "logs.query"),
        role: SlotRole = SlotRole.TOOL,
        tier: Tier? = Tier.T2,
        salience: Double = 0.8,
        voi: Double? = 0.5,
        cost: Budget = Budget(tokens = 120),
        why: String = "验证错误分布",
        ttlTurns: Int? = null,
        state: SlotState = SlotState.ACTIVE,
    ): Slot = Slot(
        target = target,
        role = role,
        tier = tier,
        salience = salience,
        voi = voi,
        cost = cost,
        why = why,
        ttlTurns = ttlTurns,
        state = state,
    )

    private fun draftA(): FrameDraft = FrameDraft(
        id = "f.900",
        agent = "a.gamma",
        cycle = 7,
        ts = "2026-10-24T02:00:00Z",
        mode = FrameMode.EXPLORE,
        slots = listOf(slot()),
        budget = FrameBudget(
            allocated = Budget(tokens = 1000, calls = 2),
            spent = Budget(tokens = 0, calls = 0),
        ),
    )

    private fun draftB(): FrameDraft = FrameDraft(
        id = "f.901",
        agent = "a.gamma",
        cycle = 8,
        ts = "2026-10-24T02:05:00Z",
        parent = Ref(RefKind.FRAME, "f.900"),
        mode = FrameMode.EXPLOIT,
        goal = Ref(RefKind.GOAL, "g.7"),
        slots = listOf(
            slot(
                target = Ref(RefKind.CLAIM, "cl.1"),
                role = SlotRole.EVIDENCE,
                salience = 0.88,
                voi = 0.4,
                cost = Budget(tokens = 320),
                why = "已验证的相关性支持回滚决策",
            ),
            slot(
                target = Ref(RefKind.TOOL, "logs.query"),
                role = SlotRole.TOOL,
                tier = null,
                salience = 0.5,
                voi = null,
                cost = Budget(calls = 1),
                why = "补一次查询",
                state = SlotState.RELEASED,
            ),
        ),
        ignored = listOf(
            Ignored(Ref(RefKind.HYPOTHESIS, "h.12"), IgnoredReason.DEFERRED, "回滚后错误率未下降"),
        ),
        budget = FrameBudget(
            allocated = Budget(tokens = 4000, calls = 6),
            spent = Budget(tokens = 1520, calls = 3),
        ),
        openQuestions = listOf(Ref(RefKind.UNKNOWN, "u.17")),
        impasse = Impasse(ImpasseState.UNVERIFIED, "回滚效果需等 10 分钟窗口"),
        note = "第二轮",
    )

    @Test
    fun `frameFrom 计算与参考实现一致的 digest`() {
        val result = frameFrom(draftA(), refs())
        assertEquals(
            "sha256:8b3be91a779b5378c2fd99813e26414e40e3e745a498bf2e33e44e344e930969",
            result.frame.digest,
        )
        assertTrue(result.deferred.isEmpty())
        assertNull(result.frame.ignored)
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("frame", result.frame.toJson()))
    }

    @Test
    fun `frameFrom 在完整字段下仍与参考 digest 一致`() {
        val result = frameFrom(draftB(), refs())
        assertEquals(
            "sha256:df23dcb6faa27edc1c1da4298ed4d3c52e619ccaee6d9bf29cd09bf602a92365",
            result.frame.digest,
        )
        assertEquals(1, result.frame.ignored?.size)
        assertNotNull(result.frame.impasse)
        assertEquals("UNVERIFIED", result.frame.impasse?.state?.wire)
        assertEquals("第二轮", result.frame.note)
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("frame", result.frame.toJson()))
    }

    @Test
    fun `frameFrom 拒绝第 10 个 slot 但接受 9 个`() {
        val error = assertThrows(BoundaryRejection::class.java) {
            frameFrom(draftA().copy(slots = List(10) { slot() }), refs())
        }
        assertEquals("invalid_frame", error.code)
        assertTrue(error.problems.contains("slots has 10 entries, max is 9"))

        val nine = frameFrom(draftA().copy(slots = List(9) { slot() }), refs())
        assertEquals(9, nine.frame.slots.size)
    }

    @Test
    fun `maxSlots 只能收紧不能放宽`() {
        val tightened = assertThrows(BoundaryRejection::class.java) {
            frameFrom(
                draftA().copy(slots = List(3) { slot() }),
                refs(),
                FrameBoundaryOptions(maxSlots = 2),
            )
        }
        assertTrue(tightened.problems.contains("slots has 3 entries, max is 2"))

        val widened = assertThrows(BoundaryRejection::class.java) {
            frameFrom(draftA(), refs(), FrameBoundaryOptions(maxSlots = 10))
        }
        assertTrue(widened.problems.contains("maxSlots must be an integer in [0,9]"))
    }

    @Test
    fun `超出上限的 ignored 以 deferred 返回而不是丢弃`() {
        val many = List(55) { index ->
            Ignored(Ref(RefKind.HYPOTHESIS, "h.12"), IgnoredReason.DEFERRED, "revisit $index")
        }
        val result = frameFrom(draftA().copy(ignored = many), refs())
        assertEquals(50, result.frame.ignored?.size)
        assertEquals(5, result.deferred.size)
        assertEquals("revisit 50", result.deferred.first().revisitIf)
        assertEquals("revisit 54", result.deferred.last().revisitIf)
        assertEquals(55, (result.frame.ignored?.size ?: 0) + result.deferred.size)

        val limited = frameFrom(
            draftA().copy(ignored = many),
            refs(),
            FrameBoundaryOptions(maxIgnored = 2),
        )
        assertEquals(2, limited.frame.ignored?.size)
        assertEquals(53, limited.deferred.size)
    }

    @Test
    fun `负的 maxIgnored 被拒绝`() {
        val error = assertThrows(BoundaryRejection::class.java) {
            frameFrom(draftA(), refs(), FrameBoundaryOptions(maxIgnored = -1))
        }
        assertTrue(error.problems.contains("maxIgnored must be a non-negative integer"))
    }

    @Test
    fun `frameFrom 一次汇总全部可表示的问题`() {
        val bad = draftA().copy(
            id = "F.Bad",
            agent = "A.Bad",
            cycle = -1,
            ts = "not-a-time",
            parent = Ref(RefKind.FRAME, "f.404"),
            goal = Ref(RefKind.GOAL, "g.404"),
            slots = listOf(
                slot(target = Ref(RefKind.CLAIM, "cl.404"), why = "   "),
            ),
            ignored = listOf(
                Ignored(Ref(RefKind.HYPOTHESIS, "h.404"), IgnoredReason.DEFERRED, "  "),
            ),
            budget = FrameBudget(
                allocated = Budget(tokens = 1),
                spent = Budget(tokens = 5),
            ),
            openQuestions = listOf(Ref(RefKind.UNKNOWN, "u.404")),
        )
        val error = assertThrows(BoundaryRejection::class.java) { frameFrom(bad, refs()) }
        assertEquals("invalid_frame", error.code)
        assertEquals(12, error.problems.size)
        assertTrue(error.problems.contains("id is not a valid AMP id"))
        assertTrue(error.problems.contains("agent is not a valid AMP id"))
        assertTrue(error.problems.contains("cycle must be a non-negative integer"))
        assertTrue(error.problems.contains("ts is not a date-time"))
        assertTrue(error.problems.contains("slots[0].target claim:cl.404 is not present in scope run"))
        assertTrue(error.problems.contains("slots[0].why is empty"))
        assertTrue(error.problems.contains("ignored[0].target hypothesis:h.404 is not present in scope run"))
        assertTrue(error.problems.contains("ignored[0].revisit_if is empty"))
        assertTrue(error.problems.contains("budget.spent.tokens exceeds budget.allocated.tokens"))
        assertTrue(error.problems.contains("parent frame:f.404 is not present in scope run"))
        assertTrue(error.problems.contains("goal goal:g.404 is not present in scope run"))
        assertTrue(error.problems.contains("open_questions[0] unknown:u.404 is not present in scope run"))
    }

    @Test
    fun `frameFrom 拒绝空分配预算`() {
        val error = assertThrows(BoundaryRejection::class.java) {
            frameFrom(
                draftA().copy(
                    budget = FrameBudget(allocated = Budget(), spent = Budget()),
                ),
                refs(),
            )
        }
        assertTrue(error.problems.contains("budget.allocated must set at least one dimension"))
    }

    @Test
    fun `缺少 ts 时使用注入的时钟`() {
        val result = frameFrom(
            draftA().copy(ts = null),
            refs(),
            FrameBoundaryOptions(now = { "2026-10-24T05:00:00Z" }),
        )
        assertEquals("2026-10-24T05:00:00Z", result.frame.ts)

        val fallback = frameFrom(draftA().copy(ts = null), refs())
        assertTrue(fallback.frame.ts.endsWith("Z"))
    }
}
