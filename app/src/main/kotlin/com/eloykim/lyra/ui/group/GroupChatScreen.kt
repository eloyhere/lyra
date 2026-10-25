package com.eloykim.lyra.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eloykim.lyra.AppGraph
import com.eloykim.lyra.R
import com.eloykim.lyra.data.GroupAgentRecord
import com.eloykim.lyra.data.GroupMessageRecord
import com.eloykim.lyra.data.GroupRoleRecord
import com.eloykim.lyra.data.GroupScenarioRecord
import com.eloykim.lyra.data.GroupSessionRecord
import com.eloykim.lyra.settings.SettingsStore
import com.eloykim.lyra.ui.chat.ChatMessage
import com.eloykim.lyra.ui.chat.ChatMessageList
import com.eloykim.lyra.ui.chat.ChatRole
import com.eloykim.lyra.ui.chat.MessageQuote
import com.eloykim.lyra.ui.chat.rememberChatStyle
import kotlinx.coroutines.launch

/**
 * 群聊页：成员头像、@ 选择、消息流、Agent 配置。
 *
 * 只有被 @ 的成员回复；群聊 Agent 不调用工具。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<GroupChatSnapshot?>(null) }
        var typingAgentId by remember { mutableStateOf<Long?>(null) }
        var streamingText by remember { mutableStateOf("") }
        var streamingReason by remember { mutableStateOf("") }
        var pendingUserText by remember { mutableStateOf("") }

    var groupTasks by remember { mutableStateOf<List<com.eloykim.lyra.data.GroupTaskRecord>>(emptyList()) }
    val refreshTasks: suspend () -> Unit = {
        runCatching {
            val sid = com.eloykim.lyra.AppGraph.history.groupChat.latestSession()?.id
            if (sid != null) groupTasks = com.eloykim.lyra.AppGraph.history.groupChat.tasks(sid)
        }
    }
    var session by remember { mutableStateOf<GroupChatSession?>(null) }
    var input by remember { mutableStateOf("") }
    var mentionMenu by remember { mutableStateOf(false) }
    var selectedAgents by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var quoted by remember { mutableStateOf<GroupMessageRecord?>(null) }
    var sessions by remember { mutableStateOf<List<GroupSessionRecord>>(emptyList()) }
    var deleteSessionTarget by remember { mutableStateOf<GroupSessionRecord?>(null) }
    var scenarios by remember { mutableStateOf<List<GroupScenarioRecord>>(emptyList()) }
    var selectedScenarioId by remember { mutableStateOf<Long?>(null) }
    var selectedRoleId by remember { mutableStateOf<Long?>(null) }
    var editingRole by remember { mutableStateOf<GroupRoleRecord?>(null) }
    var editingScenario by remember { mutableStateOf<GroupScenarioRecord?>(null) }
    var sessionMenu by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var editAgent by remember { mutableStateOf<GroupAgentRecord?>(null) }
    var showRoleDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val sending by GroupChatController.sending.collectAsStateWithLifecycle()
    val chatStyle = rememberGroupChatStyle()

    suspend fun reload() {
        snapshot = session?.snapshot()
    }

    LaunchedEffect(Unit) {
        val store = AppGraph.history.groupChat
        val settings = SettingsStore.state.value
        store.ensurePresetScenarios(
            brandId = settings.brandId,
            model = settings.model,
            thinkingLevel = settings.thinkingLevel.wireValue,
        )
        scenarios = store.scenarios()
        val sessionRecord = store.sessions().firstOrNull() ?: store.ensureDefaultSession()
        sessions = store.sessions().ifEmpty { listOf(sessionRecord) }
        selectedScenarioId = sessionRecord.scenarioId ?: scenarios.firstOrNull()?.id
        val scenarioRoles = store.roles(selectedScenarioId)
        selectedRoleId = sessionRecord.roleId
            ?.takeIf { id -> scenarioRoles.any { it.id == id } }
            ?: scenarioRoles.firstOrNull()?.id
        session = GroupChatSession(sessionRecord.id)
        snapshot = session?.snapshot()
    }

    fun openSession(id: Long) {
        session = GroupChatSession(id)
        selectedAgents = emptySet()
        quoted = null
        scope.launch {
            val record = AppGraph.history.groupChat.session(id)
            if (record != null) {
                selectedScenarioId = record.scenarioId ?: scenarios.firstOrNull()?.id
                val roleOptions = AppGraph.history.groupChat.roles(selectedScenarioId)
                selectedRoleId = record.roleId
                    ?.takeIf { rid -> roleOptions.any { it.id == rid } }
                    ?: roleOptions.firstOrNull()?.id
            }
            reload()
        }
    }

    fun newSession() {
        scope.launch {
            val store = AppGraph.history.groupChat
            val created = store.createSession("群聊 ${sessions.size + 1}")
            sessions = store.sessions()
            // 建群即招募：拉起全部部长
            val roles = store.roles()
            val defaultRole = roles.firstOrNull()
            if (defaultRole != null) {
                val ministers = listOf(
                    "产品部长" to "PRODUCT_MINISTER",
                    "工程部长" to "ENGINEERING_MINISTER",
                    "运营部长" to "OPERATIONS_MINISTER",
                    "职能部长" to "STAFF_MINISTER",
                    "法务部长" to "LEGAL_MINISTER",
                    "人力部长" to "HR_MINISTER",
                    "财务部长" to "FINANCE_MINISTER",
                    "资源部长" to "RESOURCE_MINISTER",
                )
                ministers.forEach { (title, key) ->
                    runCatching {
                        store.addAgent(
                            sessionId = created.id,
                            roleId = defaultRole.id,
                            nickname = title,
                            identity = "",
                            positionKey = key,
                        )
                    }
                }
            }
            session = GroupChatSession(created.id)
            selectedAgents = emptySet()
            quoted = null
            snapshot = session?.snapshot()
            sessionMenu = false
        }
    }

    val data = snapshot
    val scenarioRoles = data?.roles.orEmpty()
        .filter { it.scenarioId == selectedScenarioId }
    val selectedScenario = scenarios.firstOrNull { it.id == selectedScenarioId }
    val selectedRole = scenarioRoles.firstOrNull { it.id == selectedRoleId }
    val activeAgents = data?.agents.orEmpty()
        .filter { it.id in selectedAgents }
    val quoteTarget = quoted?.takeIf { it.role == "assistant" }?.let { message ->
        data?.agents?.firstOrNull { it.id == message.authorId }
    }
    val canSend = !sending && input.isNotBlank() &&
        (activeAgents.isNotEmpty() || quoteTarget != null)

    fun sendCurrent() {
        val text = input.trim()
        val targets = activeAgents.toMutableList()
        quoteTarget?.let { target ->
            if (targets.none { it.id == target.id }) targets += target
        }
        if (text.isEmpty()) return
        // 不强制 @：没点名时由群内注意力仲裁选发言人
        val current = session ?: return
        val currentData = snapshot ?: return
        val mentions = targets.joinToString(" ") { "@${it.nickname}" }
        val content = "$mentions $text"
        val quoteSnapshot = quoted
        input = ""
        selectedAgents = emptySet()
        quoted = null
        mentionMenu = false
        scope.launch {
            try {
                error = null
                // 1) 用户消息先落库 2) 立刻刷新上屏 3) 再发起网络请求
                runCatching {
                    com.eloykim.lyra.AppGraph.history.groupChat.addMessage(
                        sessionId = currentData.session.id,
                        role = "user",
                        authorId = null,
                        authorName = "你",
                        content = content,
                        mentions = "",
                        quotedId = quoteSnapshot?.id,
                        quotedAuthor = quoteSnapshot?.authorName,
                        quotedContent = quoteSnapshot?.content,
                    )
                }
                reload()
                // 空群：启动招募流程，拉起全部部长
                var roster = currentData.agents
                if (roster.isEmpty()) {
                    val defaultRole = currentData.roles.firstOrNull()
                    if (defaultRole != null) {
                        val ministers = listOf(
                            "产品部长" to "PRODUCT_MINISTER",
                            "工程部长" to "ENGINEERING_MINISTER",
                            "运营部长" to "OPERATIONS_MINISTER",
                            "职能部长" to "STAFF_MINISTER",
                            "法务部长" to "LEGAL_MINISTER",
                            "人力部长" to "HR_MINISTER",
                            "财务部长" to "FINANCE_MINISTER",
                            "资源部长" to "RESOURCE_MINISTER",
                        )
                        runCatching {
                            ministers.forEach { (title, key) ->
                                com.eloykim.lyra.AppGraph.history.groupChat.addAgent(
                                    sessionId = currentData.session.id,
                                    roleId = defaultRole.id,
                                    nickname = title,
                                    identity = "",
                                    positionKey = key,
                                )
                            }
                            com.eloykim.lyra.AppGraph.history.ledger.append(
                                "group",
                                "group.recruitment",
                                org.json.JSONObject()
                                    .put("sessionId", currentData.session.id)
                                    .put("count", ministers.size)
                                    .toString(),
                            )
                        }
                        roster = com.eloykim.lyra.AppGraph.history.groupChat
                            .agents(currentData.session.id)
                        reload()
                    }
                }
                val count = GroupChatController.send(
                    session = current,
                    text = content,
                    agents = roster,
                    roles = currentData.roles,
                    quote = quoteSnapshot,
                    userMessageStored = true,
                    onTyping = { who ->
                        typingAgentId = who
                        if (who != null) {
                            streamingText = ""
                            streamingReason = ""
                        }
                    },
                    onDelta = { who, piece, think ->
                        typingAgentId = who
                        streamingText += piece
                        if (think != null) streamingReason += think
                    },
                    onReply = { _, _, _ ->
                        // 每有一位说完就刷新一次，逐条上屏
                        scope.launch {
                            streamingText = ""
                            streamingReason = ""
                            reload()
                        }
                    },
                )
                reload()
            } catch (t: Throwable) {
                error = t.message ?: "发送失败"
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Column(
                            modifier = Modifier
                                .clickable { sessionMenu = true }
                                .padding(vertical = 2.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(data?.session?.title ?: "群聊")
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "▾",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = data?.let {
                                    "${it.agents.size} 个 Agent · 被 @ 才回复"
                                } ?: "加载中…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                        }
                        DropdownMenu(
                            expanded = sessionMenu,
                            onDismissRequest = { sessionMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("新建群聊") },
                                onClick = {
                                    sessionMenu = false
                                    newSession()
                                },
                            )
                            if (sessions.isNotEmpty()) {
                                HorizontalDivider()
                                sessions.forEach { group ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(group.title)
                                                Text(
                                                    text = "${group.tokensUsed}/${group.tokenBudget} tokens",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        trailingIcon = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (group.id == data?.session?.id) {
                                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                                                }
                                                IconButton(
                                                    onClick = {
                                                        sessionMenu = false
                                                        deleteSessionTarget = group
                                                    },
                                                ) {
                                                    Icon(
                                                        painterResource(R.drawable.ic_delete),
                                                        contentDescription = "删除群聊",
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            sessionMenu = false
                                            openSession(group.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回")
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (data == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }

            AgentStrip(
                agents = data.agents,
                onLongPress = { name ->
                    // 长按职位：@ 对应的人（没有对应成员时退化为 @职位名）
                    val nick = data.agents.firstOrNull { it.nickname.contains(name) }?.nickname ?: name
                    input = (if (input.isBlank()) "" else input.trimEnd() + " ") + "@$nick "
                },
            )
            TaskStrip(
                tasks = groupTasks,
                onAdvance = { task ->
                    scope.launch {
                        val next = when (task.state) {
                            "proposed", "claimed" -> "doing"
                            "doing" -> "done"
                            "blocked" -> "doing"
                            else -> "done"
                        }
                        com.eloykim.lyra.AppGraph.history.groupChat.moveTask(task.id, next)
                        refreshTasks()
                    }
                },
            )
            HorizontalDivider()

            GroupMessageList(
                messages = data.messages.map { record ->
                    val agent = data.agents.firstOrNull { it.id == record.authorId }
                    val title = agent?.positionKey?.let {
                        com.eloykim.lyra.attention.Positions.byKey(it)?.title
                    } ?: data.roles.firstOrNull { it.id == agent?.roleId }?.name
                    record.toChatMessage(authorOverride = title)
                }.let { list ->
                    // 用户消息先上屏，不等模型
                    val withUser = if (pendingUserText.isBlank()) {
                        list
                    } else {
                        list + ChatMessage(
                            id = -2L,
                            role = ChatRole.User,
                            content = pendingUserText,
                            pending = false,
                        )
                    }
                    val typing = typingAgentId?.let { id -> data.agents.firstOrNull { it.id == id } }
                    if (typing == null) {
                        withUser
                    } else {
                        val name = typing.positionKey?.let {
                            com.eloykim.lyra.attention.Positions.byKey(it)?.title
                        } ?: typing.nickname
                        withUser + ChatMessage(
                            id = -1L,
                            role = ChatRole.Assistant,
                            content = if (streamingText.isBlank()) "正在思考…" else streamingText,
                            reasoning = streamingReason.takeIf { it.isNotBlank() },
                            pending = true,
                            authorName = name,
                        )
                    }
                },
                style = chatStyle,
                onReaction = { _, _ -> },
                onQuote = { id -> quoted = data.messages.firstOrNull { it.id == id } },
                onRegenerate = { },
                onMentionClick = { name ->
                    val agent = data.agents.firstOrNull { it.nickname == name }
                    if (agent != null) {
                        selectedAgents = selectedAgents + agent.id
                        error = "已选择 @${agent.nickname}"
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            quoted?.let { message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "引用 ${message.authorName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = message.content.replace('\n', ' ').take(120),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                    }
                    IconButton(onClick = { quoted = null }) {
                        Text("×")
                    }
                }
            }

            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (activeAgents.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = activeAgents.joinToString("、") { it.nickname },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入消息，回车发送") },
                    maxLines = 4,
                    enabled = !sending,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendCurrent() }),
                )
            }
        }
    }

    if (showRoleDialog) {
        RoleDialog(
            scenarioId = selectedScenarioId,
            onDismiss = { showRoleDialog = false },
            onCreated = {
                showRoleDialog = false
                scope.launch { reload() }
            },
        )
    }

    if (showAdd) {
        AgentEditorDialog(
            agent = null,
            roles = scenarioRoles,
            onDismiss = { showAdd = false },
            onNeedRole = {
                showAdd = false
                showRoleDialog = true
            },
            onSave = { nickname, identity, roleId ->
                scope.launch {
                    data?.session?.let { record ->
                        AppGraph.history.groupChat.addAgent(
                            sessionId = record.id,
                            roleId = roleId,
                            nickname = nickname,
                            identity = identity,
                        )
                    }
                    showAdd = false
                    reload()
                }
            },
        )
    }

    editAgent?.let { agent ->
        AgentEditorDialog(
            agent = agent,
            roles = scenarioRoles,
            onDismiss = { editAgent = null },
            onNeedRole = {
                editAgent = null
                showRoleDialog = true
            },
            onSave = { nickname, identity, roleId ->
                scope.launch {
                    AppGraph.history.groupChat.updateAgent(
                        agentId = agent.id,
                        roleId = roleId,
                        nickname = nickname,
                        identity = identity,
                    )
                    editAgent = null
                    reload()
                }
            },
            onRemove = {
                scope.launch {
                    AppGraph.history.groupChat.removeAgent(agent.id)
                    editAgent = null
                    reload()
                }
            },
        )
    }

    editingScenario?.let { scenario ->
        ScenarioEditDialog(
            scenario = scenario,
            onDismiss = { editingScenario = null },
            onSave = { name, description ->
                scope.launch {
                    AppGraph.history.groupChat.updateScenario(
                        scenario.id,
                        name,
                        description,
                    )
                    scenarios = AppGraph.history.groupChat.scenarios()
                    editingScenario = null
                    reload()
                }
            },
        )
    }

    editingRole?.let { role ->
        RoleEditDialog(
            role = role,
            onDismiss = { editingRole = null },
            onSave = { name, brand, model, thinking, duty ->
                scope.launch {
                    AppGraph.history.groupChat.updateRole(
                        id = role.id,
                        name = name,
                        brandId = brand,
                        model = model,
                        thinkingLevel = thinking,
                        duty = duty,
                    )
                    editingRole = null
                    reload()
                }
            },
        )
    }

    deleteSessionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteSessionTarget = null },
            title = { Text("删除群聊") },
            text = { Text("删除「${target.title}」及其成员、消息和角色选择？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val store = AppGraph.history.groupChat
                            store.deleteSession(target.id)
                            var remaining = store.sessions()
                            if (remaining.isEmpty()) {
                                store.ensureDefaultSession()
                                remaining = store.sessions()
                            }
                            sessions = remaining
                            val next = remaining.first()
                            session = GroupChatSession(next.id)
                            selectedAgents = emptySet()
                            quoted = null
                            deleteSessionTarget = null
                            snapshot = session?.snapshot()
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSessionTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AgentStrip(
    agents: List<GroupAgentRecord>,
    onLongPress: (String) -> Unit = {},
) {
    // 三层，各自独立横向滚动
    val tiers = remember {
        com.eloykim.lyra.attention.Positions.hierarchy().map { row -> row.map { it.title } }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tiers.forEach { row ->
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { label ->
                    val emoji = com.eloykim.lyra.attention.Positions.emojiOf(label)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "$emoji $label",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable

private fun MentionPicker(
    agents: List<GroupAgentRecord>,
    onPick: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(agents, key = { it.id }) { agent ->
            AssistChip(
                onClick = { onPick(agent.nickname) },
                label = { Text("@${agent.nickname}") },
            )
        }
    }
}

@Composable
private fun GroupMessageBubble(message: GroupMessageRecord) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(avatarColor(message.authorName)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = message.authorName.take(1).uppercase(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 290.dp),
        ) {
            if (!isUser) {
                Text(
                    text = message.authorName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isUser) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = highlightMentions(message.content),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun GroupMessageRecord.toChatMessage(authorOverride: String? = null): ChatMessage = ChatMessage(
    id = id,
    role = if (role == "user") ChatRole.User else ChatRole.Assistant,
    content = content,
    createdAt = createdAt,
    authorName = (authorOverride ?: authorName).takeIf { role != "user" },
    quote = quotedContent?.let { content ->
        MessageQuote(
            messageId = quotedId ?: 0L,
            role = if (quotedAuthor == "你") ChatRole.User else ChatRole.Assistant,
            content = content,
            authorName = quotedAuthor,
        )
    },
)

private fun highlightMentions(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    Regex("@[\\p{L}\\p{N}_-]+").findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        withStyle(
            SpanStyle(
                color = Color(0xFF5B6CFF),
                fontWeight = FontWeight.SemiBold,
            ),
        ) {
            append(match.value)
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

private fun avatarColor(name: String): Color {
    val palette = listOf(
        Color(0xFF5B6CFF),
        Color(0xFF3A9D7C),
        Color(0xFFB05A9D),
        Color(0xFFC77B3B),
        Color(0xFF4B7EA8),
    )
    return palette[(name.hashCode() and Int.MAX_VALUE) % palette.size]
}

@Composable
private fun AgentEditorDialog(
    agent: GroupAgentRecord?,
    roles: List<GroupRoleRecord>,
    onDismiss: () -> Unit,
    onNeedRole: () -> Unit,
    onSave: (String, String, Long?) -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    var nickname by remember(agent) { mutableStateOf(agent?.nickname.orEmpty()) }
    var roleId by remember(agent) { mutableStateOf(agent?.roleId) }
    var roleMenu by remember { mutableStateOf(false) }
    val selected = roles.firstOrNull { it.id == roleId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (agent == null) "添加 Agent" else "设置 Agent") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("昵称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box {
                    OutlinedButton(
                        onClick = { roleMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            selected?.let { "${it.name} · ${it.model}" } ?: "选择全局角色",
                            modifier = Modifier.weight(1f),
                        )
                        Text("▾")
                    }
                    DropdownMenu(
                        expanded = roleMenu,
                        onDismissRequest = { roleMenu = false },
                    ) {
                        if (roles.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("还没有角色，先创建一个") },
                                onClick = onNeedRole,
                            )
                        }
                        roles.forEach { role ->
                            DropdownMenuItem(
                                text = { Text("${role.name} · ${role.brandId}/${role.model}") },
                                onClick = {
                                    roleId = role.id
                                    roleMenu = false
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = onNeedRole) {
                    Text("新建全局角色")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = nickname.isNotBlank(),
                onClick = {
                    if (roleId == null) {
                        onNeedRole()
                    } else {
                        onSave(nickname.trim(), agent?.identity.orEmpty(), roleId)
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                onRemove?.let {
                    TextButton(onClick = it) { Text("移除", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun ScenarioEditDialog(
    scenario: GroupScenarioRecord,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(scenario) { mutableStateOf(scenario.name) }
    var description by remember(scenario) { mutableStateOf(scenario.description) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("场景设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("场景名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("场景说明") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim(), description.trim()) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RoleEditDialog(
    role: GroupRoleRecord,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit,
) {
    var name by remember(role) { mutableStateOf(role.name) }
    var brand by remember(role) { mutableStateOf(role.brandId) }
    var model by remember(role) { mutableStateOf(role.model) }
    var thinking by remember(role) { mutableStateOf(role.thinkingLevel) }
    var duty by remember(role) { mutableStateOf(role.duty) }
    var thinkingMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("角色设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("角色名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = brand,
                    onValueChange = { brand = it },
                    label = { Text("品牌 id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型 id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box {
                    OutlinedButton(
                        onClick = { thinkingMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(thinking, modifier = Modifier.weight(1f))
                        Text("▾")
                    }
                    DropdownMenu(
                        expanded = thinkingMenu,
                        onDismissRequest = { thinkingMenu = false },
                    ) {
                        listOf("off", "minimum", "medium", "maximum").forEach { level ->
                            DropdownMenuItem(
                                text = { Text(level) },
                                onClick = {
                                    thinking = level
                                    thinkingMenu = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = duty,
                    onValueChange = { duty = it },
                    label = { Text("职责") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && brand.isNotBlank() && model.isNotBlank(),
                onClick = { onSave(name.trim(), brand.trim(), model.trim(), thinking, duty.trim()) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RoleDialog(
    scenarioId: Long?,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val settings = SettingsStore.state.value
    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf(settings.brandId) }
    var model by remember { mutableStateOf(settings.model) }
    var thinking by remember { mutableStateOf(settings.thinkingLevel.wireValue) }
    var thinkingMenu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建全局角色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("角色名，例如 产品经理") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = brand,
                    onValueChange = { brand = it },
                    label = { Text("品牌 id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型 id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "思考等级",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    OutlinedButton(
                        onClick = { thinkingMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(thinking, modifier = Modifier.weight(1f))
                        Text("▾")
                    }
                    DropdownMenu(
                        expanded = thinkingMenu,
                        onDismissRequest = { thinkingMenu = false },
                    ) {
                        listOf("off", "minimum", "medium", "maximum").forEach { level ->
                            DropdownMenuItem(
                                text = { Text(level) },
                                onClick = {
                                    thinking = level
                                    thinkingMenu = false
                                },
                            )
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && name.isNotBlank() && brand.isNotBlank() && model.isNotBlank(),
                onClick = {
                    saving = true
                    scope.launch {
                        runCatching {
                            AppGraph.history.groupChat.createRole(
                                scenarioId = scenarioId,
                                name = name.trim(),
                                brandId = brand.trim(),
                                model = model.trim(),
                                thinkingLevel = thinking.trim().ifBlank { "medium" },
                            )
                        }.onSuccess { onCreated() }
                            .onFailure { error = it.message ?: "创建失败" }
                        saving = false
                    }
                },
            ) { Text(if (saving) "创建中…" else "创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 任务板：只显示未完成的任务，点一下推进状态，末尾加按钮新建。 */
@Composable
private fun TaskStrip(
    tasks: List<com.eloykim.lyra.data.GroupTaskRecord>,
    onAdvance: (com.eloykim.lyra.data.GroupTaskRecord) -> Unit,
) {
    val open = tasks.filter { it.state != "done" && it.state != "dropped" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        open.forEach { task ->
            val color = when (task.state) {
                "doing" -> MaterialTheme.colorScheme.primary
                "blocked" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onAdvance(task) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "${stateLabel(task.state)} · ${task.title}",
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                )
            }
        }
    }
}

private fun stateLabel(state: String): String = when (state) {
    "proposed" -> "待认领"
    "claimed" -> "已认领"
    "doing" -> "进行中"
    "blocked" -> "阻塞"
    "done" -> "完成"
    "dropped" -> "取消"
    else -> state
}
