package com.eloykim.lyra.amp.protocol.database.entity

import com.eloykim.lyra.amp.protocol.database.sql.Row
import com.eloykim.lyra.amp.protocol.store.HandleEntity as StoreHandleEntity
import com.eloykim.lyra.amp.protocol.store.MessageEntity as StoreMessageEntity
import com.eloykim.lyra.amp.protocol.store.ObjectEntity as StoreObjectEntity
import com.eloykim.lyra.amp.protocol.store.ProcessEntity as StoreProcessEntity
import com.eloykim.lyra.amp.protocol.store.ThreadEntity as StoreThreadEntity
import com.eloykim.lyra.amp.protocol.store.WindowEntity as StoreWindowEntity
import com.eloykim.lyra.amp.protocol.types.Digest

/**
 * Row shapes and JPA metadata for the kernel tables (port of
 * `src/database/entity/{object,process,thread,handle,message,worker,window,registry}.ts`).
 *
 * The reference interfaces are structural, so the same type doubles as the row
 * and as the `store` package cache projection. Kotlin does the same by
 * implementing the narrow interfaces from `com.eloykim.lyra.amp.protocol.store`
 * ([StoreObjectEntity], [StoreProcessEntity], [StoreThreadEntity],
 * [StoreHandleEntity], [StoreMessageEntity], [StoreWindowEntity]). That is the
 * seam `store/Context.kt` documents as "a database-layer row type implements it
 * when that layer lands".
 *
 * JSON columns stay TEXT (`budget`, `used`, `success`, `rights`, `digest`): the
 * reference never parses them in this layer and neither does this port.
 */

/** Row shape of `objects`; bodies stay canonical JSON strings. */
data class ObjectEntity(
    override val seq: Int,
    val kind: String,
    val id: String,
    val body: String,
    val hash: Digest,
    val prev: Digest,
    val at: String,
) : StoreObjectEntity {

    companion object {
        fun fromRow(row: Row): ObjectEntity = ObjectEntity(
            seq = row.requireInt("seq"),
            kind = row.requireString("kind"),
            id = row.requireString("id"),
            body = row.requireString("body"),
            hash = row.requireString("hash"),
            prev = row.requireString("prev"),
            at = row.requireString("at"),
        )
    }
}

/** JPA metadata for the append-only object table. */
val ObjectEntityMeta: Entity = defineEntity(
    Entity(
        name = "Object",
        table = Table(
            name = "objects",
            uniqueConstraints = listOf(UniqueConstraint(columnNames = listOf("kind", "id"))),
        ),
        fields = listOf(
            Field("seq", id = true, generated = GeneratedValue(GenerationType.IDENTITY)),
            Field("kind"),
            Field("id"),
            Field("body"),
            Field("hash"),
            Field("prev"),
            Field("at"),
        ),
    ),
)

val ObjectTable: TableMeta = toTableMeta(ObjectEntityMeta)

/** Row shape of `processes`: a persistent agent, not a task. */
data class ProcessEntity(
    override val pid: Int,
    val id: String,
    val name: String,
    val role: String?,
    val state: String,
    val priority: Int,
    val budget: String,
    val used: String,
    val parent_pid: Int?,
    val created_at: String,
    val started_at: String?,
    val terminated_at: String?,
    val exit_code: String?,
    val exit_note: String?,
    val version: Int,
) : StoreProcessEntity {

    companion object {
        fun fromRow(row: Row): ProcessEntity = ProcessEntity(
            pid = row.requireInt("pid"),
            id = row.requireString("id"),
            name = row.requireString("name"),
            role = row.string("role"),
            state = row.requireString("state"),
            priority = row.requireInt("priority"),
            budget = row.requireString("budget"),
            used = row.requireString("used"),
            parent_pid = row.int("parent_pid"),
            created_at = row.requireString("created_at"),
            started_at = row.string("started_at"),
            terminated_at = row.string("terminated_at"),
            exit_code = row.string("exit_code"),
            exit_note = row.string("exit_note"),
            version = row.requireInt("version"),
        )
    }
}

