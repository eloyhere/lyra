package com.eloykim.lyra

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eloykim.lyra.accessibility.AutomationClient
import com.eloykim.lyra.designsystem.LyraTheme
import com.eloykim.lyra.permission.PermissionBroker
import com.eloykim.lyra.service.ui.ServiceUiOverlay
import com.eloykim.lyra.settings.SettingsStore
import com.eloykim.lyra.settings.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var permissionContinuation: CompletableDeferred<Map<String, Boolean>>? = null
    private var allFilesContinuation: CompletableDeferred<Boolean>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionContinuation?.complete(result)
        permissionContinuation = null
    }

    private val allFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        allFilesContinuation?.complete(Environment.isExternalStorageManager())
        allFilesContinuation = null
    }

    private val permissionHost = object : PermissionBroker.Host {
        override suspend fun requestPermissions(
            permissions: List<String>,
        ): Map<String, Boolean> = withContext(Dispatchers.Main) {
            if (permissionContinuation != null) return@withContext emptyMap()
            val deferred = CompletableDeferred<Map<String, Boolean>>()
            permissionContinuation = deferred
            val launched = runCatching {
                permissionLauncher.launch(permissions.toTypedArray())
            }.isSuccess
            if (!launched) {
                permissionContinuation = null
                deferred.complete(emptyMap())
            }
            deferred.await()
        }

        override suspend fun requestAllFilesAccess(): Boolean = withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT < 30) return@withContext false
            if (Environment.isExternalStorageManager()) return@withContext true
            if (allFilesContinuation != null) return@withContext false
            val deferred = CompletableDeferred<Boolean>()
            allFilesContinuation = deferred
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            val launched = runCatching { allFilesLauncher.launch(intent) }.isSuccess
            if (!launched) {
                allFilesContinuation = null
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
                deferred.complete(false)
            }
            deferred.await()
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        val option = com.eloykim.lyra.settings.findLanguage(
            com.eloykim.lyra.settings.SettingsStore.languageIdOf(newBase),
        )
        val locale = java.util.Locale.forLanguageTag(com.eloykim.lyra.settings.resourceTagOf(option.id))
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PermissionBroker.register(permissionHost)
        SettingsStore.init(applicationContext)
        if (BuildConfig.DEBUG) {
            applyDebugIntent(intent)
        }
        // 自身操作默认关闭：只有调试时显式传 self=1 才打开。
        // 首页命令会先经 application.list/launch 打开目标应用，不需要在 Lyra 界面里试。
        AppGraph.init(applicationContext)
        com.eloykim.lyra.voice.OfflineAsrModel.init(applicationContext)
        com.eloykim.lyra.notify.LyraNotifications.init(applicationContext)
        AutomationClient.init(applicationContext)
        setContent {
            val settings by SettingsStore.state.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.System -> systemDark
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            LyraTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColor,
                colorTheme = settings.colorTheme,
            ) {
                Box(Modifier.fillMaxSize()) {
                    LyraApp()
                    ServiceUiOverlay()
                }
            }
        }
    }

    /** debug：把 Intent 里的 task/screen/delay/tokens/interrupts/ms/money 写进 DebugLaunch。 */
    private fun applyDebugIntent(intent: android.content.Intent?) {
        val extra = intent?.getStringExtra("task")
        Log.d("DebugLaunch", "intent task=$extra")
        DebugLaunch.startDelayMs = intent?.getStringExtra("delay")?.toLongOrNull() ?: 0L
        DebugLaunch.budgetTokens = intent?.getStringExtra("tokens")?.toIntOrNull() ?: 60_000
        DebugLaunch.budgetInterrupts = intent?.getStringExtra("interrupts")?.toIntOrNull() ?: 3
        DebugLaunch.budgetMs = intent?.getStringExtra("ms")?.toLongOrNull() ?: 300_000L
        DebugLaunch.budgetMoneyMicros = intent?.getStringExtra("money")?.toLongOrNull() ?: 5_000_000L
        DebugLaunch.compactKeep = intent?.getStringExtra("compact")?.toIntOrNull() ?: 0
        DebugLaunch.focusProbe = intent?.getStringExtra("focus") == "1"
        DebugLaunch.allowSelfObservation = intent?.getStringExtra("self") == "1"
        if (BuildConfig.DEBUG) {
            com.eloykim.lyra.policy.PolicyGuard.setAllowSelfOperation(DebugLaunch.allowSelfObservation)
        }
        DebugLaunch.screen.value = intent?.getStringExtra("screen")
        DebugLaunch.task.value = extra
        intent?.getStringExtra("asr_wav")?.let { path -> runAsrDebug(path) }
        intent?.getStringExtra("gsend")?.let { text ->
            Thread {
                runCatching { Thread.sleep(1_000) }
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        val sid = AppGraph.history.groupChat.latestSession()?.id ?: return@runBlocking
                        val added = com.eloykim.lyra.group.GroupOrchestrator.send(sid, text)
                        Log.i("GroupDebug", "gsend added=$added text=$text")
                    }
                }.onFailure { Log.e("GroupDebug", "gsend failed", it) }
            }.start()
        }
        runServiceDebug(intent)
        runCommandDebug(intent)
    }

    /** debug：执行任意手机服务命令并把结果打到 logcat。 */
    private fun runCommandDebug(intent: android.content.Intent?) {
        val command = intent?.getStringExtra("command") ?: return
        val rawArgs = intent.getStringExtra("args") ?: "{}"
        Thread {
            runCatching { Thread.sleep(1_000) }

            val result = runCatching {
                val args = runCatching { org.json.JSONObject(rawArgs) }
                    .getOrDefault(org.json.JSONObject())
                kotlinx.coroutines.runBlocking {
                    if (command == "amp.appendTest") {
                        val event = AppGraph.history.ledger.append(
                            actor = "debug",
                            type = "test",
                            payload = """{"ok":true,"probe":"amp"}""",
                            refs = listOf("frame:f.1"),
                            at = System.currentTimeMillis(),
                        )
                        com.eloykim.lyra.service.ServiceResult(
                            true,
                            "ok",
                            "appended #${event.seq}",
                            org.json.JSONObject()
                                .put("seq", event.seq)
                                .put("digest", event.digest)
                                .put("hash", event.hash),
                        )
                    } else if (command == "amp.frameTest") {
                        val goal = com.eloykim.lyra.amp.protocol.types.Ref(
                            com.eloykim.lyra.amp.protocol.types.RefKind.GOAL,
                            "g.test",
                            "调试帧",
                        )
                        val refs = com.eloykim.lyra.amp.protocol.boundary.RefTable(
                            com.eloykim.lyra.amp.protocol.boundary.RefScope.RUN,
                            "a.lyra",
                        )
                            .add(com.eloykim.lyra.amp.protocol.types.RefKind.GOAL, goal.id, null)
                            .add(com.eloykim.lyra.amp.protocol.types.RefKind.TOOL, "automation.observe", null)
                        val frame = com.eloykim.lyra.amp.protocol.boundary.frameFrom(
                            com.eloykim.lyra.amp.protocol.boundary.FrameDraft(
                                id = "f.debug",
                                agent = "a.lyra",
                                cycle = 1,
                                ts = com.eloykim.lyra.amp.protocol.types.nowTimestamp(),
                                mode = com.eloykim.lyra.amp.protocol.frame.FrameMode.EXPLOIT,
                                goal = goal,
                                slots = listOf(
                                    com.eloykim.lyra.amp.protocol.frame.Slot(
                                        target = com.eloykim.lyra.amp.protocol.types.Ref(
                                            com.eloykim.lyra.amp.protocol.types.RefKind.ENTITY,
                                            "ui.1",
                                            "调试节点",
                                        ),
                                        role = com.eloykim.lyra.amp.protocol.frame.SlotRole.EVIDENCE,
                                        salience = 0.8,
                                        cost = com.eloykim.lyra.amp.protocol.types.Budget(tokens = 10, calls = 1),
                                        why = "调试帧验证 canonical Frame 落库",
                                        state = com.eloykim.lyra.amp.protocol.frame.SlotState.ACTIVE,
                                    ),
                                ),
                                budget = com.eloykim.lyra.amp.protocol.frame.FrameBudget(
                                    allocated = com.eloykim.lyra.amp.protocol.types.Budget(tokens = 100),
                                    spent = com.eloykim.lyra.amp.protocol.types.Budget(tokens = 10, calls = 1),
                                ),
                            ),
                            refs,
                        ).frame
                        AppGraph.history.frames.append(0, frame)
                        com.eloykim.lyra.service.ServiceResult(
                            true,
                            "ok",
                            "frame ${frame.id}",
                            org.json.JSONObject().put("digest", frame.digest),
                        )
                    } else {
                        AppGraph.commands.run(command, args)
                    }
                }
            }.getOrElse {
                com.eloykim.lyra.service.ServiceResult(
                    false,
                    "debug_error",
                    it.message ?: "命令执行失败",
                )
            }
            Log.i(
                "CommandDebug",
                "command=$command ok=${result.ok} code=${result.code} " +
                    "message=${result.message.take(1_500)} " +
                    "text=${com.eloykim.lyra.service.ServiceText.format(result).take(3_000)} " +
                    "data=${result.data.toString().take(4_000)}",
            )
        }.start()
    }

    /** debug：直接调用 Agent 服务端口并把结果打到 logcat（不经过模型）。 */
    private fun runServiceDebug(intent: android.content.Intent?) {
        val service = intent?.getStringExtra("service") ?: return
        val action = intent.getStringExtra("action") ?: "list"
        val arg = intent.getStringExtra("arg")
        if (service == "hub") {
            Thread {
                runCatching { Thread.sleep(800) }
                runCatching {
                    val hub = com.eloykim.lyra.service.PhoneServices.create(this, AppGraph.history)
                    val svc = intent.getStringExtra("svc") ?: "groupchat"
                    // 参数可以从文件读，绕开 adb shell 的引号转义
                    val rawArgs = intent.getStringExtra("argfile")?.let { path ->
                        runCatching { java.io.File(path).readText() }.getOrNull()
                    } ?: intent.getStringExtra("arg") ?: "{}"
                    val args = runCatching { org.json.JSONObject(rawArgs) }.getOrElse {
                        Log.e("ServiceDebug", "bad args: $rawArgs", it)
                        org.json.JSONObject()
                    }
                    val r = kotlinx.coroutines.runBlocking { hub.call(svc, action, args) }
                    Log.i(
                        "ServiceDebug",
                        "hub=$svc.$action ok=${r.ok} code=${r.code} message=${r.message} data=${r.data}",
                    )
                }
            }.start()
            return
        }
        Thread {
            runCatching { Thread.sleep(1_000) }

            val result = runCatching {
                kotlinx.coroutines.runBlocking {
                    when (service) {
                        "application" -> com.eloykim.lyra.service.agent.CommandAppCatalog(
                            AppGraph.commands,
                        ).handle(action, arg)

                        "voice" -> com.eloykim.lyra.service.agent.CommandVoiceService(
                            AppGraph.commands,
                        ).handle(action, arg)

                        else -> com.eloykim.lyra.agent.ServiceResult(
                            false,
                            "unknown_service",
                            "未知服务：$service",
                        )
                    }
                }
            }.getOrElse {
                com.eloykim.lyra.agent.ServiceResult(false, "error", it.message ?: "调用失败")
            }
            Log.i(
                "ServiceDebug",
                "service=$service action=$action ok=${result.ok} code=${result.code} " +
                    "message=${result.message.take(2_000)}",
            )
        }.start()
    }

    /** debug：不经过麦克风，直接解码一个 WAV，把结果打到 logcat。 */
    private fun runAsrDebug(path: String) {
        Thread {
            val result = runCatching {
                val input = com.eloykim.lyra.voice.OfflineVoiceInput(applicationContext)
                try {
                    input.transcribeWav(java.io.File(path))
                } finally {
                    input.release()
                }
            }.getOrElse { "ERR: ${it.message}" }
            Log.i("AsrDebug", "wav=$path result=$result")
        }.start()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BuildConfig.DEBUG) {
            applyDebugIntent(intent)
        }
    }

    override fun onDestroy() {
        PermissionBroker.unregister(permissionHost)
        AutomationClient.release()
        super.onDestroy()
    }
}
