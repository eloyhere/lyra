package com.eloykim.lyra.amp.protocol.goal

import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Criterion
import com.eloykim.lyra.amp.protocol.types.CriterionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goal 线格式与准入状态机。
 */
class GoalLifecycleTest {

    @Test
    fun `目标线格式通过 schema 且可往返`() {
        val goal = goal(horizon = GoalHorizon.SESSION, deadline = AT)
        val json = goal.toJson()
        assertEquals(emptyList<String>(), AmpSchemas.validate("plan.schema.json", json))
        assertEquals("goal", json.string("kind"))
        assertEquals("metrics.query", (json.requireArr("success").obj(0))?.string("check"))
        assertEquals(CanonicalJson.encode(json), CanonicalJson.encode(Goal.fromJson(json).toJson()))
    }

    @Test
    fun `目标必须至少有一个成功判据`() {
        assertThrows(IllegalArgumentException::class.java) {
            goal(success = emptyList())
        }
    }

    @Test
    fun `可选的 deadline 与 horizon 缺失时不写成 null`() {
        val json = goal().toJson()
        assertTrue(!json.has("deadline"))
        assertTrue(!json.has("horizon"))
        assertEquals(emptyList<String>(), AmpSchemas.validate("plan.schema.json", json))
    }

    @Test
    fun `生命周期从 proposed 开始并按边迁移`() {
        val goal = goal()
        var lifecycle = createGoalLifecycle(goal)
        assertEquals(GoalState.PROPOSED, lifecycle.state)
        assertNull(lifecycle.startedAt)

        lifecycle = transitionGoal(lifecycle, GoalState.COMMITTED, AT)
        assertEquals(GoalState.COMMITTED, lifecycle.state)
        assertNull(lifecycle.startedAt)

        lifecycle = transitionGoal(lifecycle, GoalState.ACTIVE, AT)
        assertEquals(AT, lifecycle.startedAt)

        lifecycle = transitionGoal(lifecycle, GoalState.ACHIEVED, LATER)
        assertEquals(GoalState.ACHIEVED, lifecycle.state)
        assertEquals(LATER, lifecycle.achievedAt)
        assertEquals(AT, lifecycle.startedAt)
    }

    @Test
    fun `跳过 committed 直接 active 被拒绝`() {
        val lifecycle = createGoalLifecycle(goal())
        assertThrows(IllegalArgumentException::class.java) {
            transitionGoal(lifecycle, GoalState.ACTIVE, AT)
        }
    }

    @Test
    fun `终态不能再迁移`() {
        var lifecycle = createGoalLifecycle(goal())
        lifecycle = transitionGoal(lifecycle, GoalState.ABANDONED, AT)
        assertTrue(goalEdgesFrom(GoalState.ABANDONED).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            transitionGoal(lifecycle, GoalState.COMMITTED, AT)
        }
    }

    @Test
    fun `生命周期可往返`() {
        var lifecycle = createGoalLifecycle(goal())
        lifecycle = transitionGoal(lifecycle, GoalState.COMMITTED, AT)
        lifecycle = transitionGoal(lifecycle, GoalState.ACTIVE, AT)
        val json = lifecycle.toJson()
        assertEquals("active", json.string("state"))
        assertEquals(CanonicalJson.encode(json), CanonicalJson.encode(GoalLifecycle.fromJson(json).toJson()))
    }

    private fun goal(
        success: List<Criterion> = listOf(Criterion("g1", "错误率回到基线", "metrics.query", CriterionKind.QUERY)),
        deadline: String? = null,
        horizon: GoalHorizon? = null,
    ): Goal = Goal(
        id = "g.7",
        statement = "定位并回滚导致错误率上升的发布",
        success = success,
        value = 0.9,
        priority = 1,
        deadline = deadline,
        horizon = horizon,
    )

    private companion object {
        const val AT = "2026-10-24T01:00:00Z"
        const val LATER = "2026-10-24T02:00:00Z"
    }
}