/** JPA metadata for the process/agent table. */
val ProcessEntityMeta: Entity = defineEntity(
    Entity(
        name = "Process",
        table = Table(
            name = "processes",
            uniqueConstraints = listOf(UniqueConstraint(columnNames = listOf("id"))),
        ),
        fields = listOf(
            Field("pid", id = true, generated = GeneratedValue(GenerationType.IDENTITY)),
            Field("id"),
            Field("name"),
            Field("role"),
            Field("state"),
            Field("priority"),
            Field("budget"),
            Field("used"),
            Field("parent_pid"),
            Field("created_at"),
            Field("started_at"),
            Field("terminated_at"),
            Field("exit_code"),
            Field("exit_note"),
            Field("version", version = true),
        ),
        relations = listOf(
            ManyToOne(
                name = "parent",
                target = EntityTarget("Process"),
                optional = true,
                joinColumns = listOf(JoinColumn(name = "parent_pid")),
            ),
        ),
    ),
)

val ProcessTable: TableMeta = toTableMeta(ProcessEntityMeta)

/** Row shape of `threads`: one task/goal an agent is executing. */
data class ThreadEntity(
    override val tid: Int,
    val id: String,
    val pid: Int,
    val goal: String,
    val statement: String,
    val success: String,
    val state: String,
    val priority: Int,
    val quantum_ms: Int,
    val slice_used_ms: Int,
    val budget: String,
    val used: String,
    val blocked_on: String?,
    val wake_at: String?,
    val created_at: String,
    val finished_at: String?,
    val exit_note: String?,
    val owner_worker: String?,
    val lease: Int,
    val version: Int,
) : StoreThreadEntity {

    companion object {
        fun fromRow(row: Row): ThreadEntity = ThreadEntity(
            tid = row.requireInt("tid"),
            id = row.requireString("id"),
            pid = row.requireInt("pid"),
            goal = row.requireString("goal"),
            statement = row.requireString("statement"),
            success = row.requireString("success"),
            state = row.requireString("state"),
            priority = row.requireInt("priority"),
            quantum_ms = row.requireInt("quantum_ms"),
            slice_used_ms = row.requireInt("slice_used_ms"),
            budget = row.requireString("budget"),
            used = row.requireString("used"),
            blocked_on = row.string("blocked_on"),
            wake_at = row.string("wake_at"),
            created_at = row.requireString("created_at"),
            finished_at = row.string("finished_at"),
            exit_note = row.string("exit_note"),
            owner_worker = row.string("owner_worker"),
            lease = row.requireInt("lease"),
            version = row.requireInt("version"),
        )
    }
}

/** JPA metadata for the thread/task table. */
val ThreadEntityMeta: Entity = defineEntity(
    Entity(
        name = "Thread",
        table = Table(
            name = "threads",
            uniqueConstraints = listOf(UniqueConstraint(columnNames = listOf("id"))),
        ),
        fields = listOf(
            Field("tid", id = true, generated = GeneratedValue(GenerationType.IDENTITY)),
            Field("id"),
            Field("pid"),
            Field("goal"),
            Field("statement"),
            Field("success"),
            Field("state"),
            Field("priority"),
            Field("quantum_ms"),
            Field("slice_used_ms"),
            Field("budget"),
            Field("used"),
            Field("blocked_on"),
            Field("wake_at"),
            Field("created_at"),
            Field("finished_at"),
            Field("exit_note"),
            Field("owner_worker"),
            Field("lease"),
            Field("version", version = true),
        ),
        relations = listOf(
            ManyToOne(
                name = "process",
                target = EntityTarget("Process"),
                joinColumns = listOf(JoinColumn(name = "pid")),
            ),
            OneToMany(
                name = "steps",
                target = EntityTarget("Step"),
                mappedBy = "tid",
            ),
            ManyToOne(
                name = "ownerWorker",
                target = EntityTarget("Worker"),
                optional = true,
                joinColumns = listOf(JoinColumn(name = "owner_worker")),
            ),
        ),
    ),
)

