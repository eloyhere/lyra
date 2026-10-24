package com.eloykim.lyra.policy

/**
 * 动作类别。策略按「动作 + 目标 + 屏幕语义」判断，而不是只看包名。
 */
enum class ActionKind { TAP, TEXT, SCROLL, GLOBAL, LAUNCH }

/**
 * 风险域。PAYMENT / CREDENTIAL / SYSTEM / SELF 是**绝对禁止**，没有任何开关能打开；
 * MESSAGING / FILE / ACCOUNT 需要用户当场授权。
 */
enum class RiskScope {
    PAYMENT,
    CREDENTIAL,
    SYSTEM,
    SELF,
    MESSAGING,
    FILE,
    ACCOUNT,
}

sealed interface PolicyDecision {

    /** 放行。 */
    data object Allow : PolicyDecision

    /** 永远拒绝，不可解锁。 */
    data class Deny(val scope: RiskScope, val reason: String) : PolicyDecision

    /** 需要用户当场授权（且授权 UI 必须是 Agent 无法操作的）。 */
    data class RequireAuth(val scope: RiskScope, val summary: String) : PolicyDecision
}

/**
 * 纯 Kotlin 的策略引擎：不依赖 Android，可在 JVM 上单测。
 *
 * 判断顺序（先硬后软）：
 * 1. 自身界面 —— 绝不允许 Agent 操作 Lyra（否则它可以自己点「允许」）
 * 2. 系统设置 / 权限控制器 —— 绝不允许（否则它可以自己开权限）
 * 3. 凭证类界面（密码、验证码、生物识别）—— 绝对拒绝
 * 4. 资金类（收银台、付款、免密支付）—— 绝对拒绝
 * 5. 发消息 / 文件 / 账号 —— 需要授权
 */
object PolicyGuard {

    const val SELF_PACKAGE = "com.eloykim.lyra"

    @Volatile
    private var allowSelfOperation = false

    /** 仅调试构建使用：允许 Agent 操作 Lyra 自身界面。发布版必须保持 false。 */
    fun setAllowSelfOperation(allow: Boolean) {
        allowSelfOperation = allow
    }

    fun isSelfOperationAllowed(): Boolean = allowSelfOperation

    /** 系统设置、权限授予、安装器：Agent 一旦能操作这些，就能自我提权。 */
    private val SYSTEM_PACKAGES = setOf(
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.systemui",
    )

    /** 资金类应用：整包拒绝操作（可以启动，但点不动）。 */
    private val PAYMENT_PACKAGES = setOf(
        "com.eg.android.AlipayGphone",          // 支付宝
        "com.unionpay",                          // 云闪付
        "com.paypal.android.p2pmobile",
        "com.google.android.apps.walletnfcrel",  // Google Wallet
        "com.tencent.mm.plugin.wallet"           // 微信支付插件（若单独出现）
    )

    /**
     * 聊天类应用：整体不接管。
     *
     * 这是一个产品决策，不只是技术限制：消息自动化风险高（冒名、诈骗、封号），
     * 且这些 App 大多对无障碍关闭或反自动化。需要发消息时走「人工确认」路径。
     */
    private val CHAT_PACKAGES = setOf(
        "com.tencent.mm",            // 微信
        "com.tencent.mobileqq",      // QQ
        "com.whatsapp",
        "org.telegram.messenger",
        "com.facebook.orca",
        "org.thoughtcrime.securesms",
        "com.discord",
        "com.snapchat.android",
        "com.tencent.wework",        // 企业微信（走 API 而不是模拟点击）
    )

    /** 凭证界面标记：出现即交还用户。 */
    private val CREDENTIAL_MARKERS = listOf(
        "输入支付密码", "支付密码", "输入密码", "短信验证码", "验证码",
        "指纹验证", "面容验证", "人脸识别", "请验证指纹",
    )

    /** 资金界面标记：出现即判定进入收银台。 */
    private val PAYMENT_MARKERS = listOf(
        "收银台", "确认支付", "立即支付", "去支付", "提交订单并支付",
        "免密支付", "付款码", "银行卡支付", "余额支付", "分期付款",
    )

    /** 聊天页面标记：不按包名，按屏幕语义判定（抖音、微博、小红书的私信页都算）。 */
    private val CHAT_SCREEN_MARKERS = listOf(
        "发送消息", "输入消息", "按住 说话", "按住说话", "语音消息",
        "消息免打扰", "聊天信息", "拍一拍", "撤回了一条消息", "发消息给",
    )

    /** 发消息：需要授权。 */
    private val MESSAGING_MARKERS = listOf(
        "发送", "发送消息", "发送给", "评论", "发布", "回复", "转发",
    )

