package com.eloykim.lyra.amp.protocol.database.repository

import com.eloykim.lyra.amp.protocol.authz.AuthzEventEntry
import com.eloykim.lyra.amp.protocol.authz.CapabilityDatabase
import com.eloykim.lyra.amp.protocol.authz.CapabilityRecord
import com.eloykim.lyra.amp.protocol.authz.CapabilityScope
import com.eloykim.lyra.amp.protocol.authz.PermissionAction
import com.eloykim.lyra.amp.protocol.authz.Resource
import com.eloykim.lyra.amp.protocol.authz.ResourceKind
import com.eloykim.lyra.amp.protocol.authz.Subject
import com.eloykim.lyra.amp.protocol.authz.SubjectKind
import com.eloykim.lyra.amp.protocol.database.sql.DatabaseSync
import com.eloykim.lyra.amp.protocol.database.sql.JsJson
import com.eloykim.lyra.amp.protocol.database.sql.Row
import com.eloykim.lyra.amp.protocol.database.sql.row
import com.eloykim.lyra.amp.protocol.database.sql.rows
import com.eloykim.lyra.amp.protocol.database.sql.runStatement
import com.eloykim.lyra.amp.protocol.database.store.putObject
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Timestamp

/**
 * SQLite adapter for the authz `CapabilityDatabase` port
 * (`src/authz/authz.ts` statements, wired in by `HandleService`).
 *
 * `authz/Authz.kt` already ports the policy and defines
 * [CapabilityDatabase]; the reference's SQL lives in `src/authz/authz.ts`
 * itself, outside the database directory, but it is the same `handles` table
 * this package owns. Keeping the statements here lets `HandleService.grant` /
 * `revoke` / `list` delegate to the existing Kotlin authz functions exactly as
 * the reference service delegates to its authz module.
 *
 * The rights JSON is written with [JsJson] (`JSON.stringify`) and read back with
 * `PermissionAction.fromWire`; unknown rights fail closed with the authz error.
 */
class SqliteCapabilityDatabase(private val db: DatabaseSync) : CapabilityDatabase {

    override fun insert(record: CapabilityRecord): CapabilityRecord {
        db.runStatement(
            """INSERT INTO handles
             (id, subject_kind, subject_id, subject_pid, resource_kind, resource_id, owner_pid, rights, scope, parent_hid, expires_at, granted_by, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            record.id,
            record.subject.kind.wire,
            record.subject.id,
            record.subject.pid,
            record.resource.kind.wire,
            record.resource.id,
            record.resource.ownerPid,
            JsJson.encode(JsonValue.Arr(record.rights.map { JsonValue.Str(it.wire) })),
            record.scope.wire,
            record.parentHid,
            record.expiresAt,
            record.grantedBy,
            record.createdAt,
        )
        return record
    }

    override fun row(hid: Long): CapabilityRecord? =
        db.row("SELECT * FROM handles WHERE hid = ?", hid)?.let(::toCapability)

    override fun rowsOf(
        subjectKind: SubjectKind,
        subjectId: Id,
        resourceKind: ResourceKind,
        resourceId: Id,
    ): List<CapabilityRecord> = db.rows(
        """SELECT * FROM handles
         WHERE subject_kind = ? AND subject_id = ?
           AND resource_kind = ? AND resource_id = ?
         ORDER BY hid ASC""",
        subjectKind.wire,
        subjectId,
        resourceKind.wire,
        resourceId,
    ).map(::toCapability)

    override fun rowsOfProcessPid(pid: Int, resourceKind: ResourceKind, resourceId: Id): List<CapabilityRecord> = db.rows(
        """SELECT * FROM handles
         WHERE subject_kind = 'process' AND subject_pid = ?
           AND resource_kind = ? AND resource_id = ?
         ORDER BY hid ASC""",
        pid,
        resourceKind.wire,
        resourceId,
    ).map(::toCapability)

    override fun rowsOfSubject(subjectKind: SubjectKind, subjectId: Id): List<CapabilityRecord> =
        db.rows(
            "SELECT * FROM handles WHERE subject_kind = ? AND subject_id = ? ORDER BY hid ASC",
            subjectKind.wire,
            subjectId,
        ).map(::toCapability)

    override fun count(): Long = db.row("SELECT COUNT(*) AS n FROM handles")?.long("n") ?: 0L

    override fun revokeTree(hid: Long, at: Timestamp): Int = db.runStatement(
        """WITH RECURSIVE tree(hid) AS (
           SELECT ?
           UNION ALL
           SELECT h.hid FROM handles h JOIN tree t ON h.parent_hid = t.hid
         )
         UPDATE handles SET revoked_at = ?
         WHERE hid IN (SELECT hid FROM tree) AND revoked_at IS NULL""",
        hid,
        at,
    ).changes

    override fun appendEvent(kind: String, id: Id, body: JsonValue.Obj, at: Timestamp): Digest =
        putObject(db, kind, id, body, at).hash

    override fun events(): List<AuthzEventEntry> = db.rows(
        "SELECT kind, id, body, hash, prev, at FROM objects " +
            "WHERE kind = 'event' AND id LIKE 'ev.authz.%' ORDER BY seq ASC",
    ).map(::toAuthzEvent)
}

private fun toCapability(row: Row): CapabilityRecord {
    val rights = (JsonParser.parse(row.requireString("rights")) as JsonValue.Arr).items.map { item ->
        PermissionAction.fromWire((item as JsonValue.Str).value)
    }
    return CapabilityRecord(
        hid = row.requireInt("hid").toLong(),
        id = row.requireString("id"),
        subject = Subject(
            kind = SubjectKind.fromWire(row.requireString("subject_kind")),
            id = row.requireString("subject_id"),
            pid = row.int("subject_pid"),
        ),
        resource = Resource(
            kind = ResourceKind.fromWire(row.requireString("resource_kind")),
            id = row.requireString("resource_id"),
            ownerPid = row.int("owner_pid"),
        ),
        rights = rights,
        scope = CapabilityScope.fromWire(row.requireString("scope")),
        parentHid = row.int("parent_hid")?.toLong(),
        expiresAt = row.string("expires_at"),
        revokedAt = row.string("revoked_at"),
        grantedBy = row.string("granted_by"),
        createdAt = row.requireString("created_at"),
    )
}

private fun toAuthzEvent(row: Row): AuthzEventEntry = AuthzEventEntry(
    kind = row.requireString("kind"),
    id = row.requireString("id"),
    body = row.requireString("body"),
    hash = row.requireString("hash"),
    prev = row.requireString("prev"),
    at = row.requireString("at"),
)
