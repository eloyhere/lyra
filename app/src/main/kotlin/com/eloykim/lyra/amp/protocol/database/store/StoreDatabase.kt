package com.eloykim.lyra.amp.protocol.database.store

import com.eloykim.lyra.amp.protocol.database.entity.ObjectEntity
import com.eloykim.lyra.amp.protocol.database.sql.DatabaseOpener
import com.eloykim.lyra.amp.protocol.database.sql.DatabaseSync
import com.eloykim.lyra.amp.protocol.database.sql.MetaSchema
import com.eloykim.lyra.amp.protocol.database.sql.Row
import com.eloykim.lyra.amp.protocol.database.sql.Schema
import com.eloykim.lyra.amp.protocol.database.sql.SqlParameter
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Digest

/**
 * SQLite store: one file, WAL mode, hash-chained objects plus scheduler tables
 * (port of `src/database/store/database.ts`).
 *
 * `objects` is the append-only evidence log (the same digest chain the JSONL
 * store used); `processes`, `threads` and `steps` are the mutable scheduler
 * state. A step commits its state change, its receipt and its step row in one
 * transaction, so a crash cannot leave a thread "running" without a receipt or a
 * receipt without a step.
 *
 * ## Binding seams
 *
 * - `node:sqlite` is replaced by the narrow [DatabaseSync] port: the store never
 *   imports a driver, only an [DatabaseOpener] supplied by the host.
 * - `db.prepare(...).run(...)` normalizes values through
 *   `toSqlParameter`; call sites here pass strings and numbers only.
 * - `canonical(body)`/`digestOf` are [CanonicalJson.encode] and
 *   [CanonicalJson.digest]; `object` bodies stay canonical TEXT so the chain is
 *   verifiable by the existing ledger tooling.
 */

/**
 * The genesis digest: no objects yet. Re-exported from `storage/AppendLog.kt`
 * (same value, same meaning; the reference declares it in both places).
 */
val GENESIS: Digest = com.eloykim.lyra.amp.protocol.storage.GENESIS

/** A row of `objects`; identical to the reference `ObjectRow`. */
typealias ObjectRow = ObjectEntity

/** Result of appending an object to the hash chain. */
data class PutObjectResult(
    val seq: Int,
    val hash: Digest,
)

/** Result of recomputing the object chain. */
data class VerifyResult(
    val ok: Boolean,
    val reasons: List<String>,
)

/**
 * Add a column to a pre-existing table without rewriting the whole schema.
 *
 * `PRAGMA table_info` is queried exactly like the reference; `table` and
 * `definition` are trusted schema fragments, never user input.
 */
private fun ensureColumn(db: DatabaseSync, table: String, column: String, definition: String) {
    val columns: List<Row> = db.prepare("PRAGMA table_info($table)").all()
    if (columns.none { it.string("name") == column }) {
        db.exec("ALTER TABLE $table ADD COLUMN $definition")
    }
}

/**
 * Open (or create) a store and apply the schema. WAL is skipped for `:memory:`.
 *
 * Migration behaviour is part of the contract: a `schema_version` of `1` drops
 * the pre-release message/handle shapes, any other old version rebuilds the
 * scheduler tables with foreign keys off, and the final row is version `4`.
 *
 * @param opener host-supplied way to open a path as a [DatabaseSync].
 */
fun openStore(path: String, opener: DatabaseOpener): DatabaseSync {
    val db: DatabaseSync = opener.open(path)
    db.exec("PRAGMA foreign_keys = ON")
    db.exec("PRAGMA synchronous = NORMAL")
    // A second worker waits for the write lock instead of failing immediately.
    db.exec("PRAGMA busy_timeout = 5000")
    if (path != ":memory:") {
        db.exec("PRAGMA journal_mode = WAL")
    }
    db.exec(MetaSchema)
    val version: String? = db.prepare("SELECT value FROM meta WHERE key = 'schema_version'").get()?.string("value")
    if (version == "1") {
        // Pre-release stores kept the v1 message and handle shapes. They carry
        // no durable contract yet, so migrate by replacing just those tables.
        db.exec("DROP TABLE IF EXISTS messages")
        db.exec("DROP TABLE IF EXISTS handles")
    }
    if (version != null && version != "4") {
        // v4 inverted the kernel semantics: process = agent, thread = task.
        db.exec("PRAGMA foreign_keys = OFF")
        db.exec("DROP TABLE IF EXISTS messages")
        db.exec("DROP TABLE IF EXISTS process_objects")
        db.exec("DROP TABLE IF EXISTS steps")
        db.exec("DROP TABLE IF EXISTS threads")
        db.exec("DROP TABLE IF EXISTS processes")
        db.exec("PRAGMA foreign_keys = ON")
    }
    db.exec(Schema)
    ensureColumn(db, "modules", "content_digest", "content_digest TEXT")
    db.prepare(
        "INSERT INTO meta (key, value) VALUES ('schema_version', '4') " +
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
    ).run()
    return db
}

