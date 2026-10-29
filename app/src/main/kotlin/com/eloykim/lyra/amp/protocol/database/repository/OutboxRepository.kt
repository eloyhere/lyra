package com.eloykim.lyra.amp.protocol.database.repository

import com.eloykim.lyra.amp.protocol.database.entity.OutboxEntity
import com.eloykim.lyra.amp.protocol.database.entity.OutboxTable
import com.eloykim.lyra.amp.protocol.database.sql.DatabaseSync
import com.eloykim.lyra.amp.protocol.database.sql.JsJson
import com.eloykim.lyra.amp.protocol.database.sql.SelectOptions
import com.eloykim.lyra.amp.protocol.database.sql.runStatement
import com.eloykim.lyra.amp.protocol.json.JsonValue

/**
 * Transactional outbox repository (port of
 * `src/database/repository/outboxRepository.ts`).
 *
 * `write` stores the payload as `JSON.stringify` text (`JsJson`), the same
 * column encoding the runtime sink depends on.
 */
class OutboxRepository(db: DatabaseSync) :
    BaseRepository<OutboxEntity, Int>(db, OutboxTable, OutboxEntity::fromRow) {

    /** Append one outbox row and return its generated id. */
    fun write(topic: String, payload: JsonValue, at: String): Long {
        val result = queries.insert(
            mapOf(
                "topic" to topic,
                "payload" to JsJson.encode(payload),
                "created_at" to at,
                "dispatched_at" to null,
            ),
        )
        return result.lastInsertRowid
    }

    fun pending(limit: Int = 100): List<OutboxEntity> = queries.select(
        SelectOptions(where = "dispatched_at IS NULL", orderBy = "ORDER BY id ASC", limit = limit),
    )

    fun byTopic(topic: String, limit: Int = 100): List<OutboxEntity> = queries.select(
        SelectOptions(where = "topic = ?", params = listOf(topic), orderBy = "ORDER BY id DESC", limit = limit),
    )

    fun countPending(): Int = queries.count("dispatched_at IS NULL")

    /** Delete dispatched rows older than the cutoff; topic is optional. */
    fun pruneDispatched(before: String, topic: String? = null): Int {
        val clause = if (topic == null) {
            "dispatched_at IS NOT NULL AND dispatched_at < ?"
        } else {
            "dispatched_at IS NOT NULL AND dispatched_at < ? AND topic = ?"
        }
        val result = if (topic == null) {
            db.runStatement("DELETE FROM outbox WHERE $clause", before)
        } else {
            db.runStatement("DELETE FROM outbox WHERE $clause", before, topic)
        }
        return result.changes
    }

    fun markDispatched(ids: List<Int>, at: String): Int {
        var changed = 0
        ids.forEach { id -> changed += queries.update(id, mapOf("dispatched_at" to at)) }
        return changed
    }
}
