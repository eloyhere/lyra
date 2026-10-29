package com.eloykim.lyra.amp.protocol.database.repository

import com.eloykim.lyra.amp.protocol.database.entity.InstitutionRow
import com.eloykim.lyra.amp.protocol.database.entity.InstitutionTable
import com.eloykim.lyra.amp.protocol.database.entity.MembershipRow
import com.eloykim.lyra.amp.protocol.database.entity.MembershipTable
import com.eloykim.lyra.amp.protocol.database.entity.RelationshipRow
import com.eloykim.lyra.amp.protocol.database.entity.RelationshipTable
import com.eloykim.lyra.amp.protocol.database.sql.DatabaseSync
import com.eloykim.lyra.amp.protocol.database.sql.JsJson
import com.eloykim.lyra.amp.protocol.database.sql.SelectOptions
import com.eloykim.lyra.amp.protocol.database.sql.TableSql
import com.eloykim.lyra.amp.protocol.ipc.Institution
import com.eloykim.lyra.amp.protocol.ipc.JoinInstitutionInput
import com.eloykim.lyra.amp.protocol.ipc.Membership
import com.eloykim.lyra.amp.protocol.ipc.MembershipStatus
import com.eloykim.lyra.amp.protocol.ipc.NewInstitution
import com.eloykim.lyra.amp.protocol.ipc.NewRelationship
import com.eloykim.lyra.amp.protocol.ipc.Relationship
import com.eloykim.lyra.amp.protocol.ipc.SocialStore
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.types.Id

/**
 * SQLite adapter for [SocialStore] (port of
 * `src/database/repository/socialRepository.ts`).
 *
 * Relationships and institutions are social facts, not message history; this
 * adapter maps them to their tables and never exposes rows to the domain.
 *
 * Unlike the agent facets, the social domain sibling already exists as
 * `ipc/Social.kt` ([SocialStore] and its value objects), so this class implements
 * that interface instead of defining another one.
 */

private fun parseStringListOr(text: String): List<String> {
    val value = try {
        JsonParser.parse(text)
    } catch (_: IllegalArgumentException) {
        return emptyList()
    }
    val array = value as? JsonValue.Arr ?: return emptyList()
    return array.items.mapNotNull { (it as? JsonValue.Str)?.value }
}

private fun toInstitution(row: InstitutionRow): Institution = Institution(
    id = row.id,
    name = row.name,
    norms = parseStringListOr(row.norms_json),
)

private fun toRelationship(row: RelationshipRow): Relationship = Relationship(
    id = row.id,
    fromPid = row.from_pid,
    toPid = row.to_pid,
    kind = row.kind,
    trust = row.trust,
    attachment = row.attachment,
    obligations = parseStringListOr(row.obligations_json),
    since = row.since,
    lastContact = row.last_contact,
)

