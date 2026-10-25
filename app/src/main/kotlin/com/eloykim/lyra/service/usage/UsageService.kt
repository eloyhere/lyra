package com.eloykim.lyra.service.usage

import com.eloykim.lyra.data.ChatUsageSummary
import com.eloykim.lyra.data.HistoryRepository
import com.eloykim.lyra.data.UsageDay
import com.eloykim.lyra.data.UsageProviderSummary
import com.eloykim.lyra.data.UsageReceipt
import com.eloykim.lyra.data.UsageReport
import com.eloykim.lyra.data.UsageSlice
import com.eloykim.lyra.network.UrlConnectionTransport
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optBoolOr
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.settings.SettingsStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用度服务：只负责从本机 AMP 账本聚合 token / 耗时 / 花费。
 *
 * 不采集新数据、不写账本；UI 只负责展示，Agent 通过命令读取。
 */
class UsageService(private val history: HistoryRepository) : BasePhoneService() {

    private companion object {
        const val CLOUD_CACHE_MS = 10 * 60 * 1000L
    }

    override val id: String = "usage"
    override val title: String = "Token 用度"
    override val description: String =
        "按设置里的服务商/模型单价动态计算 token 输入/输出、动作耗时与费用。"

    /** 当前设置对应的计价信息；模型没有价格时为 null，回退账本里的历史 cost。 */
    data class Pricing(
        val providerId: String,
        val providerLabel: String,
        val modelId: String,
        val modelLabel: String,
        val inputPrice: Double,
        val outputPrice: Double,
    )

    data class Snapshot(
        val report: UsageReport,
        val pricing: Pricing?,
        val chat: ChatUsageSummary = ChatUsageSummary(),
        val group: ChatUsageSummary = ChatUsageSummary(),
        val providers: List<UsageProviderSummary> = emptyList(),
    )

    /** 云端账单/用量接口的返回；字段可能缺失。 */
    data class CloudUsage(
        val providerId: String,
        val source: String,
        val tokensIn: Long? = null,
        val tokensOut: Long? = null,
        val costMicros: Long? = null,
        val balanceMicros: Long? = null,
        val balanceLabel: String? = null,
        val requests: Long? = null,
        val requestLimit: Long? = null,
        val cached: Boolean = false,
        val error: String? = null,
    ) {
        val tokens: Long? get() = if (tokensIn == null && tokensOut == null) null
        else (tokensIn ?: 0) + (tokensOut ?: 0)
    }

    data class UsageComparison(
        val local: Snapshot,
        val cloud: CloudUsage,
        val localTokens: Long,
        val localCostMicros: Long,
    )

    private data class CachedCloud(val at: Long, val usage: CloudUsage)

    @Volatile
    private var cloudCache: CachedCloud? = null

    private val cloudTransport = UrlConnectionTransport()

