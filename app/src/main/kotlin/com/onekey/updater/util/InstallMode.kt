package com.onekey.updater.util

/**
 * 安装方式。
 *
 * AUTO：有 Root 就静默安装，否则走系统安装会话（每次需手动确认）
 * SESSION：始终走系统 PackageInstaller（需要用户确认，兼容性最好）
 * ROOT：始终走 su + pm install（完全静默）
 */
object InstallMode {
    const val AUTO = 0
    const val SESSION = 1
    const val ROOT = 2
}

/**
 * 当前配置下是否应使用 Root 静默安装。
 * AUTO 模式会实际探测 su 可用性（首次会弹出授权框，结果被缓存）。
 */
suspend fun com.onekey.updater.prefs.Prefs.isRootInstall(): Boolean = when (installMode.get()) {
    InstallMode.ROOT -> true
    InstallMode.SESSION -> false
    else -> RootShell.isAvailable()
}
