package com.eloykim.lyra.amp.protocol.database.repository

import com.eloykim.lyra.amp.protocol.concurrency.RecoveryReport
import com.eloykim.lyra.amp.protocol.concurrency.WorkerInput
import com.eloykim.lyra.amp.protocol.concurrency.WorkerRecord
import com.eloykim.lyra.amp.protocol.concurrency.WorkerStore
import com.eloykim.lyra.amp.protocol.database.entity.AttestationEntity
import com.eloykim.lyra.amp.protocol.database.entity.AttestationTable
import com.eloykim.lyra.amp.protocol.database.entity.ConceptEntity
import com.eloykim.lyra.amp.protocol.database.entity.ConceptTable
import com.eloykim.lyra.amp.protocol.database.entity.HandleEntity
import com.eloykim.lyra.amp.protocol.database.entity.HandleTable
import com.eloykim.lyra.amp.protocol.database.entity.MessageEntity
import com.eloykim.lyra.amp.protocol.database.entity.MessageTable
import com.eloykim.lyra.amp.protocol.database.entity.ModuleEntity
import com.eloykim.lyra.amp.protocol.database.entity.ModuleTable
import com.eloykim.lyra.amp.protocol.database.entity.ObjectEntity
import com.eloykim.lyra.amp.protocol.database.entity.ObjectTable
import com.eloykim.lyra.amp.protocol.database.entity.ProcessEntity
import com.eloykim.lyra.amp.protocol.database.entity.ProcessTable
import com.eloykim.lyra.amp.protocol.database.entity.StepEntity
import com.eloykim.lyra.amp.protocol.database.entity.StepTable
import com.eloykim.lyra.amp.protocol.database.entity.ThreadEntity
import com.eloykim.lyra.amp.protocol.database.entity.ThreadTable
import com.eloykim.lyra.amp.protocol.database.entity.WindowEntity
import com.eloykim.lyra.amp.protocol.database.entity.WindowPageEntity
import com.eloykim.lyra.amp.protocol.database.entity.WindowPageTable
import com.eloykim.lyra.amp.protocol.database.entity.WindowTable
import com.eloykim.lyra.amp.protocol.database.entity.WorkerEntity
import com.eloykim.lyra.amp.protocol.database.entity.WorkerTable
import com.eloykim.lyra.amp.protocol.database.sql.DatabaseSync
import com.eloykim.lyra.amp.protocol.database.sql.Row
import com.eloykim.lyra.amp.protocol.database.sql.SelectOptions
import com.eloykim.lyra.amp.protocol.database.sql.TableSql
import com.eloykim.lyra.amp.protocol.database.store.PutObjectResult
import com.eloykim.lyra.amp.protocol.database.store.getObject
import com.eloykim.lyra.amp.protocol.database.store.putObject
import com.eloykim.lyra.amp.protocol.json.JsonValue

/**
 * Concrete table repositories for the kernel aggregates (port of
 * `src/database/repository/{object,process,thread,handle,message,worker,window,registry}Repository.ts`).
 *
 * Grouped in one file because each adapter is a handful of typed queries over a
 * single table; the class names and SQL fragments are kept one-to-one so a
 * reader can diff them against the reference file by file.
 */

/** Append-only object repository (port of `objectRepository.ts`). */
class ObjectRepository(db: DatabaseSync) : BaseRepository<ObjectEntity, Int>(db, ObjectTable, ObjectEntity::fromRow) {

    /** Append one canonical object to the hash chain. */
    fun put(kind: String, id: String, body: JsonValue, at: String): PutObjectResult =
        putObject(db, kind, id, body, at)

    /** Find the newest version of one object. */
    fun find(kind: String, id: String): ObjectEntity? = getObject(db, kind, id)

    /** Read objects of one kind in append order. */
    fun byKind(kind: String, limit: Int = 100): List<ObjectEntity> = queries.select(
        SelectOptions(where = "kind = ?", params = listOf(kind), orderBy = "ORDER BY seq ASC", limit = limit),
    )

    /** Read the newest objects across all kinds. */
    fun latest(limit: Int = 10): List<ObjectEntity> = queries.select(
        SelectOptions(orderBy = "ORDER BY seq DESC", limit = limit),
    )
}

/** Process/agent repository (port of `processRepository.ts`). */
class ProcessRepository(db: DatabaseSync) : BaseRepository<ProcessEntity, Int>(db, ProcessTable, ProcessEntity::fromRow) {

    /** Find a process by its public id. */
    fun byPublicId(id: String): ProcessEntity? = where("id = ?", id).firstOrNull()

