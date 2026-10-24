package com.eloykim.lyra.service.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.reqString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 联系人服务（只读）：搜索、读取详情、按号码反查。
 *
 * 需要 READ_CONTACTS 运行时权限；未授权时返回 `permission_required`。
 */
class ContactsService(private val context: Context) : BasePhoneService() {

    override fun requiredPermissions(): List<String> =
        listOf(android.Manifest.permission.READ_CONTACTS)

    override val id: String = "contacts"
    override val title: String = "联系人"
    override val description: String = "只读搜索联系人、读取号码/邮箱、按号码反查姓名。"

    init {
        register(
            ServiceMethod(
                name = "search",
                summary = "按姓名或号码搜索联系人",
                parameters = Schema.params(
                    "query" to Schema.string("姓名或号码片段"),
                    "limit" to Schema.integer("数量", 1, 50),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> search(args.reqString("query"), args.optIntOr("limit", 10)) }

        register(
            ServiceMethod(
                name = "get",
                summary = "读取一个联系人的电话和邮箱",
                parameters = Schema.params(
                    "contactId" to Schema.string("联系人 id"),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> get(args.reqString("contactId")) }

        register(
            ServiceMethod(
                name = "lookup",
                summary = "按电话号码反查联系人",
                parameters = Schema.params(
                    "number" to Schema.string("电话号码"),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> lookup(args.reqString("number")) }

        register(
            ServiceMethod(
                name = "count",
                summary = "联系人总数",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { count() }

        register(
            ServiceMethod(
                name = "status",
                summary = "联系人权限",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { status() }
    }

    private suspend fun search(query: String, limit: Int): ServiceResult =
        withContext(Dispatchers.IO) {
            if (!granted()) return@withContext permission()
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
            )
            val selection =
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                    "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            val args = arrayOf("%$query%", "%$query%")
            val results = JSONArray()
            runCatching {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    selection,
                    args,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
                )?.use { cursor ->
                    while (cursor.moveToNext() && results.length() < limit.coerceIn(1, 50)) {
                        val type = cursor.getInt(3)
                        results.put(
                            JSONObject()
                                .put("contactId", cursor.getString(0))
                                .put("name", cursor.getString(1))
                                .put("number", cursor.getString(2))
                                .put(
                                    "type",
                                    ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                                        context.resources,
                                        type,
                                        null,
                                    ).toString(),
                                ),
                        )
                    }
                }
            }.onFailure {
                return@withContext ServiceResult.fail("query_failed", it.message ?: "读取联系人失败")
            }
            ServiceResult.ok(
                "${results.length()} contacts",
                JSONObject().put("contacts", results).put("count", results.length()),
            )
        }

    private suspend fun get(contactId: String): ServiceResult = withContext(Dispatchers.IO) {
        if (!granted()) return@withContext permission()
        val phones = JSONArray()
        val emails = JSONArray()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                ),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    phones.put(
                        JSONObject()
                            .put("number", cursor.getString(0))
                            .put(
                                "type",
                                ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                                    context.resources,
                                    cursor.getInt(1),
                                    null,
                                ).toString(),
                            ),
                    )
                }
            }
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                    ContactsContract.CommonDataKinds.Email.TYPE,
                ),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                arrayOf(contactId),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    emails.put(
                        JSONObject()
                            .put("address", cursor.getString(0))
                            .put(
                                "type",
                                ContactsContract.CommonDataKinds.Email.getTypeLabel(
                                    context.resources,
                                    cursor.getInt(1),
                                    null,
                                ).toString(),
                            ),
                    )
                }
            }
        }.onFailure {
            return@withContext ServiceResult.fail("query_failed", it.message ?: "读取联系人失败")
        }
        if (phones.length() == 0 && emails.length() == 0) {
            return@withContext ServiceResult.fail("not_found", "没有联系人 $contactId")
        }
        ServiceResult.ok(
            "ok",
            JSONObject()
                .put("contactId", contactId)
                .put("phones", phones)
                .put("emails", emails),
        )
    }

    private suspend fun lookup(number: String): ServiceResult = withContext(Dispatchers.IO) {
        if (!granted()) return@withContext permission()
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        val result = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.PhoneLookup.CONTACT_ID,
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    JSONObject()
                        .put("contactId", cursor.getString(0))
                        .put("name", cursor.getString(1))
                        .put("number", number)
                } else {
                    null
                }
            }
        }.getOrElse {
            return@withContext ServiceResult.fail("query_failed", it.message ?: "反查失败")
        } ?: return@withContext ServiceResult.fail("not_found", "没有匹配 $number 的联系人")
        ServiceResult.ok("found", result)
    }

    private suspend fun count(): ServiceResult = withContext(Dispatchers.IO) {
        if (!granted()) return@withContext permission()
        val count = runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null,
            )?.use { it.count } ?: 0
        }.getOrElse {
            return@withContext ServiceResult.fail("query_failed", it.message ?: "查询失败")
        }
        ServiceResult.ok("ok", JSONObject().put("count", count))
    }

    private fun status(): ServiceResult = ServiceResult.ok(
        "ok",
        JSONObject().put("readContacts", granted()),
    )

    private fun permission(): ServiceResult =
        ServiceResult.fail("permission_required", "需要读取联系人权限（READ_CONTACTS）")

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
}
