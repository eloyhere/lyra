package com.eloykim.lyra.service.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * 交互式通知的等待队列。
 *
 * `notification.prompt` 先在这里挂一个 [CompletableDeferred]，用户点通知按钮/输入后
 * 由 [LyraNotificationActionReceiver] 唤醒；整个命令以阻塞（挂起）形式返回结果。
 */
object NotificationPromptStore {

    data class Result(
        val action: String? = null,
        val text: String? = null,
        val timedOut: Boolean = false,
    )

    const val EXTRA_ID = "lyra_prompt_id"
    const val EXTRA_ACTION = "lyra_prompt_action"
    const val EXTRA_ACTION_INDEX = "lyra_prompt_action_index"
    const val KEY_REPLY = "lyra_prompt_reply"
    const val TAG = "lyra-prompt"

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Result>>()

    fun register(id: Int): CompletableDeferred<Result> {
        val deferred = CompletableDeferred<Result>()
        pending[id] = deferred
        return deferred
    }

    fun complete(id: Int, action: String?, text: String?): Boolean {
        val deferred = pending.remove(id) ?: return false
        if (!deferred.isCompleted) {
            deferred.complete(Result(action = action, text = text))
        }
        return true
    }

    fun cancel(id: Int) {
        pending.remove(id)?.let { deferred ->
            if (!deferred.isCompleted) deferred.complete(Result(timedOut = true))
        }
    }

    fun isPending(id: Int): Boolean = pending.containsKey(id)
}

/**
 * 通知按钮的接收器。只接受本应用显式 PendingIntent，`exported=false`。
 */
class LyraNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(NotificationPromptStore.EXTRA_ID, -1)
        if (id <= 0) return
        val action = intent.getStringExtra(NotificationPromptStore.EXTRA_ACTION)
        val results = RemoteInput.getResultsFromIntent(intent)
        val text = results
            ?.getCharSequence(NotificationPromptStore.KEY_REPLY)
            ?.toString()
        NotificationPromptStore.complete(id, action, text)
        NotificationManagerCompat.from(context)
            .cancel(NotificationPromptStore.TAG, id)
    }
}
