package com.eloykim.lyra.service.database

import android.content.Context
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.reqString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 数据库服务：只读访问 Lyra 的 SQLite。
 *
 * 只允许 SELECT / WITH；写操作没有命令入口。若后续需要写，必须先由
 * `dialog.confirm` 让用户明确授权，再单独开放写连接；本服务不做静默写入。
 */
class DatabaseService(
    private val context: Context,
    private val database: ReadOnlyDatabase = ReadOnlyDatabase(context),
) : BasePhoneService() {

    override val id: String = "database"
    override val title: String = "数据库"
    override val description: String =
        "只读查看 Lyra SQLite：表结构、表数量、样本行与受限 SELECT 查询。"

    init {
        register(
            ServiceMethod(
                name = "status",
                summary = "数据库路径/大小/表数量",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { guard { status() } }

        register(
            ServiceMethod(
                name = "tables",
                summary = "列出表与视图",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { tables() }

        register(
            ServiceMethod(
                name = "schema",
                summary = "读取表结构（列 + 索引）",
                parameters = Schema.params(
                    "table" to Schema.string("表名"),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> schema(args.reqString("table")) }

        register(
            ServiceMethod(
                name = "count",
                summary = "表行数",
                parameters = Schema.params(
                    "table" to Schema.string("表名"),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args -> count(args.reqString("table")) }

        register(
            ServiceMethod(
                name = "sample",
                summary = "读取表的前 N 行",
                parameters = Schema.params(
                    "table" to Schema.string("表名"),
                    "limit" to Schema.integer("行数", 1, 100),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> sample(args.reqString("table"), args.optIntOr("limit", 20)) }

        register(
            ServiceMethod(
                name = "query",
                summary = "执行只读 SELECT/WITH 查询（单条、最多 200 行）",
                parameters = Schema.params(
                    "sql" to Schema.string("只读 SQL，必须以 SELECT 或 WITH 开头"),
                    "args" to Schema.array("绑定参数", Schema.string("参数")),
                    "limit" to Schema.integer("最多行数", 1, 200),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args ->
            query(
                sql = args.reqString("sql"),
                params = stringArgs(args),
                limit = args.optIntOr("limit", 100),
            )
        }
    }

    private fun status(): ServiceResult {
        val file = context.getDatabasePath(DB_NAME)
        val tables = runCatching { database.tableNames() }.getOrDefault(emptyList())
        return ServiceResult.ok(
            "ok",
            JSONObject()
                .put("path", file.absolutePath)
                .put("exists", file.exists())
                .put("bytes", if (file.exists()) file.length() else 0L)
                .put("readOnly", true)
                .put("tables", tables.size)
                .put("tableNames", JSONArray(tables)),
        )
    }

    private suspend fun tables(): ServiceResult = guard {
        val page = database.tables()
        ServiceResult.ok(
            "${page.rows.size} tables",
            JSONObject()
                .put("tables", JSONArray(page.rows))
                .put("columns", JSONArray(page.columns))
                .put("count", page.rows.size),
        )
    }

    private suspend fun schema(table: String): ServiceResult = guard {
        val columns = database.schema(table)
        val indexes = runCatching { database.indexes(table) }.getOrDefault(
            ReadOnlyDatabase.Page(emptyList(), emptyList(), false),
        )
        ServiceResult.ok(
            "schema",
            JSONObject()
                .put("table", table)
                .put("columns", JSONArray(columns.rows))
                .put("indexes", JSONArray(indexes.rows)),
        )
    }

    private suspend fun count(table: String): ServiceResult = guard {
        val count = database.count(table)
        ServiceResult.ok("ok", JSONObject().put("table", table).put("count", count))
    }

    private suspend fun sample(table: String, limit: Int): ServiceResult = guard {
        // count() 会校验表名存在且合法，避免把 table 直接拼进 SQL。
        database.count(table)
        val page = database.query(
            "SELECT * FROM \"$table\"",
            emptyList(),
            limit.coerceIn(1, 100),
        )
        pageResult(page, JSONObject().put("table", table))
    }

    private suspend fun query(sql: String, params: List<String>, limit: Int): ServiceResult = guard {
        val page = database.query(sql, params, limit)
        pageResult(page, JSONObject().put("sql", sql.take(1_000)))
    }

    private fun pageResult(
        page: ReadOnlyDatabase.Page,
        extra: JSONObject = JSONObject(),
    ): ServiceResult = ServiceResult.ok(
        "${page.rows.size}${if (page.truncated) "+" else ""} rows",
        extra
            .put("columns", JSONArray(page.columns))
            .put("rows", JSONArray(page.rows))
            .put("count", page.rows.size)
            .put("truncated", page.truncated),
    )

    private suspend fun guard(block: () -> ServiceResult): ServiceResult =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: ReadOnlyDatabase.DbException) {
                ServiceResult.fail(e.code, e.message)
            } catch (t: Throwable) {
                ServiceResult.fail("database_error", t.message ?: "数据库操作失败")
            }
        }

    private fun stringArgs(args: JSONObject): List<String> {
        val array = args.optJSONArray("args") ?: return emptyList()
        return (0 until array.length()).map { index -> array.opt(index).toString() }
    }

    private companion object {
        const val DB_NAME = "lyra_history.db"
    }
}

