package com.eloykim.lyra.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eloykim.lyra.AppGraph
import com.eloykim.lyra.R
import com.eloykim.lyra.data.UsageDay
import com.eloykim.lyra.data.UsageReceipt
import com.eloykim.lyra.data.UsageReport
import com.eloykim.lyra.data.UsageSlice
import com.eloykim.lyra.data.ChatUsageSummary
import com.eloykim.lyra.service.usage.UsageService
import com.eloykim.lyra.ui.components.actionLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 用度：把 AMP 账本里的 token / 耗时 / 花费摊开给用户看。
 *
 * 不做新的采集，只读本机只追加账本；没有配置模型单价时费用按 0 计。
 */
@Composable
fun UsageScreen(onBack: () -> Unit) {
    var snapshot by remember { mutableStateOf<UsageService.Snapshot?>(null) }
    var cloudUsage by remember { mutableStateOf<UsageService.CloudUsage?>(null) }
    var cloudLoading by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf(7) }
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    LaunchedEffect(reload, days) {
        val service = AppGraph.commands.hub.service("usage") as? UsageService
        snapshot = service?.let { usage ->
            runCatching { usage.snapshot(days = days, recentLimit = 20) }.getOrNull()
        }
        cloudLoading = true
        cloudUsage = runCatching { service?.cloudUsage(force = false) }.getOrNull()
        cloudLoading = false
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
                Spacer(Modifier.weight(1f))
                Text("用度", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { reload++ }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = "刷新",
                    )
                }
            }

            val data = snapshot?.report
            if (data == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = days == 7,
                            onClick = { days = 7 },
                            label = { Text("近 7 天") },
                        )
                        FilterChip(
                            selected = days == 30,
                            onClick = { days = 30 },
                            label = { Text("近 30 天") },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    val chat = snapshot?.chat ?: ChatUsageSummary()
                    val group = snapshot?.group ?: ChatUsageSummary()
                    UsageHero(data, snapshot?.pricing, chat, group, days)

                    Spacer(Modifier.height(20.dp))
                    UsageStats(data, chat, group)

                    Spacer(Modifier.height(20.dp))
                    UsageSourceCard(data, chat, group)

                    val providers = snapshot?.providers.orEmpty()
                    if (providers.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        UsageProviderCard(providers)
                    }

                    Spacer(Modifier.height(20.dp))
                    UsageCloudCard(
                        localTokens = data.tokensIn + data.tokensOut +
                            chat.totalTokens + group.totalTokens,
                        localCostMicros = data.costMicros +
                            chat.costMicros + group.costMicros,
                        cloud = cloudUsage,
                        loading = cloudLoading,
                        onRefresh = {
                            scope.launch {
                                val service = AppGraph.commands.hub.service("usage") as? UsageService
                                cloudLoading = true
                                cloudUsage = runCatching {
                                    service?.cloudUsage(force = true)
                                }.getOrNull()
                                cloudLoading = false
                            }
                        },
                    )

                    Spacer(Modifier.height(24.dp))
                    val peak = data.byDay.maxOfOrNull { it.tokens } ?: 0L
                    SectionTitle(
                        "近 $days 天",
                        if (peak > 0L) "峰值 ${formatTokens(peak)}" else "token / 天",
                    )
                    Spacer(Modifier.height(10.dp))
                    DailyChart(data.byDay)

                    Spacer(Modifier.height(24.dp))
                    SectionTitle("按动作", "token 占比")
                    Spacer(Modifier.height(10.dp))
                    ActionUsageCard(data.byAction)

                    Spacer(Modifier.height(24.dp))
                    SectionTitle(
                        "最近回执",
                        if (data.recent.isEmpty()) "还没有记录" else "最新 ${data.recent.size} 条",
                    )
                    Spacer(Modifier.height(10.dp))
                    RecentReceiptsCard(data.recent, timeFormat)

                    Spacer(Modifier.height(24.dp))
                    LedgerCard(data)

                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "用度来自本机 AMP receipt 与 chat_usage 账本：token、动作耗时与模型单价。" +
                            "没有上传；未配置模型单价时费用按 0 计。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun UsageSourceCard(
    report: UsageReport,
    chat: ChatUsageSummary,
    group: ChatUsageSummary,
) {
    val totalTokens = report.tokens + chat.totalTokens + group.totalTokens
    val totalCost = report.costMicros + chat.costMicros + group.costMicros
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("来源分布", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    "${formatTokens(totalTokens)} · ${formatCost(totalCost)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            SourceLine(
                label = "Agent",
                tokens = report.tokens,
                costMicros = report.costMicros,
                totalTokens = totalTokens,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            SourceLine(
                label = "1:1 聊天",
                tokens = chat.totalTokens,
                costMicros = chat.costMicros,
                totalTokens = totalTokens,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(10.dp))
            SourceLine(
                label = "群聊",
                tokens = group.totalTokens,
                costMicros = group.costMicros,
                totalTokens = totalTokens,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun SourceLine(
    label: String,
    tokens: Long,
    costMicros: Long,
    totalTokens: Long,
    color: androidx.compose.ui.graphics.Color,
) {
    val fraction = if (totalTokens <= 0) 0f else (tokens.toFloat() / totalTokens).coerceIn(0f, 1f)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(
                "${formatTokens(tokens)} · ${formatCost(costMicros)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape),
        )
    }
}

@Composable
private fun UsageProviderCard(
    providers: List<com.eloykim.lyra.data.UsageProviderSummary>,
) {
    val total = providers.sumOf { it.totalTokens }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("品牌分布", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            providers.take(8).forEachIndexed { index, provider ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                SourceLine(
                    label = "${provider.providerId} · ${provider.model}",
                    tokens = provider.totalTokens,
                    costMicros = provider.costMicros,
                    totalTokens = total,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun UsageCloudCard(
    localTokens: Long,
    localCostMicros: Long,
    cloud: UsageService.CloudUsage?,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("云端用量", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = cloud?.source ?: "配置服务商 billing/usage API 后获取",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            painterResource(R.drawable.ic_refresh),
                            contentDescription = "刷新云端用量",
                        )
                    }
                }
            }
            val current = cloud
            if (current == null) {
                Text(
                    "尚未获取云端用量",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (current.error != null) {
                Text(
                    current.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "云端 ${formatTokens(current.tokens ?: 0)} tokens · " +
                    "输入 ${formatTokens(current.tokensIn ?: 0)} / 输出 ${formatTokens(current.tokensOut ?: 0)}",
                style = MaterialTheme.typography.bodySmall,
            )
            current.costMicros?.let {
                Text(
                    "云端费用 ${formatCost(it)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            current.balanceMicros?.let {
                Text(
                    "云端余额 ${formatCost(it)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            current.balanceLabel?.let {
                Text(
                    "云端余额 $it",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (current.requests != null || current.requestLimit != null) {
                Text(
                    "请求 ${current.requests ?: "—"} / 限额 ${current.requestLimit ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(6.dp))
            val tokenDiff = current.tokens?.let { localTokens - it }
            val costDiff = current.costMicros?.let { localCostMicros - it }
            Text(
                buildString {
                    append("本地 ${formatTokens(localTokens)} tokens")
                    tokenDiff?.let { append(" · 差值 ${formatTokens(it)}") }
                    append(" · 本地费用 ${formatCost(localCostMicros)}")
                    costDiff?.let { append(" · 费用差 ${formatCost(it)}") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (current.cached) {
                Text(
                    "10 分钟内缓存，未重新请求云端",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            }
        }
    }
}

@Composable
private fun UsageHero(
    report: UsageReport,
    pricing: UsageService.Pricing?,
    chat: ChatUsageSummary,
    group: ChatUsageSummary,
    days: Int,
) {
    val tokensIn = report.tokensIn + chat.inputTokens + group.inputTokens
    val tokensOut = report.tokensOut + chat.outputTokens + group.outputTokens
    val totalTokens = tokensIn + tokensOut
    val calls = report.receipts + chat.calls + group.calls
    val costMicros = report.costMicros + chat.costMicros + group.costMicros
    val costLine = when {
        calls == 0 -> "跑一个任务或聊天后，这里会出现 token、耗时与花费"
        pricing == null -> "当前模型未配置单价，费用沿用历史回执"
        else -> "按 ${pricing.providerLabel} / ${pricing.modelLabel} 动态估算 ${formatCost(costMicros)}"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.primaryContainer,
                    ),
                ),
            )
            .padding(22.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "∑",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "LYRA 用度",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "${formatTokens(totalTokens)} tokens",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "输入 ${formatTokens(tokensIn)} · 输出 ${formatTokens(tokensOut)} · " +
                    "$calls 次调用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.82f),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "本地全量 · 近 $days 天 · Agent ${formatTokens(report.tokens)} · " +
                    "聊天 ${formatTokens(chat.totalTokens)} · 群聊 ${formatTokens(group.totalTokens)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
            if (chat.calls > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "其中聊天 ${formatTokens(chat.totalTokens)} tokens · ${chat.calls} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
                )
            }
            if (group.calls > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "其中群聊 ${formatTokens(group.totalTokens)} tokens · ${group.calls} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = costLine,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            pricing?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "单价：输入 ${formatPrice(it.inputPrice)} / 1M · " +
                        "输出 ${formatPrice(it.outputPrice)} / 1M",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun UsageStats(
    report: UsageReport,
    chat: ChatUsageSummary,
    group: ChatUsageSummary,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(
            "输入 tokens",
            formatTokens(report.tokensIn + chat.inputTokens + group.inputTokens),
            Modifier.weight(1f),
        )
        StatTile(
            "输出 tokens",
            formatTokens(report.tokensOut + chat.outputTokens + group.outputTokens),
            Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("动作耗时", formatDuration(report.actionMs), Modifier.weight(1f))
        StatTile("账本事件", report.events.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, hint: String? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DailyChart(days: List<UsageDay>) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (days.isEmpty() || days.all { it.tokens == 0L }) {
            EmptyHint("最近 7 天还没有 token 消耗。")
        } else {
            val max = (days.maxOfOrNull { it.tokens } ?: 0L).coerceAtLeast(1L)
            Column(Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    days.forEach { day ->
                        val fraction = if (day.tokens <= 0L) 0f else day.tokens.toFloat() / max.toFloat()
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height((110f * fraction).dp.coerceAtLeast(3.dp))
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (day.tokens > 0L) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    days.forEach { day ->
                        Text(
                            text = "${day.date.monthValue}/${day.date.dayOfMonth}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionUsageCard(slices: List<UsageSlice>) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (slices.isEmpty()) {
            EmptyHint("还没有回执。")
        } else {
            val max = slices.maxOf { it.tokens }.coerceAtLeast(1L)
            Column(Modifier.padding(vertical = 6.dp)) {
                slices.forEachIndexed { index, slice ->
                    if (index > 0) {
                        HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = actionLabel(slice.label),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(64.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(slice.tokens.toFloat() / max.toFloat())
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(MaterialTheme.colorScheme.tertiary),
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = formatTokens(slice.tokens),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(56.dp),
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "${slice.count} 次 · ${formatDuration(slice.ms)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentReceiptsCard(receipts: List<UsageReceipt>, formatter: SimpleDateFormat) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (receipts.isEmpty()) {
            EmptyHint("还没有回执。")
        } else {
            Column(Modifier.padding(vertical = 6.dp)) {
                receipts.forEachIndexed { index, receipt ->
                    if (index > 0) {
                        HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (receipt.ok) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = actionLabel(receipt.action),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(56.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = formatter.format(Date(receipt.at)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${formatDuration(receipt.ms)} · ${if (receipt.ok) "成功" else "失败"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = formatTokens(receipt.tokens),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerCard(report: UsageReport) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (report.chainProblems.isEmpty()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (report.chainProblems.isEmpty()) "账本链完整" else "账本链损坏",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${report.events} 条事件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${report.receipts} 张回执 · 只追加 · 哈希链可校验",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (report.chainProblems.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = report.chainProblems.first(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
    )
}

private fun formatTokens(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}

private fun formatCost(micros: Long): String =
    String.format(Locale.US, "\$%.4f", micros / 1_000_000.0)

private fun formatPrice(price: Double): String =
    String.format(Locale.US, "\$%.4g", price)

private fun formatDuration(ms: Long): String = when {
    ms <= 0L -> "0ms"
    ms < 1_000L -> "${ms}ms"
    ms < 60_000L -> String.format(Locale.US, "%.1fs", ms / 1_000.0)
    else -> String.format(Locale.US, "%.1fmin", ms / 60_000.0)
}
