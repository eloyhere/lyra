package com.eloykim.lyra.amp.protocol.database.repository

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.types.Confidence
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Timestamp

/**
 * Narrow port and value objects for agent facets
 * (`src/process/agent.ts` and `src/error/agentError.ts`).
 *
 * Those two sibling ports have not been ported to Kotlin yet, so this file
 * defines exactly the surface `AgentRepository` consumes — the `AgentStore`
 * operations, their inputs and the error codes it throws — with the reference
 * field names and wire spellings. When `src/process/agent.ts` lands, this file
 * should be replaced by that package's types.
 */

/** Memory kind (`src/process/agent.ts` `MemoryKind`). */
enum class MemoryKind(val wire: String) {
    EPISODIC("episodic"),
    SEMANTIC("semantic"),
    PROCEDURAL("procedural"),
    SOCIAL("social"),
    AFFECTIVE("affective"),
    ;

    companion object {
        fun fromWire(value: String): MemoryKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("unknown memory kind: $value")
    }
}

enum class RoleStatus(val wire: String) {
    ACTIVE("active"),
    RETIRED("retired"),
}

enum class BackgroundKind(val wire: String) {
    HABIT("habit"),
    EMOTION("emotion"),
    MONITOR("monitor"),
    DRIVE("drive"),
}

enum class BackgroundStatus(val wire: String) {
    ACTIVE("active"),
    PAUSED("paused"),
    RETIRED("retired"),
    ;

    companion object {
        fun fromWire(value: String): BackgroundStatus =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("unknown background status: $value")
    }
}

enum class LifeEventKind(val wire: String) {
    TURNING_POINT("turning-point"),
    ROLE_TRANSITION("role-transition"),
    RELATIONSHIP("relationship"),
    ACHIEVEMENT("achievement"),
    LOSS("loss"),
    INSIGHT("insight"),
    OTHER("other"),
}

/**
 * Error raised by the agent/social adapters (reference `AgentError`).
 *
 * The reference carries a code plus detail strings; the messages keep that
 * shape so repository tests can pin codes rather than prose.
 */
class AgentError(
    val code: String,
    val details: List<String> = emptyList(),
) : IllegalStateException("$code: ${details.joinToString(", ")}") {

    companion object {
        const val PROFILE_EXISTS: String = "profile_exists"
        const val AGENT_NOT_FOUND: String = "agent_not_found"
        const val ROLE_NOT_FOUND: String = "role_not_found"
        const val ROLE_NOT_ASSIGNED: String = "role_not_assigned"
        const val MEMORY_NOT_FOUND: String = "memory_not_found"
        const val INVALID_AFFECT: String = "invalid_affect"
        const val RELATIONSHIP_NOT_FOUND: String = "relationship_not_found"
        const val INSTITUTION_NOT_FOUND: String = "institution_not_found"
    }
}

/** One role obligation; only the id and statement are required. */
data class Obligation(
    val id: Id,
    val statement: String,
    val audience: String? = null,
    val conflictsWith: List<Id> = emptyList(),
    val weight: Confidence? = null,
) {
    companion object {
        fun fromJson(value: JsonValue): Obligation {
            val obj = value as JsonValue.Obj
            return Obligation(
                id = obj.requireString("id"),
                statement = obj.requireString("statement"),
                audience = obj.string("audience"),
                conflictsWith = obj.arr("conflictsWith")?.items
                    ?.mapNotNull { (it as? JsonValue.Str)?.value }
                    ?: emptyList(),
                weight = obj.double("weight"),
            )
        }
    }
}

data class AgentProfile(
    val pid: Int,
    val name: String,
    val lifeChapter: String,
    val values: List<String>,
    val constraints: List<String>,
    val version: Int,
    val updatedAt: Timestamp,
)

data class SelfNarrative(
    val pid: Int,
    val version: Int,
    val identity: JsonValue.Obj,
    val narrative: String,
    val at: Timestamp,
    val objectSeq: Int? = null,
)

data class Role(
    val id: Id,
    val label: String,
    val obligations: List<Obligation>,
    val expectations: List<String>,
    val audiences: List<String>,
    val scripts: List<String>,
    val status: RoleStatus,
    val createdAt: Timestamp,
)

data class RoleAssignment(
    val role: Role,
    val salience: Double,
    val active: Boolean,
    val since: Timestamp,
)

data class Memory(
    val id: Id,
    val pid: Int,
    val roleId: Id?,
    val kind: MemoryKind,
    val content: String,
    val salience: Double,
    val at: Timestamp,
    val refs: List<Ref>,
    val source: String?,
    val consolidated: Boolean,
)

