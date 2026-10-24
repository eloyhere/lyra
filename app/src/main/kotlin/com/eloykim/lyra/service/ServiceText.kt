package com.eloykim.lyra.service

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 [ServiceResult] 渲染成人类可读文本。
 *
 * 终端和 Agent 都使用同一套渲染：**不输出 JSON 大括号**，列表按行、表格按行、
 * 嵌套对象缩进展开；无法识别的字段才退化为 `key: value`。
 */
object ServiceText {

    private const val MAX_CHARS = 8_000

    fun format(result: ServiceResult): String {
        if (!result.ok) {
            val code = result.code.ifBlank { "error" }
            return "$code: ${result.message}".trim()
        }
        val data = result.data
        val handled = mutableSetOf("observation", "base64", "elements", "command")
        val sb = StringBuilder()

        if (result.message.isNotBlank() && !result.message.equals("ok", ignoreCase = true)) {
            sb.appendLine(result.message)
        }

        if (data.has("status") && data.has("body")) {
            appendHttpResponse(sb, data)
            handled.addAll(
                listOf("status", "url", "contentType", "headers", "body", "bytes", "truncated"),
            )
        }

        data.optJSONArray("services")?.let {
            appendServices(sb, it)
            handled += "services"
        }
        data.optJSONArray("methods")?.let {
            appendMethods(sb, it)
            handled += "methods"
        }
        data.optJSONArray("commands")?.let {
            appendCommands(sb, it)
            handled += "commands"
        }
        data.optJSONArray("tasks")?.let {
            appendTasks(sb, it)
            handled += "tasks"
        }
        data.optJSONArray("missions")?.let {
            appendMissions(sb, it)
            handled += "missions"
        }
        data.optJSONArray("apps")?.let {
            appendApps(sb, it)
            handled += "apps"
        }
        data.optJSONArray("notifications")?.let {
            appendNotifications(sb, it)
            handled += "notifications"
        }
        data.optJSONArray("messages")?.let {
            appendMessages(sb, it)
            handled += "messages"
        }
        data.optJSONArray("events")?.let {
            appendEvents(sb, it)
            handled += "events"
        }
        data.optJSONArray("byAction")?.let {
            appendUsageActions(sb, it)
            handled += "byAction"
        }
        data.optJSONArray("byDay")?.let {
            appendUsageDays(sb, it)
            handled += "byDay"
        }
        data.optJSONArray("recent")?.let {
            appendUsageReceipts(sb, it)
            handled += "recent"
        }
        data.optJSONArray("permissions")?.let {
            appendPermissions(sb, it)
            handled += "permissions"
        }
        data.optJSONArray("lines")?.let {
            appendLines(sb, it)
            handled.addAll(listOf("lines", "count"))
        }
        data.optJSONArray("matches")?.let {
            appendMatches(sb, it)
            handled += "matches"
        }
        data.optJSONArray("tables")?.let {
            appendTables(sb, it)
            handled.addAll(listOf("tables", "columns"))
        }
        data.optJSONArray("columns")?.let { columns ->
            val rows = data.optJSONArray("rows")
            if (rows != null) {
                appendTable(sb, columns, rows)
                handled.addAll(listOf("columns", "rows"))
            }
        }
        data.optJSONArray("facts")?.let {
            val columns = data.optJSONArray("columns") ?: JSONArray()
            appendTable(sb, columns, it)
            handled.addAll(listOf("facts", "columns"))
        }
        data.optJSONObject("command")?.let {
            appendCommandDetail(sb, it)
            handled += "command"
        }
        data.optJSONObject("pricing")?.let {
            appendPricing(sb, it)
            handled += "pricing"
        }
        data.optJSONObject("profile")?.let {
            sb.appendLine("画像：")
            appendObject(sb, it, 2)
            handled += "profile"
        }
        data.optString("text").takeIf { it.isNotBlank() }?.let {
            if ("text" !in handled) {
                sb.appendLine(it)
                handled += "text"
            }
        }

        appendRemaining(sb, data, handled)
        val output = sb.toString().trimEnd()
        return if (output.length <= MAX_CHARS) output else output.take(MAX_CHARS) + "\n…（已截断）"
    }

