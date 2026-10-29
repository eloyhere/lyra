package com.eloykim.lyra.amp.protocol.protocol

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.isTimestamp

/**
 * Runtime-side collaboration values and their snake_case wire documents.
 *
 * Faithful port of `src/cooperation/collaboration.ts` and `src/delegate/team.ts`
 * (the visible `Delegation` / `Lease` shapes) plus `src/protocol/wire.ts`. The
 * runtime types live here because the Kotlin port has no separate `delegate`
 * package; the mapping itself is the reference behaviour.
 */

/** Lease lifecycle, mirroring the schema enum `active | done | cancelled`. */
enum class LeaseStatus(val wire: String) {
    ACTIVE("active"),
    DONE("done"),
    CANCELLED("cancelled");

    companion object {
        fun fromWire(value: String): LeaseStatus =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 lease.status: $value")
    }
}

/** The local execution right; the delegation is what crosses the wire. */
data class Delegation(
    val id: Id,
    val parent: Id? = null,
    val granter: Id,
    val delegatee: Id,
    val goalDigest: Digest,
    val scope: List<String>,
    val budget: Budget,
    val deadline: Timestamp,
    val mayRedelegate: Boolean,
    val depth: Int,
) {
    init {
        ProtocolIds.require(id, "delegation.id")
        parent?.let { ProtocolIds.require(it, "delegation.parent") }
        ProtocolIds.require(granter, "delegation.granter")
        ProtocolIds.require(delegatee, "delegation.delegatee")
        require(Sha256.isValidDigest(goalDigest)) { "delegation.goalDigest 格式非法" }
        require(isTimestamp(deadline)) { "delegation.deadline 不是合法时间" }
        require(depth >= 0) { "delegation.depth 必须 >= 0" }
    }
}

/** A single-writer lease with a monotonic fence. */
data class Lease(
    val id: Id,
    val delegation: Id,
    val taskId: Id,
    val holder: Id,
    val fence: Int,
    val goalDigest: Digest,
    val budget: Budget,
    val deadline: Timestamp,
    val status: LeaseStatus,
) {
    init {
        ProtocolIds.require(id, "lease.id")
        ProtocolIds.require(delegation, "lease.delegation")
        ProtocolIds.require(taskId, "lease.task_id")
        ProtocolIds.require(holder, "lease.holder")
        require(fence >= 1) { "lease.fence 必须 >= 1" }
        require(Sha256.isValidDigest(goalDigest)) { "lease.goalDigest 格式非法" }
        require(isTimestamp(deadline)) { "lease.deadline 不是合法时间" }
    }
}

