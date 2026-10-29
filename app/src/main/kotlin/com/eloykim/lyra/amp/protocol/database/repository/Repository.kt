package com.eloykim.lyra.amp.protocol.database.repository

import com.eloykim.lyra.amp.protocol.database.entity.TableMeta
import com.eloykim.lyra.amp.protocol.database.sql.DatabaseSync
import com.eloykim.lyra.amp.protocol.database.sql.MutationResult
import com.eloykim.lyra.amp.protocol.database.sql.Row
import com.eloykim.lyra.amp.protocol.database.sql.SelectOptions
import com.eloykim.lyra.amp.protocol.database.sql.Sql
import com.eloykim.lyra.amp.protocol.database.sql.TableSql
import com.eloykim.lyra.amp.protocol.database.sql.assertSqlIdentifier

/**
 * Repository abstraction over one [Sql] table adapter (port of
 * `src/database/repository/repository.ts`).
 *
 * A repository exposes row-shaped operations and never touches the driver
 * directly. Concrete repositories extend this class, add table-specific queries
 * through the protected `sql` adapter, and keep their public methods focused on
 * the aggregate the service consumes.
 */

/** Sort direction, using the JPA/Spring Data spelling. */
enum class SortDirection(val wire: String) {
    ASCEND("Ascend"),
    DESCEND("Descend"),
}

/** Null-ordering option for a sort order. */
enum class SortNullHandling(val wire: String) {
    NATIVE("Native"),
    NULLS_FIRST("NullsFirst"),
    NULLS_LAST("NullsLast"),
}

/** One property in a sort. */
data class SortOrder(
    val property: String,
    val direction: SortDirection,
    val ignoreCase: Boolean? = null,
    val nullHandling: SortNullHandling? = null,
)

/**
 * Immutable sort specification, modelled on Spring Data's `Sort`.
 *
 * Property names are validated as SQL identifiers when the sort is built, so a
 * caller cannot smuggle an expression through the ordering API.
 *
 * The reference's `Sort.by(...)` cannot keep its name: `by` is a Kotlin keyword.
 * [of] is the same factory. [toSql] copies the reference spelling verbatim
 * (`ORDER BY "p" Ascend`), including the JPA-style direction words, which are
 * not SQLite's `ASC`/`DESC`; no kernel repository passes a direction other than
 * the default, and a host that does gets the reference's exact string.
 */
class Sort private constructor(val orders: List<SortOrder>) {

    companion object {

        private val UNSORTED: Sort = Sort(emptyList())

        /** Build a sort with one direction for several properties. */
        fun of(direction: SortDirection, vararg properties: String): Sort {
            if (properties.isEmpty()) {
                return unsorted()
            }
            return Sort(properties.map { property -> SortOrder(assertSqlIdentifier(property), direction) })
        }

        fun ascend(vararg properties: String): Sort = of(SortDirection.ASCEND, *properties)

        fun descend(vararg properties: String): Sort = of(SortDirection.DESCEND, *properties)

        /** Return the canonical unsorted value. */
        fun unsorted(): Sort = UNSORTED
    }

    /** Append another sort. */
    fun and(other: Sort): Sort {
        if (!other.isSorted()) {
            return this
        }
        if (!isSorted()) {
            return other
        }
        return Sort(orders + other.orders)
    }

    fun isSorted(): Boolean = orders.isNotEmpty()

    /** Reverse every order while preserving ignore-case and null handling. */
    fun reverse(): Sort = Sort(
        orders.map { order ->
            order.copy(
                direction = if (order.direction == SortDirection.ASCEND) SortDirection.DESCEND else SortDirection.ASCEND,
            )
        },
    )

    /** Render the ORDER BY fragment; empty for an unsorted value. */
    fun toSql(): String {
        if (!isSorted()) {
            return ""
        }
        val fragments = orders.map { order ->
            val column = "\"${assertSqlIdentifier(order.property)}\""
            val expression = if (order.ignoreCase == true) "LOWER($column)" else column
            var fragment = "$expression ${order.direction.wire}"
            when (order.nullHandling) {
                SortNullHandling.NULLS_FIRST -> fragment += " NULLS FIRST"
                SortNullHandling.NULLS_LAST -> fragment += " NULLS LAST"
                else -> Unit
            }
            fragment
        }
        return "ORDER BY ${fragments.joinToString(", ")}"
    }
}

/**
 * Query descriptor for a typed repository read.
 *
 * The WHERE fragment is trusted SQL, matching the repository layer's existing
 * boundary; values belong in `params`.
 */
data class Query(
    val where: String? = null,
    val params: List<Any?> = emptyList(),
    val sort: Sort? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val columns: List<String>? = null,
)

/** Page of repository rows, modelled on Spring Data's `Page`. */
data class Page<TEntity>(
    val content: List<TEntity>,
    val totalElements: Int,
    val totalPages: Int,
    val number: Int,
    val size: Int,
    val first: Boolean,
    val last: Boolean,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
)

