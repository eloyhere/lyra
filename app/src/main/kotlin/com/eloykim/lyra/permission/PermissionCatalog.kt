package com.eloykim.lyra.permission

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.eloykim.lyra.accessibility.LyraAccessibilityService
import com.eloykim.lyra.service.notification.LyraNotificationListener
import org.json.JSONObject

/** 权限类型：普通权限安装即授予，运行时权限弹窗申请，特殊访问只能去系统设置开。 */
internal enum class PermissionKind(val wire: String, val label: String) {
    Normal("normal", "普通"),
    Runtime("runtime", "运行时"),
    Special("special", "特殊访问"),
}

/** 权限清单里的一条。 */
internal data class PermissionEntry(
    /** 权限名；特殊访问可能是组件权限（例如无障碍服务）。 */
    val permission: String,
    val label: String,
    val group: String,
    val kind: PermissionKind,
    val granted: Boolean,
    /** 能否在应用内弹系统窗口申请；普通权限和特殊访问都不能。 */
    val requestable: Boolean,
    val summary: String,
    /** 不能弹窗时，告诉用户去哪儿开。 */
    val howToGrant: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("permission", permission)
        .put("label", label)
        .put("group", group)
        .put("kind", kind.wire)
        .put("kindLabel", kind.label)
        .put("granted", granted)
        .put("requestable", requestable)
        .put("summary", summary)
        .apply { howToGrant?.let { put("howToGrant", it) } }
}

/**
 * 应用权限清单。
 *
 * 之前 `grant.list` 只过滤 dangerous 权限，[com.eloykim.lyra.service.status.StatusService]
 * 又硬编码了 6 条，于是清单里根本没有网络、Wi-Fi、文件、数据这些项。
 * 这里以 Manifest 的 `requestedPermissions` 为准，补齐中文名、分组、类型和授权入口，
 * 再把无障碍、通知使用权这类「服务权限」一并列出来。
 */
internal object PermissionCatalog {

