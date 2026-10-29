package com.eloykim.lyra.amp.protocol.database.sql

import com.eloykim.lyra.amp.protocol.database.entity.TableMeta

/**
 * Generic SQL layer for one table (port of `src/database/sql/sql.ts`).
 *
 * Every concrete table adapter extends [Sql] and supplies its [TableMeta]. The
 * base class owns the statement shapes shared by all tables: primary-key
 * lookup, list, count, insert, update, delete, upsert and existence checks.
 * Table-specific queries belong in a concrete subclass and use the public
 * [query], [queryOne], [run] and [quote] helpers.
 *
 * Parameters are always bound; identifiers come from trusted [TableMeta] values
 * and are validated before they are interpolated.
 *
 * ## Binding seam
 *
 * The reference returns rows straight from `node:sqlite` and calls the result
 * `TEntity`. A Kotlin [Row] is a map, so each adapter also carries a
 * `(Row) -> TEntity` mapper ([map]). Selection, insertion and column validation
 * stay identical; only the projection step is explicit.
 */
data class SelectOptions(
    /** Optional SQL after WHERE. */
    val where: String? = null,
    /** Bound values for the WHERE fragment. */
    val params: List<Any?> = emptyList(),
    /** Full ORDER BY fragment, for example a value returned by `Sort.toSql()`. */
    val orderBy: String? = null,
    /** Maximum row count. */
    val limit: Int? = null,
    /** Number of rows to skip. */
    val offset: Int? = null,
    /** Optional column projection. */
    val columns: List<String>? = null,
)

/**
 * Abstract CRUD adapter for a single table.
 *
 * @param TEntity row shape returned by the table.
 */
