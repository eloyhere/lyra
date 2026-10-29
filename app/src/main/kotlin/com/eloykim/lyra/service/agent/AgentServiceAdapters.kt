package com.eloykim.lyra.service.agent

import com.eloykim.lyra.agent.AppCatalogService
import com.eloykim.lyra.agent.AppInfo
import com.eloykim.lyra.agent.ServiceResult as AgentServiceResult
import com.eloykim.lyra.agent.VoiceService
import com.eloykim.lyra.service.CommandRegistry
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceText
import org.json.JSONObject

/**
 * 把 Agent 的宿主服务端口接到统一命令层。
 *
 * 这里不再自己用 PackageManager / TextToSpeech 实现能力，只把动作翻译成
 * `application.list` / `voice.speak` 等命令，保证终端与 Agent 走同一条路径。
 */
class CommandAppCatalog(private val commands: CommandRegistry) : AppCatalogService {

    /** 最近一次 application.list 的结构化结果；启动阶段直接复用，避免重复列目录。 */
    @Volatile
    private var cachedApps: List<AppInfo> = emptyList()

    override suspend fun handle(action: String, query: String?): AgentServiceResult {
        val command = when (action.lowercase()) {
            "list", "search" -> "application.list"
            "info" -> "application.info"
            "launch" -> "application.launch"
            else -> return AgentServiceResult(
                false,
                "bad_action",
                "application 只支持 list/search/info/launch，收到：$action",
            )
        }
        val args = JSONObject()
        when {
            command == "application.launch" -> {
                val packageName = query?.trim().orEmpty()
                if (packageName.isBlank()) {
                    return AgentServiceResult(false, "bad_request", "application launch 缺少包名")
                }
                args.put("package", packageName)
            }

            !query.isNullOrBlank() -> {
                if (command == "application.info") {
                    args.put("package", query.trim())
                } else {
                    args.put("query", query.trim())
                    args.put("limit", 2000)
                }
            }

            command == "application.list" -> {
                // 首页 Agent 需要完整应用目录，不能只拿前 60 个。
                args.put("limit", 2000)
            }
        }
        val result = commands.run(command, args)
        if (command == "application.list" && result.ok) {
            cachedApps = parseApps(result)
        }
        return result.toAgentResult()
    }

    override suspend fun listApps(): List<AppInfo> = cachedApps

    override suspend fun launch(packageName: String): AgentServiceResult {
        val result = commands.run(
            "application.launch",
            JSONObject().put("package", packageName),
        )
        return result.toAgentResult()
    }

    private fun parseApps(result: ServiceResult): List<AppInfo> {
        val array = result.data.optJSONArray("apps") ?: return emptyList()
        val apps = ArrayList<AppInfo>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val packageName = item.optString("package")
            if (packageName.isBlank()) continue
            apps += AppInfo(
                packageName = packageName,
                label = item.optString("label").ifBlank { packageName },
                launchable = item.optBoolean("launchable", true),
                system = item.optBoolean("system"),
                self = item.optBoolean("self"),
            )
        }
        return apps
    }
}

class CommandVoiceService(private val commands: CommandRegistry) : VoiceService {

    override suspend fun handle(action: String, text: String?): AgentServiceResult {
        return when (action.lowercase()) {
            "speak" -> {
                val content = text?.trim().orEmpty()
                if (content.isBlank()) {
                    return AgentServiceResult(false, "bad_request", "voice speak 缺少 text")
                }
                val result = commands.run(
                    "voice.speak",
                    JSONObject().put("text", content).put("wait", false),
                )
                if (result.ok) {
                    AgentServiceResult(true, message = "已开始朗读：${content.take(60)}")
                } else {
                    result.toAgentResult()
                }
            }

            "stop" -> {
                val result = commands.run("voice.stop")
                if (result.ok) AgentServiceResult(true, message = "已停止朗读")
                else result.toAgentResult()
            }

            else -> AgentServiceResult(false, "bad_action", "voice 只支持 speak/stop，收到：$action")
        }
    }

    /** 兼容旧调用方；命令层没有常驻资源需要释放。 */
    fun release() = Unit
}

private fun ServiceResult.toAgentResult(): AgentServiceResult =
    AgentServiceResult(
        ok = ok,
        code = code.ifBlank { null },
        message = ServiceText.format(this),
    )