    /** 已知权限的中文说明；Manifest 新增权限时即使没配文案也会出现在清单里。 */
    private val KNOWN: Map<String, Known> = mapOf(
        Manifest.permission.INTERNET to Known(
            "网络访问", "网络", PermissionKind.Normal,
            "调用模型接口、发起 HTTP 请求",
        ),
        Manifest.permission.ACCESS_NETWORK_STATE to Known(
            "网络与数据状态", "网络", PermissionKind.Normal,
            "读取 Wi-Fi / 移动数据连接状态、计费网络",
        ),
        Manifest.permission.ACCESS_WIFI_STATE to Known(
            "Wi-Fi 状态", "网络", PermissionKind.Normal,
            "读取 Wi-Fi 开关、SSID、信号强度",
        ),
        Manifest.permission.CHANGE_WIFI_STATE to Known(
            "Wi-Fi 开关", "网络", PermissionKind.Normal,
            "打开系统 Wi-Fi 面板",
        ),
        Manifest.permission.QUERY_ALL_PACKAGES to Known(
            "应用列表", "应用", PermissionKind.Normal,
            "列出已安装应用（含系统应用）",
        ),
        Manifest.permission.MODIFY_AUDIO_SETTINGS to Known(
            "音量与音频", "设备", PermissionKind.Normal,
            "调整媒体音量、切换音频输出",
        ),
        Manifest.permission.SET_ALARM to Known(
            "设置闹钟", "设备", PermissionKind.Normal,
            "通过系统闹钟界面创建闹钟/计时器",
        ),
        Manifest.permission.WAKE_LOCK to Known(
            "保持唤醒", "设备", PermissionKind.Normal,
            "长任务执行时避免休眠",
        ),
        Manifest.permission.VIBRATE to Known(
            "振动", "设备", PermissionKind.Normal,
            "通知振动反馈",
        ),
        Manifest.permission.RECEIVE_BOOT_COMPLETED to Known(
            "开机自启", "设备", PermissionKind.Normal,
            "开机后恢复常驻能力",
        ),
        Manifest.permission.CAMERA to Known(
            "相机", "媒体", PermissionKind.Runtime,
            "拍照、扫码",
        ),
        Manifest.permission.RECORD_AUDIO to Known(
            "麦克风", "语音", PermissionKind.Runtime,
            "语音输入、离线识别",
        ),
        Manifest.permission.POST_NOTIFICATIONS to Known(
            "通知", "通知", PermissionKind.Runtime,
            "发送任务进度与提醒通知",
        ),
        Manifest.permission.SEND_SMS to Known(
            "发送短信", "通信", PermissionKind.Runtime,
            "按用户要求发送短信",
        ),
        Manifest.permission.READ_SMS to Known(
            "读取短信", "通信", PermissionKind.Runtime,
            "读取收件箱内容",
        ),
        Manifest.permission.CALL_PHONE to Known(
            "拨打电话", "通信", PermissionKind.Runtime,
            "直接拨号（未声明时只能打开拨号盘）",
        ),
        Manifest.permission.READ_CONTACTS to Known(
            "读取联系人", "通信", PermissionKind.Runtime,
            "按姓名查找联系人",
        ),
        Manifest.permission.READ_CALENDAR to Known(
            "读取日历", "日历", PermissionKind.Runtime,
            "读取日程",
        ),
        Manifest.permission.READ_MEDIA_IMAGES to Known(
            "读取图片", "媒体", PermissionKind.Runtime,
            "相册读取与处理",
        ),
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED to Known(
            "选择部分图片", "媒体", PermissionKind.Runtime,
            "只让应用访问用户挑选的图片",
        ),
        Manifest.permission.READ_MEDIA_VIDEO to Known(
            "读取视频", "媒体", PermissionKind.Runtime,
            "视频文件读取",
        ),
        Manifest.permission.READ_MEDIA_AUDIO to Known(
            "读取音频", "媒体", PermissionKind.Runtime,
            "音频文件读取",
        ),
        Manifest.permission.READ_EXTERNAL_STORAGE to Known(
            "读取外部存储", "文件", PermissionKind.Runtime,
            "Android 12 及以下的文件读取",
        ),
        Manifest.permission.WRITE_EXTERNAL_STORAGE to Known(
            "写入外部存储", "文件", PermissionKind.Runtime,
            "Android 10 及以下的文件写入",
        ),
        Manifest.permission.MANAGE_EXTERNAL_STORAGE to Known(
            "所有文件访问", "文件", PermissionKind.Special,
            "读写 /sdcard 任意文件（清理、导出、读取日志）",
            howToGrant = "设置 → 应用 → Lyra → 所有文件访问权限",
        ),
        Manifest.permission.BIND_ACCESSIBILITY_SERVICE to Known(
            "无障碍服务", "辅助功能", PermissionKind.Special,
            "读取屏幕结构、执行点击与滑动",
            howToGrant = "设置 → 无障碍 → Lyra → 开启",
        ),
        Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE to Known(
            "通知使用权", "通知", PermissionKind.Special,
            "读取其他应用的通知内容",
            howToGrant = "设置 → 通知 → 通知使用权 → Lyra → 开启",
        ),
        Manifest.permission.PACKAGE_USAGE_STATS to Known(
            "使用情况访问", "应用", PermissionKind.Special,
            "读取应用使用时长与前台应用",
            howToGrant = "设置 → 应用 → 特殊应用权限 → 使用情况访问 → Lyra",
        ),
        Manifest.permission.SYSTEM_ALERT_WINDOW to Known(
            "显示在其他应用上层", "设备", PermissionKind.Special,
            "悬浮窗与全局浮层",
            howToGrant = "设置 → 应用 → 特殊应用权限 → 显示在其他应用上层 → Lyra",
        ),
    )

    private data class Known(
        val label: String,
        val group: String,
        val kind: PermissionKind,
        val summary: String,
        val howToGrant: String? = null,
    )

    /** 靠服务声明实现的权限：Manifest 里没有 uses-permission。 */
    private val SERVICE_PERMISSIONS = setOf(
        Manifest.permission.BIND_ACCESSIBILITY_SERVICE,
        Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE,
    )

