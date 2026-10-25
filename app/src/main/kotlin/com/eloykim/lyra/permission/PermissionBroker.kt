package com.eloykim.lyra.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.eloykim.lyra.policy.PolicyGuard

/**
 * 运行时权限申请桥。
 *
 * Service 可能在没有 Activity 的地方被调用；此时返回 false，不弹窗。
 * 有 Activity 时由 [Host] 弹出系统权限窗口并等待结果。
 */
object PermissionBroker {

    interface Host {
        suspend fun requestPermissions(permissions: List<String>): Map<String, Boolean>
        suspend fun requestAllFilesAccess(): Boolean
    }

    @Volatile
    private var host: Host? = null

    @Volatile
    private var grantDepth: Int = 0

    private fun enterGrantWindow() {
        synchronized(this) {
            grantDepth += 1
            PolicyGuard.setSystemGrantWindowActive(true)
        }
    }

    private fun leaveGrantWindow() {
        synchronized(this) {
            grantDepth = (grantDepth - 1).coerceAtLeast(0)
            PolicyGuard.setSystemGrantWindowActive(grantDepth > 0)
        }
    }

    fun register(value: Host) {
        host = value
    }

    fun unregister(value: Host) {
        if (host === value) host = null
    }

    suspend fun ensure(context: Context, permissions: List<String>): Boolean {
        val missing = permissions.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        val currentHost = host ?: return false
        enterGrantWindow()
        return try {
            val result = currentHost.requestPermissions(missing)
            missing.all { result[it] == true }
        } finally {
            leaveGrantWindow()
        }
    }

    /** 外部存储写权限：Android 11+ 走「所有文件访问」设置页；旧系统走运行时权限。 */
    suspend fun ensureExternalStorage(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) return true
            val currentHost = host ?: return false
            enterGrantWindow()
            return try {
                currentHost.requestAllFilesAccess()
            } finally {
                leaveGrantWindow()
            }
        }
        return ensure(
            context,
            listOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ),
        )
    }
}
