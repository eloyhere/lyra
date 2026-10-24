package com.eloykim.lyra.service.shortmessage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optBoolOr
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optLongOr
import com.eloykim.lyra.service.reqLong
import com.eloykim.lyra.service.reqString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 短信服务（ShortMessage）。
 *
 * - `send` 直接发送，需要 SEND_SMS 运行时权限；
 * - `inbox` / `thread` / `unreadCount` 读取收件箱，需要 READ_SMS；
 * - `draft` 只拉起短信编辑界面，不需要敏感权限。
 *
 * 权限未授予时返回 `permission_required`，不静默降级。
 */
class ShortMessageService(private val context: Context) : BasePhoneService() {

    override fun requiredPermissions(): List<String> = listOf(
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.READ_SMS,
    )

    override val id: String = "short_message"
    override val title: String = "短信"
    override val description: String =
        "直接发送短信、拉起短信编辑界面、读取收件箱/会话/未读数。"

    init {
        register(
            ServiceMethod(
                name = "send",
                summary = "直接发送一条短信（长短信自动分段）",
                parameters = Schema.params(
                    "address" to Schema.string("手机号"),
                    "text" to Schema.string("短信正文"),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args -> send(args.reqString("address"), args.reqString("text")) }

        register(
            ServiceMethod(
                name = "draft",
                summary = "拉起短信编辑界面并填好收件人/正文（不自动发送）",
                parameters = Schema.params(
                    "address" to Schema.string("手机号"),
                    "text" to Schema.string("短信正文"),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args -> draft(args.reqString("address"), args.reqString("text")) }

        register(
            ServiceMethod(
                name = "inbox",
                summary = "读取收件箱（最新在前）",
                parameters = Schema.params(
                    "limit" to Schema.integer("数量", 1, 100),
                    "unreadOnly" to Schema.boolean("只看未读"),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args ->
            inbox(
                limit = args.optIntOr("limit", 20),
                unreadOnly = args.optBoolOr("unreadOnly", false),
            )
        }

        register(
            ServiceMethod(
                name = "thread",
                summary = "读取一个短信会话（按 threadId）",
                parameters = Schema.params(
                    "threadId" to Schema.integer("会话 id"),
                    "limit" to Schema.integer("数量", 1, 100),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> thread(args.reqLong("threadId"), args.optIntOr("limit", 30)) }

        register(
            ServiceMethod(
                name = "unreadCount",
                summary = "收件箱未读数",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { unreadCount() }

        register(
            ServiceMethod(
                name = "status",
                summary = "短信权限与可用性",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { status() }
    }

    @Suppress("DEPRECATION")
    private fun manager(): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

    private fun send(address: String, text: String): ServiceResult {
        if (!granted(Manifest.permission.SEND_SMS)) {
            return ServiceResult.fail(
                "permission_required",
                "需要短信发送权限（SEND_SMS），请在系统设置里授予",
            )
        }
        val manager = manager()
            ?: return ServiceResult.fail("sms_unavailable", "设备没有短信服务")
        val body = text.take(10_000)
        val parts = manager.divideMessage(body)
        return runCatching {
            if (parts.size <= 1) {
                manager.sendTextMessage(address, null, body, null, null)
            } else {
                manager.sendMultipartTextMessage(address, null, parts, null, null)
            }
            ServiceResult.ok(
                "queued",
                JSONObject()
                    .put("address", address)
                    .put("parts", parts.size)
                    .put("length", body.length),
            )
        }.getOrElse {
            ServiceResult.fail("sms_failed", it.message ?: "短信发送失败")
        }
    }

    private fun draft(address: String, text: String): ServiceResult {
        val uri = Uri.fromParts("smsto", address, null)
        val intent = Intent(Intent.ACTION_SENDTO, uri)
            .putExtra("sms_body", text.take(10_000))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ServiceResult.ok(
                "draft_opened",
                JSONObject().put("address", address),
            )
        }.getOrElse {
            ServiceResult.fail("no_sms_app", "无法打开短信编辑界面：${it.message}")
        }
    }

    private suspend fun inbox(limit: Int, unreadOnly: Boolean): ServiceResult =
        withContext(Dispatchers.IO) {
            if (!granted(Manifest.permission.READ_SMS)) {
                return@withContext ServiceResult.fail(
                    "permission_required",
                    "需要读取短信权限（READ_SMS），请在系统设置里授予",
                )
            }
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ,
                Telephony.Sms.THREAD_ID,
            )
            val selection = if (unreadOnly) "${Telephony.Sms.READ} = 0" else null
            val messages = ArrayList<JSONObject>()
            runCatching {
                context.contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    projection,
                    selection,
                    null,
                    "${Telephony.Sms.DATE} DESC",
                )?.use { cursor ->
                    while (cursor.moveToNext() && messages.size < limit.coerceIn(1, 100)) {
                        messages += cursor.toMessage()
                    }
                }
            }.onFailure {
                return@withContext ServiceResult.fail(
                    "query_failed",
                    it.message ?: "读取短信失败",
                )
            }
            ServiceResult.ok(
                "${messages.size} messages",
                JSONObject()
                    .put("messages", JSONArray(messages))
                    .put("count", messages.size),
            )
        }

    private suspend fun thread(threadId: Long, limit: Int): ServiceResult =
        withContext(Dispatchers.IO) {
            if (!granted(Manifest.permission.READ_SMS)) {
                return@withContext ServiceResult.fail(
                    "permission_required",
                    "需要读取短信权限（READ_SMS），请在系统设置里授予",
                )
            }
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ,
                Telephony.Sms.THREAD_ID,
            )
            val messages = ArrayList<JSONObject>()
            runCatching {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    projection,
                    "${Telephony.Sms.THREAD_ID} = ?",
                    arrayOf(threadId.toString()),
                    "${Telephony.Sms.DATE} DESC",
                )?.use { cursor ->
                    while (cursor.moveToNext() && messages.size < limit.coerceIn(1, 100)) {
                        messages += cursor.toMessage()
                    }
                }
            }.onFailure {
                return@withContext ServiceResult.fail(
                    "query_failed",
                    it.message ?: "读取短信会话失败",
                )
            }
            ServiceResult.ok(
                "${messages.size} messages",
                JSONObject()
                    .put("threadId", threadId)
                    .put("messages", JSONArray(messages))
                    .put("count", messages.size),
            )
        }

    private suspend fun unreadCount(): ServiceResult = withContext(Dispatchers.IO) {
        if (!granted(Manifest.permission.READ_SMS)) {
            return@withContext ServiceResult.fail(
                "permission_required",
                "需要读取短信权限（READ_SMS）",
            )
        }
        val count = runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.READ} = 0",
                null,
                null,
            )?.use { it.count } ?: 0
        }.getOrElse {
            return@withContext ServiceResult.fail("query_failed", it.message ?: "查询失败")
        }
        ServiceResult.ok("ok", JSONObject().put("unread", count))
    }

    private fun status(): ServiceResult = ServiceResult.ok(
        "ok",
        JSONObject()
            .put("canSend", granted(Manifest.permission.SEND_SMS))
            .put("canRead", granted(Manifest.permission.READ_SMS))
            .put("smsManager", manager() != null),
    )

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun android.database.Cursor.toMessage(): JSONObject = JSONObject()
        .put("id", getLong(getColumnIndexOrThrow(Telephony.Sms._ID)))
        .put("address", getString(getColumnIndexOrThrow(Telephony.Sms.ADDRESS)))
        .put(
            "text",
            getString(getColumnIndexOrThrow(Telephony.Sms.BODY))?.take(1_000),
        )
        .put("date", getLong(getColumnIndexOrThrow(Telephony.Sms.DATE)))
        .put("read", getInt(getColumnIndexOrThrow(Telephony.Sms.READ)) == 1)
        .put("threadId", getLong(getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)))
}
