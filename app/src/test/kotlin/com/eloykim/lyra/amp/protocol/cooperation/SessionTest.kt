package com.eloykim.lyra.amp.protocol.cooperation

import com.eloykim.lyra.amp.protocol.delegate.AttenuationError
import com.eloykim.lyra.amp.protocol.delegate.BudgetError
import com.eloykim.lyra.amp.protocol.delegate.DelegationInput
import com.eloykim.lyra.amp.protocol.delegate.DuplicateTask
import com.eloykim.lyra.amp.protocol.delegate.FencingError
import com.eloykim.lyra.amp.protocol.delegate.TeamManifest
import com.eloykim.lyra.amp.protocol.delegate.TeamMember
import com.eloykim.lyra.amp.protocol.delegate.TeamRuntime
import com.eloykim.lyra.amp.protocol.delegate.TeamTermination
import com.eloykim.lyra.amp.protocol.protocol.LeaseStatus
import com.eloykim.lyra.amp.protocol.types.Budget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `src/cooperation/session.ts` 的行为锁定：任务板、租约、共享预算与指标对齐。
 *
 * 团队运行时本身由兄弟端口 `delegate/Team.kt` 承担，这里验证会话是否把它正确
 * 接线（而不是重复测试委托语义）。
 */
class SessionTest {

    private val at: String = "2026-10-24T10:00:00Z"
    private val digest: String = "sha256:" + "a".repeat(64)

    private fun manifest(maxFanout: Int = 3): TeamManifest = TeamManifest(
        id = "team.incident",
        version = 1,
        members = listOf(
            TeamMember(
                id = "a.lead",
                role = "coordinator",
                authority = listOf("tool.logs.query"),
                capabilities = listOf("plan", "merge"),
            ),
            TeamMember(
                id = "a.worker",
                role = "investigator",
                authority = listOf("tool.logs.query"),
            ),
        ),
        maxDepth = 1,
        maxFanout = maxFanout,
        budget = Budget(tokens = 12_000, calls = 24, ms = 600_000),
        termination = TeamTermination.VERIFIED,
        attestedAt = at,
    )

    private fun session(
        manifest: TeamManifest? = manifest(),
        team: TeamRuntime? = null,
    ): TeamSession = TeamSession(
        TeamSessionOptions(
            budget = Budget(tokens = 10_000, calls = 10, ms = 1_000_000),
            now = { at },
            manifest = manifest,
        ),
        team,
    )

    private fun delegation(
        scope: List<String> = listOf("tool.logs.query"),
        delegatee: String = "a.worker",
    ): DelegationInput = DelegationInput(
        granter = "a.lead",
        delegatee = delegatee,
        goalDigest = digest,
        scope = scope,
        budget = Budget(tokens = 1_000, calls = 2),
        deadline = "2026-10-24T11:00:00Z",
        mayRedelegate = false,
        depth = 0,
    )

    private fun startInput(): StartInput = StartInput(
        taskId = "t1",
        holder = "a.worker",
        goalDigest = digest,
        budget = Budget(tokens = 500, calls = 1),
        deadline = "2026-10-24T10:30:00Z",
    )

    @Test
    fun `start 先上任务板再签发租约`() {
        val session = session()
        val delegation = session.delegate(delegation())
        assertEquals("dl.1", delegation.id)

        val started = session.start(delegation.id, startInput())
        assertEquals("ls.2", started.lease.id)
        assertEquals("dl.1", started.lease.delegation)
        assertEquals(LeaseStatus.ACTIVE, started.lease.status)
        assertEquals(1, started.lease.fence)
        assertEquals("a.worker", started.lease.holder)
        assertEquals("tb.1", started.task.id)
        assertEquals(TaskState.LEASED, started.task.state)
        assertEquals("a.worker", started.task.owner)
        assertEquals(started.lease.id, started.task.lease)
        assertEquals(BoardStats(added = 1, rejected = 0, open = 1), session.board.stats())
    }

    @Test
    fun `spend 预留 扣账 结算三步且不会透支`() {
        val session = session()
        val delegation = session.delegate(delegation())
        val started = session.start(delegation.id, startInput())

        val result = session.spend(started.lease.id, Budget(tokens = 100, calls = 1)) { "ok" }
        assertEquals("ok", result)
        assertEquals(100, session.snapshot().tokens)
        assertEquals(1, session.snapshot().calls)
        assertEquals(100, session.team.usage(started.lease.id).spent.tokens)
        assertEquals(1, session.team.usage(started.lease.id).spent.calls)
        assertNull(session.team.usage(started.lease.id).reserved.tokens)

        var ran = false
        try {
            session.spend(started.lease.id, Budget(tokens = 5_000)) {
                ran = true
                "nope"
            }
            throw AssertionError("expected BudgetError")
        } catch (error: BudgetError) {
            assertEquals("budget_exceeded", error.code)
        }
        assertFalse(ran)
        assertEquals(100, session.snapshot().tokens)
    }