val ThreadTable: TableMeta = toTableMeta(ThreadEntityMeta)

/** Row shape of the capability/handle table. */
data class HandleEntity(
    override val hid: Int,
    val id: String,
    val subject_kind: String,
    val subject_id: String,
    val subject_pid: Int?,
    val resource_kind: String,
    val resource_id: String,
    val owner_pid: Int?,
    val rights: String,
    val scope: String,
    val parent_hid: Int?,
    val refcount: Int,
    val expires_at: String?,
    val revoked_at: String?,
    val granted_by: String?,
    val created_at: String,
) : StoreHandleEntity {

    companion object {
        fun fromRow(row: Row): HandleEntity = HandleEntity(
            hid = row.requireInt("hid"),
            id = row.requireString("id"),
            subject_kind = row.requireString("subject_kind"),
            subject_id = row.requireString("subject_id"),
            subject_pid = row.int("subject_pid"),
            resource_kind = row.requireString("resource_kind"),
            resource_id = row.requireString("resource_id"),
            owner_pid = row.int("owner_pid"),
            rights = row.requireString("rights"),
            scope = row.requireString("scope"),
            parent_hid = row.int("parent_hid"),
            refcount = row.requireInt("refcount"),
            expires_at = row.string("expires_at"),
            revoked_at = row.string("revoked_at"),
            granted_by = row.string("granted_by"),
            created_at = row.requireString("created_at"),
        )
    }
}

/** JPA metadata for the handle/capability table. */
val HandleEntityMeta: Entity = defineEntity(
    Entity(
        name = "Handle",
        table = Table(
            name = "handles",
            uniqueConstraints = listOf(UniqueConstraint(columnNames = listOf("id"))),
        ),
        fields = listOf(
            Field("hid", id = true, generated = GeneratedValue(GenerationType.IDENTITY)),
            Field("id"),
            Field("subject_kind"),
            Field("subject_id"),
            Field("subject_pid"),
            Field("resource_kind"),
            Field("resource_id"),
            Field("owner_pid"),
            Field("rights"),
            Field("scope"),
            Field("parent_hid"),
            Field("refcount"),
            Field("expires_at"),
            Field("revoked_at"),
            Field("granted_by"),
            Field("created_at"),
        ),
        relations = listOf(
            ManyToOne(
                name = "parent",
                target = EntityTarget("Handle"),
                optional = true,
                joinColumns = listOf(JoinColumn(name = "parent_hid")),
            ),
            ManyToOne(
                name = "subjectProcess",
                target = EntityTarget("Process"),
                optional = true,
                joinColumns = listOf(JoinColumn(name = "subject_pid")),
            ),
            ManyToOne(
                name = "ownerProcess",
                target = EntityTarget("Process"),
                optional = true,
                joinColumns = listOf(JoinColumn(name = "owner_pid")),
            ),
        ),
    ),
)

val HandleTable: TableMeta = toTableMeta(HandleEntityMeta)

/** Row shape of the inter-process mailbox table. */
data class MessageEntity(
    override val msg_id: Int,
    val id: String,
    val from_pid: Int,
    val from_tid: Int,
    val to_pid: Int,
    val to_tid: Int?,
    val kind: String,
    val body: String,
    val correlation_id: String?,
    val at: String,
    val delivered_at: String?,
    val receipt_id: String?,
) : StoreMessageEntity {

    companion object {
        fun fromRow(row: Row): MessageEntity = MessageEntity(
            msg_id = row.requireInt("msg_id"),
            id = row.requireString("id"),
            from_pid = row.requireInt("from_pid"),
            from_tid = row.requireInt("from_tid"),
            to_pid = row.requireInt("to_pid"),
            to_tid = row.int("to_tid"),
            kind = row.requireString("kind"),
            body = row.requireString("body"),
            correlation_id = row.string("correlation_id"),
            at = row.requireString("at"),
            delivered_at = row.string("delivered_at"),
            receipt_id = row.string("receipt_id"),
        )
    }
}

