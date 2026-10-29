package com.eloykim.lyra.agent

import kotlinx.coroutines.CancellationException

/**
 * 首页命令的「先列应用、再启动」阶段。
 *
 * 为什么必须独立于屏幕循环：
 * - Agent 启动时前台往往是 Lyra 自己，直接 observe/act 只会在 Lyra 界面里打转；
 * - 目标应用可能还没打开，必须先通过 `application.list` 拿到**所有可启动应用**；
 * - 让模型从这份真实目录里选一个包名，再用 `application.launch` 打开；
 * - 打开成功后才进入读屏 / 点击循环。
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val launchable: Boolean = true,
    val system: Boolean = false,
    val self: Boolean = false,
)

/** 从应用目录里选出来的结果。 */
sealed interface AppLaunchChoice {

    /** 选择这个包名；必须来自 application.list。 */
    data class Launch(val packageName: String) : AppLaunchChoice

    /** 目录里没有合适的应用，向用户提问。 */
    data class Ask(val question: String) : AppLaunchChoice

    /** 无法判断，交还给调用方处理。 */
    data object Skip : AppLaunchChoice
}

/**
 * 目标应用选择器。
 *
 * 实现方通常是模型；测试里换成固定返回。`lastUsage` / `lastCostMicros`
 * 让会话把这次选择也记进预算与账本，避免出现「看不见的模型调用」。
 */
fun interface AppLaunchPlanner {

    suspend fun choose(task: String, apps: List<AppInfo>): AppLaunchChoice

    val lastUsage: AgentUsage? get() = null

    val lastCostMicros: Long? get() = null
}

/** 启动阶段的结果。 */
sealed interface AppLaunchOutcome {

    /** 已成功打开目标应用；可以开始屏幕循环。 */
    data class Launched(
        val app: AppInfo,
        /** application.list 返回的可启动应用数量。 */
        val listingCount: Int,
        val message: String,
    ) : AppLaunchOutcome

    /** 需要用户指定应用；不要继续在 Lyra 里尝试。 */
    data class NeedsUser(
        val question: String,
        val listingCount: Int,
    ) : AppLaunchOutcome

    /** 目录或启动失败。 */
    data class Failed(
        val code: String,
        val message: String,
    ) : AppLaunchOutcome
}

/**
 * 执行固定顺序：`application.list` → planner 选择 → `application.launch`。
 *
 * 这个类不依赖 Android，也不认识模型；[AppCatalogService] 与 [AppLaunchPlanner]
 * 让它在单测里可以完整跑一遍。
 */
class AppLaunchFlow(
    private val apps: AppCatalogService,
    private val planner: AppLaunchPlanner,
) {

    suspend fun prepare(task: String): AppLaunchOutcome {
        return try {
            prepareOrThrow(task)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            AppLaunchOutcome.Failed(
                code = "app_launch_error",
                message = t.message ?: t::class.java.simpleName,
            )
        }
    }

    private suspend fun prepareOrThrow(task: String): AppLaunchOutcome {
        // 1. 必须先调用 application 服务，列出全部应用。
        val listing = apps.handle("list", null)
        if (!listing.ok) {
            return AppLaunchOutcome.Failed(
                code = listing.code ?: "app_catalog_unavailable",
                message = listing.message.ifBlank { "应用目录不可用" },
            )
        }

        val catalog = apps.listApps()
            .filter { it.launchable && !it.self }
            .distinctBy { it.packageName }
        if (catalog.isEmpty()) {
            return AppLaunchOutcome.Failed(
                code = "app_catalog_empty",
                message = "应用目录里没有可启动的应用",
            )
        }

        // 2. 根据列出的程序选择目标。
        val choice = planner.choose(task, catalog)
        val target = when (choice) {
            is AppLaunchChoice.Launch -> catalog.firstOrNull {
                it.packageName == choice.packageName
            } ?: return AppLaunchOutcome.NeedsUser(
                question = "没有找到应用 ${choice.packageName}，请告诉我要用哪个应用",
                listingCount = catalog.size,
            )

            is AppLaunchChoice.Ask -> return AppLaunchOutcome.NeedsUser(
                question = choice.question.ifBlank { "要在哪个应用里完成这件事？" },
                listingCount = catalog.size,
            )

            AppLaunchChoice.Skip -> return AppLaunchOutcome.NeedsUser(
                question = "没有找到能完成这件事的应用，请告诉我要打开哪个应用",
                listingCount = catalog.size,
            )
        }

        // 3. 用 application.launch 打开；成功后才允许进入屏幕循环。
        val launched = apps.launch(target.packageName)
        return if (launched.ok) {
            AppLaunchOutcome.Launched(
                app = target,
                listingCount = catalog.size,
                message = launched.message,
            )
        } else {
            AppLaunchOutcome.Failed(
                code = launched.code ?: "app_launch_failed",
                message = launched.message.ifBlank { "打开 ${target.label} 失败" },
            )
        }
    }
}
