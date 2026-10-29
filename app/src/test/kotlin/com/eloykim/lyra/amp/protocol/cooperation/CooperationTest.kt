package com.eloykim.lyra.amp.protocol.cooperation

import com.eloykim.lyra.amp.protocol.protocol.Lease
import com.eloykim.lyra.amp.protocol.protocol.LeaseStatus
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `src/cooperation/{board,merge,metrics}.ts` 的行为锁定。
 */
class CooperationTest {

    private val digest: String = "sha256:" + "a".repeat(64)
    private val deadline: String = "2026-10-24T11:00:00Z"

    private fun lease(id: String, holder: String, taskId: String = "t1", fence: Int = 1): Lease = Lease(
        id = id,
        delegation = "dl.1",
        taskId = taskId,
        holder = holder,
        fence = fence,
        goalDigest = digest,
        budget = Budget(tokens = 100, calls = 1),
        deadline = deadline,
        status = LeaseStatus.ACTIVE,
    )

    // ------------------------------------------------------------------
    // board
    // ------------------------------------------------------------------

    @Test
    fun `任务板同一目标最多一个未完成任务`() {
        val board = TaskBoard()
        val task = board.add(digest)
        assertEquals("tb.1", task.id)
        assertEquals(TaskState.PENDING, task.state)
        assertEquals(0, task.attempts)
        assertEquals(BoardStats(added = 1, rejected = 0, open = 1), board.stats())

        try {
            board.add(digest)
            throw AssertionError("expected DuplicateGoal")
        } catch (error: DuplicateGoal) {
            assertEquals("duplicate_goal", error.code)
            assertEquals("goal already has task tb.1", error.message)
        }
        assertEquals(BoardStats(added = 2, rejected = 1, open = 1), board.stats())

        val claimed = board.claim(task.id, lease("ls.1", "a.worker"))
        assertEquals(TaskState.LEASED, claimed.state)
        assertEquals("a.worker", claimed.owner)
        assertEquals("ls.1", claimed.lease)
        assertEquals(1, claimed.attempts)

        board.claim(task.id, lease("ls.1", "a.worker"))
        assertEquals(2, board.get(task.id).attempts)

        try {
            board.claim(task.id, lease("ls.2", "a.other"))
            throw AssertionError("expected IllegalStateException")
        } catch (error: IllegalStateException) {
            assertEquals("task tb.1 is already leased to a.worker", error.message)
        }
    }

    @Test
    fun `完成或取消释放目标`() {
        val board = TaskBoard()
        val first = board.add(digest)
        board.claim(first.id, lease("ls.1", "a.worker"))
        val done = board.complete(first.id)
        assertEquals(TaskState.DONE, done.state)
        assertEquals(0, board.stats().open)

        val second = board.add(digest)
        assertEquals("tb.2", second.id)
        assertEquals(2, board.stats().added)
        assertEquals(0, board.stats().rejected)

        try {
            board.claim(first.id, lease("ls.2", "a.worker"))
            throw AssertionError("expected IllegalStateException")
        } catch (error: IllegalStateException) {
            assertEquals("task tb.1 is done", error.message)
        }

        board.cancel(second.id)
        assertEquals(TaskState.CANCELLED, board.get(second.id).state)
        assertEquals(0, board.stats().open)
        assertTrue(board.list().map { it.state }.contains(TaskState.CANCELLED))
    }

    @Test
    fun `未知任务与非法的完成状态`() {
        val board = TaskBoard()
        try {
            board.get("tb.9")
            throw AssertionError("expected IllegalStateException")
        } catch (error: IllegalStateException) {
            assertEquals("unknown task tb.9", error.message)
        }

        val task = board.add(digest)
        board.cancel(task.id)
        // 参考实现允许对已取消任务再次 complete（只是改状态并再次移除目标）。
        assertEquals(TaskState.DONE, board.complete(task.id).state)
    }

