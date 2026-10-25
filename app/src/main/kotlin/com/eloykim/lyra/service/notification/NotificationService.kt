package com.eloykim.lyra.service.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.eloykim.lyra.notify.LyraNotifications
import com.eloykim.lyra.service.notification.NoticeLevel
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optBoolOr
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqInt
import com.eloykim.lyra.service.reqString
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通知服务：发送 Lyra 自己的通知 + 读取/操作系统通知。
 *
 * 发送需要 POST_NOTIFICATIONS（Android 13+）；
 * 读取/撤回需要用户在系统设置里授予「通知使用权」（NotificationListenerService）。
 */
class NotificationService(private val context: Context) : BasePhoneService() {

    override val id: String = "notification"
    override val title: String = "通知"
    override val description: String =
        "发送/取消 Lyra 通知；读取当前与最近消失的系统通知，并打开/撤回通知。"

    private val nextId = AtomicInteger(2_000)

    init {
        NotificationStore.attach(context)
        LyraNotifications.init(context)

        register(
            ServiceMethod(
                name = "send",
                summary = "发送一条系统通知",
                parameters = Schema.params(
                    "title" to Schema.string("标题"),
                    "message" to Schema.string("正文"),
                    "level" to Schema.string(
                        "级别",
                        listOf("safety", "commitment", "opportunity", "curiosity", "silent"),
                    ),
                    "id" to Schema.integer("通知 id；省略自动分配"),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args ->
            send(
                title = args.reqString("title").take(80),
                message = args.reqString("message").take(1_000),
                level = args.optStringOr("level", "opportunity"),
                id = args.optIntOr("id", -1),
            )
        }

        register(
            ServiceMethod(
                name = "prompt",
                summary = "发送交互式通知（按钮/输入框），阻塞等待用户操作并返回结果",
                parameters = Schema.params(
                    "title" to Schema.string("标题"),
                    "message" to Schema.string("正文"),
                    "actions" to Schema.array(
                        "按钮文字（最多 3 个；有输入框时最多 2 个）",
                        Schema.string("按钮文字"),
                    ),
                    "input" to Schema.boolean("是否显示输入框"),
                    "inputLabel" to Schema.string("输入框按钮文字，默认「回复」"),
                    "inputPlaceholder" to Schema.string("输入框占位提示"),
                    "timeoutMs" to Schema.integer("等待超时", 5_000, 300_000),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args ->
            prompt(
                title = args.reqString("title").take(80),
                message = args.reqString("message").take(1_000),
                actions = stringList(args, "actions"),
                input = args.optBoolOr("input", false),
                inputLabel = args.optStringOr("inputLabel", "回复").take(24),
                inputPlaceholder = args.optStringOr("inputPlaceholder").take(80),
                timeoutMs = args.optIntOr("timeoutMs", 120_000).coerceIn(5_000, 300_000),
            )
        }

        register(
            ServiceMethod(
                name = "cancel",
                summary = "取消 Lyra 发出的一条通知",
                parameters = Schema.params(
                    "id" to Schema.integer("通知 id"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            val id = args.reqInt("id")
            LyraNotifications.cancel(TAG, id)
            ServiceResult.ok("cancelled", JSONObject().put("id", id))
        }

        register(
            ServiceMethod(
                name = "list",
                summary = "当前活动的系统通知",
                parameters = Schema.params(
                    "limit" to Schema.integer("数量", 1, 50),
                    "package" to Schema.string("只看某个包"),
                    "query" to Schema.string("按标题/正文/应用名过滤"),
                    "includeSelf" to Schema.boolean("是否包含 Lyra 自己的通知"),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args ->
            list(
                limit = args.optIntOr("limit", 20),
                packageFilter = args.optStringOr("package"),
                query = args.optStringOr("query"),
                includeSelf = args.optBoolOr("includeSelf", false),
            )
        }

        register(
            ServiceMethod(
                name = "history",
                summary = "最近消失的通知（内存中最多 100 条）",
                parameters = Schema.params(
                    "limit" to Schema.integer("数量", 1, 100),
                    "query" to Schema.string("按标题/正文/应用名过滤"),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args ->
            history(args.optIntOr("limit", 20), args.optStringOr("query"))
        }

        register(
            ServiceMethod(
                name = "dismiss",
                summary = "从通知栏撤回一条系统通知",
                parameters = Schema.params(
                    "key" to Schema.string("通知 key，来自 list/history"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args -> dismiss(args.reqString("key")) }

        register(
            ServiceMethod(
                name = "open",
                summary = "打开一条通知的 contentIntent",
                parameters = Schema.params(
                    "key" to Schema.string("通知 key，来自 list/history"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args -> open(args.reqString("key")) }

        register(
            ServiceMethod(
                name = "dismissAll",
                summary = "撤回当前所有可撤回的通知",
                parameters = Schema.params(),
                risk = ServiceRisk.CONTROL,
            ),
        ) { dismissAll() }

        register(
            ServiceMethod(
                name = "openSettings",
                summary = "打开系统的「通知使用权」设置页",
                parameters = Schema.params(),
                risk = ServiceRisk.CONTROL,
            ),
        ) { openSettings() }

        register(
            ServiceMethod(
                name = "status",
                summary = "通知发送/读取权限与连接状态",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { status() }
    }

    private fun send(title: String, message: String, level: String, id: Int): ServiceResult {
        if (!LyraNotifications.canPost(context)) {
            return ServiceResult.fail(
                "permission_required",
                "需要通知权限（POST_NOTIFICATIONS），请在系统设置里授予",
            )
        }
        val noticeLevel = parseLevel(level)
        val notificationId = if (id > 0) id else nextId.getAndIncrement()
        LyraNotifications.post(
            level = noticeLevel,
            message = message,
            title = title,
            tag = TAG,
            id = notificationId,
        )
        return ServiceResult.ok(
            "posted",
            JSONObject()
                .put("id", notificationId)
                .put("tag", TAG)
                .put("level", noticeLevel.name.lowercase()),
        )
    }

    private suspend fun prompt(
        title: String,
        message: String,
        actions: List<String>,
        input: Boolean,
        inputLabel: String,
        inputPlaceholder: String,
        timeoutMs: Int,
    ): ServiceResult {
        if (!LyraNotifications.canPost(context)) {
            return ServiceResult.fail(
                "permission_required",
                "需要通知权限（POST_NOTIFICATIONS）",
            )
        }
        if (!input && actions.isEmpty()) {
            return ServiceResult.fail("bad_args", "至少需要一个按钮或输入框")
        }
        val id = nextId.getAndIncrement()
        val deferred = NotificationPromptStore.register(id)
        return try {
            val notification = buildPromptNotification(
                id = id,
                title = title,
                message = message,
                actions = actions,
                input = input,
                inputLabel = inputLabel,
                inputPlaceholder = inputPlaceholder,
            )
            NotificationManagerCompat.from(context)
                .notify(NotificationPromptStore.TAG, id, notification)
            val result = withTimeoutOrNull(timeoutMs.toLong()) { deferred.await() }
                ?: return ServiceResult.fail(
                    "timeout",
                    "用户未在 ${timeoutMs / 1_000}s 内操作",
                )
            ServiceResult.ok(
                if (result.text.isNullOrBlank()) "action" else "input",
                JSONObject()
                    .put("id", id)
                    .put("action", result.action)
                    .put("text", result.text),
            )
        } catch (t: Throwable) {
            ServiceResult.fail("notify_failed", t.message ?: "发送交互式通知失败")
        } finally {
            NotificationPromptStore.cancel(id)
            NotificationManagerCompat.from(context)
                .cancel(NotificationPromptStore.TAG, id)
        }
    }

    private fun buildPromptNotification(
        id: Int,
        title: String,
        message: String,
        actions: List<String>,
        input: Boolean,
        inputLabel: String,
        inputPlaceholder: String,
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(context, NoticeLevel.COMMITMENT.channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    id,
                    Intent(context, com.eloykim.lyra.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        if (inputPlaceholder.isNotBlank()) {
            builder.setSubText(inputPlaceholder)
        }

        val maxActions = if (input) 2 else 3
        actions.take(maxActions).forEachIndexed { index, label ->
            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_send,
                    label,
                    actionPendingIntent(id, index, label, mutable = false),
                ).build(),
            )
        }

        if (input) {
            val replyIntent = actionPendingIntent(id, 9, inputLabel, mutable = true)
            val remoteInput = RemoteInput.Builder(NotificationPromptStore.KEY_REPLY)
                .setLabel(inputLabel)
                .setAllowFreeFormInput(true)
                .build()
            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_send,
                    inputLabel,
                    replyIntent,
                )
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(false)
                    .build(),
            )
        }
        return builder.build()
    }

    private fun actionPendingIntent(
        id: Int,
        index: Int,
        label: String,
        mutable: Boolean,
    ): PendingIntent {
        val intent = Intent(context, LyraNotificationActionReceiver::class.java).apply {
            putExtra(NotificationPromptStore.EXTRA_ID, id)
            putExtra(NotificationPromptStore.EXTRA_ACTION, label)
            putExtra(NotificationPromptStore.EXTRA_ACTION_INDEX, index)
        }
        val mutability = when {
            !mutable -> PendingIntent.FLAG_IMMUTABLE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> PendingIntent.FLAG_MUTABLE
            else -> 0
        }
        return PendingIntent.getBroadcast(
            context,
            id * 16 + index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutability,
        )
    }

    private fun stringList(args: JSONObject, name: String): List<String> {
        val array = args.optJSONArray(name) ?: return emptyList()
        return (0 until array.length())
            .map { index -> array.opt(index).toString().trim() }
            .filter { it.isNotEmpty() }
    }

    private fun list(
        limit: Int,
        packageFilter: String,
        query: String,
        includeSelf: Boolean,
    ): ServiceResult {
        val records = NotificationStore.activeSnapshot()
            .asSequence()
            .filter { includeSelf || it.packageName != context.packageName }
            .filter { packageFilter.isBlank() || it.packageName == packageFilter }
            .filter { it.matches(query) }
            .take(limit.coerceIn(1, 50))
            .toList()
        return recordsResult(records, "notifications")
    }

    private fun history(limit: Int, query: String): ServiceResult {
        val records = NotificationStore.historySnapshot()
            .asSequence()
            .filter { it.matches(query) }
            .take(limit.coerceIn(1, 100))
            .toList()
        return recordsResult(records, "notifications")
    }

    private fun recordsResult(
        records: List<NotificationStore.Record>,
        key: String,
    ): ServiceResult {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        return ServiceResult.ok(
            "${records.size} notifications",
            JSONObject().put(key, array).put("count", records.size),
        )
    }

    private fun dismiss(key: String): ServiceResult {
        if (!listenerEnabled()) {
            return ServiceResult.fail(
                "notification_access_required",
                "需要「通知使用权」才能撤回通知",
            )
        }
        return if (LyraNotificationListener.dismiss(key)) {
            ServiceResult.ok("dismissed", JSONObject().put("key", key))
        } else {
            ServiceResult.fail("not_found", "无法撤回通知：${key.take(80)}")
        }
    }

    private fun open(key: String): ServiceResult {
        val record = NotificationStore.activeRecord(key)
            ?: NotificationStore.historyRecord(key)
            ?: return ServiceResult.fail("not_found", "没有通知 $key")
        val intent = record.contentIntent
            ?: return ServiceResult.fail("no_content_intent", "这条通知没有可打开的内容")
        return runCatching {
            intent.send()
            ServiceResult.ok("opened", JSONObject().put("key", key))
        }.getOrElse {
            ServiceResult.fail("open_failed", it.message ?: "打开通知失败")
        }
    }

    private fun dismissAll(): ServiceResult {
        if (!listenerEnabled()) {
            return ServiceResult.fail(
                "notification_access_required",
                "需要「通知使用权」才能撤回通知",
            )
        }
        val keys = NotificationStore.activeSnapshot().map { it.key }
        val count = LyraNotificationListener.dismissAll(keys)
        return ServiceResult.ok(
            "dismissed",
            JSONObject().put("count", count).put("requested", keys.size),
        )
    }

    private fun openSettings(): ServiceResult = runCatching {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        ServiceResult.ok("settings_opened")
    }.getOrElse {
        ServiceResult.fail("open_failed", it.message ?: "无法打开通知使用权设置")
    }

    private fun status(): ServiceResult = ServiceResult.ok(
        "ok",
        JSONObject()
            .put("canPost", LyraNotifications.canPost(context))
            .put("listenerEnabled", listenerEnabled())
            .put("listenerConnected", NotificationStore.connected.value)
            .put("active", NotificationStore.activeSnapshot().size)
            .put("history", NotificationStore.historySnapshot().size),
    )

    private fun listenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    private fun parseLevel(level: String): NoticeLevel = when (level.lowercase()) {
        "safety" -> NoticeLevel.SAFETY
        "commitment" -> NoticeLevel.COMMITMENT
        "curiosity" -> NoticeLevel.CURIOSITY
        "silent" -> NoticeLevel.SILENT
        else -> NoticeLevel.OPPORTUNITY
    }

    private companion object {
        const val TAG = "lyra-service"
    }
}
