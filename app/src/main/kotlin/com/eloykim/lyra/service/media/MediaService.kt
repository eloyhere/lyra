package com.eloykim.lyra.service.media

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optIntOr
import org.json.JSONObject

/**
 * 媒体服务：通过媒体键控制当前播放器，以及查询/设置媒体音量。
 *
 * 不直接持有任何播放器；播放/暂停交给系统媒体会话，因此对音乐、播客、
 * 视频 App 都通用。
 */
class MediaService(context: Context) : BasePhoneService() {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override val id: String = "media"
    override val title: String = "媒体"
    override val description: String =
        "控制当前媒体播放（播放/暂停/上一首/下一首/停止）与媒体音量。"

    init {
        register(
            ServiceMethod(
                name = "status",
                summary = "当前是否在播放，以及媒体音量",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { ServiceResult.ok("媒体状态", statusJson()) }

        listOf(
            "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
            "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
            "toggle" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
            "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
        ).forEach { (name, keyCode) ->
            register(
                ServiceMethod(
                    name = name,
                    summary = "发送媒体键 $name",
                    parameters = Schema.params(),
                    risk = ServiceRisk.CONTROL,
                ),
            ) { dispatchMediaKey(keyCode) }
        }

        register(
            ServiceMethod(
                name = "volume",
                summary = "读取或设置媒体音量（不传 level 则读取）",
                parameters = Schema.params(
                    "level" to Schema.integer("目标音量"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            val level = args.optIntOr("level", -1)
            if (level < 0) {
                ServiceResult.ok("媒体音量", statusJson())
            } else {
                setVolume(level)
            }
        }
    }

    private fun dispatchMediaKey(keyCode: Int): ServiceResult {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0),
        )
        audio.dispatchMediaKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0),
        )
        return ServiceResult.ok("媒体键已发送", statusJson())
    }

    private fun setVolume(level: Int): ServiceResult {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = level.coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return ServiceResult.ok(
            "媒体音量已设为 $target/$max",
            statusJson(),
        )
    }

    private fun statusJson(): JSONObject {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return JSONObject()
            .put("playing", audio.isMusicActive)
            .put("volume", current)
            .put("maxVolume", max)
    }
}
