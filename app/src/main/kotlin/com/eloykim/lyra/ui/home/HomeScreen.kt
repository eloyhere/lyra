package com.eloykim.lyra.ui.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import com.eloykim.lyra.R
import androidx.compose.ui.res.stringResource
import com.eloykim.lyra.accessibility.AccessibilityState
import com.eloykim.lyra.data.RunSummary
import com.eloykim.lyra.designsystem.MicGlyph
import com.eloykim.lyra.voice.OfflineVoiceInput
import com.eloykim.lyra.voice.VoiceController
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import com.eloykim.lyra.network.LlmClients
import com.eloykim.lyra.settings.SettingsStore
import com.eloykim.lyra.designsystem.StatusPill
import com.eloykim.lyra.AppGraph
import com.eloykim.lyra.accessibility.AidlGateway
import com.eloykim.lyra.agent.ActionResult
import com.eloykim.lyra.agent.AgentEvent
import com.eloykim.lyra.agent.AgentSession
import com.eloykim.lyra.notify.LyraNotifications
import com.eloykim.lyra.notify.NoticeLevel
import com.eloykim.lyra.attention.BudgetScheduler
import com.eloykim.lyra.attention.digest
import com.eloykim.lyra.agent.toPayload
import com.eloykim.lyra.attention.BudgetVector
import com.eloykim.lyra.agent.ScriptStep
import com.eloykim.lyra.agent.AgentBackend
import com.eloykim.lyra.agent.LlmAgentBackend
import com.eloykim.lyra.agent.ScriptedBackend
import com.eloykim.lyra.agent.Verb
import com.eloykim.lyra.service.agent.CommandAppCatalog
import com.eloykim.lyra.service.agent.CommandVoiceService
import com.eloykim.lyra.service.ServiceText
import kotlinx.coroutines.launch

private data class Scenario(val labelRes: Int, val prompt: String)

