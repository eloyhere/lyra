package com.eloykim.lyra.service.provider

import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceHub
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optBoolOr
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqString
import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider 服务：查询 Lyra 自己的服务/命令目录。
 *
 * 适合 Agent 在动作前先确认「现在有哪些能力、参数是什么、风险级别多少」，
 * 避免把整份工具清单常驻在 prompt 里。只读。
 */
class ProviderService : BasePhoneService() {

    override val id: String = "provider"
    override val title: String = "服务查询"
    override val description: String =
        "查询服务与命令目录：服务列表、命令数量、命令参数 schema、按风险/关键词检索。"

    @Volatile
    private var hub: ServiceHub? = null

    fun attach(hub: ServiceHub) {
        this.hub = hub
    }

    init {
        register(
            ServiceMethod(
                name = "list",
                summary = "列出服务（可按服务名/命令名/说明过滤）",
                parameters = Schema.params(
                    "query" to Schema.string("关键词"),
                    "limit" to Schema.integer("数量", 1, 100),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args -> list(args.optStringOr("query"), args.optIntOr("limit", 50)) }

        register(
            ServiceMethod(
                name = "count",
                summary = "服务数、命令数与风险分布",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { count() }

        register(
            ServiceMethod(
                name = "commands",
                summary = "列出扁平命令（可按服务/关键词/风险过滤）",
                parameters = Schema.params(
                    "service" to Schema.string("只看某个服务 id"),
                    "query" to Schema.string("关键词"),
                    "risk" to Schema.string("风险级别", ServiceRisk.entries.map { it.name.lowercase() }),
                    "limit" to Schema.integer("数量", 1, 300),
                    "includeSchema" to Schema.boolean("是否带参数 schema"),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args ->
            commands(
                service = args.optStringOr("service"),
                query = args.optStringOr("query"),
                risk = args.optStringOr("risk"),
                limit = args.optIntOr("limit", 200),
                includeSchema = args.optBoolOr("includeSchema", false),
            )
        }

        register(
            ServiceMethod(
                name = "get",
                summary = "读取单个命令的完整定义",
                parameters = Schema.params(
                    "command" to Schema.string("命令 id，例如 touch.tap"),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args -> get(args.reqString("command")) }

        register(
            ServiceMethod(
                name = "schema",
                summary = "只读取一个命令的参数 schema",
                parameters = Schema.params(
                    "command" to Schema.string("命令 id，例如 dialog.choose"),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args -> schema(args.reqString("command")) }

        register(
            ServiceMethod(
                name = "search",
                summary = "按关键词检索命令",
                parameters = Schema.params(
                    "query" to Schema.string("关键词"),
                    "limit" to Schema.integer("数量", 1, 100),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args ->
            commands(
                service = "",
                query = args.reqString("query"),
                risk = "",
                limit = args.optIntOr("limit", 50),
                includeSchema = false,
            )
        }

        register(
            ServiceMethod(
                name = "risk",
                summary = "按风险级别列出命令",
                parameters = Schema.params(
                    "risk" to Schema.string(
                        "风险级别",
                        ServiceRisk.entries.map { it.name.lowercase() },
                    ),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args ->
            commands(
                service = "",
                query = "",
                risk = args.reqString("risk").lowercase(),
                limit = 300,
                includeSchema = false,
            )
        }
    }

    private fun requireHub(): ServiceHub? = hub

    private fun serviceJson(service: com.eloykim.lyra.service.PhoneService): JSONObject =
        JSONObject()
            .put("id", service.id)
            .put("title", service.title)
            .put("description", service.description)
            .put("methodCount", service.methods.size)

    private fun commandJson(
        serviceId: String,
        serviceTitle: String,
        method: ServiceMethod,
        includeSchema: Boolean,
    ): JSONObject = JSONObject()
        .put("id", "$serviceId.${method.name}")
        .put("service", serviceId)
        .put("serviceTitle", serviceTitle)
        .put("method", method.name)
        .put("summary", method.summary)
        .put("risk", method.risk.name.lowercase())
        .apply { if (includeSchema) put("parameters", method.parameters) }

    private fun list(query: String, limit: Int): ServiceResult {
        val current = requireHub() ?: return notReady()
        val needle = query.trim().lowercase()
        val services = current.services.asSequence()
            .filter { service ->
                needle.isEmpty() ||
                    service.id.contains(needle, true) ||
                    service.title.contains(needle, true) ||
                    service.description.contains(needle, true) ||
                    service.methods.any {
                        it.name.contains(needle, true) || it.summary.contains(needle, true)
                    }
            }
            .take(limit.coerceIn(1, 100))
            .toList()
        val array = JSONArray()
        services.forEach { array.put(serviceJson(it)) }
        return ServiceResult.ok(
            "${services.size} services",
            JSONObject()
                .put("services", array)
                .put("count", services.size)
                .put("totalCommands", current.services.sumOf { it.methods.size }),
        )
    }

    private fun count(): ServiceResult {
        val current = requireHub() ?: return notReady()
        val byRisk = JSONObject()
        ServiceRisk.entries.forEach { risk ->
            val count = current.services.sumOf { service ->
                service.methods.count { it.risk == risk }
            }
            byRisk.put(risk.name.lowercase(), count)
        }
        return ServiceResult.ok(
            "ok",
            JSONObject()
                .put("services", current.services.size)
                .put("commands", current.services.sumOf { it.methods.size })
                .put("byRisk", byRisk),
        )
    }

    private fun commands(
        service: String,
        query: String,
        risk: String,
        limit: Int,
        includeSchema: Boolean,
    ): ServiceResult {
        val current = requireHub() ?: return notReady()
        val needle = query.trim().lowercase()
        val riskFilter = risk.trim().lowercase()
        if (riskFilter.isNotEmpty() && ServiceRisk.entries.none { it.name.equals(riskFilter, true) }) {
            throw IllegalArgumentException("risk 只支持：${ServiceRisk.entries.joinToString { it.name.lowercase() }}")
        }
        val array = JSONArray()
        var truncated = false
        loop@ for (svc in current.services) {
            if (service.isNotBlank() && !svc.id.equals(service, true)) continue
            for (method in svc.methods) {
                if (riskFilter.isNotEmpty() && !method.risk.name.equals(riskFilter, true)) continue
                val id = "${svc.id}.${method.name}"
                if (needle.isNotEmpty() &&
                    !id.contains(needle, true) &&
                    !method.summary.contains(needle, true)
                ) {
                    continue
                }
                if (array.length() >= limit.coerceIn(1, 300)) {
                    truncated = true
                    break@loop
                }
                array.put(commandJson(svc.id, svc.title, method, includeSchema))
            }
        }
        return ServiceResult.ok(
            "${array.length()}${if (truncated) "+" else ""} commands",
            JSONObject()
                .put("commands", array)
                .put("count", array.length())
                .put("truncated", truncated),
        )
    }

    private fun get(raw: String): ServiceResult {
        val current = requireHub() ?: return notReady()
        val found = findCommand(current, raw)
            ?: return ServiceResult.fail(
                "unknown_command",
                "没有命令 $raw；先用 provider.list 或 provider.search 查询",
            )
        return ServiceResult.ok(
            found.first,
            JSONObject()
                .put("command", commandJson(found.second.id, found.second.title, found.third, true)),
        )
    }

    private fun schema(raw: String): ServiceResult {
        val current = requireHub() ?: return notReady()
        val found = findCommand(current, raw)
            ?: return ServiceResult.fail("unknown_command", "没有命令 $raw")
        return ServiceResult.ok(
            "${found.first} schema",
            JSONObject()
                .put("command", found.first)
                .put("risk", found.third.risk.name.lowercase())
                .put("parameters", found.third.parameters),
        )
    }

    private fun findCommand(
        hub: ServiceHub,
        raw: String,
    ): Triple<String, com.eloykim.lyra.service.PhoneService, ServiceMethod>? {
        val id = raw.trim()
        val all = hub.services.flatMap { service ->
            service.methods.map { method ->
                Triple("${service.id}.${method.name}", service, method)
            }
        }
        all.firstOrNull { it.first.equals(id, true) }?.let { return it }
        if ('.' !in id) {
            all.filter { it.third.name.equals(id, true) }
                .takeIf { it.size == 1 }
                ?.firstOrNull()
                ?.let { return it }
        }
        return null
    }

    private fun notReady(): ServiceResult =
        ServiceResult.fail("not_ready", "ProviderService 尚未连接到 ServiceHub")
}
