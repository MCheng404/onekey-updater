package com.onekey.updater.data.ui

/**
 * 设置页的一次性快照。
 *
 * 上游用「`fun getX()` / `fun setX()`」成对暴露偏好项，但读取发生在组合期，
 * 偏好变化不会触发重组（开关点一下不会变）。这里改为不可变快照 + StateFlow，
 * 任何一项变化都会让界面正确刷新。
 */
data class SettingsSnapshot(
	// 更新来源
	val useApkMirror: Boolean = false,
	val useGitHub: Boolean = true,
	val useGitLab: Boolean = true,
	val useFdroid: Boolean = true,
	val useIzzy: Boolean = true,
	val useAptoide: Boolean = true,
	val useApkPure: Boolean = true,
	val useTencent: Boolean = false,
	val usePlay: Boolean = true,

	// 过滤
	val ignoreAlpha: Boolean = true,
	val ignoreBeta: Boolean = true,
	val ignorePreRelease: Boolean = true,
	val useSafeStores: Boolean = true,
	val excludeSystem: Boolean = true,
	val excludeDisabled: Boolean = true,
	val excludeStore: Boolean = false,

	// 更新扫描范围
	val updateSystemApps: Boolean = false,
	val updateStoreApps: Boolean = true,

	// 安装
	val installMode: Int = 0,

	// 界面
	val theme: Int = 0,
	val androidTvUi: Boolean = true,
	val portraitColumns: Int = 3,
	val landscapeColumns: Int = 6,
	val playTextAnimations: Boolean = true,

	// 定时检查
	val enableAlarm: Boolean = false,
	val alarmHour: Int = 12,
	val alarmFrequency: Int = 0,

	// 国内网络
	val useChinaMirror: Boolean = true,
	val fdroidMirrorId: Int = 0,
	val fdroidCustomUrl: String = "",
	val izzyMirrorId: Int = 0,
	val izzyCustomUrl: String = "",
	val githubProxyId: Int = 0,
	val githubCustomProxy: String = "",
	val githubProxyDownloads: Boolean = true,

	// MCP 服务（让 PC 上的 AI Agent 远程控制更新器）
	val proxyEnabled: Boolean = false,
	val proxyType: Int = 0,
	val proxyHost: String = "",
	val proxyPort: Int = 0,

	val mcpEnabled: Boolean = false,
	val mcpPort: Int = 8765,
	val mcpToken: String = "",
	val mcpAllowLan: Boolean = false
)

sealed class SettingsUiState {
	object Settings : SettingsUiState()
	object About : SettingsUiState()
}
