package com.eloykim.lyra.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyGuardTest {

    private fun decision(
        kind: ActionKind = ActionKind.TAP,
        pkg: String = "com.example.shop",
        label: String? = null,
        screen: List<String> = emptyList(),
    ) = PolicyGuard.evaluate(kind, pkg, label, screen)

    @Test
    fun `禁止启动 Lyra 自身`() {
        val d = decision(kind = ActionKind.LAUNCH, pkg = PolicyGuard.SELF_PACKAGE)
        assertTrue(d is PolicyDecision.Deny)
        assertEquals(RiskScope.SELF, (d as PolicyDecision.Deny).scope)
    }

    @Test
    fun `操作自身界面永远被拒绝`() {
        val d = decision(pkg = PolicyGuard.SELF_PACKAGE, label = "允许这一次")
        assertTrue(d is PolicyDecision.Deny)
        assertEquals(RiskScope.SELF, (d as PolicyDecision.Deny).scope)
    }

    @Test
    fun `系统设置与权限界面永远被拒绝`() {
        val d = decision(pkg = "com.android.settings", label = "无障碍")
        assertTrue(d is PolicyDecision.Deny)
        assertEquals(RiskScope.SYSTEM, (d as PolicyDecision.Deny).scope)
    }

    @Test
    fun `收银台屏幕直接判定为支付`() {
        val d = decision(label = "立即支付", screen = listOf("收银台", "合计 ¥32.5"))
        assertTrue(d is PolicyDecision.Deny)
        assertEquals(RiskScope.PAYMENT, (d as PolicyDecision.Deny).scope)
    }

    @Test
    fun `支付应用整包拒绝`() {
        val d = decision(pkg = "com.eg.android.AlipayGphone", label = "扫一扫")
        assertTrue(d is PolicyDecision.Deny)
        assertEquals(RiskScope.PAYMENT, (d as PolicyDecision.Deny).scope)
    }

    @Test
    fun `密码与验证码界面强制交还用户`() {
        val d = decision(label = "确认", screen = listOf("请输入支付密码"))
        assertTrue(d is PolicyDecision.Deny)
        assertEquals(RiskScope.CREDENTIAL, (d as PolicyDecision.Deny).scope)
    }

    @Test
    fun `发消息需要授权`() {
        val d = decision(label = "发送")
        assertTrue(d is PolicyDecision.RequireAuth)
        assertEquals(RiskScope.MESSAGING, (d as PolicyDecision.RequireAuth).scope)
    }

    @Test
    fun `删除文件需要授权`() {
        val d = decision(label = "删除")
        assertTrue(d is PolicyDecision.RequireAuth)
        assertEquals(RiskScope.FILE, (d as PolicyDecision.RequireAuth).scope)
    }

    @Test
    fun `退出登录需要授权`() {
        val d = decision(label = "退出登录")
        assertTrue(d is PolicyDecision.RequireAuth)
        assertEquals(RiskScope.ACCOUNT, (d as PolicyDecision.RequireAuth).scope)
    }

    @Test
    fun `聊天应用整体不接管`() {
        val d = decision(pkg = "com.tencent.mm", label = "发送")
        assertTrue(d is PolicyDecision.Deny)
        assertEquals(RiskScope.MESSAGING, (d as PolicyDecision.Deny).scope)
    }

    @Test
    fun `任意应用的聊天页面都不接管`() {
        val d = decision(
            pkg = "com.ss.android.ugc.aweme",
            label = "发送",
            screen = listOf("发送消息", "按住 说话"),
        )
        assertTrue(d is PolicyDecision.Deny)
        assertEquals(RiskScope.MESSAGING, (d as PolicyDecision.Deny).scope)
    }

    @Test
    fun `普通浏览放行`() {
        val d = decision(label = "双层吉士堡")
        assertEquals(PolicyDecision.Allow, d)
    }

    @Test
    fun `启动资金类应用本身允许`() {
        val d = decision(kind = ActionKind.LAUNCH, pkg = "com.eg.android.AlipayGphone")
        assertEquals(PolicyDecision.Allow, d)
    }
}