    /** 文件 / 数据变更：需要授权。 */
    private val FILE_MARKERS = listOf(
        "删除", "永久删除", "移到废纸篓", "重命名", "移动", "上传", "覆盖",
        "清空", "格式化",
    )

    /** 账号 / 订阅变更：需要授权。 */
    private val ACCOUNT_MARKERS = listOf(
        "退出登录", "注销账号", "解绑", "更换手机号", "修改手机号",
        "开通会员", "订阅", "自动续费", "确认购买",
    )

    /**
     * @param kind        动作类别
     * @param packageName 目标应用包名
     * @param label       被操作元素的文本/描述（TAP/TEXT 时必传）
     * @param screenText  当前屏幕上的文本集合（用于识别「这一屏是不是收银台」）
     */
    fun evaluate(
        kind: ActionKind,
        packageName: String,
        label: String? = null,
        screenText: Collection<String> = emptyList(),
    ): PolicyDecision {
        // 1. 自身：默认绝不（调试构建可通过 setAllowSelfOperation 打开）
        if (packageName.isEmpty()) {
            return PolicyDecision.Deny(RiskScope.SELF, "无法识别目标应用")
        }
        if (packageName == SELF_PACKAGE && !allowSelfOperation) {
            return PolicyDecision.Deny(RiskScope.SELF, "不允许操作 Lyra 自身界面（防止自我授权）")
        }

        // 2. 系统设置 / 权限
        if (packageName in SYSTEM_PACKAGES) {
            return PolicyDecision.Deny(RiskScope.SYSTEM, "不允许操作系统设置或权限界面（防止自我提权）")
        }

        val haystack = buildList {
            addAll(screenText)
            label?.let { add(it) }
        }

        // 3. 凭证
        if (haystack.any { text -> CREDENTIAL_MARKERS.any { text.contains(it) } }) {
            return PolicyDecision.Deny(RiskScope.CREDENTIAL, "界面包含密码/验证码/生物识别，必须由用户手动完成")
        }

        // 4. 资金
        if (kind == ActionKind.TAP || kind == ActionKind.TEXT) {
            if (packageName in PAYMENT_PACKAGES) {
                return PolicyDecision.Deny(RiskScope.PAYMENT, "资金类应用不允许自动操作")
            }
            if (packageName in CHAT_PACKAGES) {
                return PolicyDecision.Deny(
                    RiskScope.MESSAGING,
                    "聊天类应用不接管（消息自动化已关闭，请人工发送）",
                )
            }
            if (haystack.any { text -> CHAT_SCREEN_MARKERS.any { text.contains(it) } }) {
                return PolicyDecision.Deny(
                    RiskScope.MESSAGING,
                    "检测到聊天页面，消息类操作不由 Agent 代劳",
                )
            }
            if (haystack.any { text -> PAYMENT_MARKERS.any { text.contains(it) } }) {
                return PolicyDecision.Deny(RiskScope.PAYMENT, "检测到支付流程，Lyra 永不代付")
            }
            label?.let { l ->
                if (PAYMENT_MARKERS.any { l.contains(it) }) {
                    return PolicyDecision.Deny(RiskScope.PAYMENT, "该按钮属于支付动作（$l）")
                }
            }
        }

        // 5. 需要授权的三类
        label?.let { l ->
            when {
                MESSAGING_MARKERS.any { l.contains(it) } ->
                    return PolicyDecision.RequireAuth(RiskScope.MESSAGING, "发送内容：$l")

                FILE_MARKERS.any { l.contains(it) } ->
                    return PolicyDecision.RequireAuth(RiskScope.FILE, "修改文件：$l")

                ACCOUNT_MARKERS.any { l.contains(it) } ->
                    return PolicyDecision.RequireAuth(RiskScope.ACCOUNT, "账号变更：$l")
            }
        }

        return PolicyDecision.Allow
    }

    /** 给定包名是否属于「可启动但不可操作」的资金类应用。 */
    fun isPaymentPackage(packageName: String): Boolean = packageName in PAYMENT_PACKAGES

    /** 供 UI 解释「为什么被拦」。 */
    fun scopeLabel(scope: RiskScope): String = when (scope) {
        RiskScope.PAYMENT -> "资金"
        RiskScope.CREDENTIAL -> "凭证"
        RiskScope.SYSTEM -> "系统"
        RiskScope.SELF -> "自身"
        RiskScope.MESSAGING -> "发消息"
        RiskScope.FILE -> "文件"
        RiskScope.ACCOUNT -> "账号"
    }
}
