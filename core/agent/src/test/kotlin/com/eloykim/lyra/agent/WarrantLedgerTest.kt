package com.eloykim.lyra.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarrantLedgerTest {

    @Test
    fun `W-1 验证级主张必须带 warrant`() {
        val ledger = WarrantLedger()
        val failed = runCatching { ledger.noteClaim("这是提交按钮", WarrantTier.T2, null) }
        assertTrue(failed.exceptionOrNull() is WarrantRejected)

        val conjecture = ledger.noteClaim("大概是提交按钮", WarrantTier.T0, null)
        assertTrue(conjecture.isConjecture)
    }

    @Test
    fun `W-2 假设缺少反证条件被拒`() {
        val ledger = WarrantLedger()
        val failed = runCatching { ledger.noteHypothesis("当前在结算页", "") }
        assertTrue(failed.exceptionOrNull() is WarrantRejected)

        val ok = ledger.noteHypothesis("当前在结算页", "没有出现「提交订单」按钮")
        assertEquals("没有出现「提交订单」按钮", ok.refutesIf)
    }

    @Test
    fun `W-3 取代保留链条且争议中不可取代`() {
        val ledger = WarrantLedger()
        val first = ledger.noteClaim("按钮是提交", WarrantTier.T2, "无障碍标签")
        val second = ledger.noteClaim("按钮是取消", WarrantTier.T2, "截图 OCR", supersedes = first.id)

        assertEquals(first.id, second.supersedes)
        val failed = runCatching {
            ledger.noteClaim("又变了", WarrantTier.T2, "重复观察", supersedes = first.id)
        }
        assertTrue("已取代的主张再次被取代应被拒", failed.exceptionOrNull() is WarrantRejected)
    }

    @Test
    fun `不可逆动作缺少验证级主张时被拒`() {
        val ledger = WarrantLedger()
        val guess = ledger.noteClaim("大概是支付按钮", WarrantTier.T0, null)

        assertTrue(ledger.guardIrreversible(guess.id).isFailure)
        assertTrue(ledger.guardIrreversible(null).isFailure)

        val verified = ledger.noteClaim("确认是支付按钮", WarrantTier.T2, "无障碍 + OCR 一致")
        assertTrue(ledger.guardIrreversible(verified.id).isSuccess)
    }

    @Test
    fun `W-4 阻塞未知必须显式`() {
        val ledger = WarrantLedger()
        ledger.noteUnknown("用户要送到公司还是家里", importance = 0.9, blocking = true)

        assertEquals(1, ledger.unresolvedBlocking().size)
    }

    @Test
    fun `剪枝只丢已解决项并给出理由`() {
        val ledger = WarrantLedger()
        val closed = ledger.noteHypothesis("在结算页", "看到首页")
        ledger.noteHypothesis("开着", "关闭")
        ledger.closeHypothesis(closed.id, HypothesisStatus.supported)
        val resolved = ledger.noteUnknown("地址选哪个", 0.4, blocking = false)
        ledger.resolveUnknown(resolved.id)

        val view = ledger.pruneView(keepRecent = 1)
        assertEquals(1, view.dropped.size)
        assertTrue(view.dropped.all { it.revisitIf.isNotBlank() })
        assertEquals(1, view.keptHypotheses.size) // 未关闭的那条仍在视图里
    }

    @Test
    fun `P-3 保留假设引用的未知不能被剪`() {
        val ledger = WarrantLedger()
        val unknown = ledger.noteUnknown("当前是不是结算页", 0.8, blocking = false)
        ledger.resolveUnknown(unknown.id)
        val hypothesis = ledger.noteHypothesis("在结算页", "出现首页", aboutUnknown = unknown.id)
        ledger.closeHypothesis(hypothesis.id, HypothesisStatus.refuted)

        val view = ledger.pruneView(keepRecent = 1)
        assertTrue("被假设引用的未知必须保留", view.keptUnknowns.any { it.id == unknown.id })
    }

    @Test
    fun `剪枝不改账本本身`() {
        val ledger = WarrantLedger()
        ledger.noteUnknown("地址", 0.5, false).also { ledger.resolveUnknown(it.id) }
        val before = ledger.unresolvedBlocking().size

        ledger.pruneView(keepRecent = 1)
        assertEquals(before, ledger.unresolvedBlocking().size)
    }
}
