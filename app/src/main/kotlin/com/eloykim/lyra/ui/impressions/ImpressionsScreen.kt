package com.eloykim.lyra.ui.impressions

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.eloykim.lyra.data.ActionRow
import com.eloykim.lyra.data.PackageRow
import com.eloykim.lyra.ui.components.AppIcon
import com.eloykim.lyra.ui.components.actionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 印象：Lyra 从本机历史里读出来的「你」。
 *
 * 只做统计，不读屏幕原文、不上传：任务数、步骤数、常用应用、习惯动作、活跃时段。
 */
@Composable
fun ImpressionsScreen(onBack: () -> Unit) {
    var impression by remember { mutableStateOf<Impression?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        impression = loadImpression()
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
                Text("印象", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { reload++ }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = "刷新",
                    )
                }
            }

            val data = impression
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
                    ImpressionHero(data)

                    Spacer(Modifier.height(20.dp))
                    StatRow(data)

                    Spacer(Modifier.height(24.dp))
                    SectionTitle("常去的地方", if (data.topApps.isEmpty()) "还没有记录" else "打开 ×2 + 操作")
                    Spacer(Modifier.height(10.dp))
                    TopAppsCard(data.topApps)

                    Spacer(Modifier.height(24.dp))
                    SectionTitle("习惯动作", if (data.topActions.isEmpty()) "还没有记录" else "按次数排序")
                    Spacer(Modifier.height(10.dp))
                    TopActionsCard(data.topActions)

                    Spacer(Modifier.height(24.dp))
                    SectionTitle("作息", "本机时间")
                    Spacer(Modifier.height(10.dp))
                    HoursCard(data.hours)

                    Spacer(Modifier.height(24.dp))
                    SectionTitle("Lyra 的观察")
                    Spacer(Modifier.height(10.dp))
                    InsightsCard(data)

                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "这些印象全部来自本机 SQLite 里的任务、步骤与应用目录。" +
                            "没有上传，也没有云端画像。",
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

private data class Impression(
    val runs: Int,
    val steps: Int,
    val apps: Int,
    val ledgerEvents: Int,
    val topApps: List<PackageRow>,
    val topActions: List<ActionRow>,
    val hours: IntArray,
) {
    val peakHour: Int?
        get() {
            val index = hours.indices.maxByOrNull { hours[it] } ?: return null
            return if (hours[index] > 0) index else null
        }

    val topApp: PackageRow? get() = topApps.firstOrNull()
    val topAction: ActionRow? get() = topActions.firstOrNull()
}

private suspend fun loadImpression(): Impression {
    val history = AppGraph.history
    val (runs, steps) = runCatching { history.counts() }.getOrDefault(0 to 0)
    val apps = runCatching { history.packageCount() }.getOrDefault(0)
    val ledger = withContext(Dispatchers.IO) {
        runCatching { history.ledger.size() }.getOrDefault(0)
    }
    return Impression(
        runs = runs,
        steps = steps,
        apps = apps,
        ledgerEvents = ledger,
        topApps = runCatching { history.topPackages(5) }.getOrDefault(emptyList()),
        topActions = runCatching { history.topActions(5) }.getOrDefault(emptyList()),
        hours = runCatching { history.hourlyActivity() }.getOrDefault(IntArray(24)),
    )
}

@Composable
private fun ImpressionHero(data: Impression) {
    val topName = data.topApp?.let { appLabel(it) }
    val peak = data.peakHour
    val title = if (data.runs == 0) "还没有留下印象" else "一起办过 ${data.runs} 件事"
    val body = if (data.runs == 0) {
        "跑通第一个任务后，这里会长出你的习惯轮廓：常去的 App、常用的动作、活跃的时段。"
    } else {
        buildString {
            append("横跨 ${data.apps} 个 App、${data.steps} 步操作。")
            topName?.let { append("你最常去「$it」。") }
            peak?.let { append("最常在 ${padHour(it)} 点前后出现。") }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer,
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
                        .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "L",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "LYRA 对你的印象",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun StatRow(data: Impression) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("任务", data.runs.toString(), Modifier.weight(1f))
        StatTile("步骤", data.steps.toString(), Modifier.weight(1f))
        StatTile("应用", data.apps.toString(), Modifier.weight(1f))
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
private fun TopAppsCard(apps: List<PackageRow>) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (apps.isEmpty()) {
            EmptyHint("还没有用过别的 App。")
        } else {
            val max = apps.maxOf { it.launchCount * 2 + it.stepCount }.coerceAtLeast(1)
            Column(Modifier.padding(vertical = 6.dp)) {
                apps.forEachIndexed { index, app ->
                    if (index > 0) {
                        HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    }
                    AppRow(app, max)
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: PackageRow, max: Int) {
    val label = appLabel(app)
    val score = app.launchCount * 2 + app.stepCount
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            packageName = app.packageName,
            label = label,
            size = 40.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (app.launchCount > 0) {
                    "打开 ${app.launchCount} 次 · 操作 ${app.stepCount} 步"
                } else {
                    "操作 ${app.stepCount} 步"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { score.toFloat() / max },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = score.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TopActionsCard(actions: List<ActionRow>) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (actions.isEmpty()) {
            EmptyHint("还没有动作记录。")
        } else {
            val max = actions.maxOf { it.count }.coerceAtLeast(1)
            Column(Modifier.padding(vertical = 6.dp)) {
                actions.forEachIndexed { index, action ->
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
                            text = actionLabel(action.action),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(72.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(action.count.toFloat() / max)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MaterialTheme.colorScheme.tertiary),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = action.count.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(32.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HoursCard(hours: IntArray) {
    val max = hours.maxOrNull()?.coerceAtLeast(1) ?: 1
    val peak = hours.indices.maxByOrNull { hours[it] }?.takeIf { hours[it] > 0 }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = peak?.let { "最活跃 ${padHour(it)}:00 – ${padHour((it + 1) % 24)}:00" }
                    ?: "还没有足够的记录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                hours.forEachIndexed { hour, value ->
                    val fraction = value.toFloat() / max
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height((68f * fraction).dp.coerceAtLeast(2.dp))
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (hour == peak) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
                                },
                            ),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("0", "6", "12", "18", "24").forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun InsightsCard(data: Impression) {
    val insights = buildList {
        data.topApp?.let { app ->
            add("你最常去「${appLabel(app)}」，打开 ${app.launchCount} 次、操作 ${app.stepCount} 步。")
        }
        data.peakHour?.let { hour ->
            add("你在 ${padHour(hour)} 点前后最常让我做事。")
        }
        data.topAction?.let { action ->
            add("出现最多的动作是「${actionLabel(action.action)}」，共 ${action.count} 次。")
        }
        if (data.ledgerEvents > 0) {
            add("本机账本已累计 ${data.ledgerEvents} 条事件，全部只追加、可校验。")
        }
        if (isEmpty()) {
            add("数据还不够，多跑几个任务，印象会自己长出来。")
        }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            insights.forEach { insight ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = insight,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
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

/** 应用名缺失时，用包名最后一段兜底，避免整行是 `com.miui.calculator`。 */
private fun appLabel(app: PackageRow): String {
    app.label?.takeIf { it.isNotBlank() }?.let { return it }
    val tail = app.packageName.substringAfterLast('.').ifBlank { app.packageName }
    return tail.replaceFirstChar { it.uppercase() }
}

private fun padHour(hour: Int): String = hour.toString().padStart(2, '0')