    @Test
    fun `任务与统计 JSON 往返`() {
        val board = TaskBoard()
        val task = board.add(digest)
        board.claim(task.id, lease("ls.1", "a.worker"))
        assertEquals(task, BoardTask.fromJson(task.toJson()))
        assertEquals(board.stats(), BoardStats.fromJson(board.stats().toJson()))
        assertFalse(task.toJson().has("owner").not())
    }

    // ------------------------------------------------------------------
    // merge
    // ------------------------------------------------------------------

    private fun claim(
        id: String,
        value: String = "deploy",
        tier: Tier = Tier.T3,
        agent: String = "a.1",
        origins: List<String> = listOf("o1"),
        replayable: Boolean = true,
        subject: String = "deploy.cause",
    ): MergeClaim = MergeClaim(
        id = id,
        subject = subject,
        value = value,
        tier = tier,
        agent = agent,
        origins = origins,
        replayable = replayable,
    )

    @Test
    fun `转手时层级只在可重放时保留`() {
        val original = claim("cl.1", tier = Tier.T3)
        assertEquals(Tier.T3, transferTier(original, "a.1"))
        assertEquals(Tier.T3, transferTier(original, "a.2"))
        assertEquals(Tier.T2, transferTier(original.copy(replayable = false), "a.2"))
        assertEquals(Tier.T0, transferTier(original.copy(tier = Tier.T0, replayable = false), "a.2"))
    }

