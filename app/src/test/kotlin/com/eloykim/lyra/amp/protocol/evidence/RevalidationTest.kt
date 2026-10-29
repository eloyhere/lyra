package com.eloykim.lyra.amp.protocol.evidence

import com.eloykim.lyra.amp.protocol.goal.Expectation
import com.eloykim.lyra.amp.protocol.goal.Plan
import com.eloykim.lyra.amp.protocol.goal.PlanStatus
import com.eloykim.lyra.amp.protocol.goal.PlanTask
import com.eloykim.lyra.amp.protocol.goal.TaskActor
import com.eloykim.lyra.amp.protocol.goal.TaskStatus
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Criterion
import com.eloykim.lyra.amp.protocol.types.Provenance
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.Trust
import com.eloykim.lyra.amp.protocol.warrant.Claim
import com.eloykim.lyra.amp.protocol.warrant.ClaimStatus
import com.eloykim.lyra.amp.protocol.warrant.EvidenceItem
import com.eloykim.lyra.amp.protocol.warrant.EvidenceKind as WarrantEvidenceKind
import com.eloykim.lyra.amp.protocol.warrant.EvidenceRelation
import com.eloykim.lyra.amp.protocol.warrant.Inference
import com.eloykim.lyra.amp.protocol.warrant.Warrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Warrant 依赖投影：变化传播的局部性、宿主复核竞态、指纹与 fail-closed。
 */
class RevalidationTest {

    @Test
    fun `来源变化只触达派生主张与计划，独立分支保持新鲜`() {
        val a = claim(
            "a",
            listOf(evidence("shared", origin = "measurement"), evidence("backup", origin = "backup")),
        )
        val b = claim("b", listOf(evidence("shared", ref = Ref(RefKind.CLAIM, "a"))))
        val c = claim("c", listOf(evidence("shared", origin = "other")))
        val index = WarrantRevalidation(
            listOf(a, b, c),
            listOf(plan("work", "b"), plan("unrelated", "c")),
        )

        assertEquals(
            RevalidationImpact(claims = listOf("a", "b"), plans = listOf("work")),
            index.invalidate(listOf(ORIGIN)),
        )
        assertFalse(index.isCurrent(a))
        assertTrue(index.isCurrent(c))
        // 投影不改写输入主张。
        assertEquals(ClaimStatus.HELD, a.status)
        assertEquals(2, a.warrant.evidence.size)

        val downstream = index.beginReview("b")
        assertFalse(index.completeReview(downstream)) // 前件 a 尚未复核
        assertTrue(index.completeReview(index.beginReview("a")))
        assertTrue(index.completeReview(downstream))
        assertFalse(index.completeReview(downstream)) // 令牌只能消费一次
        assertTrue(index.isCurrent(a))
        assertTrue(index.isCurrent(b))
    }

    @Test
    fun `第二次来源变化推进 generation，重复通知也会失效在途复核`() {
        val a = claim("a", listOf(evidence("e", origin = "measurement")))
        val index = WarrantRevalidation(listOf(a))
        index.invalidate(listOf(ORIGIN))
        val stale = index.beginReview("a")
        index.invalidate(listOf(ORIGIN, ORIGIN))

        assertFalse(index.completeReview(stale))
        // 字段相同但对象不同的伪造令牌不能清掉 pending。
        assertFalse(index.completeReview(ReviewToken("a", stale.generation)))
        assertTrue(index.completeReview(index.beginReview("a")))
        assertFalse(index.needsRevalidation("a"))
    }

    @Test
    fun `未知变化批次整体失败，不会部分标脏`() {
        val a = claim("a", listOf(evidence("e", origin = "measurement")))
        val index = WarrantRevalidation(listOf(a))
        val error = assertThrows(IllegalArgumentException::class.java) {
            index.invalidate(listOf(ORIGIN, WarrantSource.OriginSource("unknown")))
        }
        assertTrue(error.message!!.contains("unknown"))
        assertTrue(index.isCurrent(a))
        assertTrue(index.needsRevalidation("missing"))
        assertThrows(IllegalArgumentException::class.java) { index.beginReview("missing") }
    }

    @Test
    fun `反驳与削弱同样触发复核，ref 保留命名空间`() {
        val a = claim(
            "a",
            listOf(evidence("e", ref = Ref(RefKind.CLAIM, "b"), relation = EvidenceRelation.CONTRADICTS)),
        )
        val b = claim(
            "b",
            listOf(evidence("e", ref = Ref(RefKind.TOOL, "b"), relation = EvidenceRelation.UNDERCUTS)),
        )
        val index = WarrantRevalidation(listOf(a, b))

        assertEquals(
            listOf("a"),
            index.invalidate(listOf(WarrantSource.EvidenceSource("a", "e"))).claims,
        )
        assertTrue(index.isCurrent(b))
        // flag:tool 的 ref 先到 b 的证据，再经 b 的证据到达 a。
        assertEquals(
            listOf("b", "a"),
            index.invalidate(listOf(WarrantSource.RefSource(Ref(RefKind.TOOL, "b")))).claims,
        )
    }

