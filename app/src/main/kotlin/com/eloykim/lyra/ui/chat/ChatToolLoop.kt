package com.eloykim.lyra.ui.chat

import com.eloykim.lyra.network.LlmClient
import com.eloykim.lyra.network.LlmMessage
import com.eloykim.lyra.network.LlmTool
import com.eloykim.lyra.network.LlmToolCall
import kotlinx.coroutines.CancellationException

/**
 * 一次服务调用的执行结果。
 *
 * [label] 形如 `file.delete /sdcard/a.png`，回填给模型和兜底汇总都用它；
 * [output] 是给模型看的完整结果（回填前会按本轮预算截断）。
 * [readOnly] 标记只读命令：只读调用重复出现时要重新执行，否则模型核对状态会拿到过期快照。
 */
internal data class ToolRun(
    val label: String,
    val ok: Boolean,
    val output: String,
    val quote: MessageQuote? = null,
    val readOnly: Boolean = false,
) {
    /** 结果首行：批量调用时只回填这一行，避免几千行输出把上下文撑爆。 */
    val brief: String
        get() = output.lineSequence().firstOrNull()?.trim().orEmpty()
}

/** 工具循环的产出，字段含义与 [com.eloykim.lyra.network.LlmResult] 对齐。 */
internal data class ToolLoopOutcome(
    val text: String?,
    val reasoning: String?,
    val quote: MessageQuote?,
    val inputTokens: Int,
    val outputTokens: Int,
    /** 实际执行的服务调用数。 */
    val calls: Int,
    /** 其中失败的个数。 */
    val failures: Int,
)

/**
 * 聊天里的服务调用循环。
 *
 * 旧实现每轮只执行 `take(3)` 个调用、最多 3 轮：模型一次批量发 21 个删除时只执行了
 * 前 3 个，其余被静默丢掉，最后又拿「服务调用次数已达上限」冒充回答。现在：
 *
 *  - 一轮里的调用全部执行，只受单轮 / 单条回复的总量上限约束，超出的明确回填给模型；
 *  - 相同调用（工具名 + 参数完全一致）只执行一次，但只读调用例外：删完再列一次目录
 *    必须拿到新快照，不能复用旧结果；
 *  - 模型原地打转（整轮都是重复的写操作）时直接收尾；
 *  - 轮次用完再补一次「只能用文字」的请求，尽量让模型自己把结果说出来；
 *  - 模型始终不给文字时，用真实执行记录兜底，而不是编一条错误。
 */
