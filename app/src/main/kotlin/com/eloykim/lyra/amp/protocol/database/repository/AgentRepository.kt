package com.eloykim.lyra.amp.protocol.database.repository

import com.eloykim.lyra.amp.protocol.database.entity.AffectRow
import com.eloykim.lyra.amp.protocol.database.entity.AffectTable
import com.eloykim.lyra.amp.protocol.database.entity.AgentProfileRow
import com.eloykim.lyra.amp.protocol.database.entity.AgentProfileTable
import com.eloykim.lyra.amp.protocol.database.entity.BackgroundRow
import com.eloykim.lyra.amp.protocol.database.entity.BackgroundTable
import com.eloykim.lyra.amp.protocol.database.entity.LifeEventRow
import com.eloykim.lyra.amp.protocol.database.entity.LifeEventTable
import com.eloykim.lyra.amp.protocol.database.entity.MemoryRow
import com.eloykim.lyra.amp.protocol.database.entity.MemoryTable
import com.eloykim.lyra.amp.protocol.database.entity.PersonRoleRow
import com.eloykim.lyra.amp.protocol.database.entity.PersonRoleTable
import com.eloykim.lyra.amp.protocol.database.entity.RoleRow
import com.eloykim.lyra.amp.protocol.database.entity.RoleTable
import com.eloykim.lyra.amp.protocol.database.entity.SelfNarrativeRow
import com.eloykim.lyra.amp.protocol.database.entity.SelfNarrativeTable
import com.eloykim.lyra.amp.protocol.database.sql.DatabaseSync
import com.eloykim.lyra.amp.protocol.database.sql.JsJson
import com.eloykim.lyra.amp.protocol.database.sql.SelectOptions
import com.eloykim.lyra.amp.protocol.database.sql.TableSql
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.types.Ref

/**
 * SQLite adapter for [AgentStore] (port of
 * `src/database/repository/agentRepository.ts`).
 *
 * All table access goes through `TableSql`; this class owns mapping between the
 * database rows and the domain types. It deliberately does not expose rows or a
 * connection to the domain.
 *
 * JSON columns are read with the reference's tolerant `parseJson(text,
 * fallback)`: malformed text becomes the fallback instead of failing the read.
 * Writes use [JsJson] (`JSON.stringify`).
 */

private fun parseJsonOr(text: String, fallback: JsonValue): JsonValue =
    try {
        JsonParser.parse(text)
    } catch (_: IllegalArgumentException) {
        fallback
    }

private fun parseStringList(text: String): List<String> {
    val value = parseJsonOr(text, JsonValue.Arr())
    val array = value as? JsonValue.Arr ?: return emptyList()
    return array.items.mapNotNull { (it as? JsonValue.Str)?.value }
}

private fun parseObligations(text: String): List<Obligation> {
    val value = parseJsonOr(text, JsonValue.Arr())
    val array = value as? JsonValue.Arr ?: return emptyList()
    return array.items.mapNotNull { item -> runCatching { Obligation.fromJson(item) }.getOrNull() }
}

private fun parseRefs(text: String): List<Ref> {
    val value = parseJsonOr(text, JsonValue.Arr())
    val array = value as? JsonValue.Arr ?: return emptyList()
    return array.items.mapNotNull { item -> runCatching { Ref.fromJson(item) }.getOrNull() }
}

private fun toProfile(row: AgentProfileRow): AgentProfile = AgentProfile(
    pid = row.pid,
    name = row.name,
    lifeChapter = row.life_chapter,
    values = parseStringList(row.values_json),
    constraints = parseStringList(row.constraints_json),
    version = row.version,
    updatedAt = row.updated_at,
)

private fun toNarrative(row: SelfNarrativeRow): SelfNarrative = SelfNarrative(
    pid = row.pid,
    version = row.version,
    identity = parseJsonOr(row.identity_json, JsonValue.Obj()) as? JsonValue.Obj ?: JsonValue.Obj(),
    narrative = row.narrative,
    at = row.at,
    objectSeq = row.object_seq,
)

private fun toRole(row: RoleRow): Role = Role(
    id = row.id,
    label = row.label,
    obligations = parseObligations(row.obligations_json),
    expectations = parseStringList(row.permissions_json),
    audiences = parseStringList(row.audiences_json),
    scripts = parseStringList(row.scripts_json),
    status = RoleStatus.entries.firstOrNull { it.wire == row.status } ?: RoleStatus.ACTIVE,
    createdAt = row.created_at,
)