    @Test
    fun `缺失或非 held 的前件阻止依赖复核完成`() {
        val a = claim("a", listOf(evidence("e", ref = Ref(RefKind.CLAIM, "missing"))))
        val index = WarrantRevalidation(listOf(a))
        assertFalse(index.isCurrent(a))
        assertFalse(index.completeReview(index.beginReview("a")))

        val retracted = claim("missing", status = ClaimStatus.RETRACTED)
        val other = WarrantRevalidation(listOf(a, retracted))
        assertTrue(other.completeReview(other.beginReview("missing")))
        assertFalse(other.completeReview(other.beginReview("a")))
    }

    @Test
    fun `supersedes 是历史不是支撑边，重复身份与环被拒绝`() {
        val a = claim("a")
        val b = claim("b").copy(supersedes = Ref(RefKind.CLAIM, "a"))
        val index = WarrantRevalidation(listOf(a, b))
        assertEquals(
            listOf("a"),
            index.invalidate(listOf(WarrantSource.RefSource(Ref(RefKind.CLAIM, "a")))).claims,
        )
        assertTrue(index.isCurrent(b))

        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { WarrantRevalidation(listOf(a, a)) }
                .message!!.contains("duplicate"),
        )
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                WarrantRevalidation(listOf(a), listOf(plan("p", "a"), plan("p", "a")))
            }.message!!.contains("duplicate"),
        )

        val cyclicA = claim("a", listOf(evidence("e", ref = Ref(RefKind.CLAIM, "b"))))
        val cyclicB = claim("b", listOf(evidence("e", ref = Ref(RefKind.CLAIM, "a"))))
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { WarrantRevalidation(listOf(cyclicA, cyclicB)) }
                .message!!.contains("cycle"),
        )
    }

    @Test
    fun `指纹与 pending 共同决定新鲜度：独立分支初始新鲜，经过复核后仍校验本体`() {
        val a = claim("a", listOf(evidence("e", origin = "measurement")))
        val index = WarrantRevalidation(listOf(a))
        assertTrue(index.isCurrent(a)) // held 且无变化

        index.invalidate(listOf(ORIGIN))
        assertTrue(index.completeReview(index.beginReview("a")))
        assertTrue(index.isCurrent(a))
        assertFalse(index.isCurrent(a.copy(statement = "different")))
        assertFalse(index.isCurrent(claim("unknown")))
        assertFalse(index.isCurrent(a.copy(status = ClaimStatus.DOUBTED)))
    }

    private fun claim(
        id: String,
        items: List<EvidenceItem> = emptyList(),
        status: ClaimStatus = ClaimStatus.HELD,
    ): Claim = Claim(
        id = id,
        statement = id,
        tier = Tier.T2,
        status = status,
        warrant = Warrant(evidence = items, inference = Inference.DEDUCTION, strength = 0.8),
        provenance = Provenance(source = "revalidation-check", trust = Trust.SELF),
        created = AT,
    )

    private fun evidence(
        id: String,
        origin: String? = null,
        ref: Ref? = null,
        relation: EvidenceRelation? = null,
    ): EvidenceItem = EvidenceItem(
        id = id,
        kind = WarrantEvidenceKind.DOCUMENT,
        trust = Trust.VERIFIED,
        independence = 1.0,
        relation = relation,
        origin = origin,
        ref = ref,
        at = AT,
    )

    private fun plan(id: String, warrant: String): Plan = Plan(
        id = id,
        goal = Ref(RefKind.GOAL, "goal"),
        tasks = listOf(
            PlanTask(
                id = "$id.t1",
                intent = "do",
                actor = TaskActor.SELF,
                expect = Expectation(observation = "observed", check = "check"),
                irreversibility = 0,
                status = TaskStatus.PENDING,
            ),
        ),
        edges = emptyList(),
        commitmentWindow = 1,
        budget = Budget(),
        abandonIf = listOf(Criterion(id = "c.abandon", statement = "stop", check = "check")),
        status = PlanStatus.DRAFT,
        created = AT,
        warrant = Ref(RefKind.CLAIM, warrant),
    )

    private companion object {
        const val AT: String = "2026-10-29T00:00:00Z"
        val ORIGIN: WarrantSource = WarrantSource.OriginSource("measurement")
    }
}