    /** Read processes in one state. */
    fun byState(state: String, limit: Int = 100): List<ProcessEntity> = queries.select(
        SelectOptions(
            where = "state = ?",
            params = listOf(state),
            orderBy = "ORDER BY priority DESC, pid ASC",
            limit = limit,
        ),
    )

    /** Agents that have at least one runnable task. */
    fun runnable(limit: Int = 100): List<ProcessEntity> = queries.query(
        """SELECT p.* FROM processes p
             WHERE p.state IN ('created','idle','running')
               AND EXISTS (SELECT 1 FROM threads t WHERE t.pid = p.pid AND t.state = 'ready')
             ORDER BY p.priority DESC, p.pid ASC
             LIMIT ?""",
        limit,
    ).map(ProcessEntity::fromRow)
}

/** Thread/task repository (port of `threadRepository.ts`). */
class ThreadRepository(db: DatabaseSync) : BaseRepository<ThreadEntity, Int>(db, ThreadTable, ThreadEntity::fromRow) {

    /** Read threads owned by one process. */
    fun byProcess(pid: Int): List<ThreadEntity> = queries.select(
        SelectOptions(where = "pid = ?", params = listOf(pid), orderBy = "ORDER BY tid ASC"),
    )

    /** Read threads with one goal string. */
    fun byGoal(goal: String): List<ThreadEntity> = queries.select(
        SelectOptions(where = "goal = ?", params = listOf(goal), orderBy = "ORDER BY tid ASC"),
    )

    /** Find the next runnable thread whose process can run. */
    fun nextReady(): ThreadEntity? = queries.queryOne(
        """SELECT t.* FROM threads t
             JOIN processes p ON p.pid = t.pid
             WHERE t.state = 'ready' AND p.state IN ('created','idle','running')
             ORDER BY t.priority DESC, t.tid ASC
             LIMIT 1""",
    )?.let(ThreadEntity::fromRow)
}

/** Handle/capability repository (port of `handleRepository.ts`). */
class HandleRepository(db: DatabaseSync) : BaseRepository<HandleEntity, Int>(db, HandleTable, HandleEntity::fromRow) {

    /** Read handles granted to one subject. */
    fun bySubject(kind: String, id: String): List<HandleEntity> = queries.select(
        SelectOptions(
            where = "subject_kind = ? AND subject_id = ?",
            params = listOf(kind, id),
            orderBy = "ORDER BY hid ASC",
        ),
    )

    /** Read live handles for one subject/resource pair. */
    fun liveFor(kind: String, id: String, resourceKind: String, resourceId: String, at: String): List<HandleEntity> =
        queries.select(
            SelectOptions(
                where = """subject_kind = ? AND subject_id = ?
                    AND resource_kind = ? AND resource_id = ?
                    AND revoked_at IS NULL
                    AND (expires_at IS NULL OR expires_at > ?)""",
                params = listOf(kind, id, resourceKind, resourceId, at),
                orderBy = "ORDER BY hid ASC",
            ),
        )

    /** Read direct children of a parent handle. */
    fun children(hid: Int): List<HandleEntity> = queries.select(
        SelectOptions(where = "parent_hid = ?", params = listOf(hid), orderBy = "ORDER BY hid ASC"),
    )
}

/** Mailbox repository (port of `messageRepository.ts`). */
class MessageRepository(db: DatabaseSync) : BaseRepository<MessageEntity, Int>(db, MessageTable, MessageEntity::fromRow) {

    /** Undelivered messages for one recipient, oldest first. */
    fun pending(pid: Int, limit: Int = 64): List<MessageEntity> = queries.select(
        SelectOptions(
            where = "to_pid = ? AND delivered_at IS NULL",
            params = listOf(pid),
            orderBy = "ORDER BY msg_id ASC",
            limit = limit,
        ),
    )

    /** Count undelivered messages for one recipient. */
    fun pendingCount(pid: Int): Int = count("to_pid = ? AND delivered_at IS NULL", pid)

    /** Mark one message delivered exactly once. */
    fun markDelivered(msgId: Int, receiptId: String, at: String): Int =
        updateWhere("msg_id = ? AND delivered_at IS NULL", mapOf("delivered_at" to at, "receipt_id" to receiptId), msgId)
}

/**
 * Worker heartbeat repository (port of `workerRepository.ts`).
 *
 * Only the read query lives here. The six mutating statements of
 * `src/concurrency/worker.ts` are a separate adapter ([WorkerStoreSql]) because
 * that file is a sibling port (`concurrency/Worker.kt` defines the `WorkerStore`
 * port) and its `delete`/`all` signatures would collide with the generated CRUD
 * of this repository.
 */
