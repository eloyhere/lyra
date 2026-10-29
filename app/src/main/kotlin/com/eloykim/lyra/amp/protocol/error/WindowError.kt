package com.eloykim.lyra.amp.protocol.error

/**
 * `src/error/windowError.ts` 的移植：上下文窗口即使驱逐也无法容纳一页。
 *
 * 注意 `window/Window.kt` 已有一个窄端口内部的 `WindowError`；本类是参考错误模块的
 * 完整分类，两者名称相同但包不同，互不替代。
 */
enum class WindowCode(override val wire: String) : AmpErrorCode {
    WINDOW_FULL("window_full"),
    UNKNOWN_PAGE("unknown_page"),
    ;
}

class WindowError(
    val typedCode: WindowCode,
    problems: List<String>,
) : AmpError("WindowError", typedCode.wire, problems)