    @Test
    fun `finish 结算租约与任务并产出指标`() {
        val session = session()
        val delegation = session.delegate(delegation())
        val started = session.start(delegation.id, startInput())
        session.spend(started.lease.id, Budget(tokens = 100, calls = 1)) { Unit }

        val finished = session.finish(started.lease.id)
        assertEquals(LeaseStatus.DONE, finished.lease.status)
        assertEquals(TaskState.DONE, finished.task.state)
        assertEquals(0, session.board.stats().open)

        val metrics = session.metrics()
        assertEquals(0.0, metrics.duplicateTaskRate, 0.0)
        assertEquals(0.0, metrics.alignmentTokenShare, 0.0)
        assertEquals(100.0, metrics.costPerSuccess, 0.0)
        assertEquals(0.0, metrics.contestedRate, 0.0)

        try {
            session.finish(started.lease.id)
            throw AssertionError("expected FencingError")
        } catch (_: FencingError) {
            // 已结束的租约不能再次完成。
        }
    }

    @Test
    fun `重复目标不消耗第二个租约`() {
        val session = session()
        val delegation = session.delegate(delegation())
        session.start(delegation.id, startInput())

        try {
            session.start(delegation.id, startInput())
            throw AssertionError("expected DuplicateGoal")
        } catch (error: DuplicateGoal) {
            assertEquals("duplicate_goal", error.code)
        }
        assertEquals(1, session.board.list().size)
        assertNull(session.team.lease("ls.3"))
        assertEquals(1, session.board.stats().open)
    }

    @Test
    fun `cancel 同时结束租约与任务`() {
        val session = session()
        val delegation = session.delegate(delegation())
        val started = session.start(delegation.id, startInput())

        val cancelled = session.cancel(started.lease.id)
        assertEquals(LeaseStatus.CANCELLED, cancelled.lease.status)
        assertEquals(TaskState.CANCELLED, cancelled.task!!.state)
        assertEquals(0, session.board.stats().open)
    }

    @Test
    fun `签发失败会取消刚建立的任务板任务`() {
        val session = session()
        try {
            session.start("dl.404", startInput())
            throw AssertionError("expected AttenuationError")
        } catch (error: AttenuationError) {
            assertEquals("delegation_widened", error.code)
            assertTrue(error.message!!.contains("unknown delegation dl.404"))
        }
        assertEquals("tb.1", session.board.list().single().id)
        assertEquals(TaskState.CANCELLED, session.board.list().single().state)
        assertEquals(0, session.board.stats().open)
    }

    @Test
    fun `清单约束在委托创建之前生效`() {
        val session = session()
        try {
            session.delegate(delegation(scope = listOf("tool.metrics.query")))
            throw AssertionError("expected AttenuationError")
        } catch (error: AttenuationError) {
            assertTrue(error.message!!.contains("scope tool.metrics.query exceeds a.lead authority"))
        }

        try {
            session.delegate(delegation(delegatee = "a.stranger"))
            throw AssertionError("expected AttenuationError")
        } catch (error: AttenuationError) {
            assertTrue(error.message!!.contains("delegatee a.stranger is not a team member"))
        }

        val narrow = session(manifest(maxFanout = 1))
        narrow.delegate(delegation())
        try {
            narrow.delegate(delegation())
            throw AssertionError("expected AttenuationError")
        } catch (error: AttenuationError) {
            assertTrue(error.message!!.contains("fan-out limit 1 reached for a.lead"))
        }
    }

    @Test
    fun `租约是单写者且可以注入运行时`() {
        val runtime = TeamRuntime({ at })
        val session = session(team = runtime)
        assertTrue(session.team === runtime)
        val delegation = session.delegate(delegation())
        val started = session.start(delegation.id, startInput())

        session.team.assertWriter(started.lease.id, 1)
        try {
            session.team.assertWriter(started.lease.id, 2)
            throw AssertionError("expected FencingError")
        } catch (error: FencingError) {
            assertEquals("stale_writer", error.code)
            assertEquals("stale writer: fence 2 != 1", error.message)
        }
    }

    @Test
    fun `未知租约与过期目标不会伪造状态`() {
        val session = session()
        try {
            session.finish("ls.missing")
            throw AssertionError("expected FencingError")
        } catch (error: FencingError) {
            assertEquals("unknown lease ls.missing", error.message)
        }
        try {
            session.cancel("ls.missing")
            throw AssertionError("expected FencingError")
        } catch (_: FencingError) {
            // expected
        }
    }

    @Test
    fun `同一目标的活动租约由运行时拒绝重复签发`() {
        val session = session()
        val delegation = session.delegate(delegation())
        session.start(delegation.id, startInput())
        try {
            session.team.issueLease(
                delegation.id,
                com.eloykim.lyra.amp.protocol.delegate.LeaseGrant(
                    taskId = "t2",
                    holder = "a.worker",
                    goalDigest = digest,
                    budget = Budget(tokens = 10),
                    deadline = "2026-10-24T10:30:00Z",
                ),
            )
            throw AssertionError("expected DuplicateTask")
        } catch (error: DuplicateTask) {
            assertEquals("duplicate_goal", error.code)
            assertTrue(error.message!!.contains("goal already has an active lease: ls.2"))
        }
    }
}
