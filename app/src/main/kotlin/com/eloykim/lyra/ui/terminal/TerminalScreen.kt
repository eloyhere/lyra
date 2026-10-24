package com.eloykim.lyra.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eloykim.lyra.AppGraph
import com.eloykim.lyra.R
import com.eloykim.lyra.accessibility.AutomationClient
import com.eloykim.lyra.designsystem.StatusPill
import com.eloykim.lyra.service.ServiceCommand
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.ServiceText
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 终端：直接对自动化服务下命令的调试控制台。
 *
 * 它不是绕过策略的「后门」——所有动作仍然经过 AutomationEngine 的闸门，
 * 这里只是把 AIDL 的 observe / tap / setText / scroll / launch 暴露成可读的命令行。
 */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val automationOn by AutomationClient.connected.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    var lines by remember { mutableStateOf<List<TerminalLine>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var lastObsId by remember { mutableStateOf<Long?>(null) }
    var nextId by remember { mutableStateOf(0L) }
    var showCommands by remember { mutableStateOf(false) }
    var commandFilter by remember { mutableStateOf("") }

    fun append(tone: TerminalTone, text: String) {
        lines = lines + TerminalLine(
            id = nextId++,
            at = clock.format(Date()),
            tone = tone,
            text = text,
        )
    }

    fun printObservation(observation: JSONObject?) {
        if (observation == null) return
        val counts = observation.optJSONObject("counts")
        append(
            TerminalTone.Muted,
            "package=${observation.optString("package")} · " +
                "nodes=${counts?.optInt("nodes") ?: 0} · sent=${counts?.optInt("sent") ?: 0}",
        )
        val elements = observation.optJSONArray("elements") ?: JSONArray()
        val limit = minOf(elements.length(), 24)
        for (index in 0 until limit) {
            elements.optJSONObject(index)?.let { element ->
                append(TerminalTone.Output, formatElement(element))
            }
        }
        if (elements.length() > limit) {
            append(
                TerminalTone.Muted,
                "…还有 ${elements.length() - limit} 个元素",
            )
        }
    }

    fun reportService(result: ServiceResult) {
        if (!result.ok) {
            append(TerminalTone.Error, ServiceText.format(result))
            return
        }
        result.data.optJSONObject("observation")?.let { observation ->
            lastObsId = observation.optLong("obsId", lastObsId ?: 0L)
            printObservation(observation)
        }
        val text = ServiceText.format(result)
        if (text.isNotBlank()) append(TerminalTone.Output, text)
    }

    suspend fun runCommand(raw: String) {
        val parts = raw.trim().split(Regex("\\s+"), limit = 2)
        val command = parts.firstOrNull()?.lowercase().orEmpty()
        val rest = parts.getOrNull(1)?.trim().orEmpty()

        suspend fun runService(id: String, args: JSONObject = JSONObject()): ServiceResult {
            val result = AppGraph.commands.run(id, args)
            reportService(result)
            return result
        }

        when (command) {
            "help", "?" -> runService("provider.list")

            "cmd", "svc" -> {
                if (rest.isBlank()) {
                    append(TerminalTone.Error, "用法：cmd <service.method> [JSON|key=value ...]")
                    return
                }
                reportService(AppGraph.commands.run(rest))
            }

            "status" -> {
                runService("status.overview")
                append(TerminalTone.Muted, "最近 obsId   ${lastObsId ?: "无"}")
            }

            "usage", "tokens" -> {
                val sub = rest.substringBefore(' ').lowercase().ifBlank { "summary" }
                val argument = rest.substringAfter(' ', "").trim()
                when (sub) {
                    "report", "summary", "byAction", "byDay" -> runService(
                        "usage.$sub",
                        JSONObject().put("days", argument.toIntOrNull() ?: 7),
                    )
                    "recent" -> runService(
                        "usage.recent",
                        JSONObject().put("limit", argument.toIntOrNull() ?: 20),
                    )
                    "help" -> runService("usage.help", JSONObject().put("command", argument))
                    else -> append(
                        TerminalTone.Error,
                        "用法：usage [report|summary|byAction|byDay|recent|help] [天数|条数]",
                    )
                }
            }

            "provider", "providers" -> {
                val sub = rest.substringBefore(' ').lowercase().ifBlank { "list" }
                val argument = rest.substringAfter(' ', "").trim()
                when (sub) {
                    "list", "services" -> runService(
                        "provider.list",
                        JSONObject().put("query", argument),
                    )
                    "count" -> runService("provider.count")
                    "commands" -> runService(
                        "provider.commands",
                        JSONObject().put("query", argument),
                    )
                    "get", "schema" -> runService(
                        "provider.$sub",
                        JSONObject().put("command", argument),
                    )
                    "search" -> runService(
                        "provider.search",
                        JSONObject().put("query", argument),
                    )
                    "risk" -> runService(
                        "provider.risk",
                        JSONObject().put("risk", argument),
                    )
                    "help" -> runService(
                        "provider.help",
                        JSONObject().put("command", argument),
                    )
                    else -> append(
                        TerminalTone.Error,
                        "用法：provider [list|count|commands|get|schema|search|risk|help] [参数]",
                    )
                }
            }

            "db", "database" -> {
                val sub = rest.substringBefore(' ').lowercase().ifBlank { "status" }
                val argument = rest.substringAfter(' ', "").trim()
                when (sub) {
                    "status" -> runService("database.status")
                    "tables" -> runService("database.tables")
                    "schema", "count", "sample" -> runService(
                        "database.$sub",
                        JSONObject().put("table", argument),
                    )
                    "query" -> runService(
                        "database.query",
                        JSONObject().put("sql", argument),
                    )
                    "help" -> runService(
                        "database.help",
                        JSONObject().put("command", argument),
                    )
                    else -> append(
                        TerminalTone.Error,
                        "用法：db [status|tables|schema <表>|count <表>|sample <表>|query <SELECT ...>|help]",
                    )
                }
            }

            "impression", "impressions" -> {
                val sub = rest.substringBefore(' ').lowercase().ifBlank { "summary" }
                val argument = rest.substringAfter(' ', "").trim()
                when (sub) {
                    "summary", "profile", "hours", "counts" -> runService("impression.$sub")
                    "facts", "topApps", "topActions" -> runService(
                        "impression.$sub",
                        JSONObject().put(
                            "limit",
                            argument.toIntOrNull()?.coerceIn(1, 100) ?: 20,
                        ),
                    )
                    "get" -> runService(
                        "impression.get",
                        JSONObject().put("key", argument),
                    )
                    "help" -> runService(
                        "impression.help",
                        JSONObject().put("command", argument),
                    )
                    else -> append(
                        TerminalTone.Error,
                        "用法：impression [summary|profile|facts|get <key>|topApps|topActions|hours|counts|help]",
                    )
                }
            }

            "observe" -> {
                val result = runService("screen.observe")
                if (result.ok) {
                    val observation = result.data.optJSONObject("observation")
                    lastObsId = observation?.optLong("obsId") ?: result.data.optLong("obsId")
                }
            }

            "tap", "click" -> {
                val nodeId = rest.toIntOrNull()
                if (nodeId == null) {
                    append(TerminalTone.Error, "用法：tap <id>")
                    return
                }
                val obsId = lastObsId
                if (obsId == null) {
                    append(TerminalTone.Error, "先执行 observe，再点按编号")
                    return
                }
                runService(
                    "touch.tap",
                    JSONObject().put("nodeId", nodeId).put("obsId", obsId),
                )
            }

            "tapat" -> {
                val coordinates = rest.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                if (coordinates.size < 2) {
                    append(TerminalTone.Error, "用法：tapAt <x> <y>")
                    return
                }
                runService(
                    "touch.tapAt",
                    JSONObject().put("x", coordinates[0]).put("y", coordinates[1]),
                )
            }

            "longpress" -> {
                val values = rest.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                if (values.size < 2) {
                    append(TerminalTone.Error, "用法：longPress <x> <y> [ms]")
                    return
                }
                runService(
                    "touch.longPress",
                    JSONObject()
                        .put("x", values[0])
                        .put("y", values[1])
                        .put("durationMs", values.getOrElse(2) { 600 }),
                )
            }

            "swipe" -> {
                val direction = rest.lowercase()
                if (direction in setOf("up", "down", "left", "right")) {
                    runService("swipe.$direction")
                    return
                }
                val values = rest.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                if (values.size < 4) {
                    append(TerminalTone.Error, "用法：swipe up|down|left|right 或 swipe <x1> <y1> <x2> <y2> [ms]")
                    return
                }
                runService(
                    "swipe.from",
                    JSONObject()
                        .put("x1", values[0])
                        .put("y1", values[1])
                        .put("x2", values[2])
                        .put("y2", values[3])
                        .put("durationMs", values.getOrElse(4) { 300 }),
                )
            }

            "text", "input", "type" -> {
                val nodeId = rest.substringBefore(' ').toIntOrNull()
                val content = rest.substringAfter(' ', "").trim()
                if (nodeId == null || content.isBlank()) {
                    append(TerminalTone.Error, "用法：text <id> <内容>")
                    return
                }
                val obsId = lastObsId
                if (obsId == null) {
                    append(TerminalTone.Error, "先执行 observe")
                    return
                }
                runService(
                    "text.input",
                    JSONObject()
                        .put("text", content)
                        .put("nodeId", nodeId)
                        .put("obsId", obsId),
                )
            }

            "scroll" -> {
                val direction = rest.lowercase().ifBlank { "down" }
                if (direction !in setOf("up", "down", "left", "right")) {
                    append(TerminalTone.Error, "用法：scroll <up|down|left|right>")
                    return
                }
                runService(
                    "touch.scroll",
                    JSONObject()
                        .put("direction", direction)
                        .put("obsId", lastObsId ?: 0L),
                )
            }

            "back" -> runService("window.back")
            "home" -> runService("window.home")
            "recents" -> runService("window.recents")
            "notifications" -> runService("window.notifications")

            "launch", "open" -> {
                if (rest.isBlank()) {
                    append(TerminalTone.Error, "用法：launch <包名>")
                    return
                }
                runService("application.launch", JSONObject().put("package", rest))
            }

            "screenshot" -> runService("screenshot.capture")

            "application" -> {
                val limit = rest.toIntOrNull()?.coerceIn(1, 30) ?: 8
                runService("application.recent", JSONObject().put("limit", limit))
            }

            "apps" -> {
                runService(
                    "application.list",
                    JSONObject().put("query", rest).put("limit", 50),
                )
            }

            "history", "tasks" -> {
                val limit = rest.toIntOrNull()?.coerceIn(1, 30) ?: 8
                runService("task.list", JSONObject().put("limit", limit))
            }

            "task" -> {
                val sub = rest.substringBefore(' ').lowercase()
                val argument = rest.substringAfter(' ', "").trim()
                when (sub) {
                    "", "list", "history" -> {
                        val limit = argument.toIntOrNull()?.coerceIn(1, 30) ?: 8
                        runService("task.list", JSONObject().put("limit", limit))
                    }
                    "ledger" -> {
                        val limit = argument.toIntOrNull()?.coerceIn(1, 80) ?: 12
                        runService("task.ledger", JSONObject().put("limit", limit))
                    }
                    "status" -> runService("task.status")
                    "verify" -> runService("task.verify")
                    "help" -> runService("task.help", JSONObject().put("command", argument))
                    else -> append(
                        TerminalTone.Error,
                        "用法：task [list|ledger|status|verify|help]",
                    )
                }
            }

            "window" -> {
                val sub = rest.substringBefore(' ').lowercase().ifBlank { "status" }
                val argument = rest.substringAfter(' ', "").trim()
                when (sub) {
                    "home", "back", "recent", "recents", "notifications" ->
                        runService("window.$sub")
                    "go" -> runService(
                        "window.go",
                        if (argument.startsWith("{")) {
                            AppGraph.commands.parseArgs(argument) ?: JSONObject()
                        } else {
                            JSONObject().put(
                                if (argument.contains('.')) "package" else "app",
                                argument,
                            )
                        },
                    )
                    "current", "status" -> runService("window.$sub")
                    "help" -> runService("window.help", JSONObject().put("command", argument))
                    else -> append(
                        TerminalTone.Error,
                        "用法：window [home|back|recent|notifications|go <包名|应用名>|current|status|help]",
                    )
                }
            }

            "mission", "missions" -> {
                val sub = rest.substringBefore(' ').lowercase().ifBlank { "list" }
                val left = rest.substringAfter(' ', "").trim()
                when (sub) {
                    "list" -> runService(
                        "mission.list",
                        JSONObject().put("status", left).put("limit", 20),
                    )
                    "stats" -> runService("mission.stats")
                    "get", "remove" -> runService(
                        "mission.$sub",
                        JSONObject().put("id", left.toLongOrNull() ?: 0L),
                    )
                    "start", "complete", "cancel" -> runService(
                        "mission.$sub",
                        JSONObject().put("id", left.substringBefore(' ').toLongOrNull() ?: 0L)
                            .put("note", left.substringAfter(' ', "").trim()),
                    )
                    "create" -> runService(
                        "mission.create",
                        JSONObject().put("title", left),
                    )
                    "update" -> {
                        val id = left.substringBefore(' ').toLongOrNull() ?: 0L
                        val status = left.substringAfter(' ', "").trim()
                        runService(
                            "mission.update",
                            JSONObject().put("id", id).put("status", status),
                        )
                    }
                    "help" -> runService("mission.help", JSONObject().put("command", left))
                    else -> append(
                        TerminalTone.Error,
                        "用法：mission [list|get|create|update|start|complete|cancel|remove|stats|help]",
                    )
                }
            }

            "recent" -> runService("window.recent")

            "ledger" -> {
                val limit = rest.toIntOrNull()?.coerceIn(1, 80) ?: 12
                runService("task.ledger", JSONObject().put("limit", limit))
            }

            "profile" -> runService("impression.summary")

            "network", "net" -> {
                val sub = rest.substringBefore(' ').lowercase().ifBlank { "status" }
                val argument = rest.substringAfter(' ', "").trim()
                when (sub) {
                    "status", "wifi", "mobile", "enableWifi", "disableWifi",
                    "enableData", "disableData" -> runService("network.$sub")
                    "ping" -> runService(
                        "network.ping",
                        JSONObject().put("host", argument),
                    )
                    "get" -> runService(
                        "network.get",
                        JSONObject().put("url", argument),
                    )
                    "post" -> {
                        val url = argument.substringBefore(' ').trim()
                        val body = argument.substringAfter(' ', "").trim()
                        runService(
                            "network.post",
                            JSONObject().put("url", url).put("body", body),
                        )
                    }
                    "request" -> {
                        val method = argument.substringBefore(' ').trim()
                        val remainder = argument.substringAfter(' ', "").trim()
                        val url = remainder.substringBefore(' ').trim()
                        val body = remainder.substringAfter(' ', "").trim()
                        runService(
                            "network.request",
                            JSONObject().put("method", method).put("url", url).put("body", body),
                        )
                    }
                    "openSettings", "settings" -> runService(
                        "network.openSettings",
                        JSONObject().put("panel", argument),
                    )
                    "help" -> runService("network.help", JSONObject().put("command", argument))
                    else -> append(
                        TerminalTone.Error,
                        "用法：network [status|wifi|mobile|enableWifi|disableWifi|enableData|disableData|ping|get|post|request|openSettings|help]",
                    )
                }
            }

            "echo" -> append(TerminalTone.Output, rest)

            "clear", "cls" -> lines = emptyList()

            "" -> Unit

            else -> append(
                TerminalTone.Error,
                "未知命令：$command（输入 help 查看服务；cmd <命令> 执行）",
            )
        }
    }

    fun submit() {
        val raw = input.trim()
        if (raw.isEmpty() || busy) return
        input = ""
        append(TerminalTone.Input, raw)
        busy = true
        scope.launch {
            try {
                runCommand(raw)
            } catch (t: Throwable) {
                append(TerminalTone.Error, "执行失败：${t.message ?: t::class.java.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        append(TerminalTone.Ok, "Lyra Terminal · 0.1.0")
        append(
            TerminalTone.Muted,
            "输入 help（等同 provider list）查看服务，cmd <命令> 执行，<service>.help 查看命令。所有动作仍经过策略闸门。",
        )
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.lastIndex)
        }
    }

    // 只在「已连接 -> 断开」或反向变化时提示，避免首帧刷屏。
    var lastAutomation by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(automationOn) {
        val previous = lastAutomation
        if (previous != null && previous != automationOn) {
            append(
                if (automationOn) TerminalTone.Ok else TerminalTone.Error,
                if (automationOn) "自动化服务已绑定" else "自动化服务已断开",
            )
        }
        lastAutomation = automationOn
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "返回",
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text("终端", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showCommands = !showCommands }) {
                    Text(if (showCommands) "返回" else "服务")
                }
                StatusPill(
                    active = automationOn,
                    text = if (automationOn) "已绑定" else "未绑定",
                )
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = { lines = emptyList() },
                ) {
                    Text("清空")
                }
            }
            HorizontalDivider()
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }

            if (showCommands) {
                CommandCatalog(
                    commands = AppGraph.commands.search(commandFilter),
                    filter = commandFilter,
                    onFilterChange = { commandFilter = it },
                    onPick = { command ->
                        input = "cmd ${command.id} "
                        showCommands = false
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            } else {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        items(lines, key = { it.id }) { line ->
                            TerminalLineRow(line)
                        }
                    }
                }
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        decorationBox = { innerTextField ->
                            Box {
                                if (input.isBlank()) {
                                    Text(
                                        text = "输入命令…",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { submit() },
                        enabled = input.isNotBlank() && !busy,
                    ) {
                        Text("运行")
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalLineRow(line: TerminalLine) {
    val color = when (line.tone) {
        TerminalTone.Input -> MaterialTheme.colorScheme.primary
        TerminalTone.Output -> MaterialTheme.colorScheme.onSurface
        TerminalTone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
        TerminalTone.Ok -> MaterialTheme.colorScheme.tertiary
        TerminalTone.Error -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = line.at,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            ),
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = if (line.tone == TerminalTone.Input) "› ${line.text}" else line.text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            ),
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CommandCatalog(
    commands: List<ServiceCommand>,
    filter: String,
    onFilterChange: (String) -> Unit,
    onPick: (ServiceCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            BasicTextField(
                value = filter,
                onValueChange = onFilterChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (filter.isBlank()) {
                            Text(
                                text = "筛选命令…",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${commands.size} 条命令 · 点击一条填入输入框",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(commands, key = { it.id }) { command ->
                    val riskColor = when (command.risk) {
                        ServiceRisk.SENSITIVE -> MaterialTheme.colorScheme.error
                        ServiceRisk.CONTROL -> MaterialTheme.colorScheme.tertiary
                        ServiceRisk.COMMUNICATION -> MaterialTheme.colorScheme.primary
                        ServiceRisk.INPUT -> MaterialTheme.colorScheme.secondary
                        ServiceRisk.READ -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(command) }
                            .padding(vertical = 6.dp),
                    ) {
                        Text(
                            text = command.id,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = command.summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = command.parameterHint(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "[${command.risk.name.lowercase()}]",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = riskColor,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

private fun formatElement(element: JSONObject): String {
    val id = element.optInt("id")
    val role = element.optString("role")
    val label = element.optString("text").ifBlank { element.optString("desc") }
    val bounds = element.optJSONArray("bounds")
    val box = if (bounds != null && bounds.length() >= 4) {
        "[${bounds.optInt(0)},${bounds.optInt(1)}]"
    } else {
        ""
    }
    val flags = buildString {
        if (element.optBoolean("clickable")) append(" 可点")
        if (element.optBoolean("editable")) append(" 可输入")
        if (element.optBoolean("scrollable")) append(" 可滚动")
    }
    return "#$id $role \"${label.take(36)}\"  $box$flags"
}

private enum class TerminalTone { Input, Output, Muted, Ok, Error }

private data class TerminalLine(
    val id: Long,
    val at: String,
    val tone: TerminalTone,
    val text: String,
)

