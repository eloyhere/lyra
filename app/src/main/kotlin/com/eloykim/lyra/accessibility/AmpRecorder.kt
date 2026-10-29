package com.eloykim.lyra.accessibility

import com.eloykim.lyra.amp.protocol.receipt.Outcome
import com.eloykim.lyra.amp.protocol.receipt.ReceiptOp
import com.eloykim.lyra.model.ScreenSnapshot

/**
 * 兼容入口：服务层不再自己维护第二套 AMP 账本。
 *
 * 观察与动作的协议 Frame/Receipt 由 `core:agent` 的 AgentSession 生成，
 * 事件由 `core:data` 的协议事件账本追加。保留这个对象只为让 AIDL 服务
 * 的既有调用点保持无分支，不产生任何写入。
 */
@Deprecated("服务层不再记账；协议对象由 AgentSession / ProtocolLedgerStore 产生")
object AmpRecorder {

    fun onObserved(snapshot: ScreenSnapshot) = Unit

    fun onAction(
        op: ReceiptOp,
        label: String?,
        startedAtMs: Long,
        outcome: Outcome,
        note: String? = null,
    ) = Unit
}
