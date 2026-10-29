package com.eloykim.lyra.agent

import com.eloykim.lyra.network.LlmClient
import com.eloykim.lyra.network.LlmMessage
import com.eloykim.lyra.network.LlmTool
import org.json.JSONObject

/**
 * 用模型从 `application.list` 的真实目录里选一个要启动的应用。
 *
 * 这是首页命令的第一步：模型只看到「任务 + 已安装可启动应用」，不需要看屏幕，
 * 因此不会去点 Lyra 自己的界面。选择结果必须能在目录里找到，否则转成提问。
 */
class LlmAppLaunchPlanner(
    private val client: LlmClient,
    private val inputPrice: Double? = null,
    private val outputPrice: Double? = null,
) : AppLaunchPlanner {

    override var lastUsage: AgentUsage? = null
        private set

    override var lastCostMicros: Long? = null
        private set

    override suspend fun choose(task: String, apps: List<AppInfo>): AppLaunchChoice {
        if (apps.isEmpty()) return AppLaunchChoice.Skip

        val catalog = apps
            .sortedBy { it.label.lowercase() }
            .joinToString("\n") { app -> "- ${app.label}（${app.packageName}）" }
        val messages = listOf(
            LlmMessage("system", SYSTEM_PROMPT),
            LlmMessage(
                "user",
                buildString {
                    appendLine("任务：$task")
                    appendLine()
                    appendLine("应用目录（application.list，共 ${apps.size} 个可启动应用）：")
                    append(catalog)
                },
            ),
        )

        val result = client.complete(messages, TOOLS)
        lastUsage = AgentUsage(result.usage.inputTokens, result.usage.outputTokens)
        lastCostMicros = if (inputPrice != null && outputPrice != null) {
            (result.usage.inputTokens * inputPrice + result.usage.outputTokens * outputPrice).toLong()
        } else {
            null
        }

        val call = result.toolCalls.firstOrNull()
        if (call == null) {
            // 模型没给工具调用：能从文本里认出目录中的包名就启动，否则转成提问。
            val text = result.text.orEmpty().trim()
            val matched = apps.firstOrNull { text.contains(it.packageName) }
            return if (matched != null) {
                AppLaunchChoice.Launch(matched.packageName)
            } else {
                AppLaunchChoice.Ask(
                    text.ifBlank { "没有找到合适的应用，请告诉我要打开哪个应用" },
                )
            }
        }

        val args = runCatching { JSONObject(call.argumentsJson) }.getOrDefault(JSONObject())
        return when (call.name) {
            "launch" -> {
                val raw = args.optString("package").trim()
                val resolved = resolve(raw, apps)
                if (resolved == null) {
                    AppLaunchChoice.Ask(
                        "应用目录里没有「${raw.ifBlank { "目标" }}」，请告诉我要打开哪个应用",
                    )
                } else {
                    AppLaunchChoice.Launch(resolved.packageName)
                }
            }

            "ask" -> AppLaunchChoice.Ask(
                args.optString("question").ifBlank { "要在哪个应用里完成这件事？" },
            )

            else -> AppLaunchChoice.Ask("没有找到合适的应用，请告诉我要打开哪个应用")
        }
    }

    private fun resolve(raw: String, apps: List<AppInfo>): AppInfo? {
        if (raw.isBlank()) return null
        return apps.firstOrNull { it.packageName.equals(raw, ignoreCase = true) }
            ?: apps.firstOrNull { it.label.equals(raw, ignoreCase = true) }
            ?: apps.firstOrNull { it.label.contains(raw, ignoreCase = true) }
    }

    private companion object {

        private val TOOLS = listOf(
            LlmTool(
                name = "launch",
                description = "从应用目录中选择一个应用并打开；package 必须来自目录",
                parametersJson = """
                    {"type":"object","properties":{
                      "package":{"type":"string","description":"目录里的包名"},
                      "why":{"type":"string","description":"为什么选它"}
                    },"required":["package"]}
                """.trimIndent(),
            ),
            LlmTool(
                name = "ask",
                description = "目录里没有能完成任务的应用时，向用户询问要打开哪个应用",
                parametersJson = """
                    {"type":"object","properties":{"question":{"type":"string"}},"required":["question"]}
                """.trimIndent(),
            ),
        )

        private val SYSTEM_PROMPT = """
            你是 Lyra 的任务路由器。用户说了一句话，你只做一件事：
            从「应用目录」（application.list 的结果）里选出最能完成任务的应用，调用 launch(package)。
            规则：
            1. 只能从目录里选，package 必须逐字来自目录；不要编造包名。
            2. 当前屏幕是 Lyra 自己的界面，不能把 Lyra 作为目标，也不要尝试操作当前界面。
            3. 点外卖、打车、买咖啡、查快递这类任务，选对应的生活服务应用。
            4. 目录里没有能完成任务的应用时，调用 ask(question) 问用户要打开哪个应用。
            5. 不要输出解释，只调用一个工具。
        """.trimIndent()
    }
}
