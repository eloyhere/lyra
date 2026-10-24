package com.eloykim.lyra.service.device

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import android.view.Surface
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import org.json.JSONObject

/**
 * 设备服务：电池、音量、屏幕/锁屏状态。
 *
 * 网络归 NetworkService；这里不做系统设置写入，音量控制直接走 AudioManager。
 */
class DeviceService(private val context: Context) : BasePhoneService() {

    override val id: String = "device"
    override val title: String = "设备"
    override val description: String =
        "读取电池、屏幕/锁屏状态，以及读写各音频流音量。"

    private val audio: AudioManager? get() = context.getSystemService(AudioManager::class.java)

    init {
        register(
            ServiceMethod(
                name = "status",
                summary = "设备/系统/电池/网络/屏幕概览",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { status() }

        register(
            ServiceMethod(
                name = "battery",
                summary = "电量、充电状态、温度、电压",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { battery() }

        register(
            ServiceMethod(
                name = "volume",
                summary = "读取/设置/调整/静音音频流音量",
                parameters = Schema.params(
                    "stream" to Schema.string(
                        "音频流",
                        listOf(
                            "music", "ring", "notification", "alarm",
                            "system", "voice", "accessibility",
                        ),
                    ),
                    "action" to Schema.string(
                        "动作",
                        listOf("get", "set", "up", "down", "mute", "unmute"),
                    ),
                    "value" to Schema.integer("set 时的目标音量", 0, 100),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            volume(
                stream = args.optStringOr("stream", "music"),
                action = args.optStringOr("action", "get"),
                value = args.optIntOr("value", -1),
            )
        }

        register(
            ServiceMethod(
                name = "screen",
                summary = "屏幕分辨率/旋转/亮屏/锁屏状态",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { screen() }
    }

    private fun status(): ServiceResult = ServiceResult.ok(
        "ok",
        JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("android", Build.VERSION.RELEASE)
            .put("battery", battery().data)
            .put("screen", screen().data),
    )

    private fun battery(): ServiceResult {
        val manager = context.getSystemService(BatteryManager::class.java)
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?: if (level >= 0 && scale > 0) level * 100 / scale else -1
        val temperature = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.takeIf { it > 0 }?.div(10.0)
        val voltage = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return ServiceResult.ok(
            "ok",
            JSONObject()
                .put("percent", percent)
                .put("charging", manager?.isCharging ?: (status == BatteryManager.BATTERY_STATUS_CHARGING))
                .put("status", batteryStatus(status))
                .put("temperatureC", temperature)
                .put("voltageMv", voltage),
        )
    }

    private fun volume(stream: String, action: String, value: Int): ServiceResult {
        val manager = audio ?: return ServiceResult.fail("audio_unavailable", "设备没有音频服务")
        val streamId = streamId(stream)
            ?: return ServiceResult.fail("bad_args", "未知音频流：$stream")
        return runCatching {
            when (action.lowercase()) {
                "up" -> manager.adjustStreamVolume(streamId, AudioManager.ADJUST_RAISE, 0)
                "down" -> manager.adjustStreamVolume(streamId, AudioManager.ADJUST_LOWER, 0)
                "mute" -> manager.adjustStreamVolume(streamId, AudioManager.ADJUST_MUTE, 0)
                "unmute" -> manager.adjustStreamVolume(streamId, AudioManager.ADJUST_UNMUTE, 0)
                "set" -> {
                    val max = manager.getStreamMaxVolume(streamId)
                    val target = value.coerceIn(0, max)
                    manager.setStreamVolume(streamId, target, 0)
                }
                "get" -> Unit
                else -> throw IllegalArgumentException("action 只支持 get/set/up/down/mute/unmute")
            }
            val level = manager.getStreamVolume(streamId)
            val max = manager.getStreamMaxVolume(streamId)
            ServiceResult.ok(
                "ok",
                JSONObject()
                    .put("stream", stream)
                    .put("level", level)
                    .put("max", max)
                    .put("percent", if (max > 0) level * 100 / max else 0)
                    .put("muted", manager.isStreamMute(streamId)),
            )
        }.getOrElse {
            ServiceResult.fail("volume_failed", it.message ?: "音量操作失败")
        }
    }

    private fun screen(): ServiceResult {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = context.resources.displayMetrics
        val rotation = when (display?.rotation) {
            Surface.ROTATION_0 -> "portrait"
            Surface.ROTATION_90 -> "landscape"
            Surface.ROTATION_180 -> "portrait_reverse"
            Surface.ROTATION_270 -> "landscape_reverse"
            else -> "unknown"
        }
        return ServiceResult.ok(
            "ok",
            JSONObject()
                .put("interactive", power?.isInteractive ?: false)
                .put("locked", keyguard?.isKeyguardLocked ?: false)
                .put("widthPixels", metrics.widthPixels)
                .put("heightPixels", metrics.heightPixels)
                .put("densityDpi", metrics.densityDpi)
                .put("rotation", rotation),
        )
    }

    private fun streamId(stream: String): Int? = when (stream.lowercase()) {
        "music" -> AudioManager.STREAM_MUSIC
        "ring" -> AudioManager.STREAM_RING
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "alarm" -> AudioManager.STREAM_ALARM
        "system" -> AudioManager.STREAM_SYSTEM
        "voice" -> AudioManager.STREAM_VOICE_CALL
        "accessibility" -> AudioManager.STREAM_ACCESSIBILITY
        else -> null
    }

    private fun batteryStatus(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }
}

