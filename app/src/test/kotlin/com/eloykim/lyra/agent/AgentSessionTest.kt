package com.eloykim.lyra.agent

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.eloykim.lyra.attention.BudgetScheduler
import com.eloykim.lyra.attention.InMemoryHandleStore
import com.eloykim.lyra.attention.BudgetVector
import org.junit.Assert.assertTrue
import org.junit.Test

private fun makeObs(id: Long, pkg: String) = Observation(
    obsId = id,
    packageName = pkg,
    elements = listOf(
        ObservedElement(1, "搜索", clickable = true),
        ObservedElement(2, "输入框", editable = true),
    ),
)

class AgentSessionTest {

    private class FakeGateway(
        var observation: Observation = makeObs(1, "com.example.app"),
        private val onTap: (Int) -> ActionResult = { ActionResult.Ok(makeObs(2, "com.example.app")) },
    ) : AutomationGateway {

        val calls = mutableListOf<String>()

        override suspend fun observe(): Observation {
            calls += "observe"
            return observation
        }

        override suspend fun tap(nodeId: Int, obsId: Long): ActionResult {
            calls += "tap:$nodeId@$obsId"
            return onTap(nodeId)
        }

        override suspend fun setText(nodeId: Int, obsId: Long, text: String): ActionResult {
            calls += "text:$nodeId=$text"
            return ActionResult.Ok(observation)
        }

        override suspend fun scroll(direction: Int, obsId: Long): ActionResult {
            calls += "scroll:$direction@$obsId"
            return ActionResult.Ok(observation)
        }

        override suspend fun launch(packageName: String): ActionResult {
            calls += "launch:$packageName"
            return ActionResult.Ok(makeObs(3, packageName))
        }

        override suspend fun back(): ActionResult {
            calls += "back"
            return ActionResult.Ok(observation)
        }
    }


    @Test
    fun `按脚本执行 tap 并更新观察`() = runBlocking {
        val gateway = FakeGateway()
        val backend = ScriptedBackend(
            listOf<ScriptStep>(
                { Verb.Act("tap", nodeId = 1, why = "点搜索") },
                { Verb.Done },
            ),
        )

        val events = AgentSession(gateway, backend).run("测试").toList()

        assertTrue(gateway.calls.contains("tap:1@1"))
        assertTrue(events.any { it is AgentEvent.Finished })
        // 动作后的新观察被采纳（obsId 2）
        val observed = events.filterIsInstance<AgentEvent.Observed>()
        assertEquals(2L, observed.last().observation.obsId)
    }

    @Test
    fun `命中硬边界立即终止`() = runBlocking {
        val gateway = FakeGateway(
            onTap = { ActionResult.Blocked("blocked", "检测到支付流程", "payment") },
        )
        val backend = ScriptedBackend(listOf<ScriptStep>({ Verb.Act("tap", nodeId = 1) }))

        val events = AgentSession(gateway, backend).run("付款").toList()

        assertTrue(events.any { it is AgentEvent.Failed && it.reason.contains("安全策略") })
        assertTrue(events.none { it is AgentEvent.Finished })
    }

    @Test
    fun `需要授权时停下来等用户`() = runBlocking {
        val gateway = FakeGateway(
            onTap = { ActionResult.NeedsConsent("c.1", "messaging", "发送内容：发送") },
        )
        val backend = ScriptedBackend(listOf<ScriptStep>({ Verb.Act("tap", nodeId = 1) }))

        val events = AgentSession(gateway, backend).run("发消息").toList()

        assertTrue(events.any { it is AgentEvent.NeedsUser })
    }

    @Test
    fun `超过最大步数会失败`() = runBlocking {
        val gateway = FakeGateway()
        val backend = object : AgentBackend {
            override fun reset(task: String) = Unit
            override suspend fun next(observation: Observation, step: Int) = Verb.Reflect
        }

        val events = AgentSession(gateway, backend, maxSteps = 3).run("死循环").toList()

        assertTrue(events.any { it is AgentEvent.Failed && it.reason.contains("最大步数") })
    }

    @Test
    fun `启动阶段先列应用再启动然后才读屏`() = runBlocking {
        val gateway = FakeGateway()
        val catalog = object : AppCatalogService {
            val calls = mutableListOf<String>()

            override suspend fun handle(action: String, query: String?): ServiceResult {
                calls += "$action:${query.orEmpty()}"
                return ServiceResult(true, message = "共 2 个应用")
            }

            override suspend fun listApps(): List<AppInfo> = listOf(
                AppInfo("com.sankuai.meituan", "美团"),
            )

            override suspend fun launch(packageName: String): ServiceResult {
                calls += "launch:$packageName"
                return ServiceResult(true, message = "launched")
            }
        }
        val planner = AppLaunchPlanner { _, _ -> AppLaunchChoice.Launch("com.sankuai.meituan") }

        val events = AgentSession(
            gateway = gateway,
            backend = ScriptedBackend(listOf<ScriptStep>({ Verb.Done })),
            apps = catalog,
            launchPlanner = planner,
        ).run("帮我点一份麦当劳").toList()

        assertTrue(
            events.any { it is AgentEvent.Noted && it.text.contains("已打开 美团") },
        )
        // 启动前不能读屏：第一帧必须是目标应用，而不是 Lyra。
        assertTrue(gateway.calls.first() == "observe")
        assertTrue(events.any { it is AgentEvent.Finished })
    }

