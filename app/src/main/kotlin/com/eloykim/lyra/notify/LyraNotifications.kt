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
 * 通知投递的薄封装；级别定义归服务目录（[com.eloykim.lyra.service.notification.NoticeLevel]）。
 *
 * 级别与渠道一一对应，用户可以在系统设置里单独关掉「好奇」而保留「安全」——
 * 这比一个 total switch 更符合 AMP 的注意力预算思路。
 */
typealias NoticeLevel = com.eloykim.lyra.service.notification.NoticeLevel

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
