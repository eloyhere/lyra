package com.eloykim.lyra.amp.protocol.database.service

import com.eloykim.lyra.amp.protocol.database.repository.Page
import com.eloykim.lyra.amp.protocol.database.repository.Query
import com.eloykim.lyra.amp.protocol.database.repository.Repository
import com.eloykim.lyra.amp.protocol.database.sql.MutationResult

/**
 * Service abstraction over one repository (port of
 * `src/database/service/service.ts`).
 *
 * Services own use-case policy: they translate application input into
 * repository operations, return domain-shaped rows, and turn a missing row into
 * a stable error. They never construct SQL.
 *
 * @param TEntity row shape returned by the service.
 * @param TKey primary-key type.
 * @param TRepository concrete repository used by the service.
 */

/** Error raised when a service requires a row that does not exist. */
class EntityNotFoundError(
    /** Entity label used in the message. */
    val entityName: String,
    /** Missing primary-key value. */
    val id: Any?,
) : IllegalArgumentException("$entityName not found: $id")

/**
 * Reference name: `Service`.
 *
 * The base class only forwards repository operations; subclasses add aggregate
 * behaviour and call their concrete repository through [repository].
 */
abstract class Service<TEntity : Any, TKey : Any, TRepository : Repository<TEntity, TKey, *>>(
    protected val repository: TRepository,
    protected val entityName: String = "entity",
) {

    /** Find one row by primary key. */
    fun findById(id: TKey): TEntity? = repository.findById(id)

    /**
     * Find one row or throw.
     *
     * @throws EntityNotFoundError when the row is absent.
     */
    fun requireById(id: TKey, entityName: String = this.entityName): TEntity =
        repository.findById(id) ?: throw EntityNotFoundError(entityName, id)

    /** Read rows in unspecified order. */
    fun all(limit: Int = 1000): List<TEntity> = repository.all(limit)

    /** Read rows with a typed query descriptor. */
    fun findAll(query: Query): List<TEntity> = repository.findAll(query)

    /** Read one page of rows. */
    fun page(query: Query, pageNumber: Int = 0, pageSize: Int = 20): Page<TEntity> =
        repository.page(query, pageNumber, pageSize)

    /** Read rows with a trusted WHERE fragment. */
    fun where(clause: String, vararg params: Any?): List<TEntity> = repository.where(clause, *params)

    /** Count rows. */
    fun count(clause: String? = null, vararg params: Any?): Int = repository.count(clause, *params)

    /** Test whether a row exists. */
    fun exists(id: TKey): Boolean = repository.exists(id)

    /** Insert one row. */
    fun insert(values: Map<String, Any?>): MutationResult = repository.insert(values)

    /** Update one row by primary key. */
    fun update(id: TKey, values: Map<String, Any?>): Int = repository.update(id, values)

    /** Delete one row by primary key. */
    fun delete(id: TKey): Int = repository.delete(id)

    /** Insert or update according to unique columns. */
    fun upsert(values: Map<String, Any?>, conflictColumns: List<String>? = null): MutationResult =
        repository.upsert(values, conflictColumns)
}