internal class ChatToolLoop(
    private val client: LlmClient,
    private val tools: List<LlmTool>,
    private val execute: suspend (LlmToolCall) -> ToolRun,
) {

    suspend fun run(messages: MutableList<LlmMessage>): ToolLoopOutcome {
        // 工具名 + 参数 -> 结果：同一调用不重复执行（重复删文件、重复发消息都是伤害）。
        val done = LinkedHashMap<String, ToolRun>()
        val runs = ArrayList<ToolRun>()
        val statements = ArrayList<String>()
        var reasoning: String? = null
        var quote: MessageQuote? = null
        var inputTokens = 0
        var outputTokens = 0
        var executed = 0
        var round = 0

        while (round < MAX_ROUNDS) {
            round += 1
            val result = client.complete(messages, tools)
            inputTokens += result.usage.inputTokens
            outputTokens += result.usage.outputTokens
            result.reasoning?.trim()?.takeIf { it.isNotBlank() }?.let { reasoning = it }
            val statement = result.text?.trim()?.takeIf { it.isNotBlank() }
            if (statement != null) statements += statement

            if (result.toolCalls.isEmpty()) {
                return ToolLoopOutcome(
                    text = statement ?: statements.lastOrNull() ?: summary(runs),
                    reasoning = reasoning,
                    quote = quote,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    calls = executed,
                    failures = runs.count { !it.ok },
                )
            }

            val calls = result.toolCalls
            // 调用多时只回填每个结果的首行；调用少时给完整结果，方便模型继续推理。
            val detailed = calls.size <= DETAILED_CALLS
            val perCall = (RESULT_BUDGET / calls.size.coerceAtLeast(1))
                .coerceIn(MIN_CALL_CHARS, MAX_CALL_CHARS)
            val callBlock = StringBuilder()
            val resultBlock = StringBuilder()
            var fresh = 0

            for ((index, call) in calls.withIndex()) {
                val slot = index + 1
                callBlock.append('\n').append(slot).append(". ").append(describe(call))
                val key = keyOf(call)

                val cached = done[key]
                if (cached != null && !cached.readOnly) {
                    appendRun(resultBlock, slot, cached, note = "已执行过，未重复执行", detailed)
                    continue
                }
                if (fresh >= MAX_CALLS_PER_ROUND || executed >= MAX_CALLS) {
                    resultBlock.append('\n').append(slot).append(". ").append(describe(call))
                        .append(" -> 未执行：本次回复的调用额度已用完")
                    continue
                }

                fresh += 1
                executed += 1
                val run = try {
                    execute(call)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    ToolRun(
                        label = describe(call),
                        ok = false,
                        output = "调用失败：${t.message ?: t.javaClass.simpleName}",
                    )
                }.limited(perCall)
                done[key] = run
                runs += run
                run.quote?.let { quote = it }
                appendRun(resultBlock, slot, run, note = if (run.ok) "成功" else "失败", detailed)
            }

            messages += LlmMessage(
                "assistant",
                buildString {
                    if (statement != null) append(statement).append("\n\n")
                    append("工具调用（").append(calls.size).append(" 个）：").append(callBlock)
                },
            )
            messages += LlmMessage(
                "user",
                buildString {
                    append("工具结果：").append(resultBlock)
                    append("\n\n请基于工具结果继续回答用户。")
                    append("已经成功执行的调用不要重复执行；")
                    append("还需要别的调用就继续调用，否则直接用文字回答。")
                },
            )

            // 整轮都是重复调用 / 额度已满：再问也是原地打转，直接走收尾。
            if (fresh == 0) break
        }

        return wrapUp(messages, runs, statements, reasoning, quote, inputTokens, outputTokens, executed)
    }

    /** 轮次用完或模型原地打转：再发一次不带工具的请求，逼它用文字收尾。 */
    private suspend fun wrapUp(
        messages: List<LlmMessage>,
        runs: List<ToolRun>,
        statements: List<String>,
        reasoning: String?,
        quote: MessageQuote?,
        inputTokens: Int,
        outputTokens: Int,
        executed: Int,
    ): ToolLoopOutcome {
        var input = inputTokens
        var output = outputTokens
        var finalReasoning = reasoning
        // 上一条通常就是「工具结果」的 user 消息：并进去而不是再追加一条，
        // 免得 Anthropic / Gemini 这种要求角色交替的接口报格式错误。
        val closing = if (messages.lastOrNull()?.role == "user") {
            messages.dropLast(1) + LlmMessage("user", messages.last().content + "\n\n" + WRAP_UP_PROMPT)
        } else {
            messages + LlmMessage("user", WRAP_UP_PROMPT)
        }
        val wrap = try {
            client.complete(closing, emptyList())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            null
        }
        if (wrap != null) {
            input += wrap.usage.inputTokens
            output += wrap.usage.outputTokens
            wrap.reasoning?.trim()?.takeIf { it.isNotBlank() }?.let { finalReasoning = it }
        }
        return ToolLoopOutcome(
            text = wrap?.text?.trim()?.takeIf { it.isNotBlank() }
                ?: statements.lastOrNull()
                ?: summary(runs),
            reasoning = finalReasoning,
            quote = quote,
            inputTokens = input,
            outputTokens = output,
            calls = executed,
            failures = runs.count { !it.ok },
        )
    }

    private fun appendRun(
        block: StringBuilder,
        slot: Int,
        run: ToolRun,
        note: String,
        detailed: Boolean,
    ) {
        block.append('\n').append(slot).append(". ").append(run.label).append(" · ").append(note)
        val body = if (detailed) run.output else run.brief
        if (body.isNotBlank()) {
            block.append('\n')
            body.lineSequence().forEach { line ->
                if (line.isNotBlank()) block.append("    ").append(line).append('\n')
                else block.append('\n')
            }
            while (block.isNotEmpty() && block.last() == '\n') block.setLength(block.length - 1)
        }
    }

    /** 模型没有给出文字时，用真实执行记录兜底：用户要看到做过什么，而不是一条假报错。 */
    private fun summary(runs: List<ToolRun>): String {
        if (runs.isEmpty()) {
            return "模型这次没有返回文字，也没有执行任何服务调用。可以再问一次，或者补充一下具体要做什么。"
        }
        val failed = runs.count { !it.ok }
        return buildString {
            append("已执行 ").append(runs.size).append(" 个服务调用")
            if (failed > 0) append("（其中 ").append(failed).append(" 个失败）")
            append("：")
            runs.take(MAX_SUMMARY_LINES).forEach { run ->
                append("\n- ").append(run.label)
                if (run.brief.isNotBlank()) append("：").append(run.brief)
            }
            if (runs.size > MAX_SUMMARY_LINES) {
                append("\n- 另外还有 ").append(runs.size - MAX_SUMMARY_LINES).append(" 个调用")
            }
            append("\n\n（模型这次没有给出文字说明，上面是自动汇总的执行结果。）")
        }
    }

    private fun describe(call: LlmToolCall): String =
        call.name + " " + call.argumentsJson.replace(Regex("\\s+"), " ").trim()

    private fun keyOf(call: LlmToolCall): String =
        call.name + '\u0000' + call.argumentsJson.replace(Regex("\\s+"), " ").trim()

    private fun ToolRun.limited(maxChars: Int): ToolRun =
        if (output.length <= maxChars) {
            this
        } else {
            copy(output = output.take(maxChars).trimEnd() + "\n…（已截断）")
        }

    internal companion object {
        /** 一条回复最多来回几轮（不含最后那次「只能用文字」的收尾请求）。 */
        const val MAX_ROUNDS = 4

        /** 单轮最多执行多少个调用：模型一次批量发很多时不再静默丢弃。 */
        const val MAX_CALLS_PER_ROUND = 32

        /** 一条回复最多执行多少个调用。 */
        const val MAX_CALLS = 64

        /** 回填给模型的工具结果总字符预算。 */
        const val RESULT_BUDGET = 12_000

        const val MIN_CALL_CHARS = 240
        const val MAX_CALL_CHARS = 4_000

        /** 调用数不超过这个值时回填完整结果，超过时只回填首行。 */
        const val DETAILED_CALLS = 6

        const val MAX_SUMMARY_LINES = 24

        const val WRAP_UP_PROMPT =
            "工具调用额度已经用完（或你在重复同一批调用）。请不要再调用工具，" +
                "直接用文字把已经完成的操作、结果和还没做完的部分告诉用户。"
    }
}
