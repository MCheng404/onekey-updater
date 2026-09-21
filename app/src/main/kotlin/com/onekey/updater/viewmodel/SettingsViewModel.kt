package com.onekey.updater.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.onekey.updater.data.ui.SettingsSnapshot
import com.onekey.updater.data.ui.SettingsUiState
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.repository.AppsRepository
import com.onekey.updater.util.Clipboard
import com.onekey.updater.util.RootShell
import com.onekey.updater.util.SnackBar
import com.onekey.updater.util.Stringer
import com.onekey.updater.util.Themer
import com.onekey.updater.util.mcp.McpServer
import com.onekey.updater.util.mcp.McpState
import com.onekey.updater.util.net.MirrorResolver
import com.onekey.updater.util.net.NetworkDiagnostics
import com.onekey.updater.worker.UpdatesWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


class SettingsViewModel(
	private val prefs: Prefs,
	private val clipboard: Clipboard,
	private val appsRepository: AppsRepository,
	private val workManager: WorkManager,
	private val themer: Themer,
	private val snackBar: SnackBar,
	private val stringer: Stringer,
	private val diagnostics: NetworkDiagnostics,
	private val resolver: MirrorResolver,
	private val mcpServer: McpServer
) : ViewModel() {

	/** 网络诊断的界面状态。 */
	sealed interface DiagnosticsState {
		data object Idle : DiagnosticsState
		data object Running : DiagnosticsState
		data class Done(val results: List<NetworkDiagnostics.Result>) : DiagnosticsState
	}

	/** Root 授权状态；null 表示尚未探测。 */
	sealed interface RootState {
		data object Unknown : RootState
		data object Checking : RootState
		data object Granted : RootState
		data object Denied : RootState
	}

	private val screenState = MutableStateFlow<SettingsUiState>(SettingsUiState.Settings)
	private val snapshot = MutableStateFlow(readSnapshot())
	private val diagnosticsState = MutableStateFlow<DiagnosticsState>(DiagnosticsState.Idle)
	private val rootState = MutableStateFlow<RootState>(RootState.Unknown)

	fun state(): StateFlow<SettingsSnapshot> = snapshot.asStateFlow()

	fun screen(): StateFlow<SettingsUiState> = screenState.asStateFlow()

	fun diagnostics(): StateFlow<DiagnosticsState> = diagnosticsState.asStateFlow()

	fun root(): StateFlow<RootState> = rootState.asStateFlow()

	fun openAbout() { screenState.value = SettingsUiState.About }

	fun closeAbout() { screenState.value = SettingsUiState.Settings }

	fun refresh() { snapshot.value = readSnapshot() }

	// ---------------- 更新来源 ----------------

	fun setUseApkMirror(v: Boolean) = put { prefs.useApkMirror.put(v) }
	fun setUseGitHub(v: Boolean) = put { prefs.useGitHub.put(v) }
	fun setUseGitLab(v: Boolean) = put { prefs.useGitLab.put(v) }
	fun setUseFdroid(v: Boolean) = put { prefs.useFdroid.put(v) }
	fun setUseIzzy(v: Boolean) = put { prefs.useIzzy.put(v) }
	fun setUseAptoide(v: Boolean) = put { prefs.useAptoide.put(v) }
	fun setUseApkPure(v: Boolean) = put { prefs.useApkPure.put(v) }
	fun setUsePlay(v: Boolean) = put { prefs.usePlay.put(v) }
	fun setUseTencent(v: Boolean) = put { prefs.useTencent.put(v) }

	// ---------------- 过滤 ----------------

	fun setIgnoreAlpha(v: Boolean) = put { prefs.ignoreAlpha.put(v) }
	fun setIgnoreBeta(v: Boolean) = put { prefs.ignoreBeta.put(v) }
	fun setIgnorePreRelease(v: Boolean) = put { prefs.ignorePreRelease.put(v) }
	fun setUseSafeStores(v: Boolean) = put { prefs.useSafeStores.put(v) }
	fun setExcludeSystem(v: Boolean) = put { prefs.excludeSystem.put(v) }
	fun setExcludeDisabled(v: Boolean) = put { prefs.excludeDisabled.put(v) }
	fun setExcludeStore(v: Boolean) = put { prefs.excludeStore.put(v) }

	// ---------------- 更新扫描范围 ----------------

	/** 允许更新系统应用。开启后系统应用（含预装后被更新的）会进入更新检查。 */
	fun setUpdateSystemApps(v: Boolean) = put { prefs.updateSystemApps.put(v) }

	/** 是否把应用商店安装的应用纳入更新检查。 */
	fun setUpdateStoreApps(v: Boolean) = put { prefs.updateStoreApps.put(v) }

	// ---------------- 安装 ----------------

	fun setInstallMode(value: Int) = put { prefs.installMode.put(value) }

	/** 主动申请一次 Root，用于验证 KernelSU/Magisk 是否放行。 */
	fun requestRoot() = viewModelScope.launch {
		rootState.value = RootState.Checking
		RootShell.invalidate()
		val granted = RootShell.isAvailable(force = true)
		rootState.value = if (granted) RootState.Granted else RootState.Denied
		snackBar.snackBar(
			viewModelScope,
			stringer.get(if (granted) com.onekey.updater.R.string.root_granted else com.onekey.updater.R.string.root_denied)
		)
	}

	// ---------------- 界面 ----------------

	fun setTheme(value: Int) = put { themer.setTheme(value) }
	fun setAndroidTvUi(v: Boolean) = put { prefs.androidTvUi.put(v) }
	fun setPortraitColumns(v: Int) = put { prefs.portraitColumns.put(v) }
	fun setLandscapeColumns(v: Int) = put { prefs.landscapeColumns.put(v) }
	fun setPlayTextAnimations(v: Boolean) = put { prefs.playTextAnimations.put(v) }

	// ---------------- 定时检查 ----------------

	fun setEnableAlarm(v: Boolean) = put {
		prefs.enableAlarm.put(v)
		if (v) UpdatesWorker.launch(workManager) else UpdatesWorker.cancel(workManager)
	}

	fun setAlarmHour(v: Int) = put {
		prefs.alarmHour.put(v)
		if (prefs.enableAlarm.get()) UpdatesWorker.launch(workManager)
	}

	fun setAlarmFrequency(v: Int) = put {
		prefs.alarmFrequency.put(v)
		if (prefs.enableAlarm.get()) UpdatesWorker.launch(workManager)
	}

	// ---------------- 国内网络 ----------------

	fun setUseChinaMirror(v: Boolean) = put { prefs.useChinaMirror.put(v) }
	fun setFdroidMirrorId(v: Int) = put { prefs.fdroidMirrorId.put(v) }
	fun setFdroidCustomUrl(v: String) = put { prefs.fdroidCustomUrl.put(v.trim()) }
	fun setIzzyMirrorId(v: Int) = put { prefs.izzyMirrorId.put(v) }
	fun setIzzyCustomUrl(v: String) = put { prefs.izzyCustomUrl.put(v.trim()) }
	fun setGithubProxyId(v: Int) = put { prefs.githubProxyId.put(v) }
	fun setGithubCustomProxy(v: String) = put { prefs.githubCustomProxy.put(v.trim()) }
	fun setGithubProxyDownloads(v: Boolean) = put { prefs.githubProxyDownloads.put(v) }

	/** 当前实际生效的地址，供设置页展示，让用户确认改写结果符合预期。 */
	fun effectiveGithubPrefix(): String = resolver.githubPrefix().ifEmpty {
		stringer.get(com.onekey.updater.R.string.mirror_direct)
	}

	fun effectiveFdroidRepo(): String = resolver.fdroidBase().ifEmpty {
		MirrorResolver.OFFICIAL_FDROID
	}

	/** 逐条实测所有线路。 */
	fun runDiagnostics() = viewModelScope.launch(Dispatchers.IO) {
		diagnosticsState.value = DiagnosticsState.Running
		val results = diagnostics.run(snapshot.value.githubCustomProxy)
		diagnosticsState.value = DiagnosticsState.Done(results)
	}

	/** 采纳诊断结果：把第一条可用的 GitHub 线路写回配置。 */
	fun applyBestGithubProxy() {
		val state = diagnosticsState.value as? DiagnosticsState.Done ?: return
		val best = state.results
			.filter { it.kind == NetworkDiagnostics.Kind.GITHUB && it.ok && it.optionIndex >= 0 }
			.minByOrNull { it.millis }
		if (best == null) {
			snackBar.snackBar(viewModelScope, stringer.get(com.onekey.updater.R.string.diagnostics_no_working_line))
			return
		}
		prefs.githubProxyId.put(best.optionIndex)
		refresh()
		snackBar.snackBar(viewModelScope, "${best.label} · ${best.millis} ms")
	}

	/** 采纳诊断结果：切换 F-Droid 镜像。 */
	/**
	 * 一键把两类线路都调到实测最快的那条。
	 *
	 * 原先需要用户分别点「使用最快 GitHub 线路」与「使用最快 F-Droid 线路」两次 ——
	 * 界面上按钮一多还容易被结果挤下去。现在合并成一个动作，语义也更直白：
	 * 「优化线路」= 两类都调优，不是只调 GitHub。
	 */
	fun applyBestLines() {
		val state = diagnosticsState.value as? DiagnosticsState.Done ?: return

		val bestGithub = state.results
			.filter { it.kind == NetworkDiagnostics.Kind.GITHUB && it.ok && it.optionIndex >= 0 }
			.minByOrNull { it.millis }
		val bestFdroid = state.results
			.filter { it.kind == NetworkDiagnostics.Kind.FDROID && it.ok && it.optionIndex >= 0 }
			.minByOrNull { it.millis }

		if (bestGithub == null && bestFdroid == null) {
			snackBar.snackBar(viewModelScope, stringer.get(com.onekey.updater.R.string.diagnostics_no_working_line))
			return
		}
		bestGithub?.let { prefs.githubProxyId.put(it.optionIndex) }
		bestFdroid?.let { prefs.fdroidMirrorId.put(it.optionIndex) }
		refresh()

		val parts = buildList {
			bestGithub?.let { add("GitHub: " + it.label + " " + it.millis + "ms") }
			bestFdroid?.let { add("F-Droid: " + it.label + " " + it.millis + "ms") }
		}
		snackBar.snackBar(viewModelScope, parts.joinToString("；"))
	}

	fun applyBestFdroidMirror() {
		val state = diagnosticsState.value as? DiagnosticsState.Done ?: return
		val best = state.results
			.filter { it.kind == NetworkDiagnostics.Kind.FDROID && it.ok && it.optionIndex >= 0 }
			.minByOrNull { it.millis }
		if (best == null) {
			snackBar.snackBar(viewModelScope, stringer.get(com.onekey.updater.R.string.diagnostics_no_working_line))
			return
		}
		prefs.fdroidMirrorId.put(best.optionIndex)
		refresh()
		snackBar.snackBar(viewModelScope, "${best.label} · ${best.millis} ms")
	}

	// ---------------- 代理 ----------------

	fun setProxyEnabled(v: Boolean) = put { prefs.proxyEnabled.put(v) }
	fun setProxyType(v: Int) = put { prefs.proxyType.put(v) }
	fun setProxyHost(v: String) = put { prefs.proxyHost.put(v.trim()) }

	/** 端口：越界不写入并提示，避免把全部请求打到坏地址上。返回是否已写入。 */
	fun setProxyPort(v: Int): Boolean {
		if (v !in 1..65535) return false
		put { prefs.proxyPort.put(v) }
		return true
	}

	fun notifyProxyPortInvalid() {
		snackBar.snackBar(viewModelScope, stringer.get(com.onekey.updater.R.string.proxy_port_invalid))
	}

	// ---------------- MCP 服务 ----------------

	/** 开关：开启即拉起服务、关闭即停止；start/stop 幂等，重复调用安全。 */
	fun setMcpEnabled(v: Boolean) = put {
		prefs.mcpEnabled.put(v)
		if (v) mcpServer.start() else mcpServer.stop()
	}

	/**
	 * 端口：仅在 1024–65535 内写入并刷新快照，返回 true。
	 * 运行中改端口需重启才生效——直接在这里幂等重启，用户无需手动操作。
	 * 非法值不写入、返回 false（界面据此提示），避免把服务绑到坏端口。
	 */
	fun setMcpPort(v: Int): Boolean {
		if (v !in 1024..65535) return false
		put { prefs.mcpPort.put(v) }
		if (mcpServer.state().value.running) {
			mcpServer.stop()
			mcpServer.start()
		}
		return true
	}

	/** 端口非法时集中发提示，界面拿到 setMcpPort 的 false 后再调它。 */
	fun notifyMcpPortInvalid() {
		snackBar.snackBar(viewModelScope, stringer.get(com.onekey.updater.R.string.mcp_port_invalid))
	}

	/** 允许局域网：改变绑定地址（127.0.0.1 ↔ 0.0.0.0），运行中需重启才生效。 */
	fun setMcpAllowLan(v: Boolean) = put {
		prefs.mcpAllowLan.put(v)
		if (mcpServer.state().value.running) {
			mcpServer.stop()
			mcpServer.start()
		}
	}

	/** 重新生成令牌并刷新快照；服务在跑则重启以用上新令牌（start 幂等）。 */
	fun regenerateMcpToken() {
		mcpServer.regenerateToken()
		if (mcpServer.state().value.running) {
			mcpServer.stop()
			mcpServer.start()
		}
		refresh()
	}

	/** 把后端 McpServer 的实时状态直转界面，运行中/已停止/出错一目了然。 */
	fun mcpState(): StateFlow<McpState> = mcpServer.state()

	// ---------------- 工具 ----------------

	fun copyToClipboard(text: String) {
		clipboard.copy(text)
		snackBar.snackBar(viewModelScope, stringer.get(com.onekey.updater.R.string.copied))
	}

	fun copyAppList() = viewModelScope.launch(Dispatchers.IO) {
		appsRepository.getApps().first().onSuccess { apps ->
			copyToClipboard(apps.joinToString("\n") { "${it.name} (${it.packageName}) ${it.version}" })
		}
	}

	/** 一次性写入偏好并刷新快照。 */
	private inline fun put(block: () -> Unit) {
		block()
		snapshot.value = readSnapshot()
	}

	private fun readSnapshot() = SettingsSnapshot(
		useApkMirror = prefs.useApkMirror.get(),
		useGitHub = prefs.useGitHub.get(),
		useGitLab = prefs.useGitLab.get(),
		useFdroid = prefs.useFdroid.get(),
		useIzzy = prefs.useIzzy.get(),
		useAptoide = prefs.useAptoide.get(),
		useApkPure = prefs.useApkPure.get(),
		useTencent = prefs.useTencent.get(),
		usePlay = prefs.usePlay.get(),
		ignoreAlpha = prefs.ignoreAlpha.get(),
		ignoreBeta = prefs.ignoreBeta.get(),
		ignorePreRelease = prefs.ignorePreRelease.get(),
		useSafeStores = prefs.useSafeStores.get(),
		excludeSystem = prefs.excludeSystem.get(),
		excludeDisabled = prefs.excludeDisabled.get(),
		excludeStore = prefs.excludeStore.get(),
		updateSystemApps = prefs.updateSystemApps.get(),
		updateStoreApps = prefs.updateStoreApps.get(),
		installMode = prefs.installMode.get(),
		theme = prefs.theme.get(),
		androidTvUi = prefs.androidTvUi.get(),
		portraitColumns = prefs.portraitColumns.get(),
		landscapeColumns = prefs.landscapeColumns.get(),
		playTextAnimations = prefs.playTextAnimations.get(),
		enableAlarm = prefs.enableAlarm.get(),
		alarmHour = prefs.alarmHour.get(),
		alarmFrequency = prefs.alarmFrequency.get(),
		useChinaMirror = prefs.useChinaMirror.get(),
		fdroidMirrorId = prefs.fdroidMirrorId.get(),
		fdroidCustomUrl = prefs.fdroidCustomUrl.get(),
		izzyMirrorId = prefs.izzyMirrorId.get(),
		izzyCustomUrl = prefs.izzyCustomUrl.get(),
		githubProxyId = prefs.githubProxyId.get(),
		githubCustomProxy = prefs.githubCustomProxy.get(),
		githubProxyDownloads = prefs.githubProxyDownloads.get(),
		proxyEnabled = prefs.proxyEnabled.get(),
		proxyType = prefs.proxyType.get(),
		proxyHost = prefs.proxyHost.get(),
		proxyPort = prefs.proxyPort.get(),
		mcpEnabled = prefs.mcpEnabled.get(),
		mcpPort = prefs.mcpPort.get(),
		mcpToken = prefs.mcpToken.get(),
		mcpAllowLan = prefs.mcpAllowLan.get()
	)
}
