package com.eloykim.lyra.accessibility

import com.eloykim.lyra.model.ScreenSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无障碍服务的可观察状态：是否已连接 + 最近一次快照。
 *
 * 单一事实来源：UI、Agent、Inspector 都只读这里。
 */
object AccessibilityState {

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _snapshot = MutableStateFlow<ScreenSnapshot?>(null)
    val snapshot: StateFlow<ScreenSnapshot?> = _snapshot.asStateFlow()

    internal fun setConnected(value: Boolean) {
        _connected.value = value
    }

    internal fun publish(snapshot: ScreenSnapshot) {
        _snapshot.value = snapshot
    }
}
