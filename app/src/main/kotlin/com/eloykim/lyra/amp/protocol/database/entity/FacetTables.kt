package com.eloykim.lyra.amp.protocol.database.entity

import com.eloykim.lyra.amp.protocol.database.sql.Row

/**
 * Thin table metadata for agent facets, social facts and runtime snapshots
 * (port of `src/database/entity/{agent,outbox,runtimeSession,runtimeTurn,runtimeApproval}.ts`).
 *
 * The reference reserves `defineEntity` for aggregate roots that need
 * associations; these child tables are simple and are queried through
 * `TableSql`. The row interfaces are reproduced as data classes so repository
 * mapping stays typed.
 */

/** Row shape of `person_profiles`. */
data class AgentProfileRow(
    val pid: Int,
    val name: String,
    val narrative: String,
    val values_json: String,
    val constraints_json: String,
    val life_chapter: String,
    val updated_at: String,
    val version: Int,
) {
    companion object {
        fun fromRow(row: Row): AgentProfileRow = AgentProfileRow(
            pid = row.requireInt("pid"),
            name = row.requireString("name"),
            narrative = row.requireString("narrative"),
            values_json = row.requireString("values_json"),
            constraints_json = row.requireString("constraints_json"),
            life_chapter = row.requireString("life_chapter"),
            updated_at = row.requireString("updated_at"),
            version = row.requireInt("version"),
        )
    }
}

val AgentProfileTable: TableMeta = TableMeta(
    table = "person_profiles",
    primaryKey = "pid",
    columns = listOf("pid", "name", "narrative", "values_json", "constraints_json", "life_chapter", "updated_at", "version"),
)

/** Row shape of `self_narratives`. */
data class SelfNarrativeRow(
    val snid: Int,
    val pid: Int,
    val version: Int,
    val identity_json: String,
    val narrative: String,
    val at: String,
    val object_seq: Int?,
) {
    companion object {
        fun fromRow(row: Row): SelfNarrativeRow = SelfNarrativeRow(
            snid = row.requireInt("snid"),
            pid = row.requireInt("pid"),
            version = row.requireInt("version"),
            identity_json = row.requireString("identity_json"),
            narrative = row.requireString("narrative"),
            at = row.requireString("at"),
            object_seq = row.int("object_seq"),
        )
    }
}

val SelfNarrativeTable: TableMeta = TableMeta(
    table = "self_narratives",
    primaryKey = "snid",
    columns = listOf("snid", "pid", "version", "identity_json", "narrative", "at", "object_seq"),
)

/** Row shape of `roles`. */
data class RoleRow(
    val rid: Int,
    val id: String,
    val label: String,
    val obligations_json: String,
    val permissions_json: String,
    val audiences_json: String,
    val scripts_json: String,
    val status: String,
    val created_at: String,
) {
    companion object {
        fun fromRow(row: Row): RoleRow = RoleRow(
            rid = row.requireInt("rid"),
            id = row.requireString("id"),
            label = row.requireString("label"),
            obligations_json = row.requireString("obligations_json"),
            permissions_json = row.requireString("permissions_json"),
            audiences_json = row.requireString("audiences_json"),
            scripts_json = row.requireString("scripts_json"),
            status = row.requireString("status"),
            created_at = row.requireString("created_at"),
        )
    }
}

val RoleTable: TableMeta = TableMeta(
    table = "roles",
    primaryKey = "rid",
    columns = listOf("rid", "id", "label", "obligations_json", "permissions_json", "audiences_json", "scripts_json", "status", "created_at"),
)

/** Row shape of `person_roles`; the schema key is `(pid, rid)`. */
data class PersonRoleRow(
    val pid: Int,
    val rid: Int,
    val salience: Double,
    val active: Int,
    val since: String,
) {
    companion object {
        fun fromRow(row: Row): PersonRoleRow = PersonRoleRow(
            pid = row.requireInt("pid"),
            rid = row.requireInt("rid"),
            salience = row.requireDouble("salience"),
            active = row.requireInt("active"),
            since = row.requireString("since"),
        )
    }
}

/** Composite key in the schema; `pid` is only used as a selector here. */
val PersonRoleTable: TableMeta = TableMeta(
    table = "person_roles",
    primaryKey = "pid",
    columns = listOf("pid", "rid", "salience", "active", "since"),
)