    private fun appendHttpResponse(sb: StringBuilder, data: JSONObject) {
        val status = data.optInt("status")
        val bytes = data.optLong("bytes")
        val truncated = data.optBoolean("truncated")
        sb.appendLine(
            "HTTP $status · ${data.optString("url")} · $bytes bytes" +
                if (truncated) "（已截断）" else "",
        )
        data.optString("contentType").takeIf { it.isNotBlank() }?.let {
            sb.appendLine("Content-Type：$it")
        }
        val headers = data.optJSONObject("headers")
        if (headers != null) {
            val important = listOf("Location", "Server", "Date", "Content-Length")
            val lines = important.mapNotNull { key ->
                headers.optString(key).takeIf { it.isNotBlank() }?.let { "$key：$it" }
            }
            if (lines.isNotEmpty()) sb.appendLine(lines.joinToString(" · "))
        }
        val body = data.optString("body")
        if (body.isNotBlank()) {
            sb.appendLine("正文：")
            val json = runCatching { JSONObject(body) }.getOrNull()
            val array = runCatching { JSONArray(body) }.getOrNull()
            when {
                json != null -> appendObject(sb, json, 2)
                array != null -> {
                    for (i in 0 until array.length().coerceAtMost(50)) {
                        val item = array.opt(i)
                        sb.appendLine("  - " + if (item is JSONObject) inlineObject(item) else scalar(item))
                    }
                }
                else -> {
                    val plain = if (data.optString("contentType").contains("html", true)) {
                        body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                    } else {
                        body
                    }
                    sb.appendLine("  " + plain.take(1_200).replace("\n", " ⏎ "))
                }
            }
        }
    }

    private fun appendServices(sb: StringBuilder, services: JSONArray) {
        sb.appendLine("${services.length()} 个服务：")
        for (i in 0 until services.length()) {
            val service = services.optJSONObject(i) ?: continue
            sb.appendLine(
                "· ${service.optString("id")} · ${service.optString("title")}" +
                    "（${service.optInt("methodCount")} 个命令）",
            )
            service.optString("description").takeIf { it.isNotBlank() }?.let {
                sb.appendLine("  $it")
            }
        }
    }

    private fun appendMethods(sb: StringBuilder, methods: JSONArray) {
        sb.appendLine("${methods.length()} 个命令：")
        for (i in 0 until methods.length()) {
            val method = methods.optJSONObject(i) ?: continue
            sb.appendLine(
                "· ${method.optString("name")} — ${method.optString("summary")}" +
                    " [${method.optString("risk")}]",
            )
        }
    }

    private fun appendCommands(sb: StringBuilder, commands: JSONArray) {
        sb.appendLine("${commands.length()} 个命令：")
        for (i in 0 until commands.length()) {
            val command = commands.optJSONObject(i) ?: continue
            sb.appendLine(
                "· ${command.optString("id")} — ${command.optString("summary")}" +
                    " [${command.optString("risk")}]",
            )
        }
    }

    private fun appendTasks(sb: StringBuilder, tasks: JSONArray) {
        sb.appendLine("${tasks.length()} 个任务：")
        for (i in 0 until tasks.length()) {
            val task = tasks.optJSONObject(i) ?: continue
            sb.appendLine(
                "#${task.optLong("id")} ${task.optString("status")} " +
                    task.optString("task"),
            )
            task.optString("summary").takeIf { it.isNotBlank() }?.let {
                sb.appendLine("  $it")
            }
        }
    }

    private fun appendMissions(sb: StringBuilder, missions: JSONArray) {
        sb.appendLine("${missions.length()} 个应用内任务：")
        for (i in 0 until missions.length()) {
            val mission = missions.optJSONObject(i) ?: continue
            sb.appendLine(
                "#${mission.optLong("id")} [${mission.optString("status")}] " +
                    mission.optString("title"),
            )
            mission.optString("goal").takeIf { it.isNotBlank() }?.let {
                sb.appendLine("  目标：$it")
            }
            mission.optString("note").takeIf { it.isNotBlank() }?.let {
                sb.appendLine("  备注：$it")
            }
        }
    }

    private fun appendApps(sb: StringBuilder, apps: JSONArray) {        sb.appendLine("${apps.length()} 个应用：")
        for (i in 0 until apps.length()) {
            val app = apps.optJSONObject(i) ?: continue
            val label = app.optString("label").ifBlank { app.optString("package") }
            val pkg = app.optString("package")
            val details = buildList {
                if (app.has("launchCount")) add("打开 ${app.optInt("launchCount")}")
                if (app.has("stepCount")) add("操作 ${app.optInt("stepCount")}")
                if (app.optBoolean("system")) add("系统")
                if (app.optBoolean("launchable")) add("可启动")
            }
            sb.appendLine("· $label（$pkg）${if (details.isEmpty()) "" else " · " + details.joinToString(" · ")}")
        }
    }

    private fun appendNotifications(sb: StringBuilder, notifications: JSONArray) {
        sb.appendLine("${notifications.length()} 条通知：")
        for (i in 0 until notifications.length()) {
            val n = notifications.optJSONObject(i) ?: continue
            val app = n.optString("app").ifBlank { n.optString("package") }
            val title = n.optString("title")
            val text = n.optString("text")
            sb.appendLine("· [$app] ${title.ifBlank { "（无标题）" }}")
            if (text.isNotBlank()) sb.appendLine("  ${text.take(300)}")
            n.optString("key").takeIf { it.isNotBlank() }?.let { sb.appendLine("  key=$it") }
        }
    }

