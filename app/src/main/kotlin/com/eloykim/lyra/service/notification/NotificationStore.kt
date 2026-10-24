package com.eloykim.lyra.service.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 通知的内存缓存。
 *
 * 系统只允许持有「通知使用权」的应用读取通知；监听服务连接后这里维护：
 * - 当前活动通知（按 key 去重）；
 * - 最近消失的 100 条（供模型理解「刚刚发生了什么」）。
 */
object NotificationStore {

    data class Record(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val subText: String,
        val category: String,
        val channelId: String,
        val groupKey: String?,
        val postTime: Long,
        val ongoing: Boolean,
        val clearable: Boolean,
        val actions: List<String>,
        val contentIntent: PendingIntent? = null,
    ) {

        fun toJson(): JSONObject = JSONObject()
            .put("key", key)
            .put("package", packageName)
            .put("app", appLabel)
            .put("title", title)
            .put("text", text)
            .put("subText", subText)
            .put("category", category)
            .put("channel", channelId)
            .put("group", groupKey)
            .put("postTime", postTime)
            .put("ongoing", ongoing)
            .put("clearable", clearable)
            .put("actions", JSONArray(actions))

        fun matches(query: String): Boolean {
            if (query.isBlank()) return true
            val needle = query.trim()
            return title.contains(needle, ignoreCase = true) ||
                text.contains(needle, ignoreCase = true) ||
                appLabel.contains(needle, ignoreCase = true) ||
                packageName.contains(needle, ignoreCase = true)
        }
    }

    private const val HISTORY_LIMIT = 100

    @Volatile
    private var appContext: Context? = null

    private val labels = ConcurrentHashMap<String, String>()
    private val active = LinkedHashMap<String, Record>()
    private val history = ArrayDeque<Record>()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _activeRecords = MutableStateFlow<List<Record>>(emptyList())
    val activeRecords: StateFlow<List<Record>> = _activeRecords.asStateFlow()

    private val _historyRecords = MutableStateFlow<List<Record>>(emptyList())
    val historyRecords: StateFlow<List<Record>> = _historyRecords.asStateFlow()

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    @Synchronized
    fun setConnected(value: Boolean) {
        _connected.value = value
    }

    @Synchronized
    fun replaceAll(notifications: List<StatusBarNotification>) {
        active.clear()
        notifications.forEach { sbn ->
            runCatching { active[sbn.key] = recordOf(sbn) }
        }
        publishActive()
    }

    @Synchronized
    fun onPosted(notification: StatusBarNotification) {
        runCatching {
            active[notification.key] = recordOf(notification)
            publishActive()
        }
    }

    @Synchronized
    fun onRemoved(notification: StatusBarNotification) {
        val record = active.remove(notification.key)
            ?: runCatching { recordOf(notification) }.getOrNull()
        publishActive()
        if (record != null) {
            history.addLast(record)
            while (history.size > HISTORY_LIMIT) history.removeFirst()
            _historyRecords.value = history.toList().asReversed()
        }
    }

    @Synchronized
    fun clearActive() {
        active.clear()
        publishActive()
    }

    fun activeRecord(key: String): Record? = synchronized(this) { active[key] }

    fun historyRecord(key: String): Record? = synchronized(this) {
        history.lastOrNull { it.key == key }
    }

    fun activeSnapshot(): List<Record> = _activeRecords.value

    fun historySnapshot(): List<Record> = _historyRecords.value

    private fun publishActive() {
        _activeRecords.value = active.values.sortedByDescending { it.postTime }
    }

    private fun recordOf(sbn: StatusBarNotification): Record {
        val notification = sbn.notification
        val extras = notification.extras
        val context = appContext
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString("\n") { it?.toString().orEmpty() }
            ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val actions = notification.actions
            ?.mapNotNull { action -> action.title?.toString() }
            .orEmpty()
        return Record(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = labelOf(context, sbn.packageName),
            title = title.take(200),
            text = text.take(2_000),
            subText = subText.take(300),
            category = notification.category.orEmpty(),
            channelId = notification.channelId.orEmpty(),
            groupKey = sbn.groupKey,
            postTime = sbn.postTime,
            ongoing = sbn.isOngoing,
            clearable = sbn.isClearable,
            actions = actions,
            contentIntent = notification.contentIntent,
        )
    }

    private fun labelOf(context: Context?, packageName: String): String {
        if (context == null) return packageName
        labels[packageName]?.let { return it }
        val label = runCatching {
            val pm = context.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
        labels[packageName] = label
        return label
    }
}
