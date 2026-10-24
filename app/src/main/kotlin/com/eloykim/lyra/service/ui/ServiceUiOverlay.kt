package com.eloykim.lyra.service.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.SnackbarResult as ComposeSnackbarResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局 snackbar / dialog 宿主。
 *
 * 挂在 Activity 的 Compose 根节点（见 MainActivity），这样任何服务都能在前台
 * 请求一次轻提示或确认框；离开 composition 时自动卸载，后台调用会得到
 * `ui_unavailable` 而不是静默丢弃。
 */
@Composable
fun ServiceUiOverlay() {
    val snackbarHostState = remember { SnackbarHostState() }
    var pending by remember { mutableStateOf<DialogRequest?>(null) }
    val responder = remember { arrayOfNulls<CompletableDeferred<DialogResult>>(1) }
    val busy = remember { AtomicBoolean(false) }

    val host = remember {
        object : ServiceUiHost {

            override suspend fun showSnackbar(request: SnackbarRequest): SnackbarResult =
                withContext(Dispatchers.Main.immediate) {
                    val duration = if (request.durationMs <= 2_500L) {
                        SnackbarDuration.Short
                    } else {
                        SnackbarDuration.Long
                    }
                    val result = snackbarHostState.showSnackbar(
                        message = request.message,
                        actionLabel = request.actionLabel,
                        withDismissAction = request.withDismissAction,
                        duration = duration,
                    )
                    when (result) {
                        ComposeSnackbarResult.ActionPerformed ->
                            SnackbarResult(SnackbarOutcome.Action, request.message)
                        ComposeSnackbarResult.Dismissed ->
                            SnackbarResult(SnackbarOutcome.Dismissed, request.message)
                    }
                }

            override suspend fun showDialog(request: DialogRequest): DialogResult =
                withContext(Dispatchers.Main.immediate) {
                    if (!busy.compareAndSet(false, true)) {
                        return@withContext DialogResult(
                            DialogChoice.Busy,
                            message = "已经有一个弹窗在等待用户",
                        )
                    }
                    val deferred = CompletableDeferred<DialogResult>()
                    pending = request
                    responder[0] = deferred
                    try {
                        deferred.await()
                    } finally {
                        if (responder[0] === deferred) {
                            responder[0] = null
                            pending = null
                        }
                        busy.set(false)
                    }
                }
        }
    }

    DisposableEffect(host) {
        ServiceUi.install(host)
        onDispose {
            ServiceUi.uninstall(host)
            responder[0]?.let { deferred ->
                if (!deferred.isCompleted) {
                    deferred.complete(
                        DialogResult(DialogChoice.Dismissed, message = "界面已关闭"),
                    )
                }
            }
            responder[0] = null
            pending = null
            busy.set(false)
        }
    }

    Box(Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
        )
    }

    val request = pending
    if (request != null) {
        DialogContent(request) { result ->
            val deferred = responder[0]
            if (deferred != null && !deferred.isCompleted) {
                deferred.complete(result)
            }
        }
    }
}

@Composable
private fun DialogContent(
    request: DialogRequest,
    onResult: (DialogResult) -> Unit,
) {
    val properties = DialogProperties(
        dismissOnBackPress = request.dismissible,
        dismissOnClickOutside = request.dismissible,
    )
    val dismiss = {
        if (request.dismissible) {
            onResult(DialogResult(DialogChoice.Dismissed, message = "用户取消"))
        }
    }

    when (request.kind) {
        DialogKind.Info -> AlertDialog(
            onDismissRequest = dismiss,
            properties = properties,
            title = { Text(request.title) },
            text = {
                DialogBody(message = request.message)
            },
            confirmButton = {
                TextButton(onClick = { onResult(DialogResult(DialogChoice.Confirmed)) }) {
                    Text(request.confirmLabel)
                }
            },
        )

        DialogKind.Confirm -> AlertDialog(
            onDismissRequest = dismiss,
            properties = properties,
            title = { Text(request.title) },
            text = {
                DialogBody(message = request.message)
            },
            confirmButton = {
                TextButton(onClick = { onResult(DialogResult(DialogChoice.Confirmed)) }) {
                    Text(request.confirmLabel)
                }
            },
            dismissButton = if (request.dismissible) {
                {
                    TextButton(onClick = { onResult(DialogResult(DialogChoice.Dismissed)) }) {
                        Text(request.dismissLabel)
                    }
                }
            } else {
                null
            },
        )

        DialogKind.Choose -> AlertDialog(
            onDismissRequest = dismiss,
            properties = properties,
            title = { Text(request.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    request.message?.takeIf { it.isNotBlank() }?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                    }
                    request.options.forEachIndexed { index, option ->
                        TextButton(
                            onClick = {
                                onResult(
                                    DialogResult(
                                        DialogChoice.Option,
                                        optionIndex = index,
                                        optionLabel = option,
                                        text = option,
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(option, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = if (request.dismissible) {
                {
                    TextButton(onClick = { onResult(DialogResult(DialogChoice.Dismissed)) }) {
                        Text(request.dismissLabel)
                    }
                }
            } else {
                null
            },
        )

        DialogKind.Input -> {
            var value by remember(request.id) { mutableStateOf(request.initialValue) }
            AlertDialog(
                onDismissRequest = dismiss,
                properties = properties,
                title = { Text(request.title) },
                text = {
                    Column {
                        request.message?.takeIf { it.isNotBlank() }?.let { message ->
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                        }
                        OutlinedTextField(
                            value = value,
                            onValueChange = { if (it.length <= 4_000) value = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = request.placeholder?.takeIf { it.isNotBlank() }?.let {
                                { Text(it) }
                            },
                            maxLines = 6,
                            visualTransformation = if (request.secret) {
                                PasswordVisualTransformation()
                            } else {
                                VisualTransformation.None
                            },
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onResult(DialogResult(DialogChoice.Input, text = value))
                        },
                    ) {
                        Text(request.confirmLabel)
                    }
                },
                dismissButton = if (request.dismissible) {
                    {
                        TextButton(onClick = { onResult(DialogResult(DialogChoice.Dismissed)) }) {
                            Text(request.dismissLabel)
                        }
                    }
                } else {
                    null
                },
            )
        }

        DialogKind.Select -> {
            val lastIndex = request.options.lastIndex.coerceAtLeast(0)
            var selected by remember(request.id) {
                mutableStateOf(request.selectedIndex.coerceIn(0, lastIndex))
            }
            var expanded by remember(request.id) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = dismiss,
                properties = properties,
                title = { Text(request.title) },
                text = {
                    Column {
                        request.message?.takeIf { it.isNotBlank() }?.let { message ->
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                        }
                        Box {
                            OutlinedButton(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = request.options.getOrNull(selected)
                                        ?: request.placeholder
                                        ?: "请选择",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                            ) {
                                request.options.forEachIndexed { index, option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = {
                                            selected = index
                                            expanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val option = request.options.getOrNull(selected)
                            onResult(
                                DialogResult(
                                    DialogChoice.Selected,
                                    optionIndex = selected,
                                    optionLabel = option,
                                    text = option,
                                ),
                            )
                        },
                    ) {
                        Text(request.confirmLabel)
                    }
                },
                dismissButton = if (request.dismissible) {
                    {
                        TextButton(onClick = { onResult(DialogResult(DialogChoice.Dismissed)) }) {
                            Text(request.dismissLabel)
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun DialogBody(message: String?) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = message?.takeIf { it.isNotBlank() } ?: "",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