data class AffectState(
    val pid: Int,
    val valence: Double,
    val arousal: Double,
    val stress: Double,
    val fatigue: Double,
    val mood: String,
    val updatedAt: Timestamp,
)

data class BackgroundProcess(
    val id: Id,
    val pid: Int,
    val kind: BackgroundKind,
    val label: String,
    val intensity: Double,
    val status: BackgroundStatus,
    val updatedAt: Timestamp,
    val trigger: String? = null,
    val script: String? = null,
)

data class LifeEvent(
    val id: Id,
    val pid: Int,
    val kind: LifeEventKind,
    val summary: String,
    val at: Timestamp,
    val impact: Double,
    val roleId: Id? = null,
    val narrativeVersion: Int? = null,
)

data class MemoryQuery(
    val kind: MemoryKind? = null,
    val roleId: Id? = null,
    val minSalience: Double? = null,
    val before: Timestamp? = null,
    val limit: Int? = null,
    val includeConsolidated: Boolean? = null,
)

data class NewAgentProfile(
    val pid: Int,
    val name: String,
    val at: Timestamp,
    val lifeChapter: String? = null,
    val values: List<String>? = null,
    val constraints: List<String>? = null,
)

data class NewNarrative(
    val pid: Int,
    val identity: JsonValue.Obj,
    val narrative: String,
    val at: Timestamp,
)

data class NewRole(
    val id: Id,
    val label: String,
    val createdAt: Timestamp,
    val obligations: List<Obligation>? = null,
    val expectations: List<String>? = null,
    val audiences: List<String>? = null,
    val scripts: List<String>? = null,
)

data class AssignRoleInput(
    val pid: Int,
    val roleId: Id,
    val since: Timestamp,
    val salience: Double? = null,
    val active: Boolean? = null,
)

data class ActivateRoleInput(
    val pid: Int,
    val roleId: Id,
    val at: Timestamp,
)

data class NewMemory(
    val pid: Int,
    val kind: MemoryKind,
    val content: String,
    val at: Timestamp,
    val roleId: Id? = null,
    val salience: Double? = null,
    val refs: List<Ref>? = null,
    val source: String? = null,
)

data class AffectInput(
    val valence: Double? = null,
    val arousal: Double? = null,
    val stress: Double? = null,
    val fatigue: Double? = null,
    val mood: String? = null,
)

data class NewBackgroundProcess(
    val pid: Int,
    val kind: BackgroundKind,
    val label: String,
    val at: Timestamp,
    val intensity: Double? = null,
    val trigger: String? = null,
    val script: String? = null,
)

data class NewLifeEvent(
    val pid: Int,
    val kind: LifeEventKind,
    val summary: String,
    val at: Timestamp,
    val roleId: Id? = null,
    val impact: Double? = null,
    val narrativeVersion: Int? = null,
)

/**
 * Reader/store surface of the agent aggregate (reference `AgentStore`).
 *
 * Only the methods `AgentRepository` implements are listed; the reference
 * `AgentEvent` type belongs to the unported domain package.
 */
interface AgentStore {

    fun profile(pid: Int): AgentProfile?

    fun narrative(pid: Int, version: Int? = null): SelfNarrative?

    fun roles(pid: Int): List<RoleAssignment>

    fun activeRole(pid: Int): RoleAssignment?

    fun memories(pid: Int, query: MemoryQuery = MemoryQuery()): List<Memory>

    fun affect(pid: Int): AffectState?

    fun background(pid: Int, status: BackgroundStatus? = null): List<BackgroundProcess>

    fun lifeEvents(pid: Int, limit: Int = 20): List<LifeEvent>

    fun createProfile(input: NewAgentProfile): AgentProfile

    fun reviseNarrative(input: NewNarrative): SelfNarrative

    fun addRole(input: NewRole): Role

    fun assignRole(input: AssignRoleInput): RoleAssignment

    fun activateRole(input: ActivateRoleInput): RoleAssignment

    fun remember(input: NewMemory): Memory

    fun forget(pid: Int, memoryId: Id): Boolean

    fun consolidate(pid: Int, memoryIds: List<Id>, at: Timestamp): Int

    fun setAffect(pid: Int, input: AffectInput, at: Timestamp): AffectState

    fun addBackground(input: NewBackgroundProcess): BackgroundProcess

    fun setBackgroundStatus(pid: Int, id: Id, status: BackgroundStatus, at: Timestamp): BackgroundProcess

    fun recordLifeEvent(input: NewLifeEvent): LifeEvent
}
