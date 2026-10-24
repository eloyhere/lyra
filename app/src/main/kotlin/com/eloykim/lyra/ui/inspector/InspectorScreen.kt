package com.eloykim.lyra.ui.inspector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eloykim.lyra.R
import com.eloykim.lyra.accessibility.AccessibilityState
import com.eloykim.lyra.accessibility.AutomationClient
import com.eloykim.lyra.accessibility.LyraAccessibilityService
import com.eloykim.lyra.model.UiNode
import com.eloykim.lyra.ui.home.openAccessibilitySettings
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Date
import java.util.Locale

@Composable
fun InspectorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val connected by AccessibilityState.connected.collectAsStateWithLifecycle()
    val snapshot by AccessibilityState.snapshot.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
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
                Text("屏幕检查器", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { LyraAccessibilityService.requestCapture() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = "刷新",
                    )
                }
            }

            when {
                !connected -> NotConnected(onOpenSettings = { openAccessibilitySettings(context) })
                snapshot == null -> Waiting()
                else -> {
                    val current = snapshot!!
                    Summary(
                        packageName = current.packageName,
                        nodeCount = current.nodes.size,
                        interactiveCount = current.interactiveCount,
                        capturedAt = current.capturedAt,
                        truncated = current.truncated,
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(current.nodes, key = { it.id }) { node ->
                            NodeRow(
                                node = node,
                                onClick = {
                                    if (!node.clickable) return@NodeRow
                                    scope.launch {
                                        val result = runCatching {
                                            AutomationClient.tap(node.id, current.id)
                                        }
                                        snackbar.showSnackbar(describeResult(result, node.id))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun describeResult(result: Result<String>, nodeId: Int): String {
    if (result.isFailure) return "服务未连接：${result.exceptionOrNull()?.message ?: "未知错误"}"
    return runCatching {
        val json = JSONObject(result.getOrThrow())
        if (json.optBoolean("ok")) {
            "已点击 #$nodeId · ${json.optString("message")}"
        } else {
            "失败：${json.optString("code")} · ${json.optString("message")}"
        }
    }.getOrDefault("返回内容不是合法 JSON")
}

@Composable
private fun Summary(
    packageName: String,
    nodeCount: Int,
    interactiveCount: Int,
    capturedAt: Long,
    truncated: Boolean,
) {
    val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(capturedAt))
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = packageName.ifBlank { "未知应用" },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${nodeCount} 个节点 · ${interactiveCount} 个可交互 · ${time}" +
                    if (truncated) " · 已截断" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "点按「可点」节点，会通过自动化服务真实点击",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun NodeRow(node: UiNode, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = node.clickable, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "#${node.id}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(44.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = node.shortLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${node.bounds.left},${node.bounds.top} · ${node.bounds.width}×${node.bounds.height}" +
                    if (node.depth > 0) " · d${node.depth}" else "",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (node.clickable) {
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = "可点",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun NotConnected(onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("无障碍服务没开", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "开启后，这里会实时显示当前屏幕的控件树。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenSettings) { Text("去系统设置开启") }
        }
    }
}

@Composable
private fun Waiting() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("服务已连接", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "切到任意 App，再回来，就能看到它的界面结构。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}