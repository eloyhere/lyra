package com.eloykim.lyra.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eloykim.lyra.AppGraph
import com.eloykim.lyra.R
import com.eloykim.lyra.accessibility.AccessibilityState
import com.eloykim.lyra.accessibility.AutomationClient
import com.eloykim.lyra.data.ModelRow
import com.eloykim.lyra.data.ProviderRow
import com.eloykim.lyra.data.ProviderWithModels
import com.eloykim.lyra.designsystem.LyraColorTheme
import com.eloykim.lyra.designsystem.systemDynamicColorScheme
import com.eloykim.lyra.network.LlmClients
import com.eloykim.lyra.vision.OcrEngine
import androidx.compose.runtime.DisposableEffect
import com.eloykim.lyra.network.LlmHttpException
import com.eloykim.lyra.network.LlmMessage
import com.eloykim.lyra.settings.AppLanguages
import com.eloykim.lyra.voice.TranslationService
import com.eloykim.lyra.settings.SettingsStore
import com.eloykim.lyra.settings.findLanguage
import com.eloykim.lyra.voice.OfflineAsrModel
import android.app.Activity
import com.eloykim.lyra.settings.ThemeMode
import com.eloykim.lyra.ui.home.openAccessibilitySettings
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val connected by AccessibilityState.connected.collectAsStateWithLifecycle()
    val automationReady by AutomationClient.connected.collectAsStateWithLifecycle()
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    // 用当前主题的表面亮度判断深浅，保证配色预览的明度跟着界面走
    val darkUi = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val dynamicScheme = remember(context, darkUi) { systemDynamicColorScheme(context, darkUi) }
    val dynamicSelected = settings.dynamicColor && dynamicScheme != null
    val offlineAsr by OfflineAsrModel.status.collectAsStateWithLifecycle()
    var showOfflineAsrDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val historyTemplate = stringResource(R.string.settings_history)
    val modelsAddedTemplate = stringResource(R.string.settings_models_added)
    val ledgerOkTemplate = stringResource(R.string.settings_ledger_ok)
    val ledgerBrokenTemplate = stringResource(R.string.settings_ledger_broken)
    val keySavedTemplate = stringResource(R.string.settings_key_saved)
    val ocr = remember { OcrEngine() }
    DisposableEffect(Unit) { onDispose { ocr.close() } }

    var catalog by remember { mutableStateOf<List<ProviderWithModels>>(emptyList()) }
    var refreshStatus by remember { mutableStateOf("切换品牌时自动拉取模型列表") }
    var testStatus by remember { mutableStateOf("用当前配置发一条测试消息") }
    var shotResult by remember { mutableStateOf<String?>(null) }
    var historyText by remember { mutableStateOf("统计中…") }
    var apiKeyInput by remember { mutableStateOf("") }
    var revealKey by remember { mutableStateOf(false) }
    var cloudUsageUrlInput by remember(settings.cloudUsageUrl) {
        mutableStateOf(settings.cloudUsageUrl)
    }
    var cloudUsageKeyInput by remember { mutableStateOf("") }
    var localUrlInput by remember(settings.localBaseUrl) { mutableStateOf(settings.localBaseUrl) }
    var ocrStatus by remember { mutableStateOf("截图后离线识别中英文") }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showColorThemeDialog by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var showLedger by remember { mutableStateOf(false) }
    var showGroupRole by remember { mutableStateOf(false) }
    var groupSessionId by remember { mutableStateOf(0L) }
    var groupScenarioId by remember { mutableStateOf<Long?>(null) }
    var groupRoleId by remember { mutableStateOf<Long?>(null) }
    var groupScenarios by remember { mutableStateOf<List<com.eloykim.lyra.data.GroupScenarioRecord>>(emptyList()) }
    var groupRoles by remember { mutableStateOf<List<com.eloykim.lyra.data.GroupRoleRecord>>(emptyList()) }
    var ledgerLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var ledgerStatus by remember { mutableStateOf("") }
    var metricsText by remember { mutableStateOf("") }
    var compactionText by remember { mutableStateOf("") }
    var translateInput by remember { mutableStateOf("") }
    var translateOutput by remember { mutableStateOf("") }
    var translating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        catalog = runCatching { AppGraph.history.catalog() }.getOrDefault(emptyList())
        if (catalog.none { it.provider.id == settings.brandId }) {
            catalog.firstOrNull()?.let { first ->
                SettingsStore.setBrandAndModel(first.provider.id, first.models.firstOrNull()?.id ?: "")
            }
        }
        val initialModels = catalog.firstOrNull { it.provider.id == settings.brandId }?.models.orEmpty()
        if (initialModels.isNotEmpty() && initialModels.none { it.id == settings.model }) {
            SettingsStore.setModel(initialModels.first().id)
        }
        // 先出本地统计，不等网络（避免 9 个服务商超时把统计卡在“统计中…”）
        val (runs, steps) = runCatching { AppGraph.history.counts() }.getOrDefault(0 to 0)
        val packages = runCatching { AppGraph.history.packageCount() }.getOrDefault(0)
        historyText = String.format(historyTemplate, runs, steps, packages)
        // 打开页面即并行拉取：应用级线程池，每个品牌各自访问，失败即清空
        refreshStatus = "正在并行拉取 ${catalog.size} 个服务商的模型…"
        runCatching {
            AppGraph.history.refreshAllProviders(
                providers = catalog.map { it.provider },
                localBaseUrl = settings.localBaseUrl,
                keyFor = { brand -> if (brand == settings.brandId) SettingsStore.apiKey() else null },
                dispatcher = AppGraph.networkPool,
            )
        }
        catalog = runCatching { AppGraph.history.catalog() }.getOrDefault(catalog)
        refreshStatus = "已刷新：有 Key 的品牌显示模型，拉不到的显示「无数据」"
        val currentModels = catalog.firstOrNull { it.provider.id == settings.brandId }?.models.orEmpty()
        if (currentModels.isNotEmpty() && currentModels.none { it.id == settings.model }) {
            SettingsStore.setModel(currentModels.first().id)
        }


    }

    fun baseUrlOf(providerId: String): String? =
        if (providerId == "local") {
            settings.localBaseUrl.ifBlank { null }
        } else {
            catalog.firstOrNull { it.provider.id == providerId }?.provider?.baseUrl
        }

    // 切换品牌后自动访问该服务商的模型列表
    val refreshFor: (String) -> Unit = { providerId ->
        scope.launch {
            val provider = catalog.firstOrNull { it.provider.id == providerId }?.provider
            val baseUrl = baseUrlOf(providerId)
            val key = SettingsStore.apiKey()
            val keyOptional = providerId == "local" || providerId == "openrouter"
            when {
                provider == null || baseUrl == null ->
                    refreshStatus = "该服务商没有公开的模型列表端点"

                !keyOptional && key.isNullOrBlank() ->
                    refreshStatus = "填入 ${provider.label} 的 API Key 后会自动拉取"

                else -> {
                    refreshStatus = "正在从 ${provider.label} 拉取模型列表…"
                    refreshStatus = runCatching {
                        AppGraph.history.refreshModels(provider.id, baseUrl, key.orEmpty())
                    }.map { String.format(modelsAddedTemplate, it) }
                        .getOrElse { "拉取失败：${(it as? LlmHttpException)?.friendly() ?: it.message}" }

                    catalog = runCatching { AppGraph.history.catalog() }.getOrDefault(catalog)
                    val models = catalog.firstOrNull { it.provider.id == providerId }?.models.orEmpty()
                    if (models.none { it.id == settings.model }) {
                        models.firstOrNull()?.let { SettingsStore.setModel(it.id) }
                    }
                }
            }
        }
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text("语言 / Language") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    var lastGroup = ""
                    AppLanguages.forEach { option ->
                        if (option.group != lastGroup) {
                            lastGroup = option.group
                            Text(
                                text = option.group,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    SettingsStore.setLanguage(option.id)
                                    showLanguageDialog = false
                                    (context as? Activity)?.recreate()
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = option.id == settings.languageId,
                                onClick = null,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }

    if (showColorThemeDialog) {
        AlertDialog(
            onDismissRequest = { showColorThemeDialog = false },
            title = { Text(stringResource(R.string.label_color_theme)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(R.string.theme_color_pick_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )

                    // 动态取色是列表里的第一项：选中后从壁纸主色实时推导整套配色
                    dynamicScheme?.let { scheme ->
                        ColorThemeOption(
                            selected = dynamicSelected,
                            label = stringResource(R.string.theme_dynamic),
                            hint = stringResource(R.string.theme_dynamic_hint),
                            colors = scheme.previewColors(),
                            onClick = {
                                SettingsStore.setDynamicColor(true)
                                showColorThemeDialog = false
                            },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }

                    LyraColorTheme.entries.forEach { theme ->
                        ColorThemeOption(
                            selected = !dynamicSelected && settings.colorTheme == theme,
                            label = theme.label(),
                            colors = theme.colorScheme(darkUi).previewColors(),
                            onClick = {
                                SettingsStore.setColorTheme(theme)
                                showColorThemeDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showColorThemeDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    if (showOfflineAsrDialog) {
        val status = offlineAsr
        AlertDialog(
            onDismissRequest = { showOfflineAsrDialog = false },
            title = { Text(stringResource(R.string.row_offline_asr)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(R.string.offline_asr_dialog_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    when (status.state) {
                        OfflineAsrModel.State.Downloading -> {
                            Spacer(Modifier.height(14.dp))
                            LinearProgressIndicator(
                                progress = { status.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "${(status.progress * 100).toInt()}% · " +
                                    "${status.bytes / (1024 * 1024)} / " +
                                    "${status.total / (1024 * 1024)} MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        OfflineAsrModel.State.Ready -> {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.offline_asr_ready),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.offline_asr_delete_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        OfflineAsrModel.State.Failed -> {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = status.error ?: stringResource(R.string.offline_asr_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        OfflineAsrModel.State.Missing -> Unit
                    }
                }
            },
            confirmButton = {
                when (status.state) {
                    OfflineAsrModel.State.Ready -> TextButton(
                        onClick = {
                            OfflineAsrModel.delete(context)
                            showOfflineAsrDialog = false
                        },
                    ) {
                        Text(stringResource(R.string.action_delete))
                    }

                    OfflineAsrModel.State.Downloading -> TextButton(
                        onClick = {
                            OfflineAsrModel.cancel(context)
                            showOfflineAsrDialog = false
                        },
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }

                    else -> TextButton(
                        onClick = { OfflineAsrModel.download(context) },
                    ) {
                        Text(
                            stringResource(
                                if (status.state == OfflineAsrModel.State.Failed) {
                                    R.string.action_retry
                                } else {
                                    R.string.action_download
                                },
                            ),
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showOfflineAsrDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    if (showGroupRole) {
        AlertDialog(
            onDismissRequest = { showGroupRole = false },
            title = { Text("群聊角色") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("场景", style = MaterialTheme.typography.labelLarge)
                    groupScenarios.forEach { scenario ->
                        TextButton(onClick = {
                            groupScenarioId = scenario.id
                            groupRoleId = null
                        }) {
                            Text((if (groupScenarioId == scenario.id) "✓ " else "") + scenario.name)
                        }
                    }
                    Text("角色", style = MaterialTheme.typography.labelLarge)
                    groupRoles.filter { it.scenarioId == groupScenarioId }.forEach { role ->
                        TextButton(onClick = {
                            scope.launch {
                                AppGraph.history.groupChat.setSessionScenario(
                                    id = groupSessionId,
                                    scenarioId = groupScenarioId,
                                    roleId = role.id,
                                )
                                groupRoleId = role.id
                            }
                        }) {
                            Text((if (groupRoleId == role.id) "✓ " else "") + "${role.name} · ${role.model}")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGroupRole = false }) { Text("完成") }
            },
        )
    }

    if (showLedger) {
        AlertDialog(
            onDismissRequest = { showLedger = false },
            title = { Text(stringResource(R.string.row_ledger)) },
            text = {
                Column {
                    Text(ledgerStatus, style = MaterialTheme.typography.bodyMedium)
                    if (compactionText.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = compactionText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (metricsText.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = metricsText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(ledgerLines) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 3.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLedger = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
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
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回")
                }
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(8.dp))

                SectionLabel(stringResource(R.string.section_agent))
                SettingsGroup {
                    ProviderPicker(
                        providers = catalog.map { it.provider },
                        selectedId = settings.brandId,
                        onSelect = { id ->
                            val first = catalog.firstOrNull { it.provider.id == id }?.models?.firstOrNull()
                            SettingsStore.setBrandAndModel(id, first?.id ?: "")
                            refreshFor(id)
                        },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.label_api_key)) },
                            placeholder = { Text("粘贴该品牌的 Key（本地服务可留空）") },
                            visualTransformation = if (revealKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { revealKey = !revealKey }) {
                                    Icon(
                                        painter = painterResource(
                                            if (revealKey) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                                        ),
                                        contentDescription = if (revealKey) "隐藏" else "显示",
                                    )
                                }
                            },
                            supportingText = {
                                Text(
                                    if (settings.apiKeyHint.isNotBlank()) {
                                        String.format(keySavedTemplate, settings.apiKeyHint)
                                    } else {
                                        "保存后会自动拉取该品牌的模型列表"
                                    },
                                )
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = {
                                    SettingsStore.setApiKey(apiKeyInput)
                                    apiKeyInput = ""
                                    refreshFor(settings.brandId)
                                },
                                enabled = apiKeyInput.isNotBlank(),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = "保存 Key",
                                )
                            }
                            if (settings.apiKeyHint.isNotBlank()) {
                                IconButton(onClick = { SettingsStore.setApiKey("") }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = "清除 Key",
                                    )
                                }
                            }
                            IconButton(onClick = { refreshFor(settings.brandId) }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_refresh),
                                    contentDescription = "刷新该品牌的模型列表",
                                )
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(
                            text = "云端用量 API",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "服务商 billing/usage 接口；结果缓存 10 分钟。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = cloudUsageUrlInput,
                            onValueChange = { cloudUsageUrlInput = it },
                            label = { Text("用量 API URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = cloudUsageKeyInput,
                            onValueChange = { cloudUsageKeyInput = it },
                            label = { Text("云端查询 Key") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = {
                                Text(
                                    if (settings.cloudUsageKeyHint.isNotBlank()) {
                                        "已保存：${settings.cloudUsageKeyHint}"
                                    } else {
                                        "单独配置，不复用推理 Key"
                                    },
                                )
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = {
                                    SettingsStore.setCloudUsageUrl(cloudUsageUrlInput)
                                    if (cloudUsageKeyInput.isNotBlank()) {
                                        SettingsStore.setCloudUsageKey(cloudUsageKeyInput)
                                        cloudUsageKeyInput = ""
                                    }
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = "保存云端用量配置",
                                )
                            }
                            if (settings.cloudUsageKeyHint.isNotBlank()) {
                                IconButton(onClick = { SettingsStore.setCloudUsageKey("") }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = "清除云端 Key",
                                    )
                                }
                            }
                        }
                    }

                    if (settings.brandId == "local") {
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            OutlinedTextField(
                                value = localUrlInput,
                                onValueChange = {
                                    localUrlInput = it
                                    SettingsStore.setLocalBaseUrl(it)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(R.string.label_local_url)) },
                                placeholder = { Text("http://192.168.1.10:11434/v1") },
                                supportingText = {
                                    Text("局域网内的 OpenAI 兼容服务：Ollama / LM Studio / vLLM / llama.cpp")
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { refreshFor("local") }) {
                                Text("保存并拉取模型")
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    ModelPicker(
                        models = catalog.firstOrNull { it.provider.id == settings.brandId }?.models.orEmpty(),
                        selectedId = settings.model,
                        onSelect = SettingsStore::setModel,
                    )
                }

                Spacer(Modifier.height(20.dp))

                SectionLabel(stringResource(R.string.section_appearance))
                SettingsGroup {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text(stringResource(R.string.label_theme), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { SettingsStore.setThemeMode(mode) },
                                    label = { Text(mode.label()) },
                                )
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    SettingsRow(
                        title = stringResource(R.string.label_color_theme),
                        subtitle = if (dynamicSelected) {
                            stringResource(R.string.theme_color_dynamic_active)
                        } else {
                            settings.colorTheme.label()
                        },
                        onClick = { showColorThemeDialog = true },
                        preview = {
                            val previewScheme = if (dynamicSelected) {
                                dynamicScheme
                            } else {
                                settings.colorTheme.colorScheme(darkUi)
                            }
                            PaletteDots(
                                colors = previewScheme?.previewColors().orEmpty(),
                                dotSize = 14.dp,
                            )
                        },
                    )
                }

                Spacer(Modifier.height(20.dp))

                SectionLabel(stringResource(R.string.section_language))
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.label_language),
                        subtitle = findLanguage(settings.languageId).label,
                        onClick = { showLanguageDialog = true },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    SettingsRow(
                        title = stringResource(R.string.row_translate),
                        subtitle = stringResource(R.string.row_translate_hint),
                        onClick = { showTranslateDialog = true },
                    )
                }

                Spacer(Modifier.height(20.dp))

                SectionLabel(stringResource(R.string.section_voice))
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.row_offline_asr),
                        subtitle = when (offlineAsr.state) {
                            OfflineAsrModel.State.Ready ->
                                stringResource(R.string.offline_asr_ready)

                            OfflineAsrModel.State.Downloading ->
                                stringResource(
                                    R.string.offline_asr_downloading,
                                    (offlineAsr.progress * 100).toInt(),
                                )

                            OfflineAsrModel.State.Failed ->
                                stringResource(R.string.offline_asr_failed)

                            OfflineAsrModel.State.Missing ->
                                stringResource(R.string.offline_asr_missing)
                        },
                        onClick = { showOfflineAsrDialog = true },
                    )
                }

                Spacer(Modifier.height(20.dp))

                SectionLabel(stringResource(R.string.section_permissions))
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.row_accessibility),
                        subtitle = if (connected) "已连接 · Lyra 操作服务" else "未连接，点此开启",
                        onClick = { openAccessibilitySettings(context) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    SettingsRow(
                        title = stringResource(R.string.row_automation),
                        subtitle = if (automationReady) {
                            "已绑定 · observe / tap / setText / scroll / screenshot / files"
                        } else {
                            "未绑定"
                        },
                        onClick = {},
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    SettingsRow(
                        title = stringResource(R.string.row_refresh_models),
                        subtitle = refreshStatus,
                        onClick = { refreshFor(settings.brandId) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    SettingsRow(
                        title = stringResource(R.string.row_test_connection),
                        subtitle = testStatus,
                        onClick = {
                            val provider = catalog.firstOrNull { it.provider.id == settings.brandId }?.provider
                            val baseUrl = baseUrlOf(settings.brandId)
                            val key = SettingsStore.apiKey()
                            when {
                                provider == null || baseUrl == null -> testStatus = "先完成服务商配置"
                                settings.brandId != "local" && key.isNullOrBlank() ->
                                    testStatus = "先保存 API Key"

                                else -> scope.launch {
                                    testStatus = "连接中…"
                                    val started = System.currentTimeMillis()
                                    testStatus = runCatching {
                                        LlmClients.create(provider.id, baseUrl, key.orEmpty(), settings.model)
                                            .complete(listOf(LlmMessage("user", "只回复 OK")))
                                    }.map {
                                        "连接成功 · ${System.currentTimeMillis() - started}ms · " +
                                            (it.text?.take(24) ?: "（无文本）")
                                    }.getOrElse {
                                        "失败：${(it as? LlmHttpException)?.friendly() ?: it.message}"
                                    }
                                }
                            }
                        },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    SettingsRow(
                        title = stringResource(R.string.row_test_screenshot),
                        subtitle = shotResult ?: "通过服务截图；Lyra 自身界面会被拒绝",
                        onClick = {
                            scope.launch {
                                shotResult = runCatching { AutomationClient.screenshot() }
                                    .mapCatching { json ->
                                        val o = org.json.JSONObject(json)
                                        if (o.optBoolean("ok")) {
                                            "已截图 ${o.optInt("width")}×${o.optInt("height")}"
                                        } else {
                                            "被拒绝：${o.optString("code")} · ${o.optString("message")}"
                                        }
                                    }
                                    .getOrElse { "调用失败：${it.message}" }
                            }
                        },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    SettingsRow(
                        title = stringResource(R.string.row_ocr),
                        subtitle = ocrStatus,
                        onClick = {
                            scope.launch {
                                ocrStatus = "截图中…"
                                ocrStatus = runCatching {
                                    val json = AutomationClient.screenshot()
                                    val o = org.json.JSONObject(json)
                                    if (!o.optBoolean("ok")) {
                                        error("截图被拒绝：${o.optString("code")}")
                                    }
                                    val lines = ocr.recognize(o.optString("path"))
                                    if (lines.isEmpty()) {
                                        "没有识别到文字"
                                    } else {
                                        "识别 ${lines.size} 行：" +
                                            lines.take(2).joinToString(" / ") { it.text.take(20) }
                                    }
                                }.getOrElse { "失败：${it.message}" }
                            }
                        },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    SettingsRow(
                        title = stringResource(R.string.row_ledger),
                        subtitle = stringResource(R.string.row_ledger_hint),
                        onClick = {
                            scope.launch {
                                ledgerStatus = "…"
                                metricsText = "…"
                                runCatching {
                                    // 账本整表读取与链校验都可能很大，放 IO，别卡 UI
                                    val (events, problems) = kotlinx.coroutines.withContext(
                                        kotlinx.coroutines.Dispatchers.IO,
                                    ) {
                                        val loaded = AppGraph.history.ledger.loadAll()
                                        loaded to AppGraph.history.ledger.verify()
                                    }
                                    metricsText = runCatching {
                                        val m = AppGraph.history.metrics()
                                        val cost = "%.4f".format(m.totalCostMicros / 1_000_000.0)
                                        "VOI 误差 %.2f · 浪费 %.0f%% · 僵局 %.1f/任务\n".format(
                                            m.voiMae,
                                            m.wastedAttentionRatio * 100,
                                            m.impasseRate,
                                        ) + "共 ${m.receipts} 次动作 · ${m.totalTokens} tokens · $$cost" +
                                            if (m.reliable) "" else "（样本不足，仅供参考）"
                                    }.getOrElse { "指标读取失败：${it.message}" }
                                    compactionText = "未压缩（协议 signed checkpoint 待接入）"
                                    ledgerLines = events.takeLast(80).map { event ->
                                        "#${event.seq} ${event.type}  ${event.digest.take(23)}…\n    ${event.payload.take(80)}"
                                    }
                                    ledgerStatus = if (problems.isEmpty()) {
                                        String.format(ledgerOkTemplate, events.size)
                                    } else {
                                        String.format(ledgerBrokenTemplate, problems.first())
                                    }
                                }.getOrElse { ledgerStatus = "读取失败：${it.message}" }
                                showLedger = true
                            }
                        },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    SettingsRow(
                        title = "群聊角色",
                        subtitle = "选择场景与角色；成员由总经理增减",
                        onClick = {
                            showGroupRole = true
                            scope.launch {
                                val session = AppGraph.history.groupChat.latestSession()
                                groupSessionId = session?.id ?: 0L
                                groupScenarioId = session?.scenarioId
                                groupRoleId = session?.roleId
                                groupScenarios = AppGraph.history.groupChat.scenarios()
                                groupRoles = AppGraph.history.groupChat.roles()
                            }
                        },
                    )
                    SettingsRow(
                        title = stringResource(R.string.row_history),
                        subtitle = "$historyText · 点击清空",
                        onClick = {
                            scope.launch {
                                AppGraph.history.clearAll()
                                historyText = "已清空 · 0 次任务 · 0 条步骤 · 0 个应用"
                            }
                        },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    SettingsRow(
                        title = stringResource(R.string.row_system_settings),
                        subtitle = "手动检查服务状态",
                        onClick = { openAccessibilitySettings(context) },
                    )
                }

                Spacer(Modifier.height(20.dp))

                SectionLabel(stringResource(R.string.section_about))
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.app_name),
                        subtitle = stringResource(R.string.about_version),
                        onClick = {},
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    SettingsRow(
                        title = stringResource(R.string.about_boundary),
                        subtitle = stringResource(R.string.about_boundary_value),
                        onClick = {},
                    )
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Lyra 的界面（包括本设置页）对 Agent 是只读禁区：" +
                        "策略层拒绝一切以 com.eloykim.lyra 为目标的动作，防止 Agent 自己改配置或自己授权。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun ProviderPicker(
    providers: List<ProviderRow>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = providers.firstOrNull { it.id == selectedId }

    Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = current?.label ?: stringResource(R.string.state_loading),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.label_provider)) },
            trailingIcon = {
                Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.label) },
                    onClick = {
                        onSelect(provider.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelPicker(
    models: List<ModelRow>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val empty = models.isEmpty()
    val current = models.firstOrNull { it.id == selectedId }

    Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = when {
                empty -> stringResource(R.string.state_no_data)
                current != null -> current.label
                else -> stringResource(R.string.state_loading)
            },
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.label_model)) },
            trailingIcon = {
                Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!empty) {
            Box(Modifier.matchParentSize().clickable { expanded = true })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(model.label)
                            Text(
                                text = modelMeta(model),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onSelect(model.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun modelMeta(model: ModelRow): String {
    val context = model.contextWindow?.let {
        if (it >= 1_000_000) "${it / 1_000_000}M" else "${it / 1000}K"
    } ?: "上下文未知"
    val price = if (model.inputPrice != null && model.outputPrice != null) {
        " · $${model.inputPrice}/${model.outputPrice} 每 1M"
    } else {
        ""
    }
    val vision = if (model.supportsVision) " · 视觉" else ""
    return "$context$price$vision"
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

/** 配色方案列表的一行：单选点 + 三色预览 + 名称（动态取色额外带一行说明）。 */
@Composable
private fun ColorThemeOption(
    selected: Boolean,
    label: String,
    colors: List<Color>,
    onClick: () -> Unit,
    hint: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(10.dp))
        PaletteDots(colors = colors, dotSize = 16.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 主色 / 主色容器 / 三级色三颗点，用来一眼认出配色。 */
@Composable
private fun PaletteDots(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    dotSize: Dp = 14.dp,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        colors.forEach { color ->
            Box(
                Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

/** 配色预览固定取三颗最有辨识度的角色色。 */
private fun ColorScheme.previewColors(): List<Color> = listOf(primary, primaryContainer, tertiary)

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    preview: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (preview != null) {
            preview()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThemeMode.label(): String = when (this) {
    ThemeMode.System -> stringResource(R.string.theme_system)
    ThemeMode.Light -> stringResource(R.string.theme_light)
    ThemeMode.Dark -> stringResource(R.string.theme_dark)
}

@Composable
private fun LyraColorTheme.label(): String = when (this) {
    LyraColorTheme.Lyra -> stringResource(R.string.theme_lyra)
    LyraColorTheme.Ocean -> stringResource(R.string.theme_ocean)
    LyraColorTheme.Teal -> stringResource(R.string.theme_teal)
    LyraColorTheme.Forest -> stringResource(R.string.theme_forest)
    LyraColorTheme.Amber -> stringResource(R.string.theme_amber)
    LyraColorTheme.Sunset -> stringResource(R.string.theme_sunset)
    LyraColorTheme.Rose -> stringResource(R.string.theme_rose)
    LyraColorTheme.Graphite -> stringResource(R.string.theme_graphite)
}