    @Test
    fun `一致的多数按独立来源封顶`() {
        val result = mergeResults(
            listOf(
                claim("cl.1", tier = Tier.T3, agent = "a.1", origins = listOf("o1")),
                claim("cl.2", tier = Tier.T2, agent = "a.2", origins = listOf("o2")),
            ),
            MergeOptions(forAgent = "a.2"),
        )
        assertEquals(1, result.merged.size)
        val merged = result.merged.single()
        assertEquals("deploy", merged.value)
        assertEquals(Tier.T3, merged.tier)
        assertEquals(listOf("a.1", "a.2"), merged.supporters)
        assertEquals(listOf("o1", "o2"), merged.origins)
        assertEquals(MergedStatus.HELD, merged.status)
        assertTrue(result.conflicts.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `单一智能体是 single_agent 且非重放会降级并告警`() {
        val result = mergeResults(
            listOf(claim("cl.1", tier = Tier.T2, agent = "a.1", origins = listOf("o1"), replayable = false)),
            MergeOptions(forAgent = "a.2"),
        )
        val merged = result.merged.single()
        assertEquals(Tier.T1, merged.tier)
        assertEquals(MergedStatus.SINGLE_AGENT, merged.status)
        assertEquals(listOf("cl.1: tier degraded T2 → T1 on transfer"), result.warnings)
    }

    @Test
    fun `冲突保留两个值并标记 contested`() {
        val result = mergeResults(
            listOf(
                claim("cl.1", value = "deploy", agent = "a.1", origins = listOf("o1")),
                claim("cl.2", value = "load", agent = "a.2", origins = listOf("o2")),
            ),
            MergeOptions(forAgent = "a.1"),
        )
        assertEquals(listOf("deploy.cause"), result.conflicts)
        assertEquals(2, result.merged.size)
        result.merged.forEach { item ->
            assertEquals(MergedStatus.CONTESTED, item.status)
            assertEquals(Tier.T2, item.tier)
            assertEquals(1, item.supporters.size)
        }
        assertEquals(listOf("deploy", "load"), result.merged.map { it.value })
    }

    @Test
    fun `仲裁先看层级再看权威`() {
        val highTier = MergedClaim("s", "a", Tier.T3, listOf("a.1"), listOf("o1"), MergedStatus.CONTESTED)
        val lowTier = MergedClaim("s", "b", Tier.T2, listOf("a.2"), listOf("o2"), MergedStatus.CONTESTED)
        val tierVerdict = adjudicate(listOf(lowTier, highTier), emptyMap())
        assertEquals(AdjudicationDecision.ACCEPT, tierVerdict.decision)
        assertEquals("a", tierVerdict.value)
        assertEquals("higher evidence tier", tierVerdict.reason)

        val sameTier = lowTier.copy(tier = Tier.T2)
        val authorityVerdict = adjudicate(
            listOf(highTier.copy(tier = Tier.T2), sameTier),
            mapOf("a.1" to 0.4, "a.2" to 0.8),
        )
        assertEquals(AdjudicationDecision.ACCEPT, authorityVerdict.decision)
        assertEquals("b", authorityVerdict.value)
        assertEquals("higher authority", authorityVerdict.reason)

        val tie = adjudicate(
            listOf(highTier.copy(tier = Tier.T2), sameTier),
            mapOf("a.1" to 0.5, "a.2" to 0.5),
        )
        assertEquals(AdjudicationDecision.ESCALATE, tie.decision)
        assertNull(tie.value)
        assertEquals("no side dominates; escalate to a named authority", tie.reason)

        val single = adjudicate(listOf(highTier), emptyMap())
        assertEquals(AdjudicationDecision.ACCEPT, single.decision)
        assertEquals("single value", single.reason)
        assertEquals("a", single.value)
    }

    @Test
    fun `合并结果 JSON 往返`() {
        val result = mergeResults(
            listOf(
                claim("cl.1", value = "deploy"),
                claim("cl.2", value = "load", agent = "a.2", origins = listOf("o2")),
            ),
            MergeOptions(forAgent = "a.2"),
        )
        assertEquals(result, MergeResult.fromJson(result.toJson()))
        assertEquals(AdjudicationDecision.ACCEPT, Adjudication.fromJson(
            Adjudication(AdjudicationDecision.ACCEPT, "v", "because").toJson(),
        ).decision)
        assertEquals(claim("cl.1"), MergeClaim.fromJson(claim("cl.1").toJson()))
    }

    // ------------------------------------------------------------------
    // metrics
    // ------------------------------------------------------------------

    @Test
    fun `协调指标全部来自任务板与快照`() {
        val board = TaskBoard()
        val first = board.add(digest)
        val second = board.add("sha256:" + "b".repeat(64))
        try {
            board.add(digest)
        } catch (_: DuplicateGoal) {
            // 计入 rejected
        }
        board.complete(first.id)
        assertTrue(board.stats().open == 1)
        assertEquals(TaskState.PENDING, board.get(second.id).state)

        val snapshot = UnifiedSnapshot(
            tokens = 100,
            calls = 10,
            ms = 500,
            interruptsGranted = 2,
            debtScore = 0,
        )
        val merge = MergeResult(
            merged = listOf(
                MergedClaim("s1", "v1", Tier.T3, listOf("a.1"), listOf("o1"), MergedStatus.HELD),
                MergedClaim("s2", "v2", Tier.T2, listOf("a.2"), listOf("o2"), MergedStatus.SINGLE_AGENT),
            ),
            conflicts = listOf("s1"),
            warnings = emptyList(),
        )
        val metrics = coordinationMetrics(CoordinationInput(board, snapshot, merge))
        assertEquals(0.333, metrics.duplicateTaskRate, 0.0)
        assertEquals(0.167, metrics.alignmentTokenShare, 0.0)
        assertEquals(100.0, metrics.costPerSuccess, 0.0)
        assertEquals(0.5, metrics.contestedRate, 0.0)
        assertEquals(metrics, CoordinationMetrics.fromJson(metrics.toJson()))
    }

    @Test
    fun `空输入不产生除零`() {
        val metrics = coordinationMetrics(
            CoordinationInput(
                board = TaskBoard(),
                snapshot = UnifiedSnapshot(0, 0, 0, 0, 0),
            ),
        )
        assertEquals(0.0, metrics.duplicateTaskRate, 0.0)
        assertEquals(0.0, metrics.alignmentTokenShare, 0.0)
        assertEquals(0.0, metrics.costPerSuccess, 0.0)
        assertEquals(0.0, metrics.contestedRate, 0.0)
    }
}