/**
 * Build a page and derive its navigation flags.
 *
 * @throws IllegalArgumentException when a count/index/size is out of range
 *   (`RangeError` in the reference).
 */
fun <TEntity> pageOf(content: List<TEntity>, totalElements: Int, number: Int, size: Int): Page<TEntity> {
    require(totalElements >= 0) { "totalElements must be a non-negative integer" }
    require(number >= 0) { "page number must be a non-negative integer" }
    require(size > 0) { "page size must be a positive integer" }
    val totalPages = (totalElements + size - 1) / size
    return Page(
        content = ArrayList(content),
        totalElements = totalElements,
        totalPages = totalPages,
        number = number,
        size = size,
        first = number == 0,
        last = totalPages == 0 || number >= totalPages - 1,
        hasNext = number + 1 < totalPages,
        hasPrevious = number > 0,
    )
}

/**
 * Repository abstraction; generated CRUD is delegated to [Sql].
 *
 * @param TEntity row shape returned by the repository.
 * @param TKey primary-key type.
 * @param TSql concrete SQL adapter used by the repository.
 */
abstract class Repository<TEntity : Any, TKey : Any, TSql : Sql<TEntity>>(
    protected val sql: TSql,
) {

    /** Access the table adapter for concrete repository queries. */
    protected val queries: TSql get() = sql

    fun findById(id: TKey): TEntity? = sql.findById(id)

    fun all(limit: Int = 1000): List<TEntity> = sql.all(limit)

    /** Read rows using a typed query descriptor. */
    fun findAll(query: Query): List<TEntity> {
        val where = query.where?.trim().orEmpty()
        return sql.select(
            SelectOptions(
                where = where.ifEmpty { null },
                params = ArrayList(query.params),
                orderBy = query.sort?.toSql().orEmpty(),
                limit = query.limit,
                offset = query.offset,
                columns = query.columns,
            ),
        )
    }

    /**
     * Read one page and count the full result set.
     *
     * `query.limit`/`query.offset` are ignored, exactly as in the reference.
     */
    fun page(query: Query, pageNumber: Int = 0, pageSize: Int = 20): Page<TEntity> {
        require(pageNumber >= 0) { "pageNumber must be a non-negative integer" }
        require(pageSize > 0) { "pageSize must be a positive integer" }
        val where = query.where?.trim().orEmpty()
        val params = ArrayList(query.params)
        val total = if (where.isEmpty()) sql.count() else sql.count(where, *params.toTypedArray())
        val content = sql.select(
            SelectOptions(
                where = where.ifEmpty { null },
                params = params,
                orderBy = query.sort?.toSql().orEmpty(),
                limit = pageSize,
                offset = pageNumber * pageSize,
                columns = query.columns,
            ),
        )
        return pageOf(content, total, pageNumber, pageSize)
    }

    /** Read rows with a trusted WHERE fragment. */
    fun where(clause: String, vararg params: Any?): List<TEntity> = sql.where(clause, *params)

    /** Count rows. */
    fun count(clause: String? = null, vararg params: Any?): Int = sql.count(clause, *params)

    /** Test whether a primary key exists. */
    fun exists(id: TKey): Boolean = sql.exists(id)

    /** Insert one row. */
    fun insert(values: Map<String, Any?>): MutationResult = sql.insert(values)

    /** Update one row by primary key. */
    fun update(id: TKey, values: Map<String, Any?>): Int = sql.update(id, values)

    /** Update rows with a trusted WHERE fragment. */
    fun updateWhere(clause: String, values: Map<String, Any?>, vararg params: Any?): Int =
        sql.updateWhere(clause, values, *params)

    /** Delete one row by primary key. */
    fun delete(id: TKey): Int = sql.delete(id)

    /** Delete rows with a trusted WHERE fragment. */
    fun deleteWhere(clause: String, vararg params: Any?): Int = sql.deleteWhere(clause, *params)

    /** Insert or update according to unique columns. */
    fun upsert(values: Map<String, Any?>, conflictColumns: List<String>? = null): MutationResult =
        sql.upsert(values, conflictColumns)
}

/**
 * Reference name: `BaseRepository`.
 *
 * Concrete repository backed by one table; generated CRUD comes from
 * [TableSql], and `connection` is exposed for services that need a transaction
 * or a store helper.
 */
open class BaseRepository<TEntity : Any, TKey : Any> internal constructor(
    protected val db: DatabaseSync,
    val meta: TableMeta,
    map: (Row) -> TEntity,
) : Repository<TEntity, TKey, TableSql<TEntity>>(
    TableSql(db, meta, map),
) {

    /** Exposed for services that need a transaction or a store helper. */
    val connection: DatabaseSync get() = db
}