    @Test
    fun `启动阶段失败时不读屏直接结束`() = runBlocking {
        val gateway = FakeGateway()
        val catalog = object : AppCatalogService {
            override suspend fun handle(action: String, query: String?): ServiceResult =
                ServiceResult(false, "app_catalog_unavailable", "应用目录不可用")
        }
        val planner = AppLaunchPlanner { _, _ -> AppLaunchChoice.Launch("com.sankuai.meituan") }

        val events = AgentSession(
            gateway = gateway,
            backend = ScriptedBackend(listOf<ScriptStep>({ Verb.Done })),
            apps = catalog,
            launchPlanner = planner,
        ).run("帮我点外卖").toList()

        assertTrue(gateway.calls.isEmpty())
        assertTrue(
            events.any { it is AgentEvent.Failed && it.reason.contains("启动应用失败") },
        )
    }

    @Test
    fun `启动阶段需要用户指定应用时不读屏`() = runBlocking {
        val gateway = FakeGateway()
        val catalog = object : AppCatalogService {
            override suspend fun handle(action: String, query: String?): ServiceResult =
                ServiceResult(true, message = "共 1 个应用")

            override suspend fun listApps(): List<AppInfo> = listOf(
                AppInfo("com.sankuai.meituan", "美团"),
            )
        }
        val planner = AppLaunchPlanner { _, _ -> AppLaunchChoice.Ask("要打开哪个应用？") }

        val events = AgentSession(
            gateway = gateway,
            backend = ScriptedBackend(listOf<ScriptStep>({ Verb.Done })),
            apps = catalog,
            launchPlanner = planner,
        ).run("帮我点一份麦当劳").toList()

        assertTrue(gateway.calls.isEmpty())
        assertTrue(
            events.any { it is AgentEvent.NeedsUser && it.message == "要打开哪个应用？" },
        )
    }

    @Test
    fun `set_text 动作写入第一个可编辑节点`() = runBlocking {
        val gateway = FakeGateway()
        val backend = ScriptedBackend(
            listOf<ScriptStep>({ Verb.Act("set_text", text = "新闻") }, { Verb.Done }),
        )

        AgentSession(gateway, backend).run("搜索新闻").toList()

        assertTrue(gateway.calls.contains("text:2=新闻"))
    }

    @Test
    fun `文本输入默认选第一个可编辑节点`() = runBlocking {
        val gateway = FakeGateway()
        val backend = ScriptedBackend(
            listOf<ScriptStep>({ Verb.Act("input", text = "麦当劳") }, { Verb.Done }),
        )

        AgentSession(gateway, backend).run("搜索").toList()

        assertTrue(gateway.calls.contains("text:2=麦当劳"))
    }

    @Test
    fun `启动应用走 launch 而不是 tap`() = runBlocking {
        val gateway = FakeGateway()
        val backend = ScriptedBackend(
            listOf<ScriptStep>(
                { Verb.Act("launch", packageName = "com.android.settings") },
                { Verb.Done },
            ),
        )

        AgentSession(gateway, backend).run("打开设置").toList()

        assertTrue(gateway.calls.contains("launch:com.android.settings"))
    }

    @Test
    fun `预算耗尽时终止并说明维度`() = runBlocking {
        val backend = ScriptedBackend(
            listOf(
                { Verb.Act("tap", nodeId = 1) },
                { Verb.Act("tap", nodeId = 2) },
            ),
        )
        val session = AgentSession(
            gateway = FakeGateway(),
            backend = backend,
            budget = BudgetScheduler(BudgetVector(tokens = 10_000, ms = 10_000, calls = 2)),
            maxSteps = 10,
        )

        val events = session.run("预算测试").toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().firstOrNull()

        assertTrue("应因预算耗尽终止", failed?.reason?.contains("预算耗尽") == true)
    }

