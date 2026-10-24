package com.eloykim.lyra.data

import android.content.ContentValues

/**
 * Frame 落库（P1 帧链）。
 *
 * 只追加；一次注意周期一行，`payload` 里带 digest 与槽位快照，供 Inspector 回放。
 */
class FrameStore internal constructor(private val helper: LyraDatabase) {

    fun append(
        runId: Long,
        cycle: Int,
        mode: String,
        goal: String,
        digest: String,
        payload: String,
        at: Long = System.currentTimeMillis(),
    ) {
        helper.writableDatabase.insertOrThrow(
            "frames",
            null,
            ContentValues().apply {
                put("run_id", runId)
                put("cycle", cycle)
                put("mode", mode)
                put("goal", goal)
                put("digest", digest)
                put("payload", payload)
                put("at", at)
            },
        )
    }

    /** 动作执行后才知道是否卡住：回填到该 run 最近一帧。 */
    fun markImpasse(runId: Long, state: String) {
        helper.writableDatabase.execSQL(
            "UPDATE frames SET impasse = ? WHERE id = (SELECT MAX(id) FROM frames WHERE run_id = ?)",
            arrayOf(state, runId),
        )
    }

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
