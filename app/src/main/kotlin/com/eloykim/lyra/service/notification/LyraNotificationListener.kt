package com.eloykim.lyra.service.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 系统通知监听服务。
 *
 * 用户必须在「设置 → 通知 → 通知使用权」里授予 Lyra 权限；
 * 未授权时系统不会绑定，[NotificationStore.connected] 为 false。
 */
class LyraNotificationListener : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationStore.attach(applicationContext)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onListenerConnected() {
        NotificationStore.attach(applicationContext)
        NotificationStore.setConnected(true)
        runCatching {
            NotificationStore.replaceAll(activeNotifications?.toList().orEmpty())
        }
    }

    override fun onListenerDisconnected() {
        NotificationStore.setConnected(false)
        NotificationStore.clearActive()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { NotificationStore.onPosted(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let { NotificationStore.onRemoved(it) }
    }

    companion object {

        @Volatile
        private var instance: LyraNotificationListener? = null

        internal fun dismiss(key: String): Boolean {
            val service = instance ?: return false
            return runCatching { service.cancelNotification(key) }.isSuccess
        }

        internal fun dismissAll(keys: Collection<String>): Int {
            val service = instance ?: return 0
            var count = 0
            keys.forEach { key ->
                if (runCatching { service.cancelNotification(key) }.isSuccess) count++
            }
            return count
        }
    }
}
