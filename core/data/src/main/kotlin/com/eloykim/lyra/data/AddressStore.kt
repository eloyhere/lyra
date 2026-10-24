package com.eloykim.lyra.data

import android.content.ContentValues
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一条从购物/外卖等 App 页面识别出的收货地址。 */
data class AddressRecord(
    val id: Long,
    val name: String?,
    val phone: String?,
    val region: String?,
    val detail: String,
    val sourceApp: String?,
    val raw: String?,
    val isDefault: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 地址库。
 *
 * - 地址来自 AddressService 对当前页面的观察；
 * - 同一 detail + phone 只保留一行，重复扫描会刷新；
 * - 第一条地址自动成为默认地址。
 */
class AddressStore internal constructor(private val helper: LyraDatabase) {

    private val lock = Any()

    suspend fun list(limit: Int = 30): List<AddressRecord> = withContext(Dispatchers.IO) {
        val result = ArrayList<AddressRecord>()
        helper.readableDatabase.rawQuery(
            """
            SELECT id, name, phone, region, detail, source_app, raw, is_default, created_at, updated_at
            FROM addresses
            ORDER BY is_default DESC, updated_at DESC
            LIMIT ${limit.coerceIn(1, 100)}
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toAddress()
        }
        result
    }

    suspend fun defaultAddress(): AddressRecord? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            """
            SELECT id, name, phone, region, detail, source_app, raw, is_default, created_at, updated_at
            FROM addresses
            ORDER BY is_default DESC, updated_at DESC
            LIMIT 1
            """.trimIndent(),
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toAddress() else null }
    }

    suspend fun upsert(
        name: String?,
        phone: String?,
        region: String?,
        detail: String,
        sourceApp: String?,
        raw: String?,
    ): AddressRecord = withContext(Dispatchers.IO) {
        val cleanDetail = detail.trim()
        require(cleanDetail.isNotBlank()) { "地址不能为空" }
        synchronized(lock) {
            val db = helper.writableDatabase
            val now = System.currentTimeMillis()
            val existing = findByDetail(cleanDetail, phone)
            if (existing != null) {
                db.update(
                    "addresses",
                    ContentValues().apply {
                        put("name", name ?: existing.name)
                        put("phone", phone ?: existing.phone)
                        put("region", region ?: existing.region)
                        put("source_app", sourceApp ?: existing.sourceApp)
                        put("raw", raw ?: existing.raw)
                        put("updated_at", now)
                    },
                    "id = ?",
                    arrayOf(existing.id.toString()),
                )
                findById(existing.id) ?: existing
            } else {
                val hasAny = countLocked() > 0
                val id = db.insertOrThrow(
                    "addresses",
                    null,
                    ContentValues().apply {
                        put("name", name)
                        put("phone", phone)
                        put("region", region)
                        put("detail", cleanDetail)
                        put("source_app", sourceApp)
                        put("raw", raw)
                        put("is_default", if (hasAny) 0 else 1)
                        put("created_at", now)
                        put("updated_at", now)
                    },
                )
                findById(id) ?: error("地址写入失败")
            }
        }
    }

    suspend fun setDefault(id: Long): AddressRecord? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val db = helper.writableDatabase
            if (findById(id) == null) return@withContext null
            db.beginTransaction()
            try {
                db.update("addresses", ContentValues().apply { put("is_default", 0) }, null, null)
                val rows = db.update(
                    "addresses",
                    ContentValues().apply { put("is_default", 1) },
                    "id = ?",
                    arrayOf(id.toString()),
                )
                db.setTransactionSuccessful()
                if (rows > 0) findById(id) else null
            } finally {
                db.endTransaction()
            }
        }
    }

    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val db = helper.writableDatabase
            val wasDefault = findById(id)?.isDefault == true
            val rows = db.delete("addresses", "id = ?", arrayOf(id.toString()))
            if (wasDefault) {
                db.execSQL(
                    """
                    UPDATE addresses SET is_default = 1
                    WHERE id = (SELECT id FROM addresses ORDER BY updated_at DESC LIMIT 1)
                    """.trimIndent(),
                )
            }
            rows > 0
        }
    }

    private fun findById(id: Long): AddressRecord? {
        helper.readableDatabase.rawQuery(
            """
            SELECT id, name, phone, region, detail, source_app, raw, is_default, created_at, updated_at
            FROM addresses WHERE id = ? LIMIT 1
            """.trimIndent(),
            arrayOf(id.toString()),
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.toAddress() else null }
    }

    private fun findByDetail(detail: String, phone: String?): AddressRecord? {
        val where = if (phone.isNullOrBlank()) {
            "detail = ?"
        } else {
            "detail = ? AND (phone = ? OR phone IS NULL)"
        }
        val args = if (phone.isNullOrBlank()) {
            arrayOf(detail)
        } else {
            arrayOf(detail, phone)
        }
        helper.readableDatabase.rawQuery(
            """
            SELECT id, name, phone, region, detail, source_app, raw, is_default, created_at, updated_at
            FROM addresses WHERE $where ORDER BY updated_at DESC LIMIT 1
            """.trimIndent(),
            args,
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.toAddress() else null }
    }

    private fun countLocked(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM addresses", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun Cursor.toAddress(): AddressRecord = AddressRecord(
        id = getLong(0),
        name = getString(1),
        phone = getString(2),
        region = getString(3),
        detail = getString(4),
        sourceApp = getString(5),
        raw = getString(6),
        isDefault = getInt(7) != 0,
        createdAt = getLong(8),
        updatedAt = getLong(9),
    )
}
