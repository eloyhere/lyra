package com.eloykim.lyra.service.database

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * 只读 SQLite 访问层。
 *
 * 直接以 [SQLiteDatabase.OPEN_READONLY] 打开 app 自己的 `lyra_history.db`，
 * 不持有写连接；SQL 再做一层「只允许 SELECT / WITH」的白名单校验。
 */
class ReadOnlyDatabase(private val context: Context) {

    data class Page(
        val columns: List<String>,
        val rows: List<JSONObject>,
        val truncated: Boolean,
    )

    class DbException(val code: String, override val message: String) : Exception(message)

    fun exists(): Boolean = context.getDatabasePath(DB_NAME).exists()

    fun tables(): Page = query(
        """
        SELECT name, type, sql
        FROM sqlite_master
        WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%'
        ORDER BY name ASC
        """.trimIndent(),
        emptyList(),
        200,
    )

    fun schema(table: String): Page {
        val name = requireTable(table)
        return queryInternal("PRAGMA table_info(\"$name\")", emptyList(), 100)
    }

    fun indexes(table: String): Page {
        val name = requireTable(table)
        return queryInternal("PRAGMA index_list(\"$name\")", emptyList(), 100)
    }

    fun count(table: String): Long {
        val name = requireTable(table)
        val page = query("SELECT COUNT(*) AS count FROM \"$name\"", emptyList(), 1)
        return page.rows.firstOrNull()?.optLong("count") ?: 0L
    }

    fun facts(limit: Int = 50): Page {
        ensureTable("profile_facts")
        return query(
            "SELECT key, value, confidence, updated_at FROM profile_facts " +
                "ORDER BY updated_at DESC",
            emptyList(),
            limit,
        )
    }

    /**
     * 执行一条只读查询。允许 SELECT 与 WITH；拒绝 PRAGMA 与任何写关键字。
     */
    fun query(sql: String, args: List<String> = emptyList(), limit: Int = 100): Page {
        return queryInternal(validate(sql), args, limit)
    }

    private fun queryInternal(sql: String, args: List<String>, limit: Int): Page {
        val safeLimit = limit.coerceIn(1, 200)
        val file = context.getDatabasePath(DB_NAME)
        if (!file.exists()) {
            throw DbException("database_missing", "数据库还不存在：${file.absolutePath}")
        }
        val db = runCatching {
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
        }.getOrElse {
            throw DbException("database_open_failed", it.message ?: "无法以只读方式打开数据库")
        }
        try {
            db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                val columns = cursor.columnNames.toList()
                val rows = ArrayList<JSONObject>()
                var truncated = false
                while (cursor.moveToNext()) {
                    if (rows.size >= safeLimit) {
                        truncated = true
                        break
                    }
                    rows += rowJson(cursor, columns)
                }
                return Page(columns = columns, rows = rows, truncated = truncated)
            }
        } finally {
            runCatching { db.close() }
        }
    }

    fun tableNames(): List<String> {
        val file = context.getDatabasePath(DB_NAME)
        if (!file.exists()) return emptyList()
        val db = runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull() ?: return emptyList()
        return try {
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type IN ('table','view') " +
                    "AND name NOT LIKE 'sqlite_%' ORDER BY name",
                null,
            ).use { cursor ->
                val names = ArrayList<String>()
                while (cursor.moveToNext()) names += cursor.getString(0)
                names
            }
        } finally {
            runCatching { db.close() }
        }
    }

    private fun requireTable(table: String): String {
        val name = table.trim()
        if (!name.matches(TABLE_NAME)) {
            throw DbException("bad_args", "表名不合法：$table")
        }
        ensureTable(name)
        return name
    }

    private fun ensureTable(name: String) {
        if (name !in tableNames()) {
            throw DbException("table_not_found", "没有表或视图 $name")
        }
    }

    private fun validate(raw: String): String {
        val sql = raw.trim().trimEnd(';').trim()
        if (sql.isEmpty()) throw DbException("bad_args", "SQL 为空")
        if (sql.contains(';')) {
            throw DbException("read_only", "只允许单条只读语句")
        }
        val upper = sql.uppercase()
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw DbException("read_only", "只允许 SELECT / WITH 查询")
        }
        val forbidden = listOf(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "REPLACE",
            "ATTACH", "DETACH", "VACUUM", "REINDEX", "TRIGGER", "PRAGMA",
        )
        val hit = forbidden.firstOrNull { word ->
            Regex("\\b$word\\b").containsMatchIn(upper)
        }
        if (hit != null) {
            throw DbException("read_only", "只读服务不允许 $hit")
        }
        return sql
    }

    private fun rowJson(cursor: Cursor, columns: List<String>): JSONObject {
        val row = JSONObject()
        columns.forEachIndexed { index, name ->
            val value: Any = when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                Cursor.FIELD_TYPE_BLOB -> "<blob ${cursor.getBlob(index).size} bytes>"
                else -> cursor.getString(index) ?: ""
            }
            row.put(name, value)
        }
        return row
    }

    private companion object {
        const val DB_NAME = "lyra_history.db"
        val TABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