class WorkerRepository(db: DatabaseSync) : BaseRepository<WorkerEntity, String>(db, WorkerTable, WorkerEntity::fromRow) {

    /** Read workers whose lease has expired. */
    fun expired(at: String): List<WorkerEntity> = queries.select(
        SelectOptions(where = "expires_at <= ?", params = listOf(at), orderBy = "ORDER BY worker_id ASC"),
    )
}

/**
 * SQLite adapter for the `WorkerStore` port (port of the SQL inside
 * `src/concurrency/worker.ts`).
 *
 * The reference executes these six statements directly from the domain file; the
 * Kotlin port already factored the domain into `concurrency/Worker.kt`, so the
 * statements land here, where every other table's SQL lives. `recoverExpired`
 * keeps the reference order (requeue per expired worker, delete the expired
 * workers, then demote running agents that have no running task); the reference
 * performs no explicit transaction, so neither does this adapter.
 */
class WorkerStoreSql(private val db: DatabaseSync) : WorkerStore {

    private val workers: TableSql<WorkerEntity> = TableSql(db, WorkerTable, WorkerEntity::fromRow)

    override fun upsert(
        input: WorkerInput,
        startedAt: String,
        heartbeatAt: String,
        expiresAt: String,
    ): WorkerRecord {
        workers.run(
            """INSERT INTO workers (worker_id, host, pid, started_at, heartbeat_at, expires_at)
             VALUES (?, ?, ?, ?, ?, ?)
             ON CONFLICT(worker_id) DO UPDATE SET
               host = excluded.host,
               pid = excluded.pid,
               heartbeat_at = excluded.heartbeat_at,
               expires_at = excluded.expires_at""",
            input.workerId,
            input.host,
            input.pid,
            startedAt,
            heartbeatAt,
            expiresAt,
        )
        val row = workers.queryOne("SELECT * FROM workers WHERE worker_id = ?", input.workerId)
            ?: throw IllegalStateException("worker ${input.workerId} was not stored")
        return toWorkerRecord(WorkerEntity.fromRow(row))
    }

    override fun updateHeartbeat(workerId: String, at: String, expiresAt: String): Boolean =
        workers.run(
            "UPDATE workers SET heartbeat_at = ?, expires_at = ? WHERE worker_id = ?",
            at,
            expiresAt,
            workerId,
        ).changes == 1

    override fun delete(workerId: String) {
        workers.run("DELETE FROM workers WHERE worker_id = ?", workerId)
    }

    override fun all(): List<WorkerRecord> =
        workers.query("SELECT * FROM workers ORDER BY worker_id ASC")
            .map { toWorkerRecord(WorkerEntity.fromRow(it)) }

    override fun recoverExpired(at: String): RecoveryReport {
        val expired = workers.query("SELECT worker_id FROM workers WHERE expires_at <= ?", at)
            .mapNotNull { it.string("worker_id") }
        var threads = 0
        expired.forEach { workerId ->
            threads += workers.run(
                "UPDATE threads SET state = 'ready', owner_worker = NULL, lease = lease + 1, slice_used_ms = 0 " +
                    "WHERE state = 'running' AND owner_worker = ?",
                workerId,
            ).changes
        }
        workers.run("DELETE FROM workers WHERE expires_at <= ?", at)
        val processes = workers.run(
            """UPDATE processes SET state = 'idle'
             WHERE state = 'running'
               AND EXISTS (SELECT 1 FROM threads t WHERE t.pid = processes.pid AND t.state = 'ready')
               AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.pid = processes.pid AND t.state = 'running')""",
        ).changes
        return RecoveryReport(workers = expired, threads = threads, processes = processes)
    }
}

private fun toWorkerRecord(row: WorkerEntity): WorkerRecord = WorkerRecord(
    workerId = row.worker_id,
    startedAt = row.started_at,
    heartbeatAt = row.heartbeat_at,
    expiresAt = row.expires_at,
    host = row.host,
    pid = row.pid,
)
/** Execution step repository (port of `workerRepository.ts`, second class). */
class StepRepository(db: DatabaseSync) : BaseRepository<StepEntity, Int>(db, StepTable, StepEntity::fromRow) {

    /** Read steps for one process. */
    fun byProcess(pid: Int, limit: Int = 200): List<StepEntity> = queries.select(
        SelectOptions(where = "pid = ?", params = listOf(pid), orderBy = "ORDER BY step_id ASC", limit = limit),
    )