private fun toMemory(row: MemoryRow): Memory = Memory(
    id = row.id,
    pid = row.pid,
    roleId = row.role_id,
    kind = MemoryKind.fromWire(row.kind),
    content = row.content,
    salience = row.salience,
    at = row.at,
    refs = parseRefs(row.refs_json),
    source = row.source,
    consolidated = row.consolidated != 0,
)

private fun toAffect(row: AffectRow): AffectState = AffectState(
    pid = row.pid,
    valence = row.valence,
    arousal = row.arousal,
    stress = row.stress,
    fatigue = row.fatigue,
    mood = row.mood,
    updatedAt = row.updated_at,
)

private fun toBackground(row: BackgroundRow): BackgroundProcess = BackgroundProcess(
    id = row.id,
    pid = row.pid,
    kind = BackgroundKind.entries.firstOrNull { it.wire == row.kind } ?: BackgroundKind.HABIT,
    label = row.label,
    intensity = row.intensity,
    status = BackgroundStatus.fromWire(row.status),
    updatedAt = row.updated_at,
    trigger = row.trigger,
    script = row.script,
)

private fun toLifeEvent(row: LifeEventRow): LifeEvent = LifeEvent(
    id = row.id,
    pid = row.pid,
    kind = LifeEventKind.entries.firstOrNull { it.wire == row.kind } ?: LifeEventKind.OTHER,
    summary = row.summary,
    at = row.at,
    impact = row.impact,
    roleId = row.role_id,
    narrativeVersion = row.narrative_version,
)

