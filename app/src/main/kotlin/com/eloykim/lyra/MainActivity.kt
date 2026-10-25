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
        // 仅调试包允许 Agent 操作 Lyra 自身；release 保持硬拒绝
        if (BuildConfig.DEBUG) {
            com.eloykim.lyra.policy.PolicyGuard.setAllowSelfOperation(true)
        }
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
                    AppGraph.commands.run(command, args)
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
