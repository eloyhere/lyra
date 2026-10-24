package com.eloykim.lyra.service.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optBoolOr
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqString
import com.eloykim.lyra.voice.OfflineAsrModel
import com.eloykim.lyra.voice.OfflineVoiceInput
import com.eloykim.lyra.voice.VoiceController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale

/**
 * 语音服务：TTS 播报 + 一次听写。
 *
 * 听写优先用端侧 SenseVoice（[OfflineVoiceInput]），模型未下载时回退系统
 * SpeechRecognizer；设备没有可用引擎时明确返回 `asr_unavailable`。
 * 播报用独立的 TextToSpeech，可等待播完再返回，便于 Agent 串行执行。
 */
class VoiceService(
    context: Context,
    private val speechLanguage: String = "zh-CN",
) : BasePhoneService() {

    override fun requiredPermissions(): List<String> =
        listOf(android.Manifest.permission.RECORD_AUDIO)

    override val id: String = "voice"
    override val title: String = "语音"
    override val description: String =
        "朗读一段文字（TTS），或听写一次语音并返回文本（端侧优先，系统兜底）。"

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val speakMutex = Mutex()
    private val listenMutex = Mutex()
    private val ttsLock = Mutex()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsStatus: Int = TextToSpeech.ERROR

    @Volatile
    private var controllerInstance: VoiceController? = null

    init {
        register(
            ServiceMethod(
                name = "speak",
                summary = "朗读文字；wait=true 时等播完再返回",
                parameters = Schema.params(
                    "text" to Schema.string("要朗读的文字"),
                    "language" to Schema.string("BCP-47 语言，例如 zh-CN / en-US"),
                    "wait" to Schema.boolean("是否等待播报完成；默认 true"),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args ->
            speak(
                text = args.reqString("text").take(1_000),
                language = args.optStringOr("language", speechLanguage),
                wait = args.optBoolOr("wait", true),
            )
        }

        register(
            ServiceMethod(
                name = "listen",
                summary = "听写一次语音并返回文本；端侧模型缺失时走系统识别",
                parameters = Schema.params(
                    "timeoutMs" to Schema.integer("最长等待时间", 1_000, 60_000),
                    "durationMs" to Schema.integer("端侧录音时长", 1_000, 30_000),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args ->
            listen(
                timeoutMs = args.optIntOr("timeoutMs", 15_000),
                durationMs = args.optIntOr("durationMs", 6_000),
            )
        }

        register(
            ServiceMethod(
                name = "stop",
                summary = "停止当前播报",
                parameters = Schema.params(),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) {
            tts?.stop()
            ServiceResult.ok("stopped")
        }

        register(
            ServiceMethod(
                name = "status",
                summary = "TTS / 系统识别 / 端侧模型的可用性",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { status() }
    }

    private suspend fun speak(text: String, language: String, wait: Boolean): ServiceResult =
        speakMutex.withLock {
            val engine = ensureTts()
                ?: return@withLock ServiceResult.fail(
                    "tts_unavailable",
                    "设备没有可用的 TTS 引擎，或初始化失败",
                )
            withContext(Dispatchers.Main.immediate) {
                val locale = Locale.forLanguageTag(language)
                val languageResult = engine.setLanguage(locale)
                if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    return@withContext ServiceResult.fail(
                        "tts_language_unsupported",
                        "TTS 不支持语言 $language",
                    )
                }
                if (!wait) {
                    val queued = engine.speak(
                        text,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "lyra-voice-${System.currentTimeMillis()}",
                    )
                    return@withContext if (queued == TextToSpeech.SUCCESS) {
                        ServiceResult.ok(
                            "speaking",
                            JSONObject()
                                .put("text", text)
                                .put("language", language)
                                .put("waited", false),
                        )
                    } else {
                        ServiceResult.fail("tts_error", "无法开始播报")
                    }
                }

                val done = CompletableDeferred<ServiceResult>()
                engine.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            if (!done.isCompleted) {
                                done.complete(
                                    ServiceResult.ok(
                                        "spoken",
                                        JSONObject()
                                            .put("text", text)
                                            .put("language", language)
                                            .put("waited", true),
                                    ),
                                )
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (!done.isCompleted) {
                                done.complete(
                                    ServiceResult.fail("tts_error", "播报失败"),
                                )
                            }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            if (!done.isCompleted) {
                                done.complete(
                                    ServiceResult.fail("tts_error", "播报失败（$errorCode）"),
                                )
                            }
                        }
                    },
                )
                val utteranceId = "lyra-voice-${System.currentTimeMillis()}"
                val queued = engine.speak(
                    text,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    utteranceId,
                )
                if (queued != TextToSpeech.SUCCESS) {
                    return@withContext ServiceResult.fail("tts_error", "无法开始播报")
                }
                val timeout = (text.length * 120L + 5_000L).coerceIn(10_000L, 60_000L)
                withTimeoutOrNull(timeout) { done.await() }
                    ?: ServiceResult.fail("timeout", "播报超时（可能仍在播放）")
            }
        }

    private suspend fun listen(timeoutMs: Int, durationMs: Int): ServiceResult {
        if (!hasRecordPermission()) {
            return ServiceResult.fail(
                "permission_required",
                "需要麦克风权限（RECORD_AUDIO）",
            )
        }
        if (!listenMutex.tryLock()) {
            return ServiceResult.fail("busy", "已经在听写，请稍后再试")
        }
        try {
            val offlineReady = OfflineAsrModel.isReady(appContext)
            val controller = controller()
            if (!offlineReady && !controller.isRecognitionAvailable()) {
                return ServiceResult.fail(
                    "asr_unavailable",
                    "设备没有可用的语音识别引擎，且端侧模型未下载",
                )
            }

            val done = CompletableDeferred<ServiceResult>()
            val stopJob = scope.launch {
                delay(durationMs.coerceIn(1_000, 30_000).toLong())
                if (offlineReady) {
                    runCatching { controller.stopListening() }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                controller.listen(
                    onReady = {},
                    onResult = { text ->
                        if (!done.isCompleted) {
                            done.complete(
                                ServiceResult.ok(
                                    "heard",
                                    JSONObject()
                                        .put("text", text)
                                        .put("engine", if (offlineReady) "offline" else "system"),
                                ),
                            )
                        }
                    },
                    onError = { message ->
                        if (!done.isCompleted) {
                            done.complete(ServiceResult.fail("asr_error", message))
                        }
                    },
                )
            }

            return try {
                withTimeoutOrNull(timeoutMs.coerceIn(1_000, 60_000).toLong()) {
                    done.await()
                } ?: ServiceResult.fail("timeout", "没有听到语音")
            } finally {
                stopJob.cancel()
                if (!done.isCompleted) {
                    runCatching { controller.cancelListening() }
                }
            }
        } finally {
            listenMutex.unlock()
        }
    }

    private fun status(): ServiceResult {
        val controller = controller()
        val offlineReady = OfflineAsrModel.isReady(appContext)
        return ServiceResult.ok(
            "ok",
            JSONObject()
                .put("recordPermission", hasRecordPermission())
                .put("systemAsr", controller.isRecognitionAvailable())
                .put("offlineAsr", offlineReady)
                .put("ttsReady", ttsStatus == TextToSpeech.SUCCESS)
                .put("ttsInitialized", tts != null)
                .put("language", speechLanguage),
        )
    }

    private fun controller(): VoiceController =
        controllerInstance ?: VoiceController(
            appContext,
            speechLanguage,
            OfflineVoiceInput(appContext),
        ).also { controllerInstance = it }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun ensureTts(): TextToSpeech? = ttsLock.withLock {
        tts?.let { existing ->
            if (ttsStatus == TextToSpeech.SUCCESS) return@withLock existing
        }
        val ready = CompletableDeferred<Int>()
        val engine = withContext(Dispatchers.Main.immediate) {
            TextToSpeech(appContext) { status ->
                ttsStatus = status
                if (!ready.isCompleted) ready.complete(status)
            }.also { tts = it }
        }
        val status = withTimeoutOrNull(8_000) { ready.await() } ?: TextToSpeech.ERROR
        ttsStatus = status
        if (status == TextToSpeech.SUCCESS) engine else null
    }

    override fun close() {
        scope.cancel()
        runCatching { controllerInstance?.cancelListening() }
        runCatching { controllerInstance?.release() }
        controllerInstance = null
        runCatching { tts?.shutdown() }
        tts = null
        ttsStatus = TextToSpeech.ERROR
    }
}
