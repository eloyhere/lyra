package com.eloykim.lyra.service.alarm

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optBoolOr
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqInt
import org.json.JSONObject

/**
 * 闹钟/计时器服务：通过系统 Clock Intent 设置，不依赖任何私有 API。
 */
class AlarmService(private val context: Context) : BasePhoneService() {

    override val id: String = "alarm"
    override val title: String = "闹钟"
    override val description: String = "设置闹钟、计时器，或打开系统闹钟列表。"

    init {
        register(
            ServiceMethod(
                name = "setAlarm",
                summary = "设置一个闹钟",
                parameters = Schema.params(
                    "hour" to Schema.integer("小时 0–23", 0, 23),
                    "minute" to Schema.integer("分钟 0–59", 0, 59),
                    "message" to Schema.string("闹钟标签"),
                    "skipUi" to Schema.boolean("是否跳过时钟界面直接设置；默认 true"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            val hour = args.reqInt("hour")
            val minute = args.reqInt("minute")
            if (hour !in 0..23) throw IllegalArgumentException("hour 需要在 0–23")
            if (minute !in 0..59) throw IllegalArgumentException("minute 需要在 0–59")
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, args.optStringOr("message"))
                putExtra(AlarmClock.EXTRA_SKIP_UI, args.optBoolOr("skipUi", true))
            }
            start(intent, "clock")
        }

        register(
            ServiceMethod(
                name = "setTimer",
                summary = "设置倒计时",
                parameters = Schema.params(
                    "seconds" to Schema.integer("秒数", 1, 86_400),
                    "message" to Schema.string("计时器标签"),
                    "skipUi" to Schema.boolean("是否跳过时钟界面；默认 true"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            val seconds = args.reqInt("seconds")
            if (seconds <= 0) throw IllegalArgumentException("seconds 需要大于 0")
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, args.optStringOr("message"))
                putExtra(AlarmClock.EXTRA_SKIP_UI, args.optBoolOr("skipUi", true))
            }
            start(intent, "clock")
        }

        register(
            ServiceMethod(
                name = "showAlarms",
                summary = "打开系统闹钟列表",
                parameters = Schema.params(),
                risk = ServiceRisk.CONTROL,
            ),
        ) { start(Intent(AlarmClock.ACTION_SHOW_ALARMS), "clock") }

        register(
            ServiceMethod(
                name = "status",
                summary = "设备是否有时钟应用",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) {
            ServiceResult.ok(
                "ok",
                JSONObject()
                    .put("alarm", resolvable(Intent(AlarmClock.ACTION_SET_ALARM)))
                    .put("timer", resolvable(Intent(AlarmClock.ACTION_SET_TIMER)))
                    .put("showAlarms", resolvable(Intent(AlarmClock.ACTION_SHOW_ALARMS))),
            )
        }
    }

    private fun start(intent: Intent, target: String): ServiceResult {
        if (!resolvable(intent)) {
            return ServiceResult.fail("no_app", "设备上没有可用的时钟应用")
        }
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ServiceResult.ok(
                "started",
                JSONObject().put("target", target).put("message", intent.getStringExtra(AlarmClock.EXTRA_MESSAGE)),
            )
        }.getOrElse {
            ServiceResult.fail("start_failed", it.message ?: "无法打开时钟应用")
        }
    }

    private fun resolvable(intent: Intent): Boolean {
        val pm = context.packageManager
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0)) != null
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0) != null
        }
    }
}
