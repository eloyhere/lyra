package com.eloykim.lyra.service.address

import com.eloykim.lyra.accessibility.AutomationClient
import com.eloykim.lyra.data.AddressRecord
import com.eloykim.lyra.data.HistoryRepository
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * 地址服务。
 *
 * `scan` 观察当前屏幕（通常先由 Agent 打开京东/美团的收货地址页），从无障碍节点里
 * 识别姓名、手机号和详细地址，去重后写入 SQLite；`list/default` 供后续下单使用。
 */
class AddressService(
    private val history: HistoryRepository,
) : BasePhoneService() {

    override val id: String = "address"
    override val title: String = "地址"
    override val description: String =
        "从当前 App 页面识别收货地址（京东/美团等），存入地址库；可查列表和默认地址。"

    init {
        register(
            ServiceMethod(
                name = "scan",
                summary = "扫描当前页面并保存识别到的收货地址",
                parameters = Schema.params(
                    "packageName" to Schema.string("期望的前台包名，例如 com.jingdong.app.mall"),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> scan(args.optStringOr("packageName")) }

        register(
            ServiceMethod(
                name = "list",
                summary = "列出地址库",
                parameters = Schema.params(
                    "limit" to Schema.integer("数量", 1, 100),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> list(args.optIntOr("limit", 30)) }

        register(
            ServiceMethod(
                name = "default",
                summary = "读取默认地址",
                parameters = Schema.params(),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { defaultAddress() }

        register(
            ServiceMethod(
                name = "set_default",
                summary = "设置默认地址",
                parameters = Schema.params(
                    "id" to Schema.integer("地址 id"),
                    required = listOf("id"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args -> setDefault(args.reqLong("id")) }

        register(
            ServiceMethod(
                name = "delete",
                summary = "删除地址",
                parameters = Schema.params(
                    "id" to Schema.integer("地址 id"),
                    required = listOf("id"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args -> delete(args.reqLong("id")) }
    }

    private suspend fun scan(expectedPackage: String?): ServiceResult {
        if (!AutomationClient.connected.value) {
            return ServiceResult.fail(
                "automation_unavailable",
                "自动化服务未连接：请先开启 Lyra 的无障碍服务",
            )
        }
        val raw = runCatching { AutomationClient.observe() }
            .getOrElse { return ServiceResult.fail("automation_error", it.message ?: "观察失败") }
        val observed = ServiceResult.fromAutomation(raw)
        if (!observed.ok) return observed

        val observation = observed.data.optJSONObject("observation") ?: observed.data
        val packageName = observation.optString("package")
        if (!expectedPackage.isNullOrBlank() && packageName != expectedPackage) {
            return ServiceResult.fail(
                "wrong_app",
                "当前前台是 $packageName，不是 $expectedPackage",
            )
        }
        val elements = observation.optJSONArray("elements") ?: JSONArray()
        val texts = ArrayList<String>()
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val text = element.optString("text").ifBlank { element.optString("desc") }.trim()
            if (text.isNotBlank()) texts += text
        }

        val candidates = AddressParser.extract(texts)
        if (candidates.isEmpty()) {
            return ServiceResult.fail("address_not_found", "当前页面没有识别到地址")
        }
        val saved = ArrayList<AddressRecord>()
        candidates.forEach { candidate ->
            saved += history.addresses.upsert(
                name = candidate.name,
                phone = candidate.phone,
                region = candidate.region,
                detail = candidate.detail,
                sourceApp = packageName.ifBlank { null },
                raw = candidate.raw,
            )
        }
        val array = JSONArray()
        saved.forEach { array.put(it.toJson()) }
        return ServiceResult.ok(
            "识别到 ${saved.size} 个地址",
            JSONObject()
                .put("package", packageName)
                .put("count", saved.size)
                .put("addresses", array),
        )
    }

    private suspend fun list(limit: Int): ServiceResult {
        val records = history.addresses.list(limit)
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        return ServiceResult.ok(
            "${records.size} 个地址",
            JSONObject().put("count", records.size).put("addresses", array),
        )
    }

    private suspend fun defaultAddress(): ServiceResult {
        val record = history.addresses.defaultAddress()
            ?: return ServiceResult.fail("no_address", "地址库为空")
        return ServiceResult.ok("默认地址", record.toJson())
    }

    private suspend fun setDefault(id: Long): ServiceResult {
        val record = history.addresses.setDefault(id)
            ?: return ServiceResult.fail("not_found", "没有地址 $id")
        return ServiceResult.ok("已设为默认地址", record.toJson())
    }

    private suspend fun delete(id: Long): ServiceResult {
        val deleted = history.addresses.delete(id)
        return if (deleted) {
            ServiceResult.ok("已删除地址 $id", JSONObject().put("id", id))
        } else {
            ServiceResult.fail("not_found", "没有地址 $id")
        }
    }

    private fun AddressRecord.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("phone", phone)
        .put("region", region)
        .put("detail", detail)
        .put("sourceApp", sourceApp)
        .put("default", isDefault)
        .put("updatedAt", updatedAt)
}

/** 无障碍文本 -> 地址候选；不依赖具体 App 的控件 id。 */
internal object AddressParser {

    data class Candidate(
        val name: String?,
        val phone: String?,
        val region: String?,
        val detail: String,
        val raw: String,
    )

    private val phoneRegex = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
    private val addressMarkers = Regex("""(省|市|区|县|镇|街道|路|街|巷|号|小区|大厦|广场|公寓|楼|室)""")

    fun extract(texts: List<String>): List<Candidate> {
        val candidates = LinkedHashMap<String, Candidate>()
        texts.forEachIndexed { index, text ->
            if (text.length !in 6..160) return@forEachIndexed
            if (text.count { it.isDigit() } > 16) return@forEachIndexed
            if (addressMarkers.findAll(text).count() < 2) return@forEachIndexed
            val window = texts.subList((index - 2).coerceAtLeast(0), (index + 3).coerceAtMost(texts.size))
            val phone = window.firstNotNullOfOrNull { phoneRegex.find(it)?.value }
            val name = texts.subList((index - 2).coerceAtLeast(0), index)
                .lastOrNull { looksLikeName(it) }
            val raw = window.joinToString(" / ")
            val candidate = Candidate(
                name = name,
                phone = phone,
                region = extractRegion(text),
                detail = text,
                raw = raw,
            )
            candidates[buildKey(text, phone)] = candidate
        }
        return candidates.values.toList()
    }

    private fun looksLikeName(text: String): Boolean {
        val value = text.trim()
        if (value.length !in 2..12) return false
        if (value.any { it.isDigit() }) return false
        if (addressMarkers.containsMatchIn(value)) return false
        if (value in setOf("收货人", "联系人", "姓名", "地址", "电话", "手机号", "默认地址")) return false
        return true
    }

    private fun extractRegion(text: String): String? {
        val match = Regex("""^(.{2,12}?(?:省|自治区|市))""").find(text) ?: return null
        return match.groupValues[1]
    }

    private fun buildKey(detail: String, phone: String?): String =
        "${detail.trim()}|${phone.orEmpty()}"
}
