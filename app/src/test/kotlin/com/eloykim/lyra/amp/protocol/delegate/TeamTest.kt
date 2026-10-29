package com.eloykim.lyra.amp.protocol.delegate

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.protocol.toDelegationDoc
import com.eloykim.lyra.amp.protocol.protocol.toLeaseDoc
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.types.Budget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamTest {

    private val digest = "sha256:" + "a".repeat(64)
    private val later = "2026-10-24T12:00:00Z"
    private val earlier = "2026-10-24T11:00:00Z"

    private fun delegationInput(
        granter: String = "a.lead",
        delegatee: String = "a.worker",
        budget: Budget = Budget(tokens = 4000, calls = 8),
        scope: List<String> = listOf("tool.logs.query"),
        depth: Int = 0,
        mayRedelegate: Boolean = false,
        deadline: String = later,
        parent: String? = null,
    ): DelegationInput = DelegationInput(
        granter = granter,
        delegatee = delegatee,
        goalDigest = digest,
        scope = scope,
        budget = budget,
        deadline = deadline,
        mayRedelegate = mayRedelegate,
        depth = depth,
        parent = parent,
    )

    private val manifest = TeamManifest(
        id = "team.incident",
        version = 1,
        members = listOf(
            TeamMember(id = "a.lead", role = "coordinator", authority = listOf("assign")),
            TeamMember(id = "a.worker", role = "investigator", authority = listOf("tool.logs.query")),
        ),
        maxDepth = 1,
        maxFanout = 2,
        budget = Budget(tokens = 12000, calls = 24, ms = 600000),
        termination = TeamTermination.VERIFIED,
        attestedAt = "2026-10-24T10:00:00Z",
    )

    private fun example(name: String): JsonValue {
        val stream = javaClass.getResourceAsStream("/amp/examples/$name")
            ?: error("缺少示例 /amp/examples/$name")
        return stream.bufferedReader(Charsets.UTF_8).use { JsonParser.parse(it.readText()) }
    }

    @Test
    fun `子预算只能收缩每个维度独立检查`() {
        val runtime = TeamRuntime { "2026-10-24T10:00:00Z" }
        val parent = runtime.createDelegation(
            delegationInput(budget = Budget(tokens = 100, ms = 1000, money = 2.0), mayRedelegate = true),
        )
        assertThrows(AttenuationError::class.java) {
            runtime.createDelegation(
                delegationInput(
                    parent = parent.id,
                    depth = 1,
                    budget = Budget(tokens = 101),
                    mayRedelegate = true,
                ),
            )
        }
        // 未超出的维度不阻塞；缺省维度不参与比较。
        val child = runtime.createDelegation(
            delegationInput(
                parent = parent.id,
                depth = 1,
                budget = Budget(tokens = 50, money = 1.0),
                mayRedelegate = true,
            ),
        )
        assertEquals(parent.id, child.parent)
        assertEquals("dl.2", child.id)
    }

    @Test
    fun `深度超过 2 与不可再委托被拒绝`() {
        val runtime = TeamRuntime { "2026-10-24T10:00:00Z" }
        assertThrows(AttenuationError::class.java) {
            runtime.createDelegation(delegationInput(depth = 3))
        }
        val parent = runtime.createDelegation(delegationInput(mayRedelegate = false))
        assertThrows(AttenuationError::class.java) {
            runtime.createDelegation(delegationInput(parent = parent.id, depth = 1))
        }
    }

    @Test
    fun `子截止时间不能超过父委托`() {
        val runtime = TeamRuntime { "2026-10-24T10:00:00Z" }
        val parent = runtime.createDelegation(
            delegationInput(deadline = earlier, mayRedelegate = true),
        )
        assertThrows(AttenuationError::class.java) {
            runtime.createDelegation(
                delegationInput(parent = parent.id, depth = 1, deadline = later),
            )
        }
    }

    @Test
    fun `同一目标只能有一个活租约`() {
        val runtime = TeamRuntime { "2026-10-24T10:00:00Z" }
        val delegation = runtime.createDelegation(delegationInput())
        val lease = runtime.issueLease(
            delegation.id,
            LeaseGrant(
                taskId = "t1",
                holder = "a.worker",
                goalDigest = digest,
                budget = Budget(tokens = 1200, calls = 2),
                deadline = earlier,
            ),
        )
        assertEquals(1, lease.fence)
        assertThrows(DuplicateTask::class.java) {
            runtime.issueLease(
                delegation.id,
                LeaseGrant("t2", "a.worker", digest, Budget(tokens = 10), earlier),
            )
        }
        runtime.complete(lease.id)
        // 目标释放后可以重新签发，fence 继续单调。
        val second = runtime.issueLease(
            delegation.id,
            LeaseGrant("t1", "a.worker", digest, Budget(tokens = 10), earlier),
        )
        assertEquals(2, second.fence)
    }

    @Test
    fun `租约是单写者且完成或取消后拒绝写入`() {
        val runtime = TeamRuntime { "2026-10-24T10:00:00Z" }
        val delegation = runtime.createDelegation(delegationInput())
        val lease = runtime.issueLease(
            delegation.id,
            LeaseGrant("t1", "a.worker", digest, Budget(tokens = 100), earlier),
        )
        runtime.assertWriter(lease.id, lease.fence)
        assertThrows(FencingError::class.java) { runtime.assertWriter(lease.id, lease.fence + 1) }

        runtime.cancel(lease.id)
        assertThrows(FencingError::class.java) { runtime.assertWriter(lease.id, lease.fence) }
        assertEquals("cancelled", runtime.lease(lease.id)?.status?.wire)
    }

    @Test
    fun `预留不能超过租约预算结算后清空预留`() {
        val runtime = TeamRuntime { "2026-10-24T10:00:00Z" }
        val delegation = runtime.createDelegation(delegationInput(budget = Budget(tokens = 100)))
        val lease = runtime.issueLease(
            delegation.id,
            LeaseGrant("t1", "a.worker", digest, Budget(tokens = 10), earlier),
        )
        runtime.reserve(lease.id, Budget(tokens = 4))
        runtime.reserve(lease.id, Budget(tokens = 5))
        assertEquals(9, runtime.usage(lease.id).reserved.tokens)
        assertThrows(BudgetError::class.java) { runtime.reserve(lease.id, Budget(tokens = 2)) }

        runtime.settle(lease.id, Budget(tokens = 7))
        assertNull(runtime.usage(lease.id).reserved.tokens)
        assertEquals(7, runtime.usage(lease.id).spent.tokens)
    }

    @Test
    fun `manifest 在委托存在之前检查成员深度 scope 预算与 fan-out`() {
        val runtime = TeamRuntime { "2026-10-24T10:00:00Z" }
        assertThrows(AttenuationError::class.java) {
            runtime.createDelegation(delegationInput(granter = "a.other"), manifest)
        }
        assertThrows(AttenuationError::class.java) {
            runtime.createDelegation(delegationInput(delegatee = "a.other"), manifest)
        }
        assertThrows(AttenuationError::class.java) {
            runtime.createDelegation(delegationInput(scope = listOf("tool.logs.query")), manifest)
        }
        assertThrows(AttenuationError::class.java) {
            runtime.createDelegation(
                delegationInput(budget = Budget(tokens = 20000), scope = listOf("assign")),
                manifest,
            )
        }
        assertThrows(AttenuationError::class.java) {
            runtime.createDelegation(delegationInput(depth = 2, scope = listOf("assign")), manifest)
        }

        val first = runtime.createDelegation(delegationInput(scope = listOf("assign")), manifest)
        assertEquals("dl.1", first.id)
        runtime.createDelegation(delegationInput(scope = listOf("assign")), manifest)
        assertThrows(AttenuationError::class.java) {
            runtime.createDelegation(delegationInput(scope = listOf("assign")), manifest)
        }
    }

    @Test
    fun `team 示例对象往返并通过 schema`() {
        val example = example("team.json") as JsonValue.Arr
        val manifestValue = TeamObject.fromJson(example.obj(0)!!)
        assertTrue(manifestValue is TeamManifest)
        val commitment = TeamObject.fromJson(example.obj(1)!!)
        assertTrue(commitment is CommitmentRecord)

        val manifestProblems = AmpSchemas.validateKind("team", manifestValue.toJson())
        assertTrue("manifest: $manifestProblems", manifestProblems.isEmpty())
        val commitmentProblems = AmpSchemas.validateKind("team", commitment.toJson())
        assertTrue("commitment: $commitmentProblems", commitmentProblems.isEmpty())

        val roundTrip = TeamManifest.fromJson(manifestValue.toJson())
        assertEquals(manifestValue, roundTrip)
        assertEquals(listOf("a.lead", "a.worker"), (commitment as CommitmentRecord).parties)
        assertEquals(CommitmentStatus.ACTIVE, commitment.status)
    }

    @Test
    fun `团队产出的 delegation 与 lease 通过 collaboration schema`() {
        val runtime = TeamRuntime { "2026-10-24T10:00:00Z" }
        val delegation = runtime.createDelegation(delegationInput())
        val lease = runtime.issueLease(
            delegation.id,
            LeaseGrant("t1", "a.worker", digest, Budget(tokens = 1200, calls = 2), earlier),
        )
        val delegationProblems = AmpSchemas.validateKind(
            "collaboration",
            toDelegationDoc(delegation).toJson(),
        )
        assertTrue("delegation: $delegationProblems", delegationProblems.isEmpty())
        val leaseProblems = AmpSchemas.validateKind("collaboration", toLeaseDoc(lease).toJson())
        assertTrue("lease: $leaseProblems", leaseProblems.isEmpty())
    }
}