/** JPA metadata for the mailbox table. */
val MessageEntityMeta: Entity = defineEntity(
    Entity(
        name = "Message",
        table = Table(
            name = "messages",
            uniqueConstraints = listOf(UniqueConstraint(columnNames = listOf("id"))),
        ),
        fields = listOf(
            Field("msg_id", id = true, generated = GeneratedValue(GenerationType.IDENTITY)),
            Field("id"),
            Field("from_pid"),
            Field("from_tid"),
            Field("to_pid"),
            Field("to_tid"),
            Field("kind"),
            Field("body"),
            Field("correlation_id"),
            Field("at"),
            Field("delivered_at"),
            Field("receipt_id"),
        ),
        relations = listOf(
            ManyToOne(
                name = "fromProcess",
                target = EntityTarget("Process"),
                joinColumns = listOf(JoinColumn(name = "from_pid")),
            ),
            ManyToOne(
                name = "toProcess",
                target = EntityTarget("Process"),
                joinColumns = listOf(JoinColumn(name = "to_pid")),
            ),
            ManyToOne(
                name = "fromThread",
                target = EntityTarget("Thread"),
                joinColumns = listOf(JoinColumn(name = "from_tid")),
            ),
            ManyToOne(
                name = "toThread",
                target = EntityTarget("Thread"),
                optional = true,
                joinColumns = listOf(JoinColumn(name = "to_tid")),
            ),
        ),
    ),
)

val MessageTable: TableMeta = toTableMeta(MessageEntityMeta)

/** Row shape of the worker heartbeat registry. */
data class WorkerEntity(
    val worker_id: String,
    val host: String?,
    val pid: Int?,
    val started_at: String,
    val heartbeat_at: String,
    val expires_at: String,
) {

    companion object {
        fun fromRow(row: Row): WorkerEntity = WorkerEntity(
            worker_id = row.requireString("worker_id"),
            host = row.string("host"),
            pid = row.int("pid"),
            started_at = row.requireString("started_at"),
            heartbeat_at = row.requireString("heartbeat_at"),
            expires_at = row.requireString("expires_at"),
        )
    }
}

/** JPA metadata for the worker registry. */
val WorkerEntityMeta: Entity = defineEntity(
    Entity(
        name = "Worker",
        table = Table(name = "workers"),
        fields = listOf(
            Field("worker_id", id = true),
            Field("host"),
            Field("pid"),
            Field("started_at"),
            Field("heartbeat_at"),
            Field("expires_at"),
        ),
        relations = listOf(
            ManyToOne(
                name = "process",
                target = EntityTarget("Process"),
                optional = true,
                joinColumns = listOf(JoinColumn(name = "pid")),
            ),
        ),
    ),
)

val WorkerTable: TableMeta = toTableMeta(WorkerEntityMeta)

/** Row shape of one execution step. */
data class StepEntity(
    val step_id: Int,
    val pid: Int,
    val tid: Int,
    val at: String,
    val outcome: String,
    val usage: String,
    val note: String?,
    val receipt_id: String?,
) {

    companion object {
        fun fromRow(row: Row): StepEntity = StepEntity(
            step_id = row.requireInt("step_id"),
            pid = row.requireInt("pid"),
            tid = row.requireInt("tid"),
            at = row.requireString("at"),
            outcome = row.requireString("outcome"),
            usage = row.requireString("usage"),
            note = row.string("note"),
            receipt_id = row.string("receipt_id"),
        )
    }
}

/** JPA metadata for execution steps. */
val StepEntityMeta: Entity = defineEntity(
    Entity(
        name = "Step",
        table = Table(name = "steps"),
        fields = listOf(
            Field("step_id", id = true, generated = GeneratedValue(GenerationType.IDENTITY)),
            Field("pid"),
            Field("tid"),
            Field("at"),
            Field("outcome"),
            Field("usage"),
            Field("note"),
            Field("receipt_id"),
        ),
        relations = listOf(
            ManyToOne(
                name = "process",
                target = EntityTarget("Process"),
                joinColumns = listOf(JoinColumn(name = "pid")),
            ),
            ManyToOne(
                name = "thread",
                target = EntityTarget("Thread"),
                joinColumns = listOf(JoinColumn(name = "tid")),
            ),
        ),
    ),
)

