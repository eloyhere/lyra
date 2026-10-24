package com.eloykim.lyra.service

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条扁平化后的 Agent 命令。
 *
 * `service.method` 是稳定 id；终端、工具目录、Agent 都使用同一个 id。
 */
data class ServiceCommand(
    val id: String,
    val serviceId: String,
    val serviceTitle: String,
    val method: String,
    val summary: String,
    val parameters: JSONObject,
    val risk: ServiceRisk,
) {

    val required: List<String>
        get() {
            val array = parameters.optJSONArray("required") ?: return emptyList()
            return (0 until array.length()).map { array.optString(it) }
        }

    /** 终端里显示的一行参数提示。 */
    fun parameterHint(): String {
        val properties = parameters.optJSONObject("properties") ?: return ""
        val names = properties.keys()
        val parts = ArrayList<String>()
        while (names.hasNext()) {
            val name = names.next()
            parts += if (name in required) name else "$name?"
        }
        return if (parts.isEmpty()) "" else parts.joinToString(", ", "(", ")")
    }

    fun oneLine(): String = buildString {
        append(id)
        append("  ")
        append(summary)
        val hint = parameterHint()
        if (hint.isNotEmpty()) {
            append("  ")
            append(hint)
        }
        append("  [")
        append(risk.name.lowercase())
        append("]")
    }
}

/**
 * 命令目录：把 [ServiceHub] 里的服务方法拍平成可直接调用的命令。
 *
 * 支持三种调用形式：
 * - `CommandRegistry.run("screenshot.capture")`
 * - `CommandRegistry.run("screenshot.capture", JSONObject().put("maxDimension", 1024))`
 * - `CommandRegistry.run("screenshot.capture maxDimension=1024")`（终端用）
 */
class CommandRegistry(val hub: ServiceHub) {

    val commands: List<ServiceCommand> = hub.services.flatMap { service ->
        service.methods.map { method ->
            ServiceCommand(
                id = "${service.id}.${method.name}",
                serviceId = service.id,
                serviceTitle = service.title,
                method = method.name,
                summary = method.summary,
                parameters = method.parameters,
                risk = method.risk,
            )
        }
    }

    private val byId = commands.associateBy { it.id }

    /** 方法名唯一时才允许省略服务前缀（例如 `capture` → `screenshot.capture`）。 */
    private val byUniqueMethod = commands
        .groupBy { it.method }
        .filterValues { it.size == 1 }
        .mapValues { it.value.first() }

    fun find(raw: String): ServiceCommand? {
        val id = raw.trim()
        byId[id]?.let { return it }
        if ('.' !in id) byUniqueMethod[id]?.let { return it }
        return null
    }

    fun search(query: String = ""): List<ServiceCommand> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return commands
        return commands.filter { command ->
            command.id.lowercase().contains(needle) ||
                command.summary.lowercase().contains(needle) ||
                command.serviceTitle.lowercase().contains(needle)
        }
    }

    suspend fun run(commandId: String, args: JSONObject = JSONObject()): ServiceResult {
        if (commandId == "help" || commandId == "?") {
            return run("provider.list", args)
        }
        val command = find(commandId) ?: return ServiceResult.fail(
            "unknown_command",
            "没有命令 $commandId；输入 help 查看服务列表",
        )
        val result = hub.call(command.serviceId, command.method, args)
        if (!result.data.has("command")) {
            result.data.put("command", command.id)
        }
        return result
    }

    /** 解析 `命令 [JSON | key=value ...]` 并执行。 */
    suspend fun run(line: String): ServiceResult {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return ServiceResult.fail("empty_command", "命令为空")
        if (trimmed == "help" || trimmed == "?") {
            return run("provider.list")
        }
        val parts = trimmed.split(Regex("\\s+"), limit = 2)
        val rawId = parts[0]
        val rest = parts.getOrNull(1)?.trim().orEmpty()
        val command = find(rawId) ?: return ServiceResult.fail(
            "unknown_command",
            "没有命令 $rawId；可用：${search(rawId).take(5).joinToString { it.id }.ifBlank { "输入 help 查看服务" }}",
        )
        val args = parseArgs(rest) ?: return ServiceResult.fail(
            "bad_args",
            "无法解析参数：$rest（可用 JSON 或 key=value）",
        )
        return run(command.id, args)
    }

    fun catalogJson(): JSONObject {
        val array = JSONArray()
        commands.forEach { command ->
            array.put(
                JSONObject()
                    .put("id", command.id)
                    .put("service", command.serviceId)
                    .put("method", command.method)
                    .put("summary", command.summary)
                    .put("risk", command.risk.name.lowercase())
                    .put("parameters", command.parameters),
            )
        }
        return JSONObject().put("commands", array)
    }

    fun helpText(): String = buildString {
        appendLine("手机命令（service.method）")
        commands.groupBy { it.serviceId }.forEach { (serviceId, serviceCommands) ->
            appendLine()
            appendLine("${serviceCommands.first().serviceTitle} · $serviceId")
            serviceCommands.forEach { command ->
                append("  ")
                append(command.id)
                append("  ")
                append(command.summary)
                val hint = command.parameterHint()
                if (hint.isNotEmpty()) {
                    append("  ")
                    append(hint)
                }
                appendLine()
            }
        }
    }.trimEnd()

    /** 把 `key=value` / JSON 字符串解析成 JSONObject；解析失败返回 null。 */
    fun parseArgs(raw: String): JSONObject? {
        val text = raw.trim()
        if (text.isEmpty()) return JSONObject()
        if (text.startsWith("{")) {
            return runCatching { JSONObject(text) }.getOrNull()
        }
        val token = Regex("""([A-Za-z_][A-Za-z0-9_]*)=("[^"]*"|'[^']*'|\S+)""")
        val matches = token.findAll(text).toList()
        if (matches.isEmpty()) return null
        val args = JSONObject()
        matches.forEach { match ->
            val key = match.groupValues[1]
            var value = match.groupValues[2]
            if (value.length >= 2 &&
                ((value.startsWith('"') && value.endsWith('"')) ||
                    (value.startsWith('\'') && value.endsWith('\'')))
            ) {
                value = value.substring(1, value.length - 1)
            }
            args.put(key, coerceArg(value))
        }
        return args
    }

    private fun coerceArg(value: String): Any = when {
        value.equals("true", ignoreCase = true) -> true
        value.equals("false", ignoreCase = true) -> false
        value.equals("null", ignoreCase = true) -> JSONObject.NULL
        value.matches(Regex("-?\\d+")) -> value.toLongOrNull()?.let {
            if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it
        } ?: value
        value.matches(Regex("-?\\d+\\.\\d+")) -> value.toDoubleOrNull() ?: value
        value.startsWith("[") -> runCatching { JSONArray(value) }.getOrNull() ?: value
        value.startsWith("{") -> runCatching { JSONObject(value) }.getOrNull() ?: value
        else -> value
    }
}
