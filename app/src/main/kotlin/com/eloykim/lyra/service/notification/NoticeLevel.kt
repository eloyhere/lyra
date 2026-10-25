package com.eloykim.lyra.service.notification

import android.app.NotificationManager

/**
 * 打断级别（AMP §5.5）。
 *
 * 定义放在 service 层：通知能力归服务目录，UI 侧的 LyraNotifications 只是投递封装。
 */
enum class NoticeLevel(val channelId: String, val importance: Int, val title: String) {
    /** P0 安全：伤害、数据泄露、不可逆破坏。 */
    SAFETY("lyra_safety", NotificationManager.IMPORTANCE_HIGH, "安全提醒"),

    /** P1 承诺：需要用户裁决，否则任务停在这里。 */
    COMMITMENT("lyra_commitment", NotificationManager.IMPORTANCE_HIGH, "需要你的确认"),

    /** P2 机会：有结果或分叉点，可以稍后处理。 */
    OPPORTUNITY("lyra_opportunity", NotificationManager.IMPORTANCE_DEFAULT, "任务进展"),

    /** P3 好奇：锦上添花，不打扰。 */
    CURIOSITY("lyra_curiosity", NotificationManager.IMPORTANCE_LOW, "顺便一提"),

    /** P4 静默：只进通知栏，不出声、不弹。 */
    SILENT("lyra_silent", NotificationManager.IMPORTANCE_MIN, "记录"),
}
