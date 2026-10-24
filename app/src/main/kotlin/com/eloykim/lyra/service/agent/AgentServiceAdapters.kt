package com.eloykim.lyra.service.agent

import com.eloykim.lyra.agent.AppCatalogService
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

    override suspend fun handle(action: String, query: String?): AgentServiceResult {
        val command = when (action.lowercase()) {
            "list", "search" -> "application.list"
            "info" -> "application.info"
            else -> return AgentServiceResult(
                false,
                "bad_action",
                "application 只支持 list/search/info，收到：$action",
            )
        }
        val args = JSONObject()
        if (!query.isNullOrBlank()) {
            if (command == "application.info") {
                args.put("package", query.trim())
            } else {
                args.put("query", query.trim())
                args.put("limit", 60)
            }
        } else if (command == "application.list") {
            args.put("limit", 60)
        }
        return commands.run(command, args).toAgentResult()
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
