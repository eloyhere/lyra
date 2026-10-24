package com.eloykim.lyra

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eloykim.lyra.accessibility.ConsentManager
import com.eloykim.lyra.policy.PolicyGuard
import com.eloykim.lyra.ui.chat.ChatScreen
import com.eloykim.lyra.ui.group.GroupChatScreen
import com.eloykim.lyra.ui.home.HomeScreen
import com.eloykim.lyra.ui.splash.SplashScreen
import com.eloykim.lyra.ui.impressions.ImpressionsScreen
import com.eloykim.lyra.ui.inspector.InspectorScreen
import com.eloykim.lyra.ui.settings.SettingsScreen
import com.eloykim.lyra.ui.terminal.TerminalScreen
import com.eloykim.lyra.ui.usage.UsageScreen

private enum class LyraScreen { Home, Chat, Group, Terminal, Impressions, Usage, Inspector, Settings }

@Composable
fun LyraApp() {
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1100)
        showSplash = false
    }
    if (showSplash) {
        SplashScreen()
        return
    }

    var screen by rememberSaveable { mutableStateOf(LyraScreen.Home) }

    LaunchedEffect(Unit) {
        DebugLaunch.screen.collect { requested ->
            val target = when (requested?.lowercase()) {
                "home" -> LyraScreen.Home
                "chat" -> LyraScreen.Chat
                "group" -> LyraScreen.Group
                "terminal" -> LyraScreen.Terminal
                "impressions" -> LyraScreen.Impressions
                "usage" -> LyraScreen.Usage
                "inspector" -> LyraScreen.Inspector
                "settings" -> LyraScreen.Settings
                else -> null
            }
            if (target != null) screen = target
        }
    }

    BackHandler(enabled = screen != LyraScreen.Home) {
        screen = LyraScreen.Home
    }

    val challenge by ConsentManager.pending.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = screen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "lyra-screen",
    ) { target ->
        when (target) {
            LyraScreen.Home -> HomeScreen(
                onOpenInspector = { screen = LyraScreen.Inspector },
                onOpenChat = { screen = LyraScreen.Chat },
                onOpenGroup = { screen = LyraScreen.Group },
                onOpenTerminal = { screen = LyraScreen.Terminal },
                onOpenImpressions = { screen = LyraScreen.Impressions },
                onOpenUsage = { screen = LyraScreen.Usage },
                onOpenSettings = { screen = LyraScreen.Settings },
            )

            LyraScreen.Chat -> ChatScreen(
                onBack = { screen = LyraScreen.Home },
                onOpenSettings = { screen = LyraScreen.Settings },
            )

            LyraScreen.Group -> GroupChatScreen(
                onBack = { screen = LyraScreen.Home },
            )

            LyraScreen.Terminal -> TerminalScreen(
                onBack = { screen = LyraScreen.Home },
            )

            LyraScreen.Impressions -> ImpressionsScreen(
                onBack = { screen = LyraScreen.Home },
            )

            LyraScreen.Usage -> UsageScreen(
                onBack = { screen = LyraScreen.Home },
            )

            LyraScreen.Inspector -> InspectorScreen(
                onBack = { screen = LyraScreen.Home },
            )

            LyraScreen.Settings -> SettingsScreen(
                onBack = { screen = LyraScreen.Home },
            )
        }
    }

    challenge?.let { pending ->
        AlertDialog(
            onDismissRequest = { /* 只能显式选择，不能点外部关闭 */ },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
            title = { Text("需要你确认") },
            text = {
                Column {
                    Text(
                        text = "${PolicyGuard.scopeLabel(pending.scope)}操作 · 授权一次",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = pending.summary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Agent 无法代替你确认。授权只对这一次操作有效，60 秒过期，用掉即焚。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { ConsentManager.approve(pending.id) }) {
                    Text("允许这一次")
                }
            },
            dismissButton = {
                TextButton(onClick = { ConsentManager.deny(pending.id) }) {
                    Text("拒绝")
                }
            },
        )
    }
}