    init {
        register(
            ServiceMethod(
                name = "report",
                summary = "完整用度报表（汇总 + 按动作 + 按天 + 最近回执）",
                parameters = Schema.params(
                    "days" to Schema.integer("统计天数", 1, 30),
                    "recentLimit" to Schema.integer("最近回执条数", 1, 50),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args ->
            val snapshot = snapshot(
                days = args.optIntOr("days", 7),
                recentLimit = args.optIntOr("recentLimit", 20),
            )
            ServiceResult.ok(
                "${snapshot.report.tokens} tokens",
                snapshot.toJson(includeDetails = true),
            )
        }

        register(
            ServiceMethod(
                name = "summary",
                summary = "只返回 token/耗时/花费汇总",
                parameters = Schema.params(
                    "days" to Schema.integer("统计天数", 1, 30),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args ->
            val snapshot = snapshot(days = args.optIntOr("days", 7))
            ServiceResult.ok(
                "${snapshot.report.tokens} tokens",
                snapshot.toJson(includeDetails = false),
            )
        }

        register(
            ServiceMethod(
                name = "byAction",
                summary = "按动作聚合的 token/耗时/花费",
                parameters = Schema.params(
                    "days" to Schema.integer("统计天数", 1, 30),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args ->
            val snapshot = snapshot(days = args.optIntOr("days", 7), recentLimit = 1)
            ServiceResult.ok(
                "${snapshot.report.byAction.size} actions",
                snapshot.toJson(includeDetails = false)
                    .put("byAction", slicesJson(snapshot.report.byAction)),
            )
        }

        register(
            ServiceMethod(
                name = "byDay",
                summary = "按天聚合的 token/回执/花费",
                parameters = Schema.params(
                    "days" to Schema.integer("统计天数", 1, 30),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args ->
            val snapshot = snapshot(days = args.optIntOr("days", 7), recentLimit = 1)
            ServiceResult.ok(
                "${snapshot.report.byDay.size} days",
                snapshot.toJson(includeDetails = false)
                    .put("byDay", daysJson(snapshot.report.byDay)),
            )
        }

        register(
            ServiceMethod(
                name = "recent",
                summary = "最近的用量回执",
                parameters = Schema.params(
                    "limit" to Schema.integer("数量", 1, 50),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args ->
            val snapshot = snapshot(days = 30, recentLimit = args.optIntOr("limit", 20))
            ServiceResult.ok(
                "${snapshot.report.recent.size} receipts",
                snapshot.toJson(includeDetails = false)
                    .put("recent", receiptsJson(snapshot.report.recent)),
            )
        }

        register(
            ServiceMethod(
                name = "cloud",
                summary = "获取模型服务商的云端 token/费用/额度",
                parameters = Schema.params(
                    "force" to Schema.boolean("忽略 10 分钟缓存，强制刷新"),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args ->
            val usage = cloudUsage(force = args.optBoolOr("force", false))
            ServiceResult.ok(
                usage.error ?: "云端用量",
                usage.toJson(),
            )
        }

        register(
            ServiceMethod(
                name = "compare",
                summary = "本地全量 token/费用 vs 云端",
                parameters = Schema.params(
                    "days" to Schema.integer("统计天数", 1, 30),
                    "force" to Schema.boolean("忽略缓存"),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args ->
            val comparison = compareUsage(
                days = args.optIntOr("days", 7),
                force = args.optBoolOr("force", false),
            )
            val cloudTokens = comparison.cloud.tokens
            val cloudCost = comparison.cloud.costMicros
            ServiceResult.ok(
                "本地 ${comparison.localTokens} / 云端 ${cloudTokens ?: "—"} tokens",
                JSONObject()
                    .put("localTokens", comparison.localTokens)
                    .put("localCostMicros", comparison.localCostMicros)
                    .put("cloud", comparison.cloud.toJson())
                    .put(
                        "tokenDiff",
                        cloudTokens?.let { comparison.localTokens - it },
                    )
                    .put(
                        "costDiffMicros",
                        cloudCost?.let { comparison.localCostMicros - it },
                    ),
            )
        }
    }

    /**
     * 云端用量：请求模型服务商提供的 billing/usage API。
     * 10 分钟内直接返回缓存；force=true 才重新请求（手动刷新）。
     */
    suspend fun cloudUsage(force: Boolean = false): CloudUsage {
        val now = System.currentTimeMillis()
        cloudCache?.let { cached ->
            if (!force && now - cached.at < CLOUD_CACHE_MS) {
                return cached.usage.copy(cached = true)
            }
        }

        val settings = SettingsStore.state.value
        var url = settings.cloudUsageUrl.trim()
        if (url.isEmpty()) {
            val catalog = runCatching { history.catalog() }.getOrDefault(emptyList())
            val baseUrl = catalog.firstOrNull { it.provider.id == settings.brandId }
                ?.provider?.baseUrl?.trimEnd('/')
                .orEmpty()
            url = when (settings.brandId) {
                "openai" -> {
                    val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    val end = System.currentTimeMillis()
                    val start = end - 7 * 86_400_000L
                    "$baseUrl/dashboard/billing/usage?start_date=${format.format(java.util.Date(start))}" +
                        "&end_date=${format.format(java.util.Date(end))}"
                }

                "openrouter" -> "https://openrouter.ai/api/v1/credits"
                "deepseek" -> "$baseUrl/user/balance"
                else -> ""
            }
            if (url.isEmpty()) {
                return CloudUsage(
                    providerId = settings.brandId,
                    source = "未配置",
                    error = "当前品牌没有内置云端用量接口，请在设置里填写 API 地址",
                ).also { cloudCache = CachedCloud(now, it) }
            }
        }
        val key = SettingsStore.cloudUsageKey()
        if (key.isNullOrBlank()) {
            return CloudUsage(
                providerId = settings.brandId,
                source = url,
                error = "请先配置独立的云端查询 Key",
            ).also { cloudCache = CachedCloud(now, it) }
        }
        val headers = buildMap {
            put("Accept", "application/json")
            if (!key.isNullOrBlank()) put("Authorization", "Bearer $key")
        }
        val text = runCatching { cloudTransport.get(url, headers) }.getOrElse { error ->
            return CloudUsage(
                providerId = settings.brandId,
                source = url,
                error = error.message ?: "云端请求失败",
            ).also { cloudCache = CachedCloud(now, it) }
        }
        val usage = runCatching { parseCloudUsage(settings.brandId, url, text) }.getOrElse {
            CloudUsage(
                providerId = settings.brandId,
                source = url,
                error = "云端响应解析失败：${it.message}",
            )
        }
        cloudCache = CachedCloud(now, usage)
        return usage
    }

    suspend fun compareUsage(days: Int = 7, force: Boolean = false): UsageComparison {
        val local = snapshot(days)
        val cloud = cloudUsage(force)
        return UsageComparison(
            local = local,
            cloud = cloud,
            localTokens = local.report.tokens + local.chat.totalTokens + local.group.totalTokens,
            localCostMicros = local.report.costMicros + local.chat.costMicros + local.group.costMicros,
        )
    }

    private fun parseCloudUsage(providerId: String, source: String, text: String): CloudUsage {
        val root = JSONObject(text)
        if (providerId == "deepseek") {
            val infos = root.optJSONArray("balance_infos")
            val first = infos?.optJSONObject(0)
            val balance = first?.optString("total_balance")
            if (!balance.isNullOrBlank()) {
                val currency = first?.optString("currency").orEmpty()
                return CloudUsage(
                    providerId = providerId,
                    source = source,
                    balanceLabel = "$balance $currency".trim(),
                )
            }
        }
        if (providerId == "openrouter") {
            val data = root.optJSONObject("data") ?: root
            val credits = data.optDouble("total_credits", 0.0)
            val used = data.optDouble("total_usage", 0.0)
            if (credits > 0 || used > 0) {
                return CloudUsage(
                    providerId = providerId,
                    source = source,
                    costMicros = (used * 1_000_000).toLong(),
                    balanceMicros = ((credits - used).coerceAtLeast(0.0) * 1_000_000).toLong(),
                )
            }
        }
        fun firstLong(vararg keys: String): Long? {
            keys.forEach { key ->
                if (root.has(key) && !root.isNull(key)) {
                    return root.optLong(key, 0L)
                }
            }
            return null
        }
        fun firstDouble(vararg keys: String): Double? {
            keys.forEach { key ->
                if (root.has(key) && !root.isNull(key)) {
                    return root.optDouble(key, 0.0)
                }
            }
            return null
        }

        val input = firstLong(
            "input_tokens", "prompt_tokens", "tokens_in", "inputTokens",
            "total_input_tokens",
        )
        val output = firstLong(
            "output_tokens", "completion_tokens", "tokens_out", "outputTokens",
            "total_output_tokens",
        )
        val totalUsageCents = firstDouble("total_usage")
        val costUsd = firstDouble("total_cost", "cost", "amount", "usage_cost")
        val balanceUsd = firstDouble("balance", "remaining", "remaining_balance")
        val requests = firstLong("requests", "request_count", "total_requests")
        val limit = firstLong("request_limit", "limit", "quota")
        val costMicros = when {
            totalUsageCents != null -> (totalUsageCents * 10_000).toLong()
            costUsd != null -> (costUsd * 1_000_000).toLong()
            else -> null
        }
        val balanceMicros = balanceUsd?.let { (it * 1_000_000).toLong() }
        if (input == null && output == null && costMicros == null &&
            balanceMicros == null && requests == null
        ) {
            return CloudUsage(
                providerId = providerId,
                source = source,
                error = "响应里没有可识别的 token/费用/额度字段",
            )
        }
        return CloudUsage(
            providerId = providerId,
            source = source,
            tokensIn = input,
            tokensOut = output,
            costMicros = costMicros,
            balanceMicros = balanceMicros,
            requests = requests,
            requestLimit = limit,
        )
    }

    private fun CloudUsage.toJson(): JSONObject = JSONObject()
        .put("providerId", providerId)
        .put("source", source)
        .put("tokensIn", tokensIn)
        .put("tokensOut", tokensOut)
        .put("tokens", tokens)
        .put("costMicros", costMicros)
        .put("balanceMicros", balanceMicros)
        .put("balanceLabel", balanceLabel)
        .put("requests", requests)
        .put("requestLimit", requestLimit)
        .put("cached", cached)
        .put("error", error)

    /** UI / 测试用的类型化入口；命令层与 UI 共用同一份聚合结果。 */
    suspend fun snapshot(days: Int = 7, recentLimit: Int = 20): Snapshot {
        val raw = history.usageReport(
            days = days.coerceIn(1, 30),
            recentLimit = recentLimit.coerceIn(1, 50),
        )
        val pricing = resolvePricing()
        val chat = runCatching { history.chatUsage.summary(days) }
            .getOrDefault(ChatUsageSummary())
        val group = runCatching { history.groupChat.usageSummary(days) }
            .getOrDefault(ChatUsageSummary())
        val providerRows = runCatching { history.chatUsage.byProvider(days) }
            .getOrDefault(emptyList()) +
            runCatching { history.groupChat.usageByProvider(days) }.getOrDefault(emptyList())
        val providers = providerRows
            .groupBy { it.providerId to it.model }
            .map { (key, rows) ->
                UsageProviderSummary(
                    providerId = key.first,
                    model = key.second,
                    inputTokens = rows.sumOf { it.inputTokens },
                    outputTokens = rows.sumOf { it.outputTokens },
                    costMicros = rows.sumOf { it.costMicros },
                    calls = rows.sumOf { it.calls },
                )
            }
            .sortedByDescending { it.totalTokens }
        return Snapshot(
            report = if (pricing != null) raw.repriced(pricing) else raw,
            pricing = pricing,
            chat = chat,
            group = group,
            providers = providers,
        )
    }

    suspend fun report(days: Int = 7, recentLimit: Int = 20): UsageReport =
        snapshot(days, recentLimit).report

    /** 读设置里的品牌/模型，再从模型目录取单价；单价的单位是 USD / 1M tokens。 */
    private suspend fun resolvePricing(): Pricing? {
        val settings = SettingsStore.state.value
        val catalog = runCatching { history.catalog() }.getOrNull().orEmpty()
        val provider = catalog.firstOrNull { it.provider.id == settings.brandId }
        val model = provider?.models?.firstOrNull { it.id == settings.model }
        val catalogInput = model?.inputPrice
        val catalogOutput = model?.outputPrice
        val catalogPrices = if (catalogInput != null && catalogOutput != null) {
            catalogInput to catalogOutput
        } else {
            null
        }
        // catalog() 会过滤 deprecated；设置里仍可能选中旧模型，直接按模型 id 查价格。
        val prices = catalogPrices
            ?: runCatching { history.modelPrices(settings.brandId, settings.model) }.getOrNull()
            ?: return null
        return Pricing(
            providerId = settings.brandId,
            providerLabel = provider?.provider?.label ?: settings.brandId,
            modelId = settings.model,
            modelLabel = model?.label ?: settings.model,
            inputPrice = prices.first,
            outputPrice = prices.second,
        )
    }

    private fun UsageReport.repriced(pricing: Pricing): UsageReport {
        fun cost(tokensIn: Long, tokensOut: Long): Long =
            (tokensIn * pricing.inputPrice + tokensOut * pricing.outputPrice).toLong()
        return copy(
            costMicros = cost(tokensIn, tokensOut),
            byAction = byAction.map {
                it.copy(costMicros = cost(it.tokensIn, it.tokensOut))
            },
            byDay = byDay.map {
                it.copy(costMicros = cost(it.tokensIn, it.tokensOut))
            },
            recent = recent.map {
                it.copy(costMicros = cost(it.tokensIn, it.tokensOut))
            },
        )
    }

    private fun Snapshot.toJson(includeDetails: Boolean): JSONObject =
        report.toJson(includeDetails).apply {
            // 把聊天页的 token/费用并入总览，同时保留独立的 chat 明细。
            put("tokensIn", report.tokensIn + chat.inputTokens + group.inputTokens)
            put("tokensOut", report.tokensOut + chat.outputTokens + group.outputTokens)
            put("tokens", report.tokens + chat.totalTokens + group.totalTokens)
            put("costMicros", report.costMicros + chat.costMicros + group.costMicros)
            put(
                "group",
                JSONObject()
                    .put("inputTokens", group.inputTokens)
                    .put("outputTokens", group.outputTokens)
                    .put("totalTokens", group.totalTokens)
                    .put("costMicros", group.costMicros)
                    .put("calls", group.calls)
                    .put("sessions", group.sessions),
            )
            put(
                "chat",
                JSONObject()
                    .put("inputTokens", chat.inputTokens)
                    .put("outputTokens", chat.outputTokens)
                    .put("totalTokens", chat.totalTokens)
                    .put("costMicros", chat.costMicros)
                    .put("calls", chat.calls)
                    .put("sessions", chat.sessions),
            )
            pricing?.let { put("pricing", it.toJson()) }
            put("costSource", if (pricing != null) "settings_model" else "stored_receipts")
        }

    private fun Pricing.toJson(): JSONObject = JSONObject()
        .put("providerId", providerId)
        .put("providerLabel", providerLabel)
        .put("modelId", modelId)
        .put("modelLabel", modelLabel)
        .put("inputPrice", inputPrice)
        .put("outputPrice", outputPrice)
        .put("unit", "USD per 1M tokens")

    private fun UsageReport.toJson(includeDetails: Boolean): JSONObject {
        val json = JSONObject()
            .put("events", events)
            .put("receipts", receipts)
            .put("tokensIn", tokensIn)
            .put("tokensOut", tokensOut)
            .put("tokens", tokens)
            .put("costMicros", costMicros)
            .put("actionMs", actionMs)
            .put("chainIntact", chainProblems.isEmpty())
            .put("chainProblems", JSONArray(chainProblems))
        if (includeDetails) {
            json.put("byAction", slicesJson(byAction))
                .put("byDay", daysJson(byDay))
                .put("recent", receiptsJson(recent))
        }
        return json
    }

    private fun slicesJson(slices: List<UsageSlice>): JSONArray {
        val array = JSONArray()
        slices.forEach { slice ->
            array.put(
                JSONObject()
                    .put("label", slice.label)
                    .put("count", slice.count)
                    .put("tokens", slice.tokens)
                    .put("tokensIn", slice.tokensIn)
                    .put("tokensOut", slice.tokensOut)
                    .put("ms", slice.ms)
                    .put("costMicros", slice.costMicros),
            )
        }
        return array
    }

    private fun daysJson(days: List<UsageDay>): JSONArray {
        val array = JSONArray()
        days.forEach { day ->
            array.put(
                JSONObject()
                    .put("date", day.date.toString())
                    .put("tokens", day.tokens)
                    .put("tokensIn", day.tokensIn)
                    .put("tokensOut", day.tokensOut)
                    .put("receipts", day.receipts)
                    .put("costMicros", day.costMicros),
            )
        }
        return array
    }

    private fun receiptsJson(receipts: List<UsageReceipt>): JSONArray {
        val array = JSONArray()
        receipts.forEach { receipt ->
            array.put(
                JSONObject()
                    .put("at", receipt.at)
                    .put("action", receipt.action)
                    .put("ok", receipt.ok)
                    .put("ms", receipt.ms)
                    .put("tokensIn", receipt.tokensIn)
                    .put("tokensOut", receipt.tokensOut)
                    .put("tokens", receipt.tokens)
                    .put("costMicros", receipt.costMicros),
            )
        }
        return array
    }
}
