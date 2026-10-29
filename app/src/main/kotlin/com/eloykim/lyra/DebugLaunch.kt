package com.eloykim.lyra

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 仅调试构建使用：
 *
 * ```
 * adb shell am start -n com.eloykim.lyra/.MainActivity \
 *   -e task "列出当前屏幕上的所有条目" -e delay 6000 -f 0x20000000
 * ```
 *
 * delay 毫秒后才开始跑任务，方便先用 adb 把目标 App 切到前台，
 * 这样第一次观察就能看到真正的大界面（用来验证句柄与 read）。
 *
 * 也可以直接打开某一页，方便截图与 UI 验证：
 *
 * ```
 * adb shell am start -n com.eloykim.lyra/.MainActivity -e screen usage
 * ```
 *
 * screen 支持 home / terminal / impressions / usage / inspector / settings。
 */
object DebugLaunch {
    val task = MutableStateFlow<String?>(null)

    /** 调试直达页面；空值表示按正常启动进入首页。 */
    val screen = MutableStateFlow<String?>(null)

    @Volatile
    var startDelayMs: Long = 0

    /** 调试用 token 预算；默认 60000，调小可验证 TOKENS 维度耗尽。 */
    @Volatile
    var budgetTokens: Int = 60_000

    /** 调试用打断预算；设 0 可验证 INTERRUPTS 维度耗尽。 */
    @Volatile
    var budgetInterrupts: Int = 3

    /** 调试用时间预算（毫秒）；设很小可验证 MS 维度耗尽。 */
    @Volatile
    var budgetMs: Long = 300_000

    /** 调试用金额预算（微美元）；默认 5 美元。 */
    @Volatile
    var budgetMoneyMicros: Long = 5_000_000

    /** 调试用：启动时强制压缩账本，保留最近 N 条；0 表示按阈值自动判断。 */
    @Volatile
    var compactKeep: Int = 0

    /** 调试用：只跑一次焦点选择并落一帧，不调用模型；用于验证 AMP 落库链路。 */
    @Volatile
    var focusProbe: Boolean = false

    /** 调试用：允许观察到 Lyra 自身界面，仅 debug 构建且显式传 self=true 时生效。 */
    @Volatile
    var allowSelfObservation: Boolean = false
}