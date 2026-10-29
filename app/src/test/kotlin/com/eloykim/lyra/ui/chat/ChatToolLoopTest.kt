package com.eloykim.lyra.ui.chat

import com.eloykim.lyra.network.LlmClient
import com.eloykim.lyra.network.LlmMessage
import com.eloykim.lyra.network.LlmResult
import com.eloykim.lyra.network.LlmTool
import com.eloykim.lyra.network.LlmToolCall
import com.eloykim.lyra.network.LlmUsage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：模型一次批量发多个服务调用时，聊天页必须全部执行。
 *
 * 线上事故是模型一次发了 21 个 file.delete，旧循环 `take(3)` 只执行了前 3 个，
 * 剩下 18 个被静默丢掉，最后界面显示「服务调用次数已达上限」。
 */
class ChatToolLoopTest {

    private val tool = LlmTool(
        name = "lyra_service",
        description = "调用手机服务",
        parametersJson = """{"type":"object","properties":{}}""",
    )

    @Test
    fun `一次批量发 21 个删除全部执行，不再只跑前 3 个`() {
        val client = scripted(
            LlmResult(null, deleteCalls(0 until 21), LlmUsage(10, 5)),
            LlmResult("删除完成", emptyList(), LlmUsage(10, 5)),
        )
        val executed = ArrayList<String>()

        val outcome = runBlocking { ChatToolLoop(client, listOf(tool), executor(executed)).run(seed()) }

        assertEquals(21, executed.size)
        assertEquals(21, outcome.calls)
        assertEquals("删除完成", outcome.text)
    }

    @Test
    fun `同一批调用重复出现时不再执行，改为直接收尾`() {
        val calls = deleteCalls(0 until 21)
        val client = scripted(
            LlmResult(null, calls, LlmUsage()),
            LlmResult(null, calls, LlmUsage()),
            LlmResult("已经全部删掉了", emptyList(), LlmUsage()),
        )
        val executed = ArrayList<String>()

        val outcome = runBlocking { ChatToolLoop(client, listOf(tool), executor(executed)).run(seed()) }

        assertEquals(21, executed.size)
        assertEquals("已经全部删掉了", outcome.text)
        // 收尾请求必须不带工具，模型才只能写文字
        assertTrue(client.toolLists.last().isEmpty())
    }

    @Test
    fun `只读调用重复出现时重新执行，拿到新快照`() {
        val listCall = LlmToolCall(
            id = "call_list",
            name = "lyra_service",
            argumentsJson = """{"service":"file","method":"list","args":{"path":"/"}}""",
        )
        val client = scripted(
            LlmResult(null, listOf(listCall), LlmUsage()),
            LlmResult(null, listOf(listCall), LlmUsage()),
            LlmResult("目录现在是空的", emptyList(), LlmUsage()),
        )
        var runs = 0

        val outcome = runBlocking {
            ChatToolLoop(client, listOf(tool)) {
                runs += 1
                ToolRun(label = "file.list /", ok = true, output = "第 $runs 次快照", readOnly = true)
            }.run(seed())
        }

        assertEquals(2, runs)
        assertEquals("目录现在是空的", outcome.text)
    }

    @Test
    fun `模型始终不写文字时用真实执行记录兜底，而不是假报错`() {
        val client = scripted(
            LlmResult(null, deleteCalls(0 until 21), LlmUsage()),
            LlmResult(null, emptyList(), LlmUsage()),
        )
        val executed = ArrayList<String>()

        val outcome = runBlocking { ChatToolLoop(client, listOf(tool), executor(executed)).run(seed()) }

        assertEquals(21, executed.size)
        assertTrue(outcome.text!!.contains("已执行 21 个服务调用"))
        assertTrue(outcome.text.contains("file.delete /sdcard/f0.png"))
        assertFalse(outcome.text.contains("服务调用次数已达上限"))
    }

    @Test
    fun `每轮都发新调用时轮次用完自动收尾`() {
        val client = scripted(
            LlmResult(null, deleteCalls(0..0), LlmUsage()),
            LlmResult(null, deleteCalls(1..1), LlmUsage()),
            LlmResult(null, deleteCalls(2..2), LlmUsage()),
            LlmResult(null, deleteCalls(3..3), LlmUsage()),
            LlmResult("只做到这里", emptyList(), LlmUsage()),
        )
        val executed = ArrayList<String>()

        val outcome = runBlocking { ChatToolLoop(client, listOf(tool), executor(executed)).run(seed()) }

        assertEquals(ChatToolLoop.MAX_ROUNDS, executed.size)
        assertEquals(ChatToolLoop.MAX_ROUNDS, outcome.calls)
        assertEquals("只做到这里", outcome.text)
    }