open class Sql<TEntity : Any>(
    protected val db: DatabaseSync,
    val meta: TableMeta,
    private val map: (Row) -> TEntity,
) {

    private val columnSet: Set<String> = LinkedHashSet(meta.columns)

    init {
        assertMeta()
    }

    /** Physical table name. */
    val table: String get() = meta.table

    /** Primary-key column name. */
    val primaryKey: String get() = meta.primaryKey

    /** Columns allowed in generated statements. */
    val columns: List<String> get() = meta.columns

    /** Find one row by primary key, or null when absent. */
    fun findById(id: Any?): TEntity? {
        val sql = "SELECT * FROM ${quote(table)} WHERE ${quote(primaryKey)} = ? LIMIT 1"
        return queryOne(sql, id)?.let(map)
    }

    /** Read rows in unspecified order. */
    fun all(limit: Int = 1000): List<TEntity> {
        val bounded = normalizeLimit(limit)
        val sql = "SELECT * FROM ${quote(table)} LIMIT ?"
        return query(sql, bounded).map(map)
    }

    /**
     * Read rows with a trusted SQL WHERE fragment.
     *
     * The fragment is not analyzed; callers must pass only literals and
     * placeholders, never interpolated user input.
     */
    fun where(clause: String, vararg params: Any?): List<TEntity> {
        val where = clause.trim()
        if (where.isEmpty()) {
            throw IllegalArgumentException("where clause must not be empty")
        }
        val sql = "SELECT * FROM ${quote(table)} WHERE $where"
        return query(sql, *params).map(map)
    }

    /** Read rows with optional filtering, ordering and pagination. */
    fun select(options: SelectOptions = SelectOptions()): List<TEntity> {
        val where = options.where?.trim().orEmpty()
        val params = ArrayList<Any?>(options.params)
        if (where.isEmpty() && params.isNotEmpty()) {
            throw IllegalArgumentException("select parameters require a where clause")
        }
        var sql = "SELECT ${selectList(options.columns)} FROM ${quote(table)}"
        if (where.isNotEmpty()) {
            sql += " WHERE $where"
        }
        val orderBy = options.orderBy?.trim().orEmpty()
        if (orderBy.isNotEmpty()) {
            sql += " $orderBy"
        }
        if (options.limit != null) {
            val limit = normalizeLimit(options.limit)
            sql += " LIMIT ?"
            params += limit
        } else if (options.offset != null && options.offset > 0) {
            normalizeLimit(options.offset)
            sql += " LIMIT -1"
        }
        if (options.offset != null) {
            val offset = normalizeLimit(options.offset)
            sql += " OFFSET ?"
            params += offset
        }
        return query(sql, *params.toTypedArray()).map(map)
    }

    /** Count rows, optionally restricted by a WHERE fragment. */
    fun count(clause: String? = null, vararg params: Any?): Int {
        val where = clause?.trim().orEmpty()
        val sql = if (where.isEmpty()) {
            "SELECT COUNT(*) AS n FROM ${quote(table)}"
        } else {
            "SELECT COUNT(*) AS n FROM ${quote(table)} WHERE $where"
        }
        return queryOne(sql, *params)?.int("n") ?: 0
    }

    /** Test whether a primary key exists. */
    fun exists(id: Any?): Boolean {
        val sql = "SELECT 1 AS present FROM ${quote(table)} WHERE ${quote(primaryKey)} = ? LIMIT 1"
        return queryOne(sql, id) != null
    }

    /** Insert one row; unknown column keys are rejected. */
    fun insert(values: Map<String, Any?>): MutationResult {
        val columns = collectColumns(values, includePrimaryKey = true)
        val placeholders = columns.joinToString(", ") { "?" }
        val params = collectParams(values, columns)
        val sql = "INSERT INTO ${quote(table)} (${columns.joinToString(", ") { quote(it) }}) VALUES ($placeholders)"
        return run(sql, *params.toTypedArray())
    }

    /** Update one row by primary key; the primary key is ignored. */
    fun update(id: Any?, values: Map<String, Any?>): Int {
        val columns = collectColumns(values, includePrimaryKey = false)
        val params = collectParams(values, columns)
        val assignments = columns.joinToString(", ") { "${quote(it)} = ?" }
        val sql = "UPDATE ${quote(table)} SET $assignments WHERE ${quote(primaryKey)} = ?"
        return run(sql, *(params + id).toTypedArray()).changes
    }

    /** Update rows selected by a trusted WHERE fragment; the primary key is ignored. */
    fun updateWhere(clause: String, values: Map<String, Any?>, vararg params: Any?): Int {
        val where = clause.trim()
        if (where.isEmpty()) {
            throw IllegalArgumentException("where clause must not be empty")
        }
        val columns = collectColumns(values, includePrimaryKey = false)
        val assignments = columns.joinToString(", ") { "${quote(it)} = ?" }
        val sql = "UPDATE ${quote(table)} SET $assignments WHERE $where"
        return run(sql, *(collectParams(values, columns) + params.toList()).toTypedArray()).changes
    }

    /** Delete one row by primary key. */
    fun delete(id: Any?): Int {
        val sql = "DELETE FROM ${quote(table)} WHERE ${quote(primaryKey)} = ?"
        return run(sql, id).changes
    }

    /** Delete rows selected by a trusted WHERE fragment. */
    fun deleteWhere(clause: String, vararg params: Any?): Int {
        val where = clause.trim()
        if (where.isEmpty()) {
            throw IllegalArgumentException("where clause must not be empty")
        }
        val sql = "DELETE FROM ${quote(table)} WHERE $where"
        return run(sql, *params).changes
    }

    /** Insert a row, replacing conflicting columns on a conflict. */
    fun upsert(values: Map<String, Any?>, conflictColumns: List<String>? = null): MutationResult {
        val columns = collectColumns(values, includePrimaryKey = true)
        val params = collectParams(values, columns)
        val conflicts = conflictColumns ?: listOf(primaryKey)
        conflicts.forEach { column ->
            if (column !in columns) {
                throw IllegalArgumentException("upsert conflict column is not supplied: $column")
            }
        }
        val conflictSql = conflicts.joinToString(", ") { quote(it) }
        val updates = columns.filter { it !in conflicts }
        val action = if (updates.isEmpty()) {
            "DO NOTHING"
        } else {
            "DO UPDATE SET " + updates.joinToString(", ") { "${quote(it)} = excluded.${quote(it)}" }
        }
        val placeholders = columns.joinToString(", ") { "?" }
        val sql = "INSERT INTO ${quote(table)} (${columns.joinToString(", ") { quote(it) }}) " +
            "VALUES ($placeholders) ON CONFLICT($conflictSql) $action"
        return run(sql, *params.toTypedArray())
    }

    private fun selectList(columns: List<String>?): String {
        if (columns == null || columns.isEmpty()) {
            return "*"
        }
        return columns.joinToString(", ") { quote(it) }
    }

    /**
     * Execute a trusted statement with bound parameters.
     *
     * The reference marks this `protected`; this port exposes it because a
     * composed adapter (for example `WindowRepository.pagesSql`) holds a second
     * table adapter and must execute statements through that instance.
     */
    fun run(sql: String, vararg params: Any?): MutationResult {
        val bound = normalize(params)
        return db.prepare(sql).run(*bound)
    }

    /** Read rows from a trusted statement. */
    fun query(sql: String, vararg params: Any?): List<Row> {
        val bound = normalize(params)
        return db.prepare(sql).all(*bound)
    }

    /** Read one row from a trusted statement. */
    fun queryOne(sql: String, vararg params: Any?): Row? {
        val bound = normalize(params)
        return db.prepare(sql).get(*bound)
    }

    /** Quote an identifier after validating it. */
    protected fun quote(identifier: String): String = "\"${assertSqlIdentifier(identifier)}\""

    private fun normalize(params: Array<out Any?>): Array<SqlParameter> =
        Array(params.size) { index -> toSqlParameter(params[index]) }

    private fun assertMeta() {
        assertSqlIdentifier(meta.table)
        assertSqlIdentifier(meta.primaryKey)
        if (meta.columns.isEmpty()) {
            throw IllegalArgumentException("table ${meta.table} must declare at least one column")
        }
        if (meta.primaryKey !in columnSet) {
            throw IllegalArgumentException("primary key ${meta.primaryKey} is not listed in columns")
        }
        meta.columns.forEach { assertSqlIdentifier(it) }
    }

    private fun collectColumns(values: Map<String, Any?>, includePrimaryKey: Boolean): List<String> {
        val collected = ArrayList<String>(values.size)
        for (column in values.keys) {
            if (column !in columnSet) {
                throw IllegalArgumentException("unknown column for $table: $column")
            }
            if (!includePrimaryKey && column == primaryKey) {
                continue
            }
            collected += column
        }
        if (collected.isEmpty()) {
            throw IllegalArgumentException("no writable columns supplied for $table")
        }
        return collected
    }

    private fun collectParams(values: Map<String, Any?>, columns: List<String>): List<Any?> =
        columns.map { column -> toSqlParameter(values[column]) }

    private fun normalizeLimit(limit: Int): Int {
        if (limit < 0) {
            throw IllegalArgumentException("limit must be a non-negative integer")
        }
        return limit
    }
}

/**
 * Concrete SQL adapter for a table that only needs generated CRUD statements.
 *
 * Table-specific operations can instead subclass [Sql] and use the public query
 * helpers.
 */
class TableSql<TEntity : Any>(
    db: DatabaseSync,
    meta: TableMeta,
    map: (Row) -> TEntity,
) : Sql<TEntity>(db, meta, map)