    fun entries(context: Context): List<PermissionEntry> {
        val declared = declaredPermissions(context)
        val names = LinkedHashSet<String>()
        names += declared
        // 服务权限（无障碍、通知使用权）不在 requestedPermissions 里，单独补进来。
        if (hasService(context, LyraAccessibilityService::class.java)) {
            names += Manifest.permission.BIND_ACCESSIBILITY_SERVICE
        }
        if (hasService(context, LyraNotificationListener::class.java)) {
            names += Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE
        }
        return names.map { permission -> entryOf(context, permission, declared) }
            .sortedWith(compareBy({ GROUP_ORDER.indexOf(it.group).let { i -> if (i < 0) GROUP_ORDER.size else i } }, { it.label }))
    }

    fun entry(context: Context, permission: String): PermissionEntry? {
        val declared = declaredPermissions(context)
        val known = permission in SERVICE_PERMISSIONS || permission in declared
        if (!known) return null
        return entryOf(context, permission, declared)
    }

    fun declared(context: Context, permission: String): Boolean =
        permission in SERVICE_PERMISSIONS || permission in declaredPermissions(context)

    fun granted(context: Context, permission: String): Boolean = when (permission) {
        Manifest.permission.MANAGE_EXTERNAL_STORAGE -> allFilesAccess(context)
        Manifest.permission.BIND_ACCESSIBILITY_SERVICE -> accessibilityEnabled(context)
        Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE -> listenerEnabled(context)
        else -> context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    fun allFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.contains(context.packageName)
    }

    fun listenerEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private fun entryOf(
        context: Context,
        permission: String,
        declared: List<String>,
    ): PermissionEntry {
        val known = KNOWN[permission]
        val kind = known?.kind ?: kindOf(context, permission)
        val granted = granted(context, permission)
        val requestable = kind == PermissionKind.Runtime &&
            permission in declared &&
            permission != Manifest.permission.MANAGE_EXTERNAL_STORAGE
        return PermissionEntry(
            permission = permission,
            label = known?.label ?: fallbackLabel(permission),
            group = known?.group ?: "其他",
            kind = kind,
            granted = granted,
            requestable = requestable,
            summary = known?.summary ?: "Manifest 声明的权限",
            howToGrant = if (granted || requestable) null else known?.howToGrant ?: defaultHint(kind, permission),
        )
    }

    private fun kindOf(context: Context, permission: String): PermissionKind {
        if (permission == Manifest.permission.MANAGE_EXTERNAL_STORAGE) return PermissionKind.Special
        if (permission in SERVICE_PERMISSIONS) return PermissionKind.Special
        val protection = runCatching {
            context.packageManager.getPermissionInfo(permission, 0).protection
        }.getOrDefault(0)
        return if (protection and android.content.pm.PermissionInfo.PROTECTION_DANGEROUS != 0) {
            PermissionKind.Runtime
        } else {
            PermissionKind.Normal
        }
    }

    private fun defaultHint(kind: PermissionKind, permission: String): String? = when (kind) {
        PermissionKind.Runtime -> "应用内会弹窗申请"
        PermissionKind.Normal -> null
        PermissionKind.Special -> "设置 → 应用 → Lyra → 特殊应用权限（${fallbackLabel(permission)}）"
    }

    private fun declaredPermissions(context: Context): List<String> = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        info.requestedPermissions.orEmpty().filterNot { permission ->
            // AGP 会塞一条 <包名>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION，它不是用户要看的权限。
            permission.startsWith(context.packageName + ".")
        }
    }.getOrDefault(emptyList())

    private fun hasService(context: Context, type: Class<*>): Boolean = runCatching {
        context.packageManager.getServiceInfo(ComponentName(context, type), 0)
        true
    }.getOrDefault(false)

    private fun fallbackLabel(permission: String): String =
        permission.substringAfterLast('.').lowercase().replace('_', ' ')

    private val GROUP_ORDER = listOf("网络", "文件", "媒体", "通信", "通知", "语音", "应用", "设备", "日历", "辅助功能")
}