val StepTable: TableMeta = toTableMeta(StepEntityMeta)

/** Row shape of a thread's context window. */
data class WindowEntity(
    override val tid: Int,
    val capacity_tokens: Int,
    val used_tokens: Int,
    val updated_at: String,
) : StoreWindowEntity {

    companion object {
        fun fromRow(row: Row): WindowEntity = WindowEntity(
            tid = row.requireInt("tid"),
            capacity_tokens = row.requireInt("capacity_tokens"),
            used_tokens = row.requireInt("used_tokens"),
            updated_at = row.requireString("updated_at"),
        )
    }
}

/** JPA metadata for context windows. */
val WindowEntityMeta: Entity = defineEntity(
    Entity(
        name = "Window",
        table = Table(name = "windows"),
        fields = listOf(
            Field("tid", id = true),
            Field("capacity_tokens"),
            Field("used_tokens"),
            Field("updated_at"),
        ),
        relations = listOf(
            ManyToOne(
                name = "thread",
                target = EntityTarget("Thread"),
                joinColumns = listOf(JoinColumn(name = "tid")),
            ),
            OneToMany(
                name = "pages",
                target = EntityTarget("WindowPage"),
                mappedBy = "tid",
            ),
        ),
    ),
)

val WindowTable: TableMeta = toTableMeta(WindowEntityMeta)

/** Row shape of one resident window page. */
data class WindowPageEntity(
    val tid: Int,
    val page_id: String,
    val object_kind: String,
    val object_id: String,
    val tokens: Int,
    val pinned: Int,
    val last_used: Int,
) {

    companion object {
        fun fromRow(row: Row): WindowPageEntity = WindowPageEntity(
            tid = row.requireInt("tid"),
            page_id = row.requireString("page_id"),
            object_kind = row.requireString("object_kind"),
            object_id = row.requireString("object_id"),
            tokens = row.requireInt("tokens"),
            pinned = row.requireInt("pinned"),
            last_used = row.requireInt("last_used"),
        )
    }
}

/** JPA metadata for window pages. */
val WindowPageEntityMeta: Entity = defineEntity(
    Entity(
        name = "WindowPage",
        table = Table(name = "window_pages"),
        fields = listOf(
            Field("tid"),
            Field("page_id", id = true),
            Field("object_kind"),
            Field("object_id"),
            Field("tokens"),
            Field("pinned"),
            Field("last_used"),
        ),
        relations = listOf(
            ManyToOne(
                name = "window",
                target = EntityTarget("Window"),
                joinColumns = listOf(JoinColumn(name = "tid")),
            ),
        ),
    ),
)

val WindowPageTable: TableMeta = toTableMeta(WindowPageEntityMeta)

/** Current state of one concept; history lives in `objects`. */
data class ConceptEntity(
    val cid: Int,
    val id: String,
    val label: String,
    val definition: String,
    val status: String,
    val version: Int,
    val object_seq: Int?,
    val digest: String?,
    val created_at: String,
    val updated_at: String,
) {

    companion object {
        fun fromRow(row: Row): ConceptEntity = ConceptEntity(
            cid = row.requireInt("cid"),
            id = row.requireString("id"),
            label = row.requireString("label"),
            definition = row.requireString("definition"),
            status = row.requireString("status"),
            version = row.requireInt("version"),
            object_seq = row.int("object_seq"),
            digest = row.string("digest"),
            created_at = row.requireString("created_at"),
            updated_at = row.requireString("updated_at"),
        )
    }
}

