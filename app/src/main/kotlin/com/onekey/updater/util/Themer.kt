package com.onekey.updater.util

import com.onekey.updater.prefs.Prefs
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 主题状态。
 *
 * 上游只保存「深色/浅色」布尔值，无法表达「跟随系统」；
 * 改用 Miuix 后直接持有主题偏好值（0 跟随系统 / 1 深色 / 2 浅色）。
 */
class Themer(private val prefs: Prefs) {

    private val theme = MutableStateFlow(prefs.theme.get())

    fun flow() = theme

    fun setTheme(value: Int) {
        theme.value = value
        prefs.theme.put(value)
    }
}