/** Row shape of `memories`. */
data class MemoryRow(
    val mem_id: Int,
    val id: String,
    val pid: Int,
    val role_id: String?,
    val kind: String,
    val content: String,
    val salience: Double,
    val at: String,
    val refs_json: String,
    val source: String?,
    val consolidated: Int,
) {
    companion object {
        fun fromRow(row: Row): MemoryRow = MemoryRow(
            mem_id = row.requireInt("mem_id"),
            id = row.requireString("id"),
            pid = row.requireInt("pid"),
            role_id = row.string("role_id"),
            kind = row.requireString("kind"),
            content = row.requireString("content"),
            salience = row.requireDouble("salience"),
            at = row.requireString("at"),
            refs_json = row.requireString("refs_json"),
            source = row.string("source"),
            consolidated = row.requireInt("consolidated"),
        )
    }
}

val MemoryTable: TableMeta = TableMeta(
    table = "memories",
    primaryKey = "mem_id",
    columns = listOf("mem_id", "id", "pid", "role_id", "kind", "content", "salience", "at", "refs_json", "source", "consolidated"),
)

/** Row shape of `affect_states`. */
data class AffectRow(
    val pid: Int,
    val valence: Double,
    val arousal: Double,
    val stress: Double,
    val fatigue: Double,
    val mood: String,
    val updated_at: String,
) {
    companion object {
        fun fromRow(row: Row): AffectRow = AffectRow(
            pid = row.requireInt("pid"),
            valence = row.requireDouble("valence"),
            arousal = row.requireDouble("arousal"),
            stress = row.requireDouble("stress"),
            fatigue = row.requireDouble("fatigue"),
            mood = row.requireString("mood"),
            updated_at = row.requireString("updated_at"),
        )
    }
}

val AffectTable: TableMeta = TableMeta(
    table = "affect_states",
    primaryKey = "pid",
    columns = listOf("pid", "valence", "arousal", "stress", "fatigue", "mood", "updated_at"),
)

/** Row shape of `relationships`. */
data class RelationshipRow(
    val rel_id: Int,
    val id: String,
    val from_pid: Int,
    val to_pid: Int,
    val kind: String,
    val trust: Double,
    val attachment: Double,
    val obligations_json: String,
    val since: String,
    val last_contact: String?,
) {
    companion object {
        fun fromRow(row: Row): RelationshipRow = RelationshipRow(
            rel_id = row.requireInt("rel_id"),
            id = row.requireString("id"),
            from_pid = row.requireInt("from_pid"),
            to_pid = row.requireInt("to_pid"),
            kind = row.requireString("kind"),
            trust = row.requireDouble("trust"),
            attachment = row.requireDouble("attachment"),
            obligations_json = row.requireString("obligations_json"),
            since = row.requireString("since"),
            last_contact = row.string("last_contact"),
        )
    }
}

val RelationshipTable: TableMeta = TableMeta(
    table = "relationships",
    primaryKey = "rel_id",
    columns = listOf("rel_id", "id", "from_pid", "to_pid", "kind", "trust", "attachment", "obligations_json", "since", "last_contact"),
)

/** Row shape of `institutions`. */
data class InstitutionRow(
    val iid: Int,
    val id: String,
    val name: String,
    val norms_json: String,
) {
    companion object {
        fun fromRow(row: Row): InstitutionRow = InstitutionRow(
            iid = row.requireInt("iid"),
            id = row.requireString("id"),
            name = row.requireString("name"),
            norms_json = row.requireString("norms_json"),
        )
    }
}

val InstitutionTable: TableMeta = TableMeta(
    table = "institutions",
    primaryKey = "iid",
    columns = listOf("iid", "id", "name", "norms_json"),
)

/** Row shape of `memberships`; the schema key is `(pid, iid)`. */
data class MembershipRow(
    val pid: Int,
    val iid: Int,
    val role: String,
    val status: String,
    val since: String,
) {
    companion object {
        fun fromRow(row: Row): MembershipRow = MembershipRow(
            pid = row.requireInt("pid"),
            iid = row.requireInt("iid"),
            role = row.requireString("role"),
            status = row.requireString("status"),
            since = row.requireString("since"),
        )
    }
}

/** Composite key in the schema; `pid` is only used as a selector here. */
val MembershipTable: TableMeta = TableMeta(
    table = "memberships",
    primaryKey = "pid",
    columns = listOf("pid", "iid", "role", "status", "since"),
)

/** Row shape of `background_processes`. */
data class BackgroundRow(
    val bpid: Int,
    val id: String,
    val pid: Int,
    val kind: String,
    val label: String,
    val intensity: Double,
    val trigger: String?,
    val script: String?,
    val status: String,
    val updated_at: String,
) {
    companion object {
        fun fromRow(row: Row): BackgroundRow = BackgroundRow(
            bpid = row.requireInt("bpid"),
            id = row.requireString("id"),
            pid = row.requireInt("pid"),
            kind = row.requireString("kind"),
            label = row.requireString("label"),
            intensity = row.requireDouble("intensity"),
            trigger = row.string("trigger"),
            script = row.string("script"),
            status = row.requireString("status"),
            updated_at = row.requireString("updated_at"),
        )
    }
}

