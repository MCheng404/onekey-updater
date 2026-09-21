package com.onekey.updater.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.LruCache

/**
 * 应用信息的轻量缓存。
 *
 * 更新列表要按「系统应用 / 用户应用」分段，而 [com.onekey.updater.data.ui.AppUpdate] 里没有这个标志
 * （它由各来源的转换函数构造，逐个补字段要改 8 个来源，容易漏）。
 * 因此这里按包名向 PackageManager 查询，并做缓存 ——
 * 否则列表滚动时每一行都会触发一次跨进程调用，直接掉帧。
 */
private object SystemAppCache : LruCache<String, Boolean>(512)

/**
 * 是否为系统应用。
 *
 * 「系统应用」包含两类：
 *  · FLAG_SYSTEM：随系统镜像预装；
 *  · FLAG_UPDATED_SYSTEM_APP：预装后被更新过（例如 Chrome、WebView）。
 * 两者在用户眼里都属于「手机自带的」，因此归为同一段。
 */
fun Context.isSystemApp(packageName: String): Boolean {
    SystemAppCache.get(packageName)?.let { return it }
    val result = runCatching {
        val flags = packageManager.getApplicationInfo(packageName, 0).flags
        val systemMask = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        (flags and systemMask) != 0
    }.getOrDefault(false)
    SystemAppCache.put(packageName, result)
    return result
}

/** 清理缓存（应用列表刷新后调用，避免卸载/重装后判断过期）。 */
fun clearSystemAppCache() = SystemAppCache.evictAll()