private val scenarios = listOf(
    Scenario(R.string.home_quick_food, "帮我点一份麦当劳，送到公司"),
    Scenario(R.string.home_quick_ride, "帮我叫一辆车回家"),
    Scenario(R.string.home_quick_coffee, "点一杯冰美式，少冰"),
    Scenario(R.string.home_quick_parcel, "帮我查一下最近的快递到哪了"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenInspector: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenGroup: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenImpressions: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val connected by AccessibilityState.connected.collectAsStateWithLifecycle()
    val snapshot by AccessibilityState.snapshot.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var demoRunning by remember { mutableStateOf(false) }
    var ampFrame by remember { mutableStateOf<AgentEvent.Frame?>(null) }
    var ampBudget by remember { mutableStateOf<AgentEvent.Budget?>(null) }
    var demoLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var showAccessibilityPrompt by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val voice = remember {
        VoiceController(
            context = context,
            speechLanguage = com.eloykim.lyra.settings
                .findLanguage(SettingsStore.state.value.languageId).speechLanguage,
            offline = OfflineVoiceInput(context),
        )
    }
    val appCatalog = remember { CommandAppCatalog(AppGraph.commands) }
    val agentVoice = remember { CommandVoiceService(AppGraph.commands) }
    DisposableEffect(Unit) {
        onDispose {
            voice.release()
            agentVoice.release()
        }
    }

    // 「按住说话」气泡：有权限才弹，松手停止识别
    var showVoicePopover by remember { mutableStateOf(false) }
    var voiceListening by remember { mutableStateOf(false) }
    var voiceTranscribing by remember { mutableStateOf(false) }
    var showMicRationale by remember { mutableStateOf(false) }
    val voiceSession = remember { mutableStateOf(0) }

    val startVoice: () -> Unit = {
        val session = voiceSession.value + 1
        voiceSession.value = session
        voiceListening = true
        voiceTranscribing = false
        voice.listen(
            onResult = { heard ->
                if (voiceSession.value == session) {
                    voiceListening = false
                    voiceTranscribing = false
                    showVoicePopover = false
                    query = heard
                    scope.launch { snackbarHostState.showSnackbar("听到：$heard") }
                }
            },
            onError = { reason ->
                if (voiceSession.value == session) {
                    voiceListening = false
                    voiceTranscribing = false
                    showVoicePopover = false
                    scope.launch { snackbarHostState.showSnackbar(reason) }
                }
            },
        )
    }

    val stopVoice: () -> Unit = {
        voiceListening = false
        voiceTranscribing = true
        voice.stopListening()
    }

    val dismissVoicePopover: () -> Unit = {
        showVoicePopover = false
        voiceListening = false
        voiceTranscribing = false
        voiceSession.value += 1
        voice.cancelListening()
    }
    val noticePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝也不阻塞主流程，通知静默失败 */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            noticePermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showVoicePopover = true
        } else {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.mic_permission_denied),
                    actionLabel = context.getString(R.string.action_open_settings),
                )
                if (result == SnackbarResult.ActionPerformed) openAppSettings(context)
            }
        }
    }

    // 麦克风按钮：有权限才弹「按住说话」气泡，没权限先走系统申请
    val requestVoice: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val activity = context as? Activity
        when {
            granted -> showVoicePopover = true
            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO,
            ) -> showMicRationale = true
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val history = AppGraph.history
    var recentRuns by remember { mutableStateOf<List<RunSummary>>(emptyList()) }
    LaunchedEffect(Unit) {
        recentRuns = runCatching { history.recentRuns(3) }.getOrDefault(emptyList())
    }

    val eventLabels = EventLabels(
        started = stringResource(R.string.log_started),
        observed = stringResource(R.string.log_observed),
        decided = stringResource(R.string.log_decided),
        acted = stringResource(R.string.log_acted),
        noted = stringResource(R.string.log_noted),
        needsUser = stringResource(R.string.log_needs_user),
        finished = stringResource(R.string.log_finished),
        failed = stringResource(R.string.log_failed),
    )

    fun startAgent(task: String, demo: Boolean = false) {
        if (demoRunning) return
        if (!demo && !AccessibilityState.connected.value) {
            scope.launch { snackbarHostState.showSnackbar("无障碍服务未连接，不能执行任务") }
            return
        }
        demoRunning = true
        demoLog = emptyList()
        ampFrame = null
        ampBudget = null
        submitted = task
        scope.launch {
            try {
                val settings = SettingsStore.state.value
                val key = SettingsStore.apiKey()
                val catalog = runCatching { AppGraph.history.catalog() }.getOrDefault(emptyList())
                val provider = catalog.firstOrNull { it.provider.id == settings.brandId }?.provider
                val baseUrl = if (settings.brandId == "local") {
                    settings.localBaseUrl.ifBlank { null }
                } else {
                    provider?.baseUrl?.ifBlank { null }
                }
                val modelRow = catalog.firstOrNull { it.provider.id == settings.brandId }
                    ?.models?.firstOrNull { it.id == settings.model }
                val keyOptional = settings.brandId == "local" || settings.brandId == "openrouter"
                val canUseLlm = provider != null && baseUrl != null &&
                    (keyOptional || !key.isNullOrBlank())

                if (!demo && !canUseLlm) {
                    val reason = "没有可用的模型服务：请检查品牌、Base URL 和 API Key"
                    demoLog = listOf(reason)
                    submitted = "启动失败"
                    LyraNotifications.post(NoticeLevel.OPPORTUNITY, reason, id = 102)
                    snackbarHostState.showSnackbar(reason)
                    return@launch
                }

                val backend: AgentBackend = if (demo) {
                    ScriptedBackend(demoSteps)
                } else {
                    val client = LlmClients.create(
                        providerId = provider!!.id,
                        baseUrl = baseUrl!!,
                        apiKey = key.orEmpty(),
                        model = settings.model,
                        thinking = settings.thinkingLevel,
                    )
                    LlmAgentBackend(
                        client = client,
                        handles = AppGraph.handles,
                        translator = com.eloykim.lyra.voice.TranslationService(client),
                        targetLanguage = com.eloykim.lyra.settings
                            .findLanguage(settings.languageId).english,
                        inputPrice = modelRow?.inputPrice,
                        outputPrice = modelRow?.outputPrice,
                    )
                }
                val session = AgentSession(
                    AidlGateway(),
                    backend,
                    recorder = history,
                    handles = AppGraph.handles,
                    apps = appCatalog,
                    voice = agentVoice,
                    searchChatHistory = { query, limit ->
                        val result = AppGraph.commands.run(
                            "chat_message.search",
                            org.json.JSONObject()
                                .put("query", query)
                                .put("limit", limit),
                        )
                        ServiceText.format(result)
                    },
                    reactToMessage = { messageId, reaction ->
                        val command = when (reaction) {
                            "like" -> "chat_message.like"
                            "dislike" -> "chat_message.dislike"
                            else -> "chat_message.comment"
                        }
                        val args = org.json.JSONObject().put("messageId", messageId)
                        if (command == "chat_message.comment") args.put("emoji", reaction)
                        val result = AppGraph.commands.run(command, args)
                        if (result.ok) null else result.message.ifBlank { "操作失败" }
                    },
                    quoteMessage = { messageId ->
                        val result = AppGraph.commands.run(
                            "chat_message.quote",
                            org.json.JSONObject().put("messageId", messageId),
                        )
                        if (result.ok) {
                            result.data.optString("markdown").ifBlank {
                                ServiceText.format(result)
                            }
                        } else {
                            null
                        }
                    },
                    budget = BudgetScheduler(
                        BudgetVector(
                            tokens = com.eloykim.lyra.DebugLaunch.budgetTokens,
                            ms = com.eloykim.lyra.DebugLaunch.budgetMs,
                            calls = 40,
                            interrupts = com.eloykim.lyra.DebugLaunch.budgetInterrupts,
                            moneyMicros = com.eloykim.lyra.DebugLaunch.budgetMoneyMicros,
                        ),
                    ),
                    frames = object : com.eloykim.lyra.agent.FrameRecorder {
                        override suspend fun record(
                            runId: Long,
                            frame: com.eloykim.lyra.attention.FrameDraft,
                        ) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    history.frames.append(
                                        runId = runId,
                                        cycle = frame.cycle,
                                        mode = frame.mode,
                                        goal = frame.goal,
                                        digest = frame.digest(),
                                        payload = frame.toPayload(),
                                    )
                                }
                            }
                        }

                        override suspend fun markImpasse(runId: Long, state: String) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { history.frames.markImpasse(runId, state) }
                            }
                        }
                    },
                    events = com.eloykim.lyra.agent.EventSink { type, payload, refs ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { history.ledger.append("agent", type, payload, refs) }
                        }
                    },
                )
                LyraNotifications.post(NoticeLevel.SILENT, "任务已开始：$task", id = 100)
                session.run(task).collect { event ->
                    demoLog = (demoLog + describeEvent(event, eventLabels)).takeLast(8)
                    when (event) {
                        is AgentEvent.Frame -> ampFrame = event
                        is AgentEvent.Budget -> ampBudget = event
                        is AgentEvent.NeedsUser -> {
                            submitted = "等待确认 · ${event.message}"
                            LyraNotifications.post(NoticeLevel.COMMITMENT, event.message, id = 101)
                        }

                        is AgentEvent.Failed -> {
                            submitted = "任务失败 · ${event.reason}"
                            LyraNotifications.post(NoticeLevel.OPPORTUNITY, event.reason, id = 102)
                        }

                        is AgentEvent.Finished -> {
                            submitted = "任务完成 · ${event.steps} 步"
                            voice.speak("任务完成，共 ${event.steps} 步")
                            LyraNotifications.post(
                                NoticeLevel.OPPORTUNITY,
                                event.summary,
                                title = "任务完成 · ${event.steps} 步",
                                id = 103,
                            )
                        }

                        else -> Unit
                    }
                    if (event is AgentEvent.Finished || event is AgentEvent.Failed) {
                        recentRuns = runCatching { history.recentRuns(3) }.getOrDefault(emptyList())
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                val reason = t.message ?: t::class.java.simpleName
                demoLog = (demoLog + "执行失败：$reason").takeLast(8)
                submitted = "任务失败 · $reason"
                LyraNotifications.post(NoticeLevel.OPPORTUNITY, reason, id = 102)
                snackbarHostState.showSnackbar("任务失败：$reason")
            } finally {
                demoRunning = false
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastDebugTask by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // 调试入口：adb 传 task 直接开跑，绕过输入法与坐标。
        // 不清空 StateFlow（否则多实例时可见的那个会收不到），改用「任务去重 + 仅前台消费」。
        com.eloykim.lyra.DebugLaunch.task.collect { task ->
            val resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            android.util.Log.d("DebugLaunch", "collect task=$task resumed=$resumed last=$lastDebugTask demo=$demoRunning")
            if (task != null && task != lastDebugTask && resumed && !demoRunning) {
                lastDebugTask = task
                query = task
                val delayMs = com.eloykim.lyra.DebugLaunch.startDelayMs
                if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
                startAgent(task)
            }
        }
    }

    val background = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceContainerLow,
            MaterialTheme.colorScheme.surface,
        ),
    )

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // 抽屉是模态的：打开期间主页面整棵子树不可聚焦（见下方 Scaffold 的 focusProperties）。
    // 打开瞬间再主动清一次焦点，让光标和软键盘立刻退场，而不是等下一帧的属性失效。
    val focusManager = LocalFocusManager.current
    val drawerOpen = drawerState.targetValue == DrawerValue.Open
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) focusManager.clearFocus(force = true)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(28.dp))
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text("Lyra", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "0.1.0 · 自用构建",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(12.dp))
                NavigationDrawerItem(
                    label = { Text("首页") },
                    selected = true,
                    onClick = { scope.launch { drawerState.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("聊天") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenChat()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("群聊") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenGroup()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("终端") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenTerminal()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("印象") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenImpressions()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("用度") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenUsage()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("屏幕检查器") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenInspector()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("设置") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        },
    ) {
        Scaffold(
            modifier = Modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                // 抽屉打开时主页面整体不可聚焦：焦点只能在抽屉里走，不会被送到被遮住的内容上。
                .focusProperties { canFocus = !drawerOpen },
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                LargeTopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "LYRA",
                                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 5.sp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.home_headline),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_menu),
                                contentDescription = "菜单",
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = "设置",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background)
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        active = connected,
                        text = if (connected) stringResource(R.string.home_connected) else stringResource(R.string.home_disconnected),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(16.dp))

            PromptCard(
                query = query,
                onQueryChange = { query = it },
                onMicClick = requestVoice,
                showVoicePopover = showVoicePopover,
                voiceListening = voiceListening,
                voiceTranscribing = voiceTranscribing,
                onVoiceDismiss = dismissVoicePopover,
                onVoicePressStart = startVoice,
                onVoicePressStop = stopVoice,
                onSubmit = {
                    when {
                        query.isBlank() ->
                            scope.launch { snackbarHostState.showSnackbar("先说一句要做什么") }

                        // 开始前先确认无障碍服务，否则 Agent 无法读屏 / 点击
                        !connected -> showAccessibilityPrompt = true

                        else -> {
                            submitted = query
                            startAgent(query)
                        }
                    }
                },
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                scenarios.forEach { scenario ->
                    SuggestionChip(
                        onClick = { query = scenario.prompt },
                        label = { Text(stringResource(scenario.labelRes)) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            SectionHeader(stringResource(R.string.home_skills))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SkillCard("麦", "点麦当劳", "上次 · 1 分 42 秒", Modifier.weight(1f))
                SkillCard("咖", "周五咖啡", "每周五 18:00", Modifier.weight(1f))
            }

            Spacer(Modifier.height(24.dp))

            SectionHeader(stringResource(R.string.home_permissions))
            Spacer(Modifier.height(10.dp))
            AccessibilityCard(
                connected = connected,
                packageName = snapshot?.packageName,
                nodeCount = snapshot?.nodes?.size ?: 0,
                onClick = { openAccessibilitySettings(context) },
            )

            Spacer(Modifier.height(24.dp))

            SectionHeader(stringResource(R.string.home_recent))
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    if (recentRuns.isEmpty()) {
                        Text(
                            text = stringResource(R.string.home_empty_recent),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.home_empty_recent_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        recentRuns.forEach { run ->
                            Text(
                                text = "${statusLabel(run.status)} · ${run.task}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        if (submitted != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "已存入本地历史（SQLite）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader(stringResource(R.string.home_demo))
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        text = stringResource(R.string.home_demo_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            startAgent(
                                query.ifBlank { "打开计算器，按下 7 和 8，然后返回" },
                                demo = true,
                            )
                        },
                        enabled = !demoRunning,
                    ) {
                        Text(if (demoRunning) stringResource(R.string.home_running) else stringResource(R.string.home_run_demo))
                    }
                    if (demoLog.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        demoLog.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                    ampFrame?.let { frame ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "AMP · 焦点 ${frame.slots} / 忽略 ${frame.ignored} · " +
                                "${frame.spentTokens}/${frame.allocatedTokens} tokens",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    ampBudget?.let { budget ->
                        Text(
                            text = "AMP 预算剩余：${budget.remainingTokens} tokens · " +
                                "${budget.remainingCalls} 次调用 · ${budget.remainingMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            TextButton(onClick = onOpenInspector) {
                Text("打开屏幕检查器（调试）")
            }

            Spacer(Modifier.height(28.dp))
        }
        }
    }

    if (showAccessibilityPrompt) {
        AlertDialog(
            onDismissRequest = { showAccessibilityPrompt = false },
            title = { Text(stringResource(R.string.accessibility_prompt_title)) },
            text = { Text(stringResource(R.string.accessibility_prompt_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAccessibilityPrompt = false
                        openAccessibilitySettings(context)
                    },
                ) {
                    Text(stringResource(R.string.action_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityPrompt = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showMicRationale) {
        AlertDialog(
            onDismissRequest = { showMicRationale = false },
            title = { Text(stringResource(R.string.mic_permission_title)) },
            text = { Text(stringResource(R.string.mic_permission_rationale)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMicRationale = false
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                ) {
                    Text(stringResource(R.string.action_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMicRationale = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun PromptCard(
    query: String,
    onQueryChange: (String) -> Unit,
    onMicClick: () -> Unit,
    showVoicePopover: Boolean,
    voiceListening: Boolean,
    voiceTranscribing: Boolean,
    onVoiceDismiss: () -> Unit,
    onVoicePressStart: () -> Unit,
    onVoicePressStop: () -> Unit,
    onSubmit: () -> Unit,
) {
    val micLabel = stringResource(R.string.home_mic)
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(22.dp)) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onPrimaryContainer),
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isBlank()) {
                            Text(
                                text = stringResource(R.string.home_placeholder) + "\n" + stringResource(R.string.home_placeholder_example),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f),
                            )
                        }
                        innerTextField()
                    }
                },
            )

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onSubmit,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.home_start))
                }
                Spacer(Modifier.weight(1f))
                Box {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .semantics { contentDescription = micLabel }
                            .clickable(onClick = onMicClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        MicGlyph(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }

                    // 有麦克风权限后，点击麦克风弹「按住说话」气泡
                    if (showVoicePopover) {
                        Popup(
                            popupPositionProvider = MicPopoverPositionProvider,
                            onDismissRequest = onVoiceDismiss,
                            properties = PopupProperties(focusable = true),
                        ) {
                            VoicePopover(
                                listening = voiceListening,
                                transcribing = voiceTranscribing,
                                onPressStart = onVoicePressStart,
                                onPressStop = onVoicePressStop,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「按住说话」气泡：按下去开始识别，松手结束。
 * 对位按钮用 pointerInput 自己处理按下 / 抬起，避免长按语义触发系统菜单。
 */
@Composable
private fun VoicePopover(
    listening: Boolean,
    transcribing: Boolean,
    onPressStart: () -> Unit,
    onPressStop: () -> Unit,
) {
    val pressStart by rememberUpdatedState(onPressStart)
    val pressStop by rememberUpdatedState(onPressStop)
    val haptic = LocalHapticFeedback.current
    val holdLabel = stringResource(R.string.voice_hold_to_talk)

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier
                .width(232.dp)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = when {
                    transcribing -> stringResource(R.string.voice_transcribing)
                    listening -> stringResource(R.string.voice_listening)
                    else -> holdLabel
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(
                        if (listening) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiaryContainer
                        },
                    )
                    .semantics { contentDescription = holdLabel }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                pressStart()
                                tryAwaitRelease()
                                pressStop()
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                MicGlyph(
                    modifier = Modifier.size(32.dp),
                    color = if (listening) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.voice_release_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 气泡贴在麦克风上方；上方放不下就翻到下方。 */
private object MicPopoverPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = 12
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(margin, maxX)
        val above = anchorBounds.top - popupContentSize.height - margin
        val y = if (above >= margin) above else anchorBounds.bottom + margin
        return IntOffset(x, y)
    }
}

@Composable
private fun SkillCard(
    initial: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccessibilityCard(
    connected: Boolean,
    packageName: String?,
    nodeCount: Int,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (connected) stringResource(R.string.home_connected) else stringResource(R.string.home_perm_off),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (connected) {
                        val pkg = packageName ?: "等待界面事件"
                        "$pkg · 已抓取 $nodeCount 个节点"
                    } else {
                        stringResource(R.string.home_perm_off_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    // 未开启时这行就是「点这里」的行动指引，用主色强调；已连接时只是状态明细。
                    color = if (connected) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (connected) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.home_ready),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

internal fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** 权限被永久拒绝后，只能去应用详情页手动打开。 */
internal fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
private val demoSteps: List<ScriptStep> = listOf(
    { Verb.Act("launch", packageName = "com.coloros.calculator", why = "打开计算器") },
    { obs ->
        obs.findByText("7")?.let { Verb.Act("tap", nodeId = it.id, why = "按 7") }
            ?: Verb.Act("scroll", text = "down", why = "找不到就滚动")
    },
    { obs ->
        obs.findByText("8")?.let { Verb.Act("tap", nodeId = it.id, why = "按 8") }
            ?: Verb.Act("scroll", text = "up", why = "找不到就滚动")
    },
    { Verb.Act("back", why = "返回") },
    { Verb.Done },
)

private data class EventLabels(
    val started: String,
    val observed: String,
    val decided: String,
    val acted: String,
    val noted: String,
    val needsUser: String,
    val finished: String,
    val failed: String,
)

private fun describeEvent(event: AgentEvent, l: EventLabels): String = when (event) {
    is AgentEvent.Started -> "${l.started} · ${event.task}"
    is AgentEvent.Frame -> "AMP 帧 · 焦点 ${event.slots} / 忽略 ${event.ignored}"
    is AgentEvent.Budget -> "AMP 预算 · ${event.remainingTokens} tokens 剩余"
    is AgentEvent.Observed -> "${l.observed} · ${event.observation.packageName} · ${event.observation.elements.size}"
    is AgentEvent.Decided -> "${l.decided} · ${describeVerb(event.verb)}"
    is AgentEvent.Acted -> "${l.acted} · ${describeVerb(event.verb)} → ${describeResult(event.result)}"
    is AgentEvent.Noted -> "${l.noted} · ${event.text}"
    is AgentEvent.NeedsUser -> "${l.needsUser} · ${event.message}"
    is AgentEvent.Finished -> "${l.finished} · ${event.steps} · ${event.summary}"
    is AgentEvent.Failed -> "${l.failed} · ${event.reason}"
}

private fun describeVerb(verb: Verb): String = when (verb) {
    is Verb.Act -> buildString {
        append(verb.action)
        verb.nodeId?.let { append(" #").append(it) }
        verb.packageName?.let { append(' ').append(it) }
    }

    is Verb.Attend -> "attend"
    is Verb.Ask -> "ask"
    is Verb.Note -> "note"
    is Verb.Plan -> "plan"
    is Verb.SearchMessages -> "search_messages " + verb.query
    is Verb.ReactMessage -> "react_message #${verb.messageId} ${verb.reaction}"
    is Verb.QuoteMessage -> "quote_message #${verb.messageId}"
    is Verb.Application -> "application " + verb.action + verb.query?.let { " " + it }.orEmpty()
    is Verb.Voice -> "voice " + verb.action
    is Verb.Read -> "read " + verb.handle
    Verb.Reflect -> "reflect"
    Verb.Done -> "done"
}

private fun describeResult(result: ActionResult): String = when (result) {
    is ActionResult.Ok -> "ok"
    is ActionResult.Blocked -> "blocked(${result.code})"
    is ActionResult.NeedsConsent -> "需要授权"
    is ActionResult.Failed -> "failed(${result.code})"
}
private fun statusLabel(status: String): String = when (status) {
    "succeeded" -> "✅ 完成"
    "blocked" -> "⛔ 被拦截"
    "needs_consent", "needs_user" -> "✋ 待确认"
    "failed" -> "❌ 失败"
    else -> "… 进行中"
}