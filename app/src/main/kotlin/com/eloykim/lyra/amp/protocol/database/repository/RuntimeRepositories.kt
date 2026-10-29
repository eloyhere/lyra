package com.eloykim.lyra.amp.protocol.database.repository

import com.eloykim.lyra.amp.protocol.authz.CapabilityDatabase
import com.eloykim.lyra.amp.protocol.authz.PermissionAction
import com.eloykim.lyra.amp.protocol.authz.Resource
import com.eloykim.lyra.amp.protocol.authz.ResourceKind
import com.eloykim.lyra.amp.protocol.authz.Subject
import com.eloykim.lyra.amp.protocol.authz.requirePermission
import com.eloykim.lyra.amp.protocol.database.entity.RuntimeApprovalInput
import com.eloykim.lyra.amp.protocol.database.entity.RuntimeApprovalRow
import com.eloykim.lyra.amp.protocol.database.entity.RuntimeApprovalTable
import com.eloykim.lyra.amp.protocol.database.entity.RuntimeSessionRow
import com.eloykim.lyra.amp.protocol.database.entity.RuntimeSessionTable
import com.eloykim.lyra.amp.protocol.database.entity.RuntimeTurnRow
import com.eloykim.lyra.amp.protocol.database.entity.RuntimeTurnTable
import com.eloykim.lyra.amp.protocol.database.sql.DatabaseSync
import com.eloykim.lyra.amp.protocol.database.sql.SelectOptions
import com.eloykim.lyra.amp.protocol.database.sql.runStatement
import com.eloykim.lyra.amp.protocol.runtime.RuntimeTurn
import com.eloykim.lyra.amp.protocol.runtime.SessionSnapshot

/**
 * Persistence in one file for clarity: this Kotlin file ports
 * `src/database/repository/runtimeSessionRepository.ts`,
 * `runtimeTurnRepository.ts` and `runtimeApprovalRepository.ts`, which are three
 * small adapters over the same runtime snapshot tables.
 *
 * ## Codec seam
 *
 * The reference stores `JSON.stringify(snapshot)` and parses it back. The Kotlin
 * runtime package (`runtime/Session.kt`, `runtime/CognitiveRuntime.kt`) owns
 * those types but has not defined a JSON serializer yet, so the codec is
 * injected. This is the narrow interface the "absent sibling port" rule asks
 * for; a host can back it with the wire format of its choice.
 */

/** Encodes/decodes [SessionSnapshot] for the `data` column. */
interface SessionSnapshotCodec {

    fun encode(snapshot: SessionSnapshot): String

    fun decode(text: String): SessionSnapshot
}

/** Encodes/decodes [RuntimeTurn] for the `data` column. */
interface RuntimeTurnCodec {

    fun encode(turn: RuntimeTurn): String

    fun decode(text: String): RuntimeTurn
}

/** Runtime session snapshots (port of `runtimeSessionRepository.ts`). */
class RuntimeSessionRepository(
    db: DatabaseSync,
    private val codec: SessionSnapshotCodec,
) : BaseRepository<RuntimeSessionRow, Int>(db, RuntimeSessionTable, RuntimeSessionRow::fromRow) {

    fun load(pid: Int): SessionSnapshot? = findById(pid)?.let { codec.decode(it.data) }

    fun save(pid: Int, snapshot: SessionSnapshot, at: String) {
        val data = codec.encode(snapshot)
        val existing = findById(pid)
        if (existing == null) {
            queries.insert(mapOf("pid" to pid, "data" to data, "updated_at" to at))
        } else {
            queries.update(pid, mapOf("data" to data, "updated_at" to at))
        }
    }
}

/** Queryable turn snapshots (port of `runtimeTurnRepository.ts`). */
class RuntimeTurnRepository(
    db: DatabaseSync,
    private val codec: RuntimeTurnCodec,
) : BaseRepository<RuntimeTurnRow, String>(db, RuntimeTurnTable, RuntimeTurnRow::fromRow) {

    fun save(turn: RuntimeTurn) {
        val data = codec.encode(turn)
        val existing = findById(turn.turnId)
        if (existing == null) {
            queries.insert(
                mapOf(
                    "id" to turn.turnId,
                    "pid" to turn.pid,
                    "session_id" to turn.sessionId,
                    "data" to data,
                    "created_at" to turn.at,
                ),
            )
        } else {
            queries.update(turn.turnId, mapOf("data" to data))
        }
    }

    fun get(turnId: String): RuntimeTurn? = findById(turnId)?.let { codec.decode(it.data) }

    fun list(pid: Int, limit: Int = 100): List<RuntimeTurn> = queries.select(
        SelectOptions(where = "pid = ?", params = listOf(pid), orderBy = "ORDER BY created_at DESC", limit = limit),
    ).map { codec.decode(it.data) }
}

/** Independent approval audit rows (port of `runtimeApprovalRepository.ts`). */
class RuntimeApprovalRepository(db: DatabaseSync) :
    BaseRepository<RuntimeApprovalRow, Int>(db, RuntimeApprovalTable, RuntimeApprovalRow::fromRow) {

    fun record(input: RuntimeApprovalInput): Long = queries.insert(
        mapOf(
            "pid" to input.pid,
            "turn_id" to input.turnId,
            "operation_id" to input.operationId,
            "tool" to input.tool,
            "risk" to input.risk,
            "decision" to input.decision,
            "status" to input.status,
            "created_at" to input.at,
        ),
    ).lastInsertRowid

    fun list(pid: Int, limit: Int = 100): List<RuntimeApprovalRow> = queries.select(
        SelectOptions(where = "pid = ?", params = listOf(pid), orderBy = "ORDER BY id DESC", limit = limit),
    )

    /**
     * Owner (or a subject with a read grant) may read this pid's approval audit.
     *
     * The reference calls `requirePermission` with the repository's own
     * connection; the Kotlin authz port takes a [CapabilityDatabase], so the
     * caller passes the capability adapter explicitly.
     */
    fun listForSubject(
        capabilities: CapabilityDatabase,
        subject: Subject,
        pid: Int,
        at: String,
        limit: Int = 100,
    ): List<RuntimeApprovalRow> {
        requirePermission(
            capabilities,
            subject,
            PermissionAction.READ,
            Resource(kind = ResourceKind.OBJECT, id = "approval:$pid", ownerPid = pid),
            at,
        )
        return list(pid, limit)
    }

    fun prune(before: String): Int =
        db.runStatement("DELETE FROM runtime_approvals WHERE created_at < ?", before).changes
}