    @Test
    fun `调用失败会记进兜底汇总`() {
        val client = scripted(
            LlmResult(
                null,
                listOf(call(0, "/sdcard/a.png"), call(1, "/sdcard/gone.png")),
                LlmUsage(),
            ),
            LlmResult(null, emptyList(), LlmUsage()),
        )
        val executed = ArrayList<String>()

        val outcome = runBlocking {
            ChatToolLoop(client, listOf(tool)) { toolCall ->
                val path = pathOf(toolCall)
                val gone = path.contains("gone")
                ToolRun(
                    label = "file.delete $path",
                    ok = !gone,
                    output = if (gone) "not_found: 没有路径 $path" else "已删除 $path",
                )
            }.run(seed())
        }

        assertEquals(2, outcome.calls)
        assertEquals(1, outcome.failures)
        assertTrue(outcome.text!!.contains("1 个失败"))
        assertTrue(outcome.text.contains("not_found"))
    }

    @Test
    fun `工具调用旁的正文会被保留下来当回答`() {
        val client = scripted(
            LlmResult("我先删这几个文件。", deleteCalls(0..2), LlmUsage()),
            LlmResult(null, emptyList(), LlmUsage(), reasoning = "删完了"),
        )
        val executed = ArrayList<String>()

        val outcome = runBlocking { ChatToolLoop(client, listOf(tool), executor(executed)).run(seed()) }

        assertEquals(3, executed.size)
        assertEquals("我先删这几个文件。", outcome.text)
        assertEquals("删完了", outcome.reasoning)
    }

    @Test
    fun `回填给模型的消息保持一问一答，不出现连续同角色`() {
        val client = scripted(
            LlmResult("先说明一下。", deleteCalls(0..2), LlmUsage()),
            LlmResult("好了", emptyList(), LlmUsage()),
        )
        val executed = ArrayList<String>()

        runBlocking { ChatToolLoop(client, listOf(tool), executor(executed)).run(seed()) }

        val roles = client.requests.last().filterNot { it.role == "system" }.map { it.role }
        roles.zipWithNext().forEach { (left, right) ->
            assertFalse("相邻两条消息角色相同：$roles", left == right)
        }
    }

    // --- 测试替身 -------------------------------------------------------------

    private class ScriptedClient(private val replies: List<LlmResult>) : LlmClient {
        override val model: String = "fake-model"
        val requests = ArrayList<List<LlmMessage>>()
        val toolLists = ArrayList<List<LlmTool>>()
        private var index = 0

        override suspend fun complete(messages: List<LlmMessage>, tools: List<LlmTool>): LlmResult {
            requests += messages.toList()
            toolLists += tools.toList()
            val reply = replies.getOrNull(index) ?: LlmResult(null, emptyList(), LlmUsage())
            index += 1
            return reply
        }
    }

    private fun scripted(vararg replies: LlmResult) = ScriptedClient(replies.toList())

    private fun executor(executed: MutableList<String>): suspend (LlmToolCall) -> ToolRun =
        { toolCall ->
            val path = pathOf(toolCall)
            executed += path
            ToolRun(label = "file.delete $path", ok = true, output = "已删除 $path")
        }

    private fun seed(): MutableList<LlmMessage> =
        mutableListOf(LlmMessage("system", "你是 Lyra"), LlmMessage("user", "好的，请你移除"))

    private fun deleteCalls(range: IntRange): List<LlmToolCall> = range.map { call(it) }

    private fun call(index: Int, path: String = "/sdcard/f$index.png"): LlmToolCall = LlmToolCall(
        id = "call_$index",
        name = "lyra_service",
        argumentsJson = """{"service":"file","method":"delete","args":{"path":"$path"}}""",
    )

    private fun pathOf(toolCall: LlmToolCall): String =
        Regex("\"path\":\"([^\"]+)\"").find(toolCall.argumentsJson)?.groupValues?.get(1).orEmpty()
}
