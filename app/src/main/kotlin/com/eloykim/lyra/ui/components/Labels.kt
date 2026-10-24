package com.eloykim.lyra.ui.components

/** 把账本里的 action 映射成用户能读懂的中文。未知 action 原样返回。 */
fun actionLabel(action: String): String = when (action.lowercase()) {
    "tap" -> "点按"
    "input", "settext", "type" -> "输入"
    "scroll" -> "滚动"
    "launch", "open" -> "启动"
    "back" -> "返回"
    "screenshot" -> "截图"
    "observe" -> "观察"
    "ask" -> "询问"
    "note" -> "记录"
    "plan" -> "计划"
    else -> action
}
