package com.eloykim.lyra.amp.protocol.database.repository

import com.eloykim.lyra.amp.protocol.database.sql.DatabaseSync
import com.eloykim.lyra.amp.protocol.database.sql.Row
import com.eloykim.lyra.amp.protocol.database.sql.row
import com.eloykim.lyra.amp.protocol.database.sql.rows
import com.eloykim.lyra.amp.protocol.database.sql.runStatement

/**
 * Address-space adapter: `process_objects` <-> `objects` (port of
 * `src/database/repository/addressSpaceRepository.ts`).
 *
 * This is a separate aggregate from the execution state machine, so it gets its
 * own repository instead of bloating [ExecutionRepository].
 *
 * `AddressRole`, `AddressMode` and `ProcessObjectRecord` live in
 * `src/process/process.ts`, which has no Kotlin sibling yet; they are defined
 * here with the reference wire names.
 */

enum class AddressRole(val wire: String) {
    OWN("own"),
    INHERITED("inherited"),
    MAPPED("mapped"),
    JOINED("joined"),
    ;

    companion object {
        fun fromWire(value: String): AddressRole =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("unknown address role: $value")
    }
}

enum class AddressMode(val wire: String) {
    READ("read"),
    WRITE("write"),
    ;

    companion object {
        fun fromWire(value: String): AddressMode =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("unknown address mode: $value")
    }
}

data class ProcessObjectRecord(
    val seq: Int,
    val kind: String,
    val id: String,
    val body: String,
    val role: AddressRole,
    val mode: AddressMode,
)

private fun toRecord(row: Row): ProcessObjectRecord = ProcessObjectRecord(
    seq = row.requireInt("seq"),
    kind = row.requireString("kind"),
    id = row.requireString("id"),
    body = row.requireString("body"),
    role = AddressRole.fromWire(row.requireString("role")),
    mode = AddressMode.fromWire(row.requireString("mode")),
)

private const val SELECT_PLACEMENT =
    "SELECT o.seq, o.kind, o.id, o.body, p.role, p.mode " +
        "FROM process_objects p JOIN objects o ON o.seq = p.seq"

/** Reference class name: `AddressSpaceRepository`. */
class AddressSpaceRepository(private val db: DatabaseSync) {

    fun hasObject(seq: Int): Boolean = db.row("SELECT seq FROM objects WHERE seq = ?", seq) != null

    fun attach(pid: Int, seq: Int, role: AddressRole, mode: AddressMode, at: String) {
        db.runStatement(
            "INSERT OR REPLACE INTO process_objects (pid, seq, role, mode, attached_at) VALUES (?, ?, ?, ?, ?)",
            pid,
            seq,
            role.wire,
            mode.wire,
            at,
        )
    }

    fun list(pid: Int): List<ProcessObjectRecord> =
        db.rows("$SELECT_PLACEMENT WHERE p.pid = ? ORDER BY o.seq ASC", pid).map(::toRecord)

    fun read(pid: Int, seq: Int): ProcessObjectRecord? =
        db.row("$SELECT_PLACEMENT WHERE p.pid = ? AND p.seq = ?", pid, seq)?.let(::toRecord)

    fun fork(parentPid: Int, childPid: Int, at: String) {
        db.runStatement(
            "INSERT OR REPLACE INTO process_objects (pid, seq, role, mode, attached_at) " +
                "SELECT ?, seq, 'inherited', 'read', ? FROM process_objects WHERE pid = ?",
            childPid,
            at,
            parentPid,
        )
    }

    /**
     * Copy the parent's `own` objects into the child as `joined` views.
     *
     * The reference binds `(parentPid, at, childPid)` on this statement — note
     * the reversed order compared with [fork]. The port copies that call
     * verbatim; the reference is the authority for the observable rows.
     */
    fun join(parentPid: Int, childPid: Int, at: String): Int = db.runStatement(
        "INSERT OR REPLACE INTO process_objects (pid, seq, role, mode, attached_at) " +
            "SELECT ?, seq, 'joined', 'read', ? FROM process_objects WHERE pid = ? AND role = 'own'",
        parentPid,
        at,
        childPid,
    ).changes
}
