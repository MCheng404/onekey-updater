package com.onekey.updater.prefs

import com.onekey.updater.data.ui.Screen
import com.aurora.gplayapi.data.models.AuthData
import com.kryptoprefs.context.KryptoContext
import com.kryptoprefs.gson.json
import com.kryptoprefs.preferences.KryptoPrefs


class Prefs(
	prefs: KryptoPrefs,
	isAndroidTv: Boolean
): KryptoContext(prefs) {
	val ignoredApps = json("ignoredApps", emptyList<String>(), true)
	val ignoredVersions = json("ignoredVersions", emptyList<Int>(), true)
	val excludeSystem = boolean("excludeSystem", defValue = true, backed = true)
	val excludeDisabled = boolean("excludeDisabled", defValue = true, backed = true)
	val excludeStore = boolean("excludeStore", defValue = false, backed = true)
	val playTextAnimations = boolean("playTextAnimations", defValue = true, backed = true)
	val ignoreAlpha = boolean("ignoreAlpha", defValue = true, backed = true)
	val ignoreBeta = boolean("ignoreBeta", defValue = true, backed = true)
	val ignorePreRelease = boolean("ignorePreRelease", defValue = true, backed = true)
	val useSafeStores = boolean("useSafeStores", defValue = true, backed = true)
	val useApkMirror = boolean("useApkMirror", defValue = false, backed = true)
	val useGitHub = boolean("useGitHub", defValue = true, backed = true)
	val useGitLab = boolean("useGitLab", defValue = true, backed = true)
	val useFdroid = boolean("useFdroid", defValue = true, backed = true)
	val useIzzy = boolean("useIzzy", defValue = true, backed = true)
	val useAptoide = boolean("useAptoide", defValue = true, backed = true)
	val useApkPure = boolean("useApkPure", defValue = true, backed = true)
	val usePlay = boolean("usePlay", defValue = false, backed = true)

	/** 腾讯应用宝。逐包查询、限流限量，详见 TencentRepository。 */
	val useTencent = boolean("useTencent", defValue = false, backed = true)
	val enableAlarm = boolean("enableAlarm", defValue = false, backed = true)
	val alarmHour = int("alarmHour", defValue = 12, backed = true)
	val alarmFrequency = int("alarmFrequency", 0, backed = true)
	val androidTvUi = boolean("androidTvUi", defValue = true, backed = true)

	/**
	 * 安装方式：见 [com.onekey.updater.util.InstallMode]。
	 * 取代上游的布尔开关 rootInstall / newInstaller —— 那套双路径实现里，
	 * 老路径依赖 Activity 的 ActivityResultLauncher，用户取消时会把安装锁永久占住。
	 */
	val installMode = int("installMode", defValue = 0, backed = true)

	val theme = int("theme", defValue = 0, backed = true)
	val lastTab = string("lastTab", defValue = Screen.Updates.route, backed = true)
	val playAuthData = json("playAuthData", AuthData("", ""), true)
	val lastPlayCheck = long("lastPlayCheck", 0L, true)

	// ---------- 更新扫描范围 ----------
	//
	// 注意与「应用」页那三个展示过滤（excludeSystem / excludeDisabled / excludeStore）的区别：
	// 那三个只管「应用」页显示什么，不决定为哪些应用检查更新（早期把两者混在一起，
	// 导致排除商店应用后「更新」页永远为空）。这里才是真正的扫描范围。

	/** 是否把系统应用纳入更新检查。默认关闭：侧载更新系统应用风险较高。 */
	val updateSystemApps = boolean("updateSystemApps", defValue = false, backed = true)

	/** 是否把「由应用商店安装」的应用纳入更新检查。默认开启。 */
	val updateStoreApps = boolean("updateStoreApps", defValue = true, backed = true)

	// ---------- 内嵌 MCP 服务 ----------
	//
	// 用途：让 PC 上的 AI Agent 通过 MCP 远程控制本应用（触发扫描、读待更新列表、执行安装、读日志）。
	// 默认关闭；默认只监听回环地址，PC 侧配合 `adb forward` 访问。

	/** 是否启用内嵌 MCP 服务。 */
	// ---------- 代理 ----------
	//
	// 统一在 OkHttp 层生效，因此 Retrofit / Downloader / Coil 自动全部走代理。

	/** 是否启用自定义代理。 */
	val proxyEnabled = boolean("proxyEnabled", defValue = false, backed = true)

	/** 代理类型：0 = HTTP，1 = SOCKS。见 ProxyConfig。 */
	val proxyType = int("proxyType", defValue = 0, backed = true)

	val proxyHost = string("proxyHost", defValue = "", backed = true)

	val proxyPort = int("proxyPort", defValue = 0, backed = true)

	val mcpEnabled = boolean("mcpEnabled", defValue = false, backed = true)

	/** 监听端口。范围校验在 McpServer 内做（1024–65535）。 */
	val mcpPort = int("mcpPort", defValue = 8765, backed = true)

	/** 访问令牌。为空时由 McpServer 在启动时生成并写回。 */
	val mcpToken = string("mcpToken", defValue = "", backed = true)

	/** 是否允许局域网访问。开启后必须携带令牌，且会绑定 0.0.0.0。 */
	val mcpAllowLan = boolean("mcpAllowLan", defValue = false, backed = true)

	/** 配置迁移版本号，见 [com.onekey.updater.util.Migrations]。 */
	val configVersion = int("configVersion", defValue = 0, backed = true)

	// ---------- 国内网络优化 ----------

	/** 是否启用国内镜像/加速（关闭则全部直连上游）。 */
	val useChinaMirror = boolean("useChinaMirror", defValue = true, backed = true)

	/** F-Droid 索引与 APK 的来源：见 Mirrors.fdroidMirrors。默认 1 = 清华 TUNA（已实测最快）。 */
	val fdroidMirrorId = int("fdroidMirrorId", defValue = 1, backed = true)

	/** 自定义 F-Droid 仓库地址（fdroidMirrorId = 自定义时生效）。 */
	val fdroidCustomUrl = string("fdroidCustomUrl", defValue = "", backed = true)

	/** IzzyOnDroid 仓库来源：见 Mirrors.izzyMirrors。 */
	val izzyMirrorId = int("izzyMirrorId", defValue = 0, backed = true)

	/** 自定义 IzzyOnDroid 仓库地址。 */
	val izzyCustomUrl = string("izzyCustomUrl", defValue = "", backed = true)

	/** GitHub 加速前缀：见 Mirrors.githubProxies。默认 1 = gh-proxy.com（已实测可用）。 */
	val githubProxyId = int("githubProxyId", defValue = 1, backed = true)

	/** 自定义 GitHub 加速前缀，例如 https://gh-proxy.com/ 。 */
	val githubCustomProxy = string("githubCustomProxy", defValue = "", backed = true)

	/** 是否用加速前缀代理 GitHub Release 附件下载（API 元数据走镜像站点）。 */
	val githubProxyDownloads = boolean("githubProxyDownloads", defValue = true, backed = true)

	/**
	 * 远程刷新得到的 GitHub 加速节点前缀清单（已校验、https）。
	 * 由 NodeRefresher 写入，启动时通过 MirrorResolver 恢复进内存；
	 * 网络不通时回退到 Mirrors 内置的 78 条兜底清单。
	 */
	val githubNodesRemote = json("githubNodesRemote", emptyList<String>(), true)
}