/** JPA metadata for the concept registry. */
val ConceptEntityMeta: Entity = defineEntity(
    Entity(
        name = "Concept",
        table = Table(
            name = "concepts",
            uniqueConstraints = listOf(UniqueConstraint(columnNames = listOf("id"))),
        ),
        fields = listOf(
            Field("cid", id = true, generated = GeneratedValue(GenerationType.IDENTITY)),
            Field("id"),
            Field("label"),
            Field("definition"),
            Field("status"),
            Field("version", version = true),
            Field("object_seq"),
            Field("digest"),
            Field("created_at"),
            Field("updated_at"),
        ),
    ),
)

val ConceptTable: TableMeta = toTableMeta(ConceptEntityMeta)

/** Current state of one module (a code directory is only its physical shadow). */
data class ModuleEntity(
    val mid: Int,
    val id: String,
    val kind: String,
    val entry: String,
    val description: String,
    val status: String,
    val version: Int,
    val object_seq: Int?,
    val digest: String?,
    val content_digest: String?,
    val created_at: String,
    val updated_at: String,
) {

    companion object {
        fun fromRow(row: Row): ModuleEntity = ModuleEntity(
            mid = row.requireInt("mid"),
            id = row.requireString("id"),
            kind = row.requireString("kind"),
            entry = row.requireString("entry"),
            description = row.requireString("description"),
            status = row.requireString("status"),
            version = row.requireInt("version"),
            object_seq = row.int("object_seq"),
            digest = row.string("digest"),
            content_digest = row.string("content_digest"),
            created_at = row.requireString("created_at"),
            updated_at = row.requireString("updated_at"),
        )
    }
}

/** JPA metadata for the module registry. */
val ModuleEntityMeta: Entity = defineEntity(
    Entity(
        name = "Module",
        table = Table(
            name = "modules",
            uniqueConstraints = listOf(UniqueConstraint(columnNames = listOf("id"))),
        ),
        fields = listOf(
            Field("mid", id = true, generated = GeneratedValue(GenerationType.IDENTITY)),
            Field("id"),
            Field("kind"),
            Field("entry"),
            Field("description"),
            Field("status"),
            Field("version", version = true),
            Field("object_seq"),
            Field("digest"),
            Field("content_digest"),
            Field("created_at"),
            Field("updated_at"),
        ),
    ),
)

val ModuleTable: TableMeta = toTableMeta(ModuleEntityMeta)

/** Link between a module and a concept. */
data class ModuleConceptEntity(
    val mid: Int,
    val cid: Int,
    val role: String,
)

/** JPA metadata for the module-concept join table (composite identifier). */
val ModuleConceptEntityMeta: Entity = defineEntity(
    Entity(
        name = "ModuleConcept",
        table = Table(name = "module_concepts"),
        idClass = "ModuleConceptId",
        fields = listOf(
            Field("mid", id = true),
            Field("cid", id = true),
            Field("role", id = true),
        ),
    ),
)

/** Current state of one attestation. */
data class AttestationEntity(
    val aid: Int,
    val subject_kind: String,
    val subject_id: String,
    val claim_id: String,
    val object_seq: Int?,
    val at: String,
) {

    companion object {
        fun fromRow(row: Row): AttestationEntity = AttestationEntity(
            aid = row.requireInt("aid"),
            subject_kind = row.requireString("subject_kind"),
            subject_id = row.requireString("subject_id"),
            claim_id = row.requireString("claim_id"),
            object_seq = row.int("object_seq"),
            at = row.requireString("at"),
        )
    }
}

/** JPA metadata for registry attestations. */
val AttestationEntityMeta: Entity = defineEntity(
    Entity(
        name = "Attestation",
        table = Table(name = "attestations"),
        fields = listOf(
            Field("aid", id = true, generated = GeneratedValue(GenerationType.IDENTITY)),
            Field("subject_kind"),
            Field("subject_id"),
            Field("claim_id"),
            Field("object_seq"),
            Field("at"),
        ),
    ),
)

val AttestationTable: TableMeta = toTableMeta(AttestationEntityMeta)