    private fun appendMessages(sb: StringBuilder, messages: JSONArray) {
        sb.appendLine("${messages.length()} 条消息：")
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val role = message.optString("role").ifBlank { "message" }
            val title = message.optString("conversationTitle")
            val id = message.optLong("messageId", message.optLong("id"))
            val idPrefix = if (id > 0) "#$id " else ""
            sb.appendLine("· $idPrefix[$role]${if (title.isNotBlank()) " $title" else ""}")
            message.optString("content").takeIf { it.isNotBlank() }?.let {
                sb.appendLine("  ${it.take(500)}")
            }
            message.optString("reasoning").takeIf { it.isNotBlank() }?.let {
                sb.appendLine("  思考：${it.take(300)}")
            }
            message.optString("reaction").takeIf { it.isNotBlank() }?.let {
                sb.appendLine("  表情：$it")
            }
            message.optString("quotedContent").takeIf { it.isNotBlank() }?.let {
                val who = if (message.optString("quotedRole") == "user") "用户" else "助手"
                sb.appendLine("  引用[$who]：${it.take(200)}")
            }
        }
    }

    private fun appendEvents(sb: StringBuilder, events: JSONArray) {
        sb.appendLine("${events.length()} 条账本事件：")
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            sb.appendLine(
                "#${event.optLong("seq")} ${event.optString("actor")}/${event.optString("type")} " +
                    event.optString("digest").take(16) + "…",
            )
        }
    }

    private fun appendPricing(sb: StringBuilder, pricing: JSONObject) {
        sb.appendLine(
            "计价：${pricing.optString("providerLabel")} / ${pricing.optString("modelLabel")}",
        )
        sb.appendLine(
            "  输入 ${formatPrice(pricing.optDouble("inputPrice"))} / 1M · " +
                "输出 ${formatPrice(pricing.optDouble("outputPrice"))} / 1M",
        )
    }

    private fun formatPrice(price: Double): String =
        String.format(java.util.Locale.US, "\$%.4g", price)

    private fun appendUsageActions(sb: StringBuilder, actions: JSONArray) {
        sb.appendLine("按动作：")
        for (i in 0 until actions.length()) {
            val action = actions.optJSONObject(i) ?: continue
            sb.appendLine(
                "· ${action.optString("label")}  ${action.optInt("count")} 次 · " +
                    "${formatTokens(action.optLong("tokens"))} tokens · " +
                    "${formatDuration(action.optLong("ms"))} · " +
                    formatCost(action.optLong("costMicros")),
            )
        }
    }

    private fun appendUsageDays(sb: StringBuilder, days: JSONArray) {
        sb.appendLine("按天：")
        for (i in 0 until days.length()) {
            val day = days.optJSONObject(i) ?: continue
            sb.appendLine(
                "· ${day.optString("date")}  ${formatTokens(day.optLong("tokens"))} tokens · " +
                    "${day.optInt("receipts")} 张回执 · ${formatCost(day.optLong("costMicros"))}",
            )
        }
    }

    private fun appendUsageReceipts(sb: StringBuilder, receipts: JSONArray) {
        sb.appendLine("最近回执：")
        for (i in 0 until receipts.length()) {
            val receipt = receipts.optJSONObject(i) ?: continue
            val at = java.time.Instant.ofEpochMilli(receipt.optLong("at"))
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            sb.appendLine(
                "· $at ${receipt.optString("action")} " +
                    "${if (receipt.optBoolean("ok")) "成功" else "失败"} · " +
                    "${formatDuration(receipt.optLong("ms"))} · " +
                    "${formatTokens(receipt.optLong("tokens"))} tokens",
            )
        }
    }

    private fun formatTokens(value: Long): String = when {
        value >= 1_000_000L -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000L -> String.format(java.util.Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }

    private fun formatCost(micros: Long): String =
        String.format(java.util.Locale.US, "\$%.4f", micros / 1_000_000.0)

    private fun formatDuration(ms: Long): String = when {
        ms <= 0L -> "0ms"
        ms < 1_000L -> "${ms}ms"
        ms < 60_000L -> String.format(java.util.Locale.US, "%.1fs", ms / 1_000.0)
        else -> String.format(java.util.Locale.US, "%.1fmin", ms / 60_000.0)
    }

    private fun appendPermissions(sb: StringBuilder, permissions: JSONArray) {        sb.appendLine("权限：")
        for (i in 0 until permissions.length()) {
            val p = permissions.optJSONObject(i) ?: continue
            val name = p.optString("permission").substringAfterLast('.')
            sb.appendLine("· $name：${if (p.optBoolean("granted")) "已授予" else "未授予"}")
        }
    }

    private fun appendLines(sb: StringBuilder, lines: JSONArray) {
        sb.appendLine("${lines.length()} 行文字：")
        for (i in 0 until lines.length()) {
            val line = lines.optJSONObject(i) ?: continue
            val text = line.optString("text")
            val x = line.optInt("centerX")
            val y = line.optInt("centerY")
            sb.appendLine("· $text  ($x,$y)")
        }
    }

    private fun appendMatches(sb: StringBuilder, matches: JSONArray) {
        sb.appendLine("${matches.length()} 个匹配：")
        for (i in 0 until matches.length()) {
            val match = matches.optJSONObject(i) ?: continue
            sb.appendLine(
                "· ${match.optString("text")}  " +
                    "(${match.optInt("centerX")},${match.optInt("centerY")})",
            )
        }
    }

    private fun appendTables(sb: StringBuilder, tables: JSONArray) {
        sb.appendLine("${tables.length()} 张表：")
        for (i in 0 until tables.length()) {
            val table = tables.optJSONObject(i) ?: continue
            sb.appendLine("· ${table.optString("name")}（${table.optString("type")}）")
        }
    }

    private fun appendTable(sb: StringBuilder, columns: JSONArray, rows: JSONArray) {
        val names = (0 until columns.length()).map { columns.optString(it) }
        sb.appendLine("${rows.length()} 行：")
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val keys = if (names.isNotEmpty()) names else {
                val iterator = row.keys()
                buildList { while (iterator.hasNext()) add(iterator.next()) }
            }
            sb.appendLine("· " + keys.joinToString(" | ") { key -> "$key=${scalar(row.opt(key))}" })
        }
    }

    private fun appendCommandDetail(sb: StringBuilder, command: JSONObject) {
        sb.appendLine(command.optString("id"))
        command.optString("summary").takeIf { it.isNotBlank() }?.let { sb.appendLine("  $it") }
        sb.appendLine("  风险：${command.optString("risk")}")
        command.optJSONObject("parameters")?.let { parameters ->
            sb.appendLine("  参数：")
            appendObject(sb, parameters, 4)
        }
    }

    private fun appendRemaining(sb: StringBuilder, data: JSONObject, handled: Set<String>) {
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in handled) continue
            val value = data.opt(key) ?: continue
            appendValue(sb, key, value, 0)
        }
    }

    private fun appendObject(sb: StringBuilder, obj: JSONObject, indent: Int) {
        val pad = " ".repeat(indent)
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            appendValue(sb, key, obj.opt(key), indent + 2, pad)
        }
    }

    private fun appendValue(
        sb: StringBuilder,
        key: String,
        value: Any?,
        indent: Int,
        prefix: String = "",
    ) {
        val pad = " ".repeat(indent)
        when (value) {
            is JSONObject -> {
                if (value.length() == 0) {
                    sb.appendLine("$prefix$key：（空）")
                } else {
                    sb.appendLine("$prefix$key：")
                    appendObject(sb, value, indent)
                }
            }
            is JSONArray -> {
                if (value.length() == 0) {
                    sb.appendLine("$prefix$key：0 项")
                } else {
                    sb.appendLine("$prefix$key：${value.length()} 项")
                    for (i in 0 until value.length()) {
                        val item = value.opt(i)
                        if (item is JSONObject) {
                            sb.appendLine("$pad- ${inlineObject(item)}")
                        } else {
                            sb.appendLine("$pad- ${scalar(item)}")
                        }
                    }
                }
            }
            else -> sb.appendLine("$prefix$key：${scalar(value)}")
        }
    }

    private fun inlineObject(obj: JSONObject): String {
        val preferred = listOf("id", "name", "title", "label", "package", "summary", "status")
        val parts = buildList {
            preferred.forEach { key ->
                obj.optString(key).takeIf { it.isNotBlank() }?.let { add("$key=$it") }
            }
            if (isEmpty()) {
                val keys = obj.keys()
                while (keys.hasNext() && size < 4) {
                    val key = keys.next()
                    add("$key=${scalar(obj.opt(key))}")
                }
            }
        }
        return parts.joinToString(" · ")
    }

    private fun scalar(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> if (value.trimStart().startsWith("{") || value.trimStart().startsWith("[")) {
            "<${value.length} 字符>"
        } else {
            value.replace("\n", " ⏎ ")
        }
        is JSONObject -> inlineObject(value)
        is JSONArray -> "${value.length()} 项"
        else -> value.toString()
    }
}