/** Reference class name: `AgentRepository`. */
class AgentRepository(db: DatabaseSync) :
    BaseRepository<AgentProfileRow, Int>(db, AgentProfileTable, AgentProfileRow::fromRow),
    AgentStore {

    private val narratives: TableSql<SelfNarrativeRow> = TableSql(db, SelfNarrativeTable, SelfNarrativeRow::fromRow)
    private val roleRows: TableSql<RoleRow> = TableSql(db, RoleTable, RoleRow::fromRow)
    private val assignments: TableSql<PersonRoleRow> = TableSql(db, PersonRoleTable, PersonRoleRow::fromRow)
    private val memoryRows: TableSql<MemoryRow> = TableSql(db, MemoryTable, MemoryRow::fromRow)
    private val affectRows: TableSql<AffectRow> = TableSql(db, AffectTable, AffectRow::fromRow)
    private val backgroundRows: TableSql<BackgroundRow> = TableSql(db, BackgroundTable, BackgroundRow::fromRow)
    private val eventRows: TableSql<LifeEventRow> = TableSql(db, LifeEventTable, LifeEventRow::fromRow)

    override fun profile(pid: Int): AgentProfile? = findById(pid)?.let(::toProfile)

    override fun narrative(pid: Int, version: Int?): SelfNarrative? {
        val rows = if (version == null) {
            narratives.select(
                SelectOptions(where = "pid = ?", params = listOf(pid), orderBy = "ORDER BY version DESC", limit = 1),
            )
        } else {
            narratives.select(
                SelectOptions(where = "pid = ? AND version = ?", params = listOf(pid, version), limit = 1),
            )
        }
        return rows.firstOrNull()?.let(::toNarrative)
    }

    override fun roles(pid: Int): List<RoleAssignment> {
        val rows = assignments.select(SelectOptions(where = "pid = ?", params = listOf(pid)))
        val result = ArrayList<RoleAssignment>()
        rows.forEach { row ->
            val role = roleRows.findById(row.rid) ?: return@forEach
            result += RoleAssignment(
                role = toRole(role),
                salience = row.salience,
                active = row.active != 0,
                since = row.since,
            )
        }
        return result
    }

    override fun activeRole(pid: Int): RoleAssignment? = roles(pid).firstOrNull { it.active }

    override fun memories(pid: Int, query: MemoryQuery): List<Memory> {
        val clauses = ArrayList<String>()
        val params = ArrayList<Any?>()
        clauses += "pid = ?"
        params += pid
        query.kind?.let {
            clauses += "kind = ?"
            params += it.wire
        }
        query.roleId?.let {
            clauses += "role_id = ?"
            params += it
        }
        query.minSalience?.let {
            clauses += "salience >= ?"
            params += it
        }
        query.before?.let {
            clauses += "at < ?"
            params += it
        }
        if (query.includeConsolidated == false) {
            clauses += "consolidated = 0"
        }
        return memoryRows.select(
            SelectOptions(
                where = clauses.joinToString(" AND "),
                params = params,
                orderBy = "ORDER BY salience DESC, at DESC",
                limit = query.limit ?: 200,
            ),
        ).map(::toMemory)
    }

    override fun affect(pid: Int): AffectState? = affectRows.findById(pid)?.let(::toAffect)

    override fun background(pid: Int, status: BackgroundStatus?): List<BackgroundProcess> {
        val clauses = ArrayList<String>()
        val params = ArrayList<Any?>()
        clauses += "pid = ?"
        params += pid
        status?.let {
            clauses += "status = ?"
            params += it.wire
        }
        return backgroundRows.select(
            SelectOptions(where = clauses.joinToString(" AND "), params = params, orderBy = "ORDER BY intensity DESC"),
        ).map(::toBackground)
    }

    override fun lifeEvents(pid: Int, limit: Int): List<LifeEvent> = eventRows.select(
        SelectOptions(where = "pid = ?", params = listOf(pid), orderBy = "ORDER BY at DESC", limit = limit),
    ).map(::toLifeEvent)

    override fun createProfile(input: NewAgentProfile): AgentProfile {
        if (findById(input.pid) != null) {
            throw AgentError(AgentError.PROFILE_EXISTS, listOf("pid ${input.pid}"))
        }
        queries.insert(
            mapOf(
                "pid" to input.pid,
                "name" to input.name,
                "narrative" to "",
                "values_json" to JsJson.encode(JsonValue.Arr((input.values ?: emptyList()).map { JsonValue.Str(it) })),
                "constraints_json" to JsJson.encode(JsonValue.Arr((input.constraints ?: emptyList()).map { JsonValue.Str(it) })),
                "life_chapter" to (input.lifeChapter ?: "starting"),
                "updated_at" to input.at,
                "version" to 1,
            ),
        )
        return profile(input.pid) ?: throw AgentError(AgentError.AGENT_NOT_FOUND, listOf("pid ${input.pid}"))
    }

    override fun reviseNarrative(input: NewNarrative): SelfNarrative {
        if (findById(input.pid) == null) {
            throw AgentError(AgentError.AGENT_NOT_FOUND, listOf("pid ${input.pid}"))
        }
        val version = narratives.count("pid = ?", input.pid) + 1
        narratives.insert(
            mapOf(
                "pid" to input.pid,
                "version" to version,
                "identity_json" to JsJson.encode(input.identity),
                "narrative" to input.narrative,
                "at" to input.at,
            ),
        )
        return narrative(input.pid, version)
            ?: throw AgentError(AgentError.AGENT_NOT_FOUND, listOf("narrative ${input.pid}/$version"))
    }

    override fun addRole(input: NewRole): Role {
        roleRows.insert(
            mapOf(
                "id" to input.id,
                "label" to input.label,
                "obligations_json" to JsJson.encode(
                    JsonValue.Arr((input.obligations ?: emptyList()).map { obligationToJson(it) }),
                ),
                "permissions_json" to JsJson.encode(JsonValue.Arr((input.expectations ?: emptyList()).map { JsonValue.Str(it) })),
                "audiences_json" to JsJson.encode(JsonValue.Arr((input.audiences ?: emptyList()).map { JsonValue.Str(it) })),
                "scripts_json" to JsJson.encode(JsonValue.Arr((input.scripts ?: emptyList()).map { JsonValue.Str(it) })),
                "status" to RoleStatus.ACTIVE.wire,
                "created_at" to input.createdAt,
            ),
        )
        val row = roleRows.select(SelectOptions(where = "id = ?", params = listOf(input.id), limit = 1)).firstOrNull()
            ?: throw AgentError(AgentError.ROLE_NOT_FOUND, listOf("role ${input.id}"))
        return toRole(row)
    }

    override fun assignRole(input: AssignRoleInput): RoleAssignment {
        if (findById(input.pid) == null) {
            throw AgentError(AgentError.AGENT_NOT_FOUND, listOf("pid ${input.pid}"))
        }
        val role = roleRows.select(SelectOptions(where = "id = ?", params = listOf(input.roleId), limit = 1)).firstOrNull()
            ?: throw AgentError(AgentError.ROLE_NOT_FOUND, listOf("role ${input.roleId}"))
        val active = if (input.active == true) 1 else 0
        if (active == 1) {
            assignments.updateWhere("pid = ?", mapOf("active" to 0), input.pid)
        }
        val existing = assignments.select(
            SelectOptions(where = "pid = ? AND rid = ?", params = listOf(input.pid, role.rid), limit = 1),
        )
        val salience = input.salience ?: 0.5
        if (existing.isEmpty()) {
            assignments.insert(
                mapOf("pid" to input.pid, "rid" to role.rid, "salience" to salience, "active" to active, "since" to input.since),
            )
        } else {
            assignments.updateWhere(
                "pid = ? AND rid = ?",
                mapOf("salience" to salience, "active" to active, "since" to input.since),
                input.pid,
                role.rid,
            )
        }
        val assignment = assignments.select(
            SelectOptions(where = "pid = ? AND rid = ?", params = listOf(input.pid, role.rid), limit = 1),
        ).firstOrNull()
            ?: throw AgentError(
                AgentError.ROLE_NOT_ASSIGNED,
                listOf("pid ${input.pid} role ${input.roleId}"),
            )
        return RoleAssignment(toRole(role), assignment.salience, assignment.active != 0, assignment.since)
    }

    override fun activateRole(input: ActivateRoleInput): RoleAssignment {
        val role = roleRows.select(SelectOptions(where = "id = ?", params = listOf(input.roleId), limit = 1)).firstOrNull()
            ?: throw AgentError(AgentError.ROLE_NOT_FOUND, listOf("role ${input.roleId}"))
        val assigned = assignments.select(
            SelectOptions(where = "pid = ? AND rid = ?", params = listOf(input.pid, role.rid), limit = 1),
        )
        if (assigned.isEmpty()) {
            throw AgentError(AgentError.ROLE_NOT_ASSIGNED, listOf("pid ${input.pid} role ${input.roleId}"))
        }
        assignments.updateWhere("pid = ?", mapOf("active" to 0), input.pid)
        assignments.updateWhere(
            "pid = ? AND rid = ?",
            mapOf("active" to 1, "since" to input.at),
            input.pid,
            role.rid,
        )
        val assignment = assignments.select(
            SelectOptions(where = "pid = ? AND rid = ?", params = listOf(input.pid, role.rid), limit = 1),
        ).firstOrNull()
            ?: throw AgentError(AgentError.ROLE_NOT_ASSIGNED, listOf("pid ${input.pid} role ${input.roleId}"))
        return RoleAssignment(toRole(role), assignment.salience, active = true, since = assignment.since)
    }

    override fun remember(input: NewMemory): Memory {
        if (findById(input.pid) == null) {
            throw AgentError(AgentError.AGENT_NOT_FOUND, listOf("pid ${input.pid}"))
        }
        val next = memoryRows.queryOne("SELECT COALESCE(MAX(mem_id), 0) + 1 AS n FROM memories")?.int("n") ?: 1
        val id = "mem.${input.pid}.$next"
        memoryRows.insert(
            mapOf(
                "id" to id,
                "pid" to input.pid,
                "role_id" to input.roleId,
                "kind" to input.kind.wire,
                "content" to input.content,
                "salience" to (input.salience ?: 0.5),
                "at" to input.at,
                "refs_json" to JsJson.encode(JsonValue.Arr((input.refs ?: emptyList()).map { it.toJson() })),
                "source" to input.source,
                "consolidated" to 0,
            ),
        )
        val row = memoryRows.select(SelectOptions(where = "id = ?", params = listOf(id), limit = 1)).firstOrNull()
            ?: throw AgentError(AgentError.MEMORY_NOT_FOUND, listOf("memory $id"))
        return toMemory(row)
    }

    override fun forget(pid: Int, memoryId: String): Boolean =
        memoryRows.deleteWhere("pid = ? AND id = ?", pid, memoryId) > 0

    override fun consolidate(pid: Int, memoryIds: List<String>, at: String): Int {
        var changed = 0
        memoryIds.forEach { id ->
            changed += memoryRows.updateWhere("pid = ? AND id = ?", mapOf("consolidated" to 1), pid, id)
        }
        return changed
    }

    override fun setAffect(pid: Int, input: AffectInput, at: String): AffectState {
        if (findById(pid) == null) {
            throw AgentError(AgentError.AGENT_NOT_FOUND, listOf("pid $pid"))
        }
        val previous = affect(pid)
        affectRows.upsert(
            mapOf(
                "pid" to pid,
                "valence" to (input.valence ?: previous?.valence ?: 0.0),
                "arousal" to (input.arousal ?: previous?.arousal ?: 0.0),
                "stress" to (input.stress ?: previous?.stress ?: 0.0),
                "fatigue" to (input.fatigue ?: previous?.fatigue ?: 0.0),
                "mood" to (input.mood ?: previous?.mood ?: "neutral"),
                "updated_at" to at,
            ),
        )
        return affect(pid) ?: throw AgentError(AgentError.INVALID_AFFECT, listOf("pid $pid"))
    }

    override fun addBackground(input: NewBackgroundProcess): BackgroundProcess {
        if (findById(input.pid) == null) {
            throw AgentError(AgentError.AGENT_NOT_FOUND, listOf("pid ${input.pid}"))
        }
        val next = backgroundRows.queryOne("SELECT COALESCE(MAX(bpid), 0) + 1 AS n FROM background_processes")
            ?.int("n") ?: 1
        val id = "bg.${input.pid}.$next"
        backgroundRows.insert(
            mapOf(
                "id" to id,
                "pid" to input.pid,
                "kind" to input.kind.wire,
                "label" to input.label,
                "intensity" to (input.intensity ?: 0.5),
                "trigger" to input.trigger,
                "script" to input.script,
                "status" to BackgroundStatus.ACTIVE.wire,
                "updated_at" to input.at,
            ),
        )
        val row = backgroundRows.select(SelectOptions(where = "id = ?", params = listOf(id), limit = 1)).firstOrNull()
            ?: throw AgentError(AgentError.AGENT_NOT_FOUND, listOf("background $id"))
        return toBackground(row)
    }

    override fun setBackgroundStatus(pid: Int, id: String, status: BackgroundStatus, at: String): BackgroundProcess {
        val changed = backgroundRows.updateWhere(
            "pid = ? AND id = ?",
            mapOf("status" to status.wire, "updated_at" to at),
            pid,
            id,
        )
        if (changed == 0) {
            throw AgentError(AgentError.AGENT_NOT_FOUND, listOf("background $id"))
        }
        val row = backgroundRows.select(SelectOptions(where = "id = ?", params = listOf(id), limit = 1)).firstOrNull()
            ?: throw AgentError(AgentError.AGENT_NOT_FOUND, listOf("background $id"))
        return toBackground(row)
    }

    override fun recordLifeEvent(input: NewLifeEvent): LifeEvent {
        if (findById(input.pid) == null) {
            throw AgentError(AgentError.AGENT_NOT_FOUND, listOf("pid ${input.pid}"))
        }
        val next = eventRows.queryOne("SELECT COALESCE(MAX(ev_id), 0) + 1 AS n FROM life_events")?.int("n") ?: 1
        val id = "ev.${input.pid}.$next"
        eventRows.insert(
            mapOf(
                "id" to id,
                "pid" to input.pid,
                "kind" to input.kind.wire,
                "summary" to input.summary,
                "at" to input.at,
                "role_id" to input.roleId,
                "impact" to (input.impact ?: 0.5),
                "narrative_version" to input.narrativeVersion,
            ),
        )
        val row = eventRows.select(SelectOptions(where = "id = ?", params = listOf(id), limit = 1)).firstOrNull()
            ?: throw AgentError(AgentError.AGENT_NOT_FOUND, listOf("life event $id"))
        return toLifeEvent(row)
    }
}

private fun obligationToJson(obligation: Obligation): JsonValue = com.eloykim.lyra.amp.protocol.json.jsonObj(
    "id" to JsonValue.Str(obligation.id),
    "statement" to JsonValue.Str(obligation.statement),
    "audience" to obligation.audience?.let { JsonValue.Str(it) },
    "conflictsWith" to obligation.conflictsWith.takeIf { it.isNotEmpty() }
        ?.let { ids -> JsonValue.Arr(ids.map { JsonValue.Str(it) }) },
    "weight" to obligation.weight?.let { com.eloykim.lyra.amp.protocol.json.jsonNumber(it) },
)