/**
 * Run a function inside `BEGIN IMMEDIATE` / `COMMIT`, rolling back on throw.
 *
 * Kotlin exceptions propagate unchanged, matching the reference.
 */
fun <T> transaction(db: DatabaseSync, fn: () -> T): T {
    db.exec("BEGIN IMMEDIATE")
    return try {
        val result = fn()
        db.exec("COMMIT")
        result
    } catch (error: Throwable) {
        db.exec("ROLLBACK")
        throw error
    }
}

/** Read the newest object digest. */
fun lastObjectHash(db: DatabaseSync): Digest =
    db.prepare("SELECT hash FROM objects ORDER BY seq DESC LIMIT 1").get()?.string("hash") ?: GENESIS

/**
 * Append one object and return its position and hash.
 *
 * The hash covers `prev`, `kind`, `id` and the canonical body, so editing any
 * row breaks every subsequent link. Callers that need several writes to land
 * together wrap them in [transaction].
 */
fun putObject(db: DatabaseSync, kind: String, id: String, body: JsonValue, at: String): PutObjectResult {
    val prev: Digest = lastObjectHash(db)
    val bodyText: String = CanonicalJson.encode(body)
    val hash: Digest = CanonicalJson.digest(
        jsonObj(
            "prev" to jsonText(prev),
            "kind" to jsonText(kind),
            "id" to jsonText(id),
            "body" to jsonText(bodyText),
        ),
    )
    val result = db.prepare("INSERT INTO objects (kind, id, body, hash, prev, at) VALUES (?, ?, ?, ?, ?, ?)")
        .run(toText(kind), toText(id), toText(bodyText), toText(hash), toText(prev), toText(at))
    return PutObjectResult(seq = result.insertedId, hash = hash)
}

/** Read one object by kind and id. */
fun getObject(db: DatabaseSync, kind: String, id: String): ObjectRow? {
    val row = db.prepare("SELECT seq, kind, id, body, hash, prev, at FROM objects WHERE kind = ? AND id = ?")
        .get(toText(kind), toText(id)) ?: return null
    return ObjectEntity.fromRow(row)
}

/** Recompute the chain from row one; the only proof the store itself offers. */
fun verifyObjectChain(db: DatabaseSync): VerifyResult {
    val reasons = ArrayList<String>()
    val rows: List<Row> = db.prepare("SELECT seq, kind, id, body, hash, prev FROM objects ORDER BY seq ASC").all()
    var prev: Digest = GENESIS
    for (row in rows) {
        val seq = row.int("seq") ?: -1
        val rowPrev = row.string("prev").orEmpty()
        if (rowPrev != prev) {
            reasons += "seq $seq: prev does not continue the chain"
        }
        val expected: Digest = CanonicalJson.digest(
            jsonObj(
                "prev" to jsonText(rowPrev),
                "kind" to jsonText(row.string("kind").orEmpty()),
                "id" to jsonText(row.string("id").orEmpty()),
                "body" to jsonText(row.string("body").orEmpty()),
            ),
        )
        if (row.string("hash") != expected) {
            reasons += "seq $seq: hash does not cover its body"
        }
        prev = row.string("hash").orEmpty()
    }
    return VerifyResult(ok = reasons.isEmpty(), reasons = reasons)
}

/** Table names present in the store; useful for migration checks and tests. */
fun tableNames(db: DatabaseSync): List<String> =
    db.prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").all()
        .mapNotNull { it.string("name") }

/** Close a store; kept here so callers do not depend on a driver. */
fun closeStore(db: DatabaseSync) {
    db.close()
}

private fun toText(value: String): SqlParameter = SqlParameter.Text(value)
