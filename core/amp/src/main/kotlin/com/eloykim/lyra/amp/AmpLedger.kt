package com.eloykim.lyra.amp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 只追加的注意力账本（X-1）。
 *
 * 没有 update / delete API —— 修正只能通过追加新对象表达。
 */
object AmpLedger {

    data class Snapshot(
        val frames: List<Frame> = emptyList(),
        val receipts: List<Receipt> = emptyList(),
    ) {
        val head: Ref?
            get() = frames.lastOrNull()?.let { Ref("frame", it.id) }

        val cycle: Int get() = frames.lastOrNull()?.cycle ?: 0
    }

    private val lock = Any()
    private val frames = ArrayList<Frame>()
    private val receipts = ArrayList<Receipt>()

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** 写入前自检不变量；违反直接抛异常，而不是「先写上再说」。 */
    fun append(frame: Frame): Frame {
        val violations = frame.violations()
        check(violations.isEmpty()) { "Frame 违反 AMP 不变量: $violations" }
        synchronized(lock) {
            frames += frame
            publish()
        }
        return frame
    }

    fun append(receipt: Receipt): Receipt {
        synchronized(lock) {
            receipts += receipt
            publish()
        }
        return receipt
    }

    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(frames.toList(), receipts.toList()) }

    /** 仅测试/清空数据时使用；生产环境不应调用（X-1）。 */
    fun resetForTest() {
        synchronized(lock) {
            frames.clear()
            receipts.clear()
            publish()
        }
    }

    private fun publish() {
        _state.value = Snapshot(frames.toList(), receipts.toList())
    }
}
