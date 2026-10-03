package com.onekey.updater.util

import com.onekey.updater.prefs.Prefs
import java.io.File

/**
 * 安装方式。
 *
 * AUTO：有静默通道就用静默安装，否则走系统安装会话（每次需手动确认）
 * SESSION：始终走系统 PackageInstaller（需要用户确认，兼容性最好）
 * ROOT：始终走 su + pm install（完全静默，不可用时直接失败）
 */
object InstallMode {
    const val AUTO = 0
    const val SESSION = 1
    const val ROOT = 2
}

/** 当前可用的静默安装通道。 */
enum class SilentBackend {
    /** su + pm install。能力最全。 */
    ROOT,

    /** Shizuku（shell 身份）+ pm install。没有 root 时的替代方案。 */
    SHIZUKU,

    /** 无静默通道，只能走系统安装会话。 */
    NONE
}

/**
 * 选出当前应使用的静默安装通道。
 *
 * AUTO 模式的判定顺序是 **root → Shizuku → 无**：
 * root 能力更全（能读 /data/adb 等），所以优先；没有 root 时若用户自己启用了
 * Shizuku 且它正在运行，就走 Shizuku 通道做 `pm install`，同样不需要弹系统安装框。
 */
suspend fun Prefs.silentBackend(): SilentBackend = when (installMode.get()) {
    InstallMode.ROOT -> SilentBackend.ROOT
    InstallMode.SESSION -> SilentBackend.NONE
    else -> when {
        RootShell.isAvailable() -> SilentBackend.ROOT
        useShizuku.get() && ShizukuShell.isRunning() -> SilentBackend.SHIZUKU
        else -> SilentBackend.NONE
    }
}

/** 是否有静默安装通道可用。 */
suspend fun Prefs.canSilentInstall(): Boolean = silentBackend() != SilentBackend.NONE

/** 兼容旧调用点：是否走 root 通道。 */
suspend fun Prefs.isRootInstall(): Boolean = silentBackend() == SilentBackend.ROOT

/**
 * 用当前可用的静默通道安装。
 *
 * 两条通道共用 [RootInstaller] 里同一份「暂存 → pm install → 会话式分卷安装」实现，
 * 只有执行命令的方式不同。
 */
suspend fun Prefs.silentInstall(
    apks: List<File>,
    allowDowngrade: Boolean = false,
    grantPermissions: Boolean = false
): RootInstaller.Result = when (silentBackend()) {
    SilentBackend.ROOT ->
        if (!RootShell.isAvailable()) {
            RootInstaller.Result(false, "未获得 Root 权限")
        } else {
            RootInstaller.install(apks, allowDowngrade, grantPermissions)
        }

    SilentBackend.SHIZUKU -> RootInstaller.installViaShizuku(apks, allowDowngrade, grantPermissions)

    SilentBackend.NONE -> RootInstaller.Result(false, "没有可用的静默安装通道")
}
