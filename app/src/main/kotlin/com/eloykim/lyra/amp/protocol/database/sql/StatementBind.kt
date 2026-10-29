package com.eloykim.lyra.amp.protocol.database.sql

/**
 * Binding helpers for adapters that issue trusted SQL directly instead of going
 * through [Sql].
 *
 * The reference passes raw JavaScript values to `prepare(sql).run(...)`; these
 * helpers make the same call shape available in Kotlin while still normalizing
 * every value through [toSqlParameter] before it reaches the driver.
 */

private fun bind(params: Array<out Any?>): Array<SqlParameter> =
    Array(params.size) { index -> toSqlParameter(params[index]) }

fun DatabaseSync.runStatement(sql: String, vararg params: Any?): MutationResult =
    prepare(sql).run(*bind(params))

fun DatabaseSync.rows(sql: String, vararg params: Any?): List<Row> =
    prepare(sql).all(*bind(params))

fun DatabaseSync.row(sql: String, vararg params: Any?): Row? =
    prepare(sql).get(*bind(params))