val BackgroundTable: TableMeta = TableMeta(
    table = "background_processes",
    primaryKey = "bpid",
    columns = listOf("bpid", "id", "pid", "kind", "label", "intensity", "trigger", "script", "status", "updated_at"),
)

/** Row shape of `life_events`. */
data class LifeEventRow(
    val ev_id: Int,
    val id: String,
    val pid: Int,
    val kind: String,
    val summary: String,
    val at: String,
    val role_id: String?,
    val impact: Double,
    val narrative_version: Int?,
) {
    companion object {
        fun fromRow(row: Row): LifeEventRow = LifeEventRow(
            ev_id = row.requireInt("ev_id"),
            id = row.requireString("id"),
            pid = row.requireInt("pid"),
            kind = row.requireString("kind"),
            summary = row.requireString("summary"),
            at = row.requireString("at"),
            role_id = row.string("role_id"),
            impact = row.requireDouble("impact"),
            narrative_version = row.int("narrative_version"),
        )
    }
}

val LifeEventTable: TableMeta = TableMeta(
    table = "life_events",
    primaryKey = "ev_id",
    columns = listOf("ev_id", "id", "pid", "kind", "summary", "at", "role_id", "impact", "narrative_version"),
)

/** Row shape of `outbox`. */
data class OutboxEntity(
    val id: Int,
    val topic: String,
    val payload: String,
    val created_at: String,
    val dispatched_at: String?,
) {
    companion object {
        fun fromRow(row: Row): OutboxEntity = OutboxEntity(
            id = row.requireInt("id"),
            topic = row.requireString("topic"),
            payload = row.requireString("payload"),
            created_at = row.requireString("created_at"),
            dispatched_at = row.string("dispatched_at"),
        )
    }
}

val OutboxTable: TableMeta = TableMeta(
    table = "outbox",
    primaryKey = "id",
    columns = listOf("id", "topic", "payload", "created_at", "dispatched_at"),
)

/** Row shape of `runtime_sessions`. */
data class RuntimeSessionRow(
    val pid: Int,
    val data: String,
    val updated_at: String,
) {
    companion object {
        fun fromRow(row: Row): RuntimeSessionRow = RuntimeSessionRow(
            pid = row.requireInt("pid"),
            data = row.requireString("data"),
            updated_at = row.requireString("updated_at"),
        )
    }
}

val RuntimeSessionTable: TableMeta = TableMeta(
    table = "runtime_sessions",
    primaryKey = "pid",
    columns = listOf("pid", "data", "updated_at"),
)

/** Row shape of `runtime_turns`. */
data class RuntimeTurnRow(
    val id: String,
    val pid: Int,
    val session_id: String?,
    val data: String,
    val created_at: String,
) {
    companion object {
        fun fromRow(row: Row): RuntimeTurnRow = RuntimeTurnRow(
            id = row.requireString("id"),
            pid = row.requireInt("pid"),
            session_id = row.string("session_id"),
            data = row.requireString("data"),
            created_at = row.requireString("created_at"),
        )
    }
}

val RuntimeTurnTable: TableMeta = TableMeta(
    table = "runtime_turns",
    primaryKey = "id",
    columns = listOf("id", "pid", "session_id", "data", "created_at"),
)

/** Row shape of `runtime_approvals`. */
data class RuntimeApprovalRow(
    val id: Int,
    val pid: Int,
    val turn_id: String,
    val operation_id: String,
    val tool: String,
    val risk: String,
    val decision: String,
    val status: String,
    val created_at: String,
) {
    companion object {
        fun fromRow(row: Row): RuntimeApprovalRow = RuntimeApprovalRow(
            id = row.requireInt("id"),
            pid = row.requireInt("pid"),
            turn_id = row.requireString("turn_id"),
            operation_id = row.requireString("operation_id"),
            tool = row.requireString("tool"),
            risk = row.requireString("risk"),
            decision = row.requireString("decision"),
            status = row.requireString("status"),
            created_at = row.requireString("created_at"),
        )
    }
}

val RuntimeApprovalTable: TableMeta = TableMeta(
    table = "runtime_approvals",
    primaryKey = "id",
    columns = listOf("id", "pid", "turn_id", "operation_id", "tool", "risk", "decision", "status", "created_at"),
)

/** Input accepted by `RuntimeApprovalRepository.record`. */
data class RuntimeApprovalInput(
    val pid: Int,
    val turnId: String,
    val operationId: String,
    val tool: String,
    val risk: String,
    val decision: String,
    val status: String,
    val at: String,
)
