package com.eloykim.lyra.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 语音输入 + 语音播报。
 *
 * 识别优先走端侧 SenseVoice（[OfflineVoiceInput]，模型下载后可用，音频不出设备），
 * 模型不存在时回退系统 SpeechRecognizer；播报用 TextToSpeech。
 * 两者都可能在部分机型上不可用，因此全部做可用性判断。
 */
class VoiceController(
    private val context: Context,
    private val speechLanguage: String = "zh-CN",
    private val offline: OfflineVoiceInput? = null,
) {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var offlineActive = false
    private var offlineResult: ((String) -> Unit)? = null
    private var offlineError: ((String) -> Unit)? = null

    fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** 开始一次识别；回调在主线程。 */
    fun listen(
        onReady: () -> Unit = {},
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (offline != null && offline.isAvailable()) {
            offlineResult = onResult
            offlineError = onError
            if (offline.start(onReady, onResult, onError)) {
                offlineActive = true
                return
            }
            offlineResult = null
            offlineError = null
        }
        if (!isRecognitionAvailable()) {
            onError("此设备没有可用的语音识别引擎")
            return
        }
        recognizer?.destroy()
        val instance = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = instance

        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onReady()
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                onError(errorText(error))
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isBlank()) onError("没有听清，再试一次") else onResult(text)
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, speechLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        instance.startListening(intent)
    }

    fun stopListening() {
        if (offlineActive) {
            offlineActive = false
            val result = offlineResult
            val error = offlineError
            offlineResult = null
            offlineError = null
            offline?.stop(result ?: {}, error ?: {})
            return
        }
        runCatching { recognizer?.stopListening() }
    }

    /** 取消本次识别：不回调结果，用于用户主动关掉按住说话的气泡。 */
    fun cancelListening() {
        if (offlineActive) {
            offlineActive = false
            offlineResult = null
            offlineError = null
            offline?.cancel()
            return
        }
        runCatching { recognizer?.cancel() }
    }

    /** 播报一段话；初始化失败则静默跳过。 */
    fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts ?: TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.forLanguageTag(speechLanguage)
            }
        }.also { tts = it }

        if (ttsReady) {
            engine.language = Locale.forLanguageTag(speechLanguage)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lyra-${System.currentTimeMillis()}")
        }
    }

    fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.shutdown() }
        tts = null
        offline?.release()
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错"
        SpeechRecognizer.ERROR_CLIENT -> "识别客户端出错"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络异常"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到内容"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
        SpeechRecognizer.ERROR_SERVER -> "识别服务出错"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音"
        else -> "识别失败（$code）"
    }
}
