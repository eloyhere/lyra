package com.eloykim.lyra.data

import android.content.ContentValues
import android.database.Cursor
import com.eloykim.lyra.amp.AmpEvent
import com.eloykim.lyra.amp.Compaction
import com.eloykim.lyra.amp.EventLog
import com.eloykim.lyra.amp.LedgerCheckpoint

/**
 * AMP 事件日志的持久层（X-1）。
 *
 * - 只追加：表上有 BEFORE UPDATE / BEFORE DELETE 触发器，绕过本类也改不了；
 * - 哈希链：digest 由 [EventLog] 计算，prev_digest 串联，[verify] 可检出篡改与删除；
 * - 内存缓存：启动时加载一次，后续 append 不重复读全表。
 */
class LedgerStore internal constructor(private val helper: LyraDatabase) {

    private val lock = Any()

    @Volatile
    private var cached: EventLog? = null

    private fun log(): EventLog = cached ?: EventLog(loadAll()).also { cached = it }

    fun append(
        actor: String,
        type: String,
        payload: String,
        refs: List<String> = emptyList(),
        at: Long = System.currentTimeMillis(),
    ): AmpEvent = synchronized(lock) {
        val event = log().append(actor, type, payload, refs, at)
        val values = ContentValues().apply {
            put("seq", event.seq)
            put("at", event.at)
            put("actor", event.actor)
            put("type", event.type)
            put("refs", event.refs.joinToString(","))
            put("payload", event.payload)
            put("digest", event.digest)
            put("prev_digest", event.prevDigest)
        }
        helper.writableDatabase.insertOrThrow("amp_events", null, values)
        event
    }

    fun loadAll(): List<AmpEvent> {
        val events = ArrayList<AmpEvent>()
        helper.readableDatabase.query(
            "amp_events",
            arrayOf("seq", "at", "actor", "type", "refs", "payload", "digest", "prev_digest"),
            null, null, null, null, "seq ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                events += cursor.toEvent()
            }
        }
        return events
    }

    /** 返回违规描述；空表示链完整。 */
    fun verify(): List<String> {
        val events = loadAll()
        // 压缩过的账本从 checkpoint 之后开始：基线是 checkpoint.to + 1 与它的 tip
        val checkpoint = checkpoints().firstOrNull()
        return if (checkpoint == null) {
            EventLog.verifyChain(events)
        } else {
            EventLog.verifyChain(
                events = events.filter { it.seq > checkpoint.to },
                startSeq = checkpoint.to + 1,
                startPrev = checkpoint.tip,
            )
        }
    }

    fun size(): Int = runCatching { loadAll().size }.getOrDefault(0)

    fun head(): AmpEvent? = runCatching { loadAll().lastOrNull() }.getOrNull()

    /**
     * 压缩前缀：先写 checkpoint（P-5 先承诺），再删除被它覆盖的行。
     *
     * 删除能成功，是因为 v7 的触发器只放行 `seq <= checkpoint.to_seq` 的行；
     * 没有 checkpoint 的行依然删不掉。
     */
    fun compact(keepRecent: Int = 500): LedgerCheckpoint? = synchronized(lock) {
        val events = loadAll()
        val result = runCatching { Compaction.compact(events, keepRecent) }.getOrNull() ?: return null
        if (result.removed.isEmpty()) return null

        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow(
                "ledger_checkpoints",
                null,
                ContentValues().apply {
                    put("from_seq", result.checkpoint.from)
                    put("to_seq", result.checkpoint.to)
                    put("count", result.checkpoint.count)
                    put("root", result.checkpoint.root)
                    put("tip", result.checkpoint.tip)
                    put("created_at", System.currentTimeMillis())
                },
            )
            db.delete("amp_events", "seq <= ?", arrayOf(result.checkpoint.to.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        cached = null
        result.checkpoint
    }

    /** 已存在的检查点（按时间倒序）。 */
    fun checkpoints(): List<LedgerCheckpoint> {
        val list = ArrayList<LedgerCheckpoint>()
        helper.readableDatabase.query(
            "ledger_checkpoints",
            arrayOf("from_seq", "to_seq", "count", "root", "tip"),
            null, null, null, null, "id DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list += LedgerCheckpoint(
                    from = cursor.getLong(0),
                    to = cursor.getLong(1),
                    count = cursor.getInt(2),
                    root = cursor.getString(3),
                    tip = cursor.getString(4),
                )
            }
        }
        return list
    }
    private fun Cursor.toEvent(): AmpEvent = AmpEvent(
        seq = getLong(0),
        at = getLong(1),
        actor = getString(2),
        type = getString(3),
        refs = getString(4)?.split(",")?.filter { it.isNotBlank() }.orEmpty(),
        payload = getString(5),
        digest = getString(6),
        prevDigest = getString(7),
    )
}