/** Reference class name: `SocialRepository`. */
class SocialRepository(db: DatabaseSync) :
    BaseRepository<InstitutionRow, Int>(db, InstitutionTable, InstitutionRow::fromRow),
    SocialStore {

    private val relationshipsSql: TableSql<RelationshipRow> =
        TableSql(db, RelationshipTable, RelationshipRow::fromRow)
    private val membershipsSql: TableSql<MembershipRow> =
        TableSql(db, MembershipTable, MembershipRow::fromRow)

    override fun relationships(pid: Int): List<Relationship> = relationshipsSql.select(
        SelectOptions(
            where = "from_pid = ? OR to_pid = ?",
            params = listOf(pid, pid),
            orderBy = "ORDER BY trust DESC",
        ),
    ).map(::toRelationship)

    override fun memberships(pid: Int): List<Membership> {
        val rows = membershipsSql.select(SelectOptions(where = "pid = ?", params = listOf(pid)))
        val result = ArrayList<Membership>()
        rows.forEach { row ->
            val institution = findById(row.iid) ?: return@forEach
            result += Membership(
                institution = toInstitution(institution),
                role = row.role,
                status = MembershipStatus.fromWire(row.status),
                since = row.since,
            )
        }
        return result
    }

    override fun institution(id: Id): Institution? =
        queries.select(SelectOptions(where = "id = ?", params = listOf(id), limit = 1))
            .firstOrNull()?.let(::toInstitution)

    override fun upsertRelationship(input: NewRelationship): Relationship {
        val existing = relationshipsSql.select(
            SelectOptions(
                where = "from_pid = ? AND to_pid = ? AND kind = ?",
                params = listOf(input.fromPid, input.toPid, input.kind),
                limit = 1,
            ),
        ).firstOrNull()
        if (existing == null) {
            val next = relationshipsSql.queryOne("SELECT COALESCE(MAX(rel_id), 0) + 1 AS n FROM relationships")
                ?.int("n") ?: 1
            relationshipsSql.insert(
                mapOf(
                    "id" to "rel.$next",
                    "from_pid" to input.fromPid,
                    "to_pid" to input.toPid,
                    "kind" to input.kind,
                    "trust" to (input.trust ?: 0.5),
                    "attachment" to (input.attachment ?: 0.5),
                    "obligations_json" to JsJson.encode(
                        JsonValue.Arr((input.obligations ?: emptyList()).map { JsonValue.Str(it) }),
                    ),
                    "since" to input.since,
                    "last_contact" to input.lastContact,
                ),
            )
        } else {
            val obligations = input.obligations ?: parseStringListOr(existing.obligations_json)
            relationshipsSql.updateWhere(
                "rel_id = ?",
                mapOf(
                    "trust" to (input.trust ?: existing.trust),
                    "attachment" to (input.attachment ?: existing.attachment),
                    "obligations_json" to JsJson.encode(JsonValue.Arr(obligations.map { JsonValue.Str(it) })),
                    "last_contact" to (input.lastContact ?: existing.last_contact),
                ),
                existing.rel_id,
            )
        }
        val row = relationshipsSql.select(
            SelectOptions(
                where = "from_pid = ? AND to_pid = ? AND kind = ?",
                params = listOf(input.fromPid, input.toPid, input.kind),
                limit = 1,
            ),
        ).firstOrNull()
            ?: throw AgentError(
                AgentError.RELATIONSHIP_NOT_FOUND,
                listOf("${input.fromPid}->${input.toPid}"),
            )
        return toRelationship(row)
    }

    override fun createInstitution(input: NewInstitution): Institution {
        if (institution(input.id) != null) {
            throw AgentError(
                AgentError.INSTITUTION_NOT_FOUND,
                listOf("institution ${input.id} already exists"),
            )
        }
        queries.insert(
            mapOf(
                "id" to input.id,
                "name" to input.name,
                "norms_json" to JsJson.encode(
                    JsonValue.Arr((input.norms ?: emptyList()).map { JsonValue.Str(it) }),
                ),
            ),
        )
        val row = queries.select(
            SelectOptions(where = "id = ?", params = listOf(input.id), limit = 1),
        ).firstOrNull()
            ?: throw AgentError(AgentError.INSTITUTION_NOT_FOUND, listOf("institution ${input.id}"))
        return toInstitution(row)
    }

    override fun joinInstitution(input: JoinInstitutionInput): Membership {
        val institution = institution(input.institutionId)
            ?: throw AgentError(
                AgentError.INSTITUTION_NOT_FOUND,
                listOf("institution ${input.institutionId}"),
            )
        val row = queries.select(
            SelectOptions(where = "id = ?", params = listOf(input.institutionId), limit = 1),
        ).firstOrNull()
            ?: throw AgentError(AgentError.INSTITUTION_NOT_FOUND, listOf("institution ${input.institutionId}"))
        val existing = membershipsSql.select(
            SelectOptions(where = "pid = ? AND iid = ?", params = listOf(input.pid, row.iid), limit = 1),
        ).firstOrNull()
        if (existing == null) {
            membershipsSql.insert(
                mapOf(
                    "pid" to input.pid,
                    "iid" to row.iid,
                    "role" to input.role,
                    "status" to MembershipStatus.ACTIVE.wire,
                    "since" to input.since,
                ),
            )
        } else {
            membershipsSql.updateWhere(
                "pid = ? AND iid = ?",
                mapOf("role" to input.role, "status" to MembershipStatus.ACTIVE.wire, "since" to input.since),
                input.pid,
                row.iid,
            )
        }
        return Membership(
            institution = institution,
            role = input.role,
            status = MembershipStatus.ACTIVE,
            since = input.since,
        )
    }

    override fun leaveInstitution(pid: Int, institutionId: Id, at: String): Membership {
        val institution = institution(institutionId)
            ?: throw AgentError(AgentError.INSTITUTION_NOT_FOUND, listOf("institution $institutionId"))
        val row = queries.select(
            SelectOptions(where = "id = ?", params = listOf(institutionId), limit = 1),
        ).firstOrNull()
            ?: throw AgentError(AgentError.INSTITUTION_NOT_FOUND, listOf("institution $institutionId"))
        val changed = membershipsSql.updateWhere(
            "pid = ? AND iid = ?",
            mapOf("status" to MembershipStatus.LEFT.wire, "since" to at),
            pid,
            row.iid,
        )
        if (changed == 0) {
            throw AgentError(
                AgentError.INSTITUTION_NOT_FOUND,
                listOf("membership $pid/$institutionId"),
            )
        }
        val membership = membershipsSql.select(
            SelectOptions(where = "pid = ? AND iid = ?", params = listOf(pid, row.iid), limit = 1),
        ).firstOrNull()
        return Membership(
            institution = institution,
            role = membership?.role ?: "",
            status = MembershipStatus.LEFT,
            since = membership?.since ?: at,
        )
    }
}
