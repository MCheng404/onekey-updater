package com.onekey.updater.data.ui

import androidx.annotation.StringRes
import com.onekey.updater.R

/**
 * 设置二级页面路由。
 *
 * 与 [Screen] 分开是必要的：[Screen] 会被 [com.onekey.updater.viewmodel.MainViewModel.screens]
 * 拿去渲染底部导航栏，二级页面加进去就会在底栏多出 6 个图标。
 * 二级页只需要一个 route 字符串和标题，不需要 icon。
 */
object SettingsRoute {
	const val SOURCES = "settings/sources"
	const val FILTER = "settings/filter"
	const val INSTALL = "settings/install"
	const val NETWORK = "settings/network"
	const val PROXY = "settings/proxy"
	const val MCP = "settings/mcp"
	const val ALARM = "settings/alarm"
	const val APPEARANCE = "settings/appearance"
	const val ABOUT = "settings/about"
}

/** 二级页标题，供路由与页面共用，避免标题字符串写两份。 */
object SettingsPage {
	@StringRes val sourcesTitle = R.string.settings_sources
	@StringRes val filterTitle = R.string.settings_filter
	@StringRes val installTitle = R.string.settings_install
	@StringRes val networkTitle = R.string.settings_china_sources
	@StringRes val proxyTitle = R.string.settings_proxy
	@StringRes val mcpTitle = R.string.mcp_service
	@StringRes val alarmTitle = R.string.settings_alarm
	@StringRes val appearanceTitle = R.string.settings_ui
	@StringRes val aboutTitle = R.string.about
}