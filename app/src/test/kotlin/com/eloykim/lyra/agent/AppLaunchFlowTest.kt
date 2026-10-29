package com.eloykim.lyra.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLaunchFlowTest {

    private class FakeCatalog(
        private val items: List<AppInfo>,
        private val listOk: Boolean = true,
        private val launchOk: Boolean = true,
    ) : AppCatalogService {

        val calls = mutableListOf<String>()

        override suspend fun handle(action: String, query: String?): ServiceResult {
            calls += "$action:${query.orEmpty()}"
            return if (listOk) {
                ServiceResult(true, message = "共 ${items.size} 个应用")
            } else {
                ServiceResult(false, "catalog_unavailable", "应用目录不可用")
            }
        }

        override suspend fun listApps(): List<AppInfo> = items

        override suspend fun launch(packageName: String): ServiceResult {
            calls += "launch:$packageName"
            return if (launchOk) {
                ServiceResult(true, message = "launched")
            } else {
                ServiceResult(false, "app_launch_failed", "打不开")
            }
        }
    }

    private fun planner(choice: AppLaunchChoice): AppLaunchPlanner =
        AppLaunchPlanner { _, _ -> choice }

    private val apps = listOf(
        AppInfo("com.sankuai.meituan", "美团"),
        AppInfo("com.example.hidden", "隐藏", launchable = false),
        AppInfo("com.eloykim.lyra", "Lyra", self = true),
    )

    @Test
    fun `先列应用再选择最后启动`() = runBlocking {
        val catalog = FakeCatalog(apps)
        val flow = AppLaunchFlow(catalog, planner(AppLaunchChoice.Launch("com.sankuai.meituan")))

        val outcome = flow.prepare("帮我点一份麦当劳")

        val launched = outcome as AppLaunchOutcome.Launched
        assertEquals("com.sankuai.meituan", launched.app.packageName)
        assertEquals(1, launched.listingCount)
        assertEquals(
            listOf("list:", "launch:com.sankuai.meituan"),
            catalog.calls,
        )
    }

    @Test
    fun `目录失败时直接失败且不调用选择器`() = runBlocking {
        val catalog = FakeCatalog(apps, listOk = false)
        var plannerCalled = false
        val flow = AppLaunchFlow(
            catalog,
            AppLaunchPlanner { _, _ ->
                plannerCalled = true
                AppLaunchChoice.Launch("com.sankuai.meituan")
            },
        )

        val outcome = flow.prepare("帮我点外卖")

        val failed = outcome as AppLaunchOutcome.Failed
        assertEquals("catalog_unavailable", failed.code)
        assertEquals(listOf("list:"), catalog.calls)
        assertTrue(!plannerCalled)
    }

    @Test
    fun `没有合适应用时等待用户而不是继续点 Lyra`() = runBlocking {
        val catalog = FakeCatalog(apps)
        val flow = AppLaunchFlow(catalog, planner(AppLaunchChoice.Ask("要打开哪个应用？")))

        val outcome = flow.prepare("帮我点一份麦当劳")

        val needsUser = outcome as AppLaunchOutcome.NeedsUser
        assertEquals("要打开哪个应用？", needsUser.question)
        assertEquals(listOf("list:"), catalog.calls)
    }

    @Test
    fun `选择器返回目录外的包名时转成提问`() = runBlocking {
        val catalog = FakeCatalog(apps)
        val flow = AppLaunchFlow(catalog, planner(AppLaunchChoice.Launch("com.not.installed")))

        val outcome = flow.prepare("打开不存在的应用")

        assertTrue(outcome is AppLaunchOutcome.NeedsUser)
        assertEquals(listOf("list:"), catalog.calls)
    }

    @Test
    fun `启动失败时返回失败`() = runBlocking {
        val catalog = FakeCatalog(apps, launchOk = false)
        val flow = AppLaunchFlow(catalog, planner(AppLaunchChoice.Launch("com.sankuai.meituan")))

        val outcome = flow.prepare("打开美团")

        val failed = outcome as AppLaunchOutcome.Failed
        assertEquals("app_launch_failed", failed.code)
    }

    @Test
    fun `选择器抛异常时返回失败而不是崩溃`() = runBlocking {
        val catalog = FakeCatalog(apps)
        val flow = AppLaunchFlow(
            catalog,
            AppLaunchPlanner { _, _ -> error("模型超时") },
        )

        val outcome = flow.prepare("打开美团")

        val failed = outcome as AppLaunchOutcome.Failed
        assertEquals("app_launch_error", failed.code)
        assertTrue(failed.message.contains("模型超时"))
    }
}
