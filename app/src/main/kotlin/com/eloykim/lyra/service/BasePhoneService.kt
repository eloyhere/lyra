package com.eloykim.lyra.service

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * 服务实现基类：负责方法注册、未知方法拦截、异常归一化和统一的 help。
 *
 * 每个服务都自动拥有 `help` 命令：不带参数列出本服务全部命令，带 `command`
 * 查看单个命令的参数 schema。这样 Agent 永远可以先问「这个服务能做什么」。
 */
abstract class BasePhoneService : PhoneService {

    private val handlers = LinkedHashMap<String, suspend (JSONObject) -> ServiceResult>()
    private val declared = ArrayList<ServiceMethod>()
    private val helpMethod = ServiceMethod(
        name = "help",
        summary = "列出本服务的命令；传 command 查看单个命令的参数",
        parameters = Schema.params(
            "command" to Schema.string("命令名，例如 recent；省略则列出全部"),
        ),
        risk = ServiceRisk.READ,
    )

    protected fun register(
        method: ServiceMethod,
        handler: suspend (JSONObject) -> ServiceResult,
    ) {
        require(declared.none { it.name == method.name }) {
            "服务 $id 重复注册方法 ${method.name}"
        }
        declared += method
        handlers[method.name] = handler
    }

    final override val methods: List<ServiceMethod> get() = declared + helpMethod

    final override suspend fun call(method: String, args: JSONObject): ServiceResult {
        if (method == "help") return help(args)
        val handler = handlers[method] ?: return ServiceResult.fail(
            "unknown_method",
            "服务 $id 没有方法 $method；可用：${methods.joinToString { it.name }}",
        )
        return try {
            handler(args)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (bad: IllegalArgumentException) {
            ServiceResult.fail("bad_args", bad.message ?: "参数不合法")
        } catch (t: Throwable) {
            ServiceResult.fail("internal_error", t.message ?: t::class.java.simpleName)
        }
    }

    private fun help(args: JSONObject): ServiceResult {
        val requested = args.optStringOr("command").removePrefix("$id.")
        if (requested.isNotEmpty()) {
            val method = methods.firstOrNull { it.name == requested }
                ?: return ServiceResult.fail(
                    "unknown_method",
                    "服务 $id 没有命令 $requested；可用：${methods.joinToString { it.name }}",
                )
            return ServiceResult.ok(
                "${id}.${method.name}",
                JSONObject()
                    .put("service", id)
                    .put("title", title)
                    .put("description", description)
                    .put("command", methodJson(method)),
            )
        }

        val array = JSONArray()
        methods.forEach { array.put(methodJson(it)) }
        return ServiceResult.ok(
            "$id: ${methods.size} commands",
            JSONObject()
                .put("service", id)
                .put("title", title)
                .put("description", description)
                .put("methods", array)
                .put("count", methods.size),
        )
    }

    private fun methodJson(method: ServiceMethod): JSONObject = JSONObject()
        .put("name", method.name)
        .put("summary", method.summary)
        .put("risk", method.risk.name.lowercase())
        .put("parameters", method.parameters)
}
