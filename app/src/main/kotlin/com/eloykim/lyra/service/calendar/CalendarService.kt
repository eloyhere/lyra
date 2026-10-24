package com.eloykim.lyra.service.calendar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optBoolOr
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optLongOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 日历服务：读取未来日程；新建日程走系统日历编辑界面（不静默写入）。
 *
 * `agenda` 需要 READ_CALENDAR；`create` 只需要 CalendarContract 的标准 INSERT Intent。
 */
class CalendarService(private val context: Context) : BasePhoneService() {

    override fun requiredPermissions(): List<String> =
        listOf(android.Manifest.permission.READ_CALENDAR)

    override val id: String = "calendar"
    override val title: String = "日历"
    override val description: String = "读取未来日程，或打开日历编辑界面创建日程。"

    init {
        register(
            ServiceMethod(
                name = "agenda",
                summary = "读取未来若干天的日程",
                parameters = Schema.params(
                    "days" to Schema.integer("未来天数", 1, 365),
                    "limit" to Schema.integer("数量", 1, 100),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> agenda(args.optIntOr("days", 7), args.optIntOr("limit", 20)) }

        register(
            ServiceMethod(
                name = "create",
                summary = "打开日历编辑界面并预填标题/时间/地点",
                parameters = Schema.params(
                    "title" to Schema.string("标题"),
                    "beginMs" to Schema.integer("开始时间（epoch 毫秒）"),
                    "endMs" to Schema.integer("结束时间（epoch 毫秒）"),
                    "allDay" to Schema.boolean("是否全天"),
                    "location" to Schema.string("地点"),
                    "description" to Schema.string("备注"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            create(
                title = args.reqString("title"),
                beginMs = args.optLongOr("beginMs", 0L),
                endMs = args.optLongOr("endMs", 0L),
                allDay = args.optBoolOr("allDay", false),
                location = args.optStringOr("location"),
                description = args.optStringOr("description"),
            )
        }

        register(
            ServiceMethod(
                name = "status",
                summary = "日历读取权限与编辑界面可用性",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { status() }
    }

    private suspend fun agenda(days: Int, limit: Int): ServiceResult =
        withContext(Dispatchers.IO) {
            if (!granted()) {
                return@withContext ServiceResult.fail(
                    "permission_required",
                    "需要读取日历权限（READ_CALENDAR）",
                )
            }
            val begin = System.currentTimeMillis()
            val end = begin + days.coerceIn(1, 365) * 86_400_000L
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(begin.toString())
                .appendPath(end.toString())
                .build()
            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_ID,
            )
            val events = JSONArray()
            runCatching {
                context.contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC",
                )?.use { cursor ->
                    while (cursor.moveToNext() && events.length() < limit.coerceIn(1, 100)) {
                        events.put(
                            JSONObject()
                                .put("eventId", cursor.getLong(0))
                                .put("title", cursor.getString(1))
                                .put("begin", cursor.getLong(2))
                                .put("end", cursor.getLong(3))
                                .put("allDay", cursor.getInt(4) == 1)
                                .put("location", cursor.getString(5))
                                .put("calendarId", cursor.getLong(6)),
                        )
                    }
                }
            }.onFailure {
                return@withContext ServiceResult.fail("query_failed", it.message ?: "读取日程失败")
            }
            ServiceResult.ok(
                "${events.length()} events",
                JSONObject()
                    .put("events", events)
                    .put("count", events.length())
                    .put("from", begin)
                    .put("to", end),
            )
        }

    private fun create(
        title: String,
        beginMs: Long,
        endMs: Long,
        allDay: Boolean,
        location: String,
        description: String,
    ): ServiceResult {
        if (beginMs <= 0L) {
            return ServiceResult.fail("bad_args", "需要 beginMs（epoch 毫秒）")
        }
        val end = if (endMs > beginMs) endMs else beginMs + 3_600_000L
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMs)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            .putExtra(CalendarContract.Events.DESCRIPTION, description)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ServiceResult.ok(
                "editor_opened",
                JSONObject()
                    .put("title", title)
                    .put("begin", beginMs)
                    .put("end", end),
            )
        }.getOrElse {
            ServiceResult.fail("no_calendar_app", it.message ?: "没有可用的日历应用")
        }
    }

    private fun status(): ServiceResult {
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        val pm = context.packageManager
        val resolvable = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0)) != null
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0) != null
        }
        return ServiceResult.ok(
            "ok",
            JSONObject()
                .put("readCalendar", granted())
                .put("calendarApp", resolvable),
        )
    }

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
}