/** Wire document for [Delegation]; `goal_digest` and `may_redelegate` are stable names. */
data class DelegationDoc(
    val id: Id,
    val parent: Id? = null,
    val granter: Id,
    val delegatee: Id,
    val goalDigest: Digest,
    val scope: List<String>,
    val budget: Budget,
    val deadline: Timestamp,
    val mayRedelegate: Boolean,
    val depth: Int,
) {
    init {
        ProtocolIds.require(id, "delegation.id")
        parent?.let { ProtocolIds.require(it, "delegation.parent") }
        ProtocolIds.require(granter, "delegation.granter")
        ProtocolIds.require(delegatee, "delegation.delegatee")
        require(Sha256.isValidDigest(goalDigest)) { "delegation.goal_digest 格式非法" }
        require(isTimestamp(deadline)) { "delegation.deadline 不是合法时间" }
        require(depth >= 0) { "delegation.depth 必须 >= 0" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to jsonText("0.1"),
        "kind" to jsonText("delegation"),
        "id" to jsonText(id),
        "parent" to parent?.let { jsonText(it) },
        "granter" to jsonText(granter),
        "delegatee" to jsonText(delegatee),
        "goal_digest" to jsonText(goalDigest),
        "scope" to jsonStrings(scope),
        "budget" to budget.toJson(),
        "deadline" to jsonText(deadline),
        "may_redelegate" to JsonValue.Bool(mayRedelegate),
        "depth" to jsonNumber(depth),
    )

    companion object {
        fun fromJson(value: JsonValue): DelegationDoc {
            val obj = value.asObject("delegation")
            require(obj.requireString("amp") == "0.1") { "delegation.amp 必须是 0.1" }
            require(obj.requireString("kind") == "delegation") { "delegation.kind 必须是 delegation" }
            return DelegationDoc(
                id = obj.requireString("id"),
                parent = obj.string("parent"),
                granter = obj.requireString("granter"),
                delegatee = obj.requireString("delegatee"),
                goalDigest = obj.requireString("goal_digest"),
                scope = obj.requireArr("scope").items.map { item ->
                    (item as? JsonValue.Str)?.value ?: throw IllegalArgumentException("delegation.scope 必须是字符串数组")
                },
                budget = Budget.fromJson(obj.requireObj("budget")),
                deadline = obj.requireString("deadline"),
                mayRedelegate = obj.bool("may_redelegate")
                    ?: throw IllegalArgumentException("缺少布尔字段 may_redelegate"),
                depth = obj.requireInt("depth"),
            )
        }
    }
}

/** Wire document for [Lease]. */
data class LeaseDoc(
    val id: Id,
    val delegation: Id,
    val taskId: Id,
    val holder: Id,
    val fence: Int,
    val goalDigest: Digest,
    val budget: Budget,
    val deadline: Timestamp,
    val status: LeaseStatus,
) {
    init {
        ProtocolIds.require(id, "lease.id")
        ProtocolIds.require(delegation, "lease.delegation")
        ProtocolIds.require(taskId, "lease.task_id")
        ProtocolIds.require(holder, "lease.holder")
        require(fence >= 1) { "lease.fence 必须 >= 1" }
        require(Sha256.isValidDigest(goalDigest)) { "lease.goal_digest 格式非法" }
        require(isTimestamp(deadline)) { "lease.deadline 不是合法时间" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to jsonText("0.1"),
        "kind" to jsonText("lease"),
        "id" to jsonText(id),
        "delegation" to jsonText(delegation),
        "task_id" to jsonText(taskId),
        "holder" to jsonText(holder),
        "fence" to jsonNumber(fence),
        "goal_digest" to jsonText(goalDigest),
        "budget" to budget.toJson(),
        "deadline" to jsonText(deadline),
        "status" to jsonText(status.wire),
    )

    companion object {
        fun fromJson(value: JsonValue): LeaseDoc {
            val obj = value.asObject("lease")
            require(obj.requireString("amp") == "0.1") { "lease.amp 必须是 0.1" }
            require(obj.requireString("kind") == "lease") { "lease.kind 必须是 lease" }
            return LeaseDoc(
                id = obj.requireString("id"),
                delegation = obj.requireString("delegation"),
                taskId = obj.requireString("task_id"),
                holder = obj.requireString("holder"),
                fence = obj.requireInt("fence"),
                goalDigest = obj.requireString("goal_digest"),
                budget = Budget.fromJson(obj.requireObj("budget")),
                deadline = obj.requireString("deadline"),
                status = LeaseStatus.fromWire(obj.requireString("status")),
            )
        }
    }
}

/** Runtime → wire. Unknown local fields are not invented into the document. */
fun toDelegationDoc(delegation: Delegation): DelegationDoc = DelegationDoc(
    id = delegation.id,
    parent = delegation.parent,
    granter = delegation.granter,
    delegatee = delegation.delegatee,
    goalDigest = delegation.goalDigest,
    scope = delegation.scope,
    budget = delegation.budget,
    deadline = delegation.deadline,
    mayRedelegate = delegation.mayRedelegate,
    depth = delegation.depth,
)

/** Wire → runtime; `parent` stays absent when the document omitted it. */
fun fromDelegationDoc(doc: DelegationDoc): Delegation = Delegation(
    id = doc.id,
    parent = doc.parent,
    granter = doc.granter,
    delegatee = doc.delegatee,
    goalDigest = doc.goalDigest,
    scope = doc.scope,
    budget = doc.budget,
    deadline = doc.deadline,
    mayRedelegate = doc.mayRedelegate,
    depth = doc.depth,
)

/** Runtime → wire; the reference only needs this direction for leases. */
fun toLeaseDoc(lease: Lease): LeaseDoc = LeaseDoc(
    id = lease.id,
    delegation = lease.delegation,
    taskId = lease.taskId,
    holder = lease.holder,
    fence = lease.fence,
    goalDigest = lease.goalDigest,
    budget = lease.budget,
    deadline = lease.deadline,
    status = lease.status,
)
