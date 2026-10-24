package com.eloykim.lyra.service

import android.content.Context
import com.eloykim.lyra.permission.PermissionBroker
import org.json.JSONArray
import org.json.JSONObject

/**
 * 服务容器：按 id 查找服务、执行命令、生成工具目录。
 *
 * Agent 侧只需要两件事：
 * 1. [catalog] / [describe] 作为系统提示里的能力清单；
 * 2. [call] 执行 `service.method`。
 */
class ServiceHub(
    services: List<PhoneService>,
    private val context: Context? = null,
) {

    private val ordered = services.toList()
    private val byId = LinkedHashMap<String, PhoneService>()

    init {
        ordered.forEach { service ->
            require(byId.put(service.id, service) == null) { "重复的服务 id：${service.id}" }
        }
    }

    val services: List<PhoneService> get() = ordered

    fun service(id: String): PhoneService? = byId[id]

    suspend fun call(
        serviceId: String,
        method: String,
        args: JSONObject = JSONObject(),
    ): ServiceResult {
        val service = byId[serviceId] ?: return ServiceResult.fail(
            "unknown_service",
            "没有服务 $serviceId；可用：${byId.keys.joinToString()}",
        )

        val permissions = service.requiredPermissions(method)
        if (permissions.isNotEmpty()) {
            val ctx = context ?: return ServiceResult.fail(
                "permission_unavailable",
                "当前没有 Activity，无法申请权限",
            )
            if (!PermissionBroker.ensure(ctx, permissions)) {
                return ServiceResult.fail(
                    "permission_required",
                    "缺少权限：${permissions.joinToString()}，请授权后重试",
                )
            }
        }

        // 统一的大输入/大结果闸门，避免服务把超大 JSON 交给模型导致 OOM。
        if (args.toString().length > MAX_ARGS_CHARS) {
            return ServiceResult.fail("args_too_large", "参数超过 ${MAX_ARGS_CHARS} 字符")
        }
        val result = service.call(method, args)
        if (result.message.length > MAX_MESSAGE_CHARS ||
            result.data.toString().length > MAX_RESULT_CHARS
        ) {
            return ServiceResult.fail(
                "result_too_large",
                "服务结果超过上限（${MAX_RESULT_CHARS} 字符），已拒绝返回",
            )
        }
        return result
    }

    /** 完整目录：服务、方法、参数 schema、风险级别。 */
    fun catalog(): JSONObject {
        val servicesJson = JSONArray()
        ordered.forEach { service ->
            val methodsJson = JSONArray()
            service.methods.forEach { method ->
                methodsJson.put(
                    JSONObject()
                        .put("name", method.name)
                        .put("summary", method.summary)
                        .put("risk", method.risk.name.lowercase())
                        .put("parameters", method.parameters),
                )
            }
            servicesJson.put(
                JSONObject()
                    .put("id", service.id)
                    .put("title", service.title)
                    .put("description", service.description)
                    .put("methods", methodsJson),
            )
        }
        return JSONObject().put("services", servicesJson)
    }

    fun catalogJson(): String = catalog().toString()

    /** 给模型系统提示用的紧凑清单；每条命令一行。 */
    fun describe(): String = buildString {
        appendLine("可用手机命令（service.method）：")
        ordered.forEach { service ->
            appendLine("- ${service.id}（${service.title}）：${service.description}")
            service.methods.forEach { method ->
                append("  · ${service.id}.${method.name} [${method.risk.name.lowercase()}] ${method.summary}")
                val required = method.parameters.optJSONArray("required")
                if (required != null && required.length() > 0) {
                    append("（必填：")
                    append((0 until required.length()).joinToString(",") { required.optString(it) })
                    append("）")
                }
                appendLine()
            }
        }
    }

    fun close() {
        ordered.forEach { service -> runCatching { service.close() } }
    }

    private companion object {
        const val MAX_ARGS_CHARS = 256_000
        const val MAX_RESULT_CHARS = 1_000_000
        const val MAX_MESSAGE_CHARS = 256_000
    }
}