    @Test
    fun `read 动词从句柄取内容并回传 backend`() = runBlocking {
        val store = InMemoryHandleStore()
        val ref = store.put("tree", "第1行 内容\n第2行 内容\n第3行 内容", chunkChars = 8)

        var fedBack: String? = null
        val backend = object : AgentBackend {
            private var step = 0
            override fun reset(task: String) { step = 0 }
            override suspend fun next(observation: Observation, step: Int): Verb =
                if (step == 1) Verb.Read(ref.id, null, 2) else Verb.Done
            override fun onResult(verb: Verb, ok: Boolean, code: String?, message: String?) {
                if (verb is Verb.Read) fedBack = message
            }
        }

        val events = AgentSession(FakeGateway(), backend, handles = store, maxSteps = 5)
            .run("读句柄").toList()

        assertTrue("会话应记录读取事件", events.any { it is AgentEvent.Noted && it.text.contains("读取句柄") })
        assertTrue("内容应回传给 backend", !fedBack.isNullOrBlank())
    }

    @Test
    fun `application list 调用应用目录服务并回传结果`() = runBlocking {
        val catalog = object : AppCatalogService {
            override suspend fun handle(action: String, query: String?): ServiceResult {
                assertEquals("list", action)
                return ServiceResult(
                    true,
                    message = "共 2 个可启动应用：\n微信（com.tencent.mm）\n设置（com.android.settings）",
                )
            }
        }
        var fedBack: String? = null
        val backend = object : AgentBackend {
            private var step = 0
            override fun reset(task: String) { step = 0 }
            override suspend fun next(observation: Observation, step: Int): Verb =
                if (this.step++ == 0) Verb.Application("list") else Verb.Done

            override fun onResult(verb: Verb, ok: Boolean, code: String?, message: String?) {
                if (verb is Verb.Application) fedBack = message
            }
        }

        val events = AgentSession(FakeGateway(), backend, apps = catalog).run("列出应用").toList()

        assertTrue(events.any { it is AgentEvent.Noted && it.text.contains("应用目录") })
        assertTrue("服务结果要回传给模型", fedBack?.contains("微信") == true)
    }

    @Test
    fun `语音服务收到 speak 文本`() = runBlocking {
        var spoken: String? = null
        val voice = object : VoiceService {
            override suspend fun handle(action: String, text: String?): ServiceResult {
                spoken = text
                return ServiceResult(true, message = "已朗读：$text")
            }
        }
        val backend = object : AgentBackend {
            private var step = 0
            override fun reset(task: String) { step = 0 }
            override suspend fun next(observation: Observation, step: Int): Verb =
                if (this.step++ == 0) Verb.Voice("speak", "你好，我是 Lyra") else Verb.Done
        }

        val events = AgentSession(FakeGateway(), backend, voice = voice).run("朗读").toList()

        assertEquals("你好，我是 Lyra", spoken)
        assertTrue(events.any { it is AgentEvent.Noted && it.text.contains("语音") })
    }

    @Test
    fun `未接入应用目录时返回失败而不是崩溃`() = runBlocking {
        var serviceOk: Boolean? = null
        val backend = object : AgentBackend {
            private var step = 0
            override fun reset(task: String) { step = 0 }
            override suspend fun next(observation: Observation, step: Int): Verb =
                if (this.step++ == 0) Verb.Application("list") else Verb.Done

            override fun onResult(verb: Verb, ok: Boolean, code: String?, message: String?) {
                if (verb is Verb.Application) serviceOk = ok
            }
        }

        val events = AgentSession(FakeGateway(), backend).run("列出应用").toList()

        assertTrue(events.any { it is AgentEvent.Noted && it.text.contains("失败") })
        assertEquals(false, serviceOk)
    }

    @Test
    fun `打断预算为 0 时拒绝提问并终止`() = runBlocking {
        val backend = ScriptedBackend(listOf<ScriptStep>({ Verb.Ask("要不要继续？") }))
        val session = AgentSession(
            gateway = FakeGateway(),
            backend = backend,
            budget = BudgetScheduler(BudgetVector(tokens = 10_000, ms = 10_000, calls = 10, interrupts = 0)),
            maxSteps = 5,
        )

        val events = session.run("打扰测试").toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().firstOrNull()

        assertTrue("应因打断预算终止", failed?.reason?.contains("INTERRUPTS") == true)
    }

    @Test
    fun `时间预算耗尽会终止`() = runBlocking {
        val backend = object : AgentBackend {
            override fun reset(task: String) = Unit
            override suspend fun next(observation: Observation, step: Int): Verb {
                delay(30)
                return Verb.Done
            }
        }
        val session = AgentSession(
            gateway = FakeGateway(),
            backend = backend,
            budget = BudgetScheduler(BudgetVector(tokens = 10_000, ms = 5, calls = 10)),
            maxSteps = 3,
        )

        val events = session.run("时间测试").toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().firstOrNull()

        assertTrue("应因时间预算终止", failed?.reason?.contains("MS") == true)
    }
}