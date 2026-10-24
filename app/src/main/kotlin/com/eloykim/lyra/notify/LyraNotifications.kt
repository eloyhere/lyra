package com.eloykim.lyra.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 系统通知服务：按 AMP §5.5 的打断级别分渠道推送。
 *
 * 级别与渠道一一对应，用户可以在系统设置里单独关掉「好奇」而保留「安全」——
 * 这比一个 total switch 更符合 AMP 的注意力预算思路。
 */
enum class NoticeLevel(val channelId: String, val importance: Int, val title: String) {
    /** P0 安全：伤害、数据泄露、不可逆破坏。 */
    SAFETY("lyra_safety", NotificationManager.IMPORTANCE_HIGH, "安全提醒"),

    /** P1 承诺：需要用户裁决，否则任务停在这里。 */
    COMMITMENT("lyra_commitment", NotificationManager.IMPORTANCE_HIGH, "需要你的确认"),

    /** P2 机会：有结果或分叉点，可以稍后处理。 */
    OPPORTUNITY("lyra_opportunity", NotificationManager.IMPORTANCE_DEFAULT, "任务进展"),

    /** P3 好奇：锦上添花，不打扰。 */
    CURIOSITY("lyra_curiosity", NotificationManager.IMPORTANCE_LOW, "顺便一提"),

    /** P4 静默：只进通知栏，不出声、不弹。 */
    SILENT("lyra_silent", NotificationManager.IMPORTANCE_MIN, "记录"),
}

object LyraNotifications {

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        createChannels(context.applicationContext)
    }

    /** 是否具备发通知的权限（Android 13+ 需要运行时授权）。 */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun post(
        level: NoticeLevel,
        message: String,
        title: String? = null,
        tag: String = "lyra",
        id: Int = level.ordinal + 1,
    ) {
        val context = appContext ?: return
        if (!canPost(context)) return

        val notification = NotificationCompat.Builder(context, level.channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title ?: level.title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(priorityOf(level))
            .setAutoCancel(true)
            .setOnlyAlertOnce(level != NoticeLevel.SAFETY)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        }
    }

    fun cancel(tag: String = "lyra", id: Int) {
        appContext?.let { runCatching { NotificationManagerCompat.from(it).cancel(tag, id) } }
    }

    private fun priorityOf(level: NoticeLevel): Int = when (level) {
        NoticeLevel.SAFETY, NoticeLevel.COMMITMENT -> NotificationCompat.PRIORITY_HIGH
        NoticeLevel.OPPORTUNITY -> NotificationCompat.PRIORITY_DEFAULT
        NoticeLevel.CURIOSITY -> NotificationCompat.PRIORITY_LOW
        NoticeLevel.SILENT -> NotificationCompat.PRIORITY_MIN
    }

    private fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        NoticeLevel.entries.forEach { level ->
            val channel = NotificationChannel(level.channelId, level.title, level.importance).apply {
                description = when (level) {
                    NoticeLevel.SAFETY -> "支付、凭证、数据泄露等需要立刻知道的事"
                    NoticeLevel.COMMITMENT -> "任务卡住，等你决定"
                    NoticeLevel.OPPORTUNITY -> "任务完成或有结果"
                    NoticeLevel.CURIOSITY -> "可选的信息"
                    NoticeLevel.SILENT -> "只记录，不打扰"
                }
                setShowBadge(level != NoticeLevel.SILENT)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