    /** Read steps for one thread. */
    fun byThread(tid: Int, limit: Int = 200): List<StepEntity> = queries.select(
        SelectOptions(where = "tid = ?", params = listOf(tid), orderBy = "ORDER BY step_id ASC", limit = limit),
    )
}

/** Input row for replacing a window's page set (`WindowPageInput`). */
data class WindowPageInput(
    val pageId: String,
    val objectKind: String,
    val objectId: String,
    val tokens: Int,
    val pinned: Boolean,
    val lastUsed: Int,
)

/** Context-window repository (port of `windowRepository.ts`). */
class WindowRepository(db: DatabaseSync) : BaseRepository<WindowEntity, Int>(db, WindowTable, WindowEntity::fromRow) {

    private val pagesSql: TableSql<WindowPageEntity> = TableSql(db, WindowPageTable, WindowPageEntity::fromRow)

    /** Read resident pages for one thread, least recently used first. */
    fun pages(tid: Int): List<WindowPageEntity> = pagesSql.select(
        SelectOptions(where = "tid = ?", params = listOf(tid), orderBy = "ORDER BY last_used ASC"),
    )

    /** Insert or update the window totals. */
    fun save(tid: Int, capacityTokens: Int, usedTokens: Int, at: String) {
        upsert(
            mapOf(
                "tid" to tid,
                "capacity_tokens" to capacityTokens,
                "used_tokens" to usedTokens,
                "updated_at" to at,
            ),
        )
    }

    /** Replace the page table for one thread; call inside a transaction. */
    fun replacePages(tid: Int, pages: List<WindowPageInput>, at: String, capacityTokens: Int) {
        pagesSql.deleteWhere("tid = ?", tid)
        var used = 0
        pages.forEach { page ->
            pagesSql.insert(
                mapOf(
                    "tid" to tid,
                    "page_id" to page.pageId,
                    "object_kind" to page.objectKind,
                    "object_id" to page.objectId,
                    "tokens" to page.tokens,
                    "pinned" to page.pinned,
                    "last_used" to page.lastUsed,
                ),
            )
            used += page.tokens
        }
        save(tid, capacityTokens, used, at)
    }
}

/** Concept row joined with its module link (`ModuleConceptRow`). */
data class ModuleConceptRow(
    val cid: Int,
    val id: String,
    val role: String,
)

/** Concept registry repository (port of `registryRepository.ts`). */
class ConceptRepository(db: DatabaseSync) : BaseRepository<ConceptEntity, Int>(db, ConceptTable, ConceptEntity::fromRow) {

    /** Find a concept by public id. */
    fun byPublicId(id: String): ConceptEntity? = where("id = ?", id).firstOrNull()

    /** Read concepts in one status. */
    fun byStatus(status: String): List<ConceptEntity> = queries.select(
        SelectOptions(where = "status = ?", params = listOf(status), orderBy = "ORDER BY id ASC"),
    )
}

/** Module registry repository (port of `registryRepository.ts`). */
class ModuleRepository(db: DatabaseSync) : BaseRepository<ModuleEntity, Int>(db, ModuleTable, ModuleEntity::fromRow) {

    /** Find a module by public id. */
    fun byPublicId(id: String): ModuleEntity? = where("id = ?", id).firstOrNull()

    /** Read modules of one kind. */
    fun byKind(kind: String): List<ModuleEntity> = queries.select(
        SelectOptions(where = "kind = ?", params = listOf(kind), orderBy = "ORDER BY id ASC"),
    )

    /** Read concepts linked to one module. */
    fun concepts(mid: Int): List<ModuleConceptRow> = queries.query(
        "SELECT c.cid, c.id, mc.role FROM module_concepts mc JOIN concepts c ON c.cid = mc.cid " +
            "WHERE mc.mid = ? ORDER BY c.id ASC",
        mid,
    ).map(::toModuleConceptRow)
}

private fun toModuleConceptRow(row: Row): ModuleConceptRow = ModuleConceptRow(
    cid = row.requireInt("cid"),
    id = row.requireString("id"),
    role = row.requireString("role"),
)

/** Registry attestation repository (port of `registryRepository.ts`). */
class AttestationRepository(db: DatabaseSync) :
    BaseRepository<AttestationEntity, Int>(db, AttestationTable, AttestationEntity::fromRow) {

    /** Read attestations for one subject. */
    fun forSubject(subjectKind: String, subjectId: String): List<AttestationEntity> = queries.select(
        SelectOptions(
            where = "subject_kind = ? AND subject_id = ?",
            params = listOf(subjectKind, subjectId),
            orderBy = "ORDER BY aid ASC",
        ),
    )
}

