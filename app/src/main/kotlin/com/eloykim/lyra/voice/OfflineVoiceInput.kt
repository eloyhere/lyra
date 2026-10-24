package com.eloykim.lyra.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 完全离线的「按住说话」识别（sherpa-onnx + SenseVoiceSmall）。
 *
 * 录音与解码都在本地：音频不落盘、不出设备；模型未下载时 [isAvailable] 为 false，
 * 调用方应回退到系统 SpeechRecognizer。
 */
class OfflineVoiceInput(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = PcmRecorder()
    @Volatile private var recording = false
    private var recognizer: OfflineRecognizer? = null

    fun isAvailable(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED && OfflineAsrModel.isReady(context)

    /** 返回 true 表示已接管本次识别；false 时调用方可以走系统识别。 */
    fun start(onReady: () -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit): Boolean {
        if (!isAvailable()) return false
        if (!recorder.start()) {
            onError("无法启动麦克风，请检查是否被其他应用占用")
            return true
        }
        recording = true
        onReady()
        return true
    }

    fun stop(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!recording) return
        recording = false
        val pcm = recorder.stop()
        if (pcm == null || pcm.size < 6400) { // < 0.2s
            onError("说话时间太短，再按住说一次")
            return
        }
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching { decode(pcm) }
            }
            outcome.onSuccess { text ->
                if (text.isBlank()) onError("没有识别到内容") else onResult(text)
            }.onFailure { e ->
                onError("离线识别失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun cancel() {
        if (!recording) return
        recording = false
        recorder.cancel()
    }

    /** 调试用：解码一个 16 kHz PCM WAV，返回文本；不经过麦克风。 */
    fun transcribeWav(file: File): String {
        val bytes = file.readBytes()
        val pcm = if (bytes.size > 44 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) {
            bytes.copyOfRange(44, bytes.size)
        } else {
            bytes
        }
        return decode(pcm)
    }

    fun release() {
        cancel()
        scope.cancel()
        runCatching { recognizer?.release() }
        recognizer = null
    }

    private fun decode(pcm: ByteArray): String {
        val engine = ensureRecognizer()
        val samples = FloatArray(pcm.size / 2) { i ->
            val low = pcm[i * 2].toInt() and 0xFF
            val high = pcm[i * 2 + 1].toInt() shl 8
            ((high or low).toShort()) / 32768.0f
        }
        val stream = engine.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            engine.decode(stream)
            engine.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun ensureRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }
        val dir = OfflineAsrModel.modelDir(context)
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(dir, "model.int8.onnx").absolutePath,
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )
        return OfflineRecognizer(null, config).also { recognizer = it }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/** 16 kHz / 单声道 / PCM16 录音，累计到内存，停止时一次交出去。 */
private class PcmRecorder {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val buffer = ByteArrayOutputStream()
    @Volatile private var running = false

    @SuppressLint("MissingPermission") // 调用方已检查 RECORD_AUDIO
    fun start(): Boolean {
        cancel()
        val minBuffer = AudioRecord.getMinBufferSize(
            OfflineVoiceInput.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val instance = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                OfflineVoiceInput.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, OfflineVoiceInput.SAMPLE_RATE),
            )
        }.getOrNull() ?: return false
        if (instance.state != AudioRecord.STATE_INITIALIZED) {
            instance.release()
            return false
        }
        buffer.reset()
        running = true
        val started = runCatching { instance.startRecording() }.isSuccess
        if (!started) {
            running = false
            instance.release()
            return false
        }
        record = instance
        thread = Thread {
            val chunk = ByteArray(3200) // 100 ms
            while (running) {
                val read = runCatching { instance.read(chunk, 0, chunk.size) }.getOrDefault(-1)
                if (read > 0) {
                    synchronized(buffer) { buffer.write(chunk, 0, read) }
                } else if (read < 0) {
                    break
                }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
        return true
    }

    fun stop(): ByteArray? {
        val instance = record ?: return null
        running = false
        thread?.join(400)
        thread = null
        record = null
        runCatching { instance.stop() }
        instance.release()
        val pcm = synchronized(buffer) { buffer.toByteArray() }
        buffer.reset()
        return pcm.takeIf { it.isNotEmpty() }
    }

    fun cancel() {
        val instance = record ?: return
        running = false
        thread?.join(200)
        thread = null
        record = null
        runCatching { instance.stop() }
        instance.release()
        synchronized(buffer) { buffer.reset() }
    }
}
