package com.eloykim.lyra.data

import android.content.ContentValues
import com.eloykim.lyra.amp.protocol.frame.Frame
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson

/**
 * Frame 落库（P1 帧链）。
 *
 * 只追加；一次注意周期一行，`payload` 是 canonical AMP Frame JSON。
 * X-1：没有 UPDATE。僵局通过 [LedgerStore] 的事件追加表达，不能回写旧帧。
 */
class FrameStore internal constructor(private val helper: LyraDatabase) {

    fun append(runId: Long, frame: Frame, at: Long = System.currentTimeMillis()) {
        helper.writableDatabase.insertOrThrow(
            "frames",
            null,
            ContentValues().apply {
                put("run_id", runId)
                put("cycle", frame.cycle)
                put("mode", frame.mode.wire)
                put("goal", frame.goal?.label ?: frame.goal?.id)
                put("digest", frame.digest)
                put("payload", CanonicalJson.encode(frame.toJson()))
                put("at", at)
            },
        )
    }

    /**
     * 兼容旧调用点。真正的僵局记录写入 `amp_events`；这里不再修改历史帧。
     */
    @Deprecated("X-1: impasse is an appended event, not a frame update")
    fun markImpasse(runId: Long, state: String) = Unit

    fun count(runId: Long): Int =
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM frames WHERE run_id = ?",
            arrayOf(runId.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    /** 最近一次运行的帧摘要，供 Inspector 显示。 */
    fun recent(limit: Int = 20): List<FrameRow> {
        val rows = ArrayList<FrameRow>()
        helper.readableDatabase.rawQuery(
            "SELECT run_id, cycle, mode, digest FROM frames ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += FrameRow(
                    runId = cursor.getLong(0),
                    cycle = cursor.getInt(1),
                    mode = cursor.getString(2),
                    digest = cursor.getString(3),
                )
            }
        }
        return rows
    }

    data class FrameRow(val runId: Long, val cycle: Int, val mode: String, val digest: String)
}
