package com.onekey.updater.util

import android.util.Log
import com.onekey.updater.prefs.Prefs

/**
 * 一次性配置迁移。
 *
 * 背景：偏好项是持久化的，新增的「默认值」对**已经装过本应用的用户不会生效**。
 * 实测：设备上残留 `githubProxyId=0`（直连），导致 GitHub 源持续被 403 限流，
 * 而代码里把默认值改成镜像也救不回来。
 *
 * 因此用一个自增的 configVersion 做一次性迁移；迁移只覆盖"旧默认值"，
 * 用户之后仍可在设置里自由改回直连，且不会被再次覆盖。
 */
object Migrations {

    private const val TAG = "Migrations"

    /** 当前配置版本。每次需要新的迁移时 +1 并补一条分支。 */
    private const val CURRENT_VERSION = 1

    fun run(prefs: Prefs) {
        val applied = runCatching { prefs.configVersion.get() }.getOrDefault(0)
        if (applied >= CURRENT_VERSION) return

        if (applied < 1) {
            // v1：国内线路默认生效。
            // 0 号选项是「直连官方」，属于历史默认值；迁移到 1 号（已实测可用）：
            //   · F-Droid  -> 1 = 清华 TUNA 镜像
            //   · GitHub   -> 1 = gh-proxy.com 加速
            runCatching {
                prefs.fdroidMirrorId.put(1)
                prefs.githubProxyId.put(1)
            }.onFailure { Log.e(TAG, "迁移 v1 失败。", it) }
            Log.i(TAG, "已应用配置迁移 v1：F-Droid 与 GitHub 默认线路切换为国内可用镜像。")
        }

        runCatching { prefs.configVersion.put(CURRENT_VERSION) }
            .onFailure { Log.e(TAG, "写入配置版本失败。", it) }
    }
}
