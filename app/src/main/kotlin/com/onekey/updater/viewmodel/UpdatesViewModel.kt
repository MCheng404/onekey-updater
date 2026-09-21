package com.onekey.updater.viewmodel

import androidx.lifecycle.viewModelScope
import com.onekey.updater.data.ui.ApkMirrorSource
import com.onekey.updater.data.ui.AppInstallProgress
import com.onekey.updater.data.ui.AppUpdate
import com.onekey.updater.data.ui.UpdateScan
import com.onekey.updater.data.ui.UpdatesUiState
import com.onekey.updater.data.ui.removeId
import com.onekey.updater.data.ui.setIsInstalling
import com.onekey.updater.data.ui.setProgress
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.repository.UpdatesRepository
import com.onekey.updater.util.Badger
import com.onekey.updater.util.Downloader
import com.onekey.updater.util.InstallLog
import com.onekey.updater.util.SessionInstaller
import com.onekey.updater.util.SnackBar
import com.onekey.updater.util.Stringer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


class UpdatesViewModel(
	private val updatesRepository: UpdatesRepository,
	downloader: Downloader,
	installer: SessionInstaller,
	private val prefs: Prefs,
	private val badger: Badger,
	snackBar: SnackBar,
	stringer: Stringer,
	installLog: InstallLog
) : InstallViewModel(downloader, installer, prefs, snackBar, stringer, installLog) {

	private val mutex = Mutex()
	private val state = MutableStateFlow<UpdatesUiState>(UpdatesUiState.Loading)
	private val refreshing = MutableStateFlow(false)

	/** 进度回调节流，避免每个 8 KB 数据块都重组整个列表。 */
	private var lastProgressAt = 0L
	private var lastProgressValue = 0L

	fun state(): StateFlow<UpdatesUiState> = state

	/** 下拉刷新动画的状态；首个结果到达即结束，不必等所有源跑完。 */
	fun refreshing(): StateFlow<Boolean> = refreshing.asStateFlow()

	fun refresh(load: Boolean = true) = viewModelScope.launch(Dispatchers.IO) {
		mutex.withLock {
			refreshing.value = true
			try {
				if (load) state.value = UpdatesUiState.Loading
				badger.changeUpdatesBadge("")
				updatesRepository.updates().collect { scan ->
					setSuccess(scan)
					// 渐进式扫描：第一批结果到手就可以收起刷新动画
					refreshing.value = false
				}
			} finally {
				refreshing.value = false
			}
		}
	}

	/**
	 * 全部安装。按顺序逐个安装：SessionInstaller 内部串行，且未 Root 时
	 * 每个包都要等用户确认，并行触发会造成多个安装会话互相干扰。
	 */
	fun installAll() = viewModelScope.launch(Dispatchers.IO) {
		state.value.updates()
			.toList()
			.filter { it.source != ApkMirrorSource && !it.isInstalling }
			.forEach { performInstall(it) }
	}

	fun ignoreVersion(id: Int) = viewModelScope.launch(Dispatchers.IO) {
		val ignored = prefs.ignoredVersions.get().toMutableList()
		if (ignored.contains(id)) ignored.remove(id) else ignored.add(id)
		prefs.ignoredVersions.put(ignored)
		setLocalUpdates(state.value.mutableUpdates())
	}

	override fun onInstallingChanged(id: Int, installing: Boolean) {
		state.value = UpdatesUiState.Success(
			state.value.mutableUpdates().setIsInstalling(id, installing),
			state.value.scannedAppCount(),
			state.value.systemAppsIncluded()
		)
	}

	override fun onInstallProgress(progress: AppInstallProgress) {
		// 节流：最多每 200 ms 且进度至少前进 1% 才刷新一次 UI
		val now = System.currentTimeMillis()
		val total = state.value.updates().firstOrNull { it.id == progress.id }?.total ?: 0L
		val value = progress.progress ?: 0L
		val advancedEnough = total <= 0L ||
			value - lastProgressValue > total / 100 ||
			value >= total
		if (now - lastProgressAt < 200L && !advancedEnough) return
		lastProgressAt = now
		lastProgressValue = value
		state.value = UpdatesUiState.Success(state.value.mutableUpdates().setProgress(progress), state.value.scannedAppCount(), state.value.systemAppsIncluded())
	}

	override fun onInstalled(id: Int) {
		setLocalUpdates(state.value.mutableUpdates().removeId(id))
	}

	override fun onInstallFailed(id: Int) {
		state.value = UpdatesUiState.Success(state.value.mutableUpdates().setIsInstalling(id, false), state.value.scannedAppCount(), state.value.systemAppsIncluded())
	}

	private fun setSuccess(scan: UpdateScan) {
		val ignoredVersions = prefs.ignoredVersions.get()
		val filtered = scan.updates.filter { !ignoredVersions.contains(it.id) }
		state.value = UpdatesUiState.Success(filtered, scan.scannedApps, scan.systemAppsIncluded)
		// 角标必须是「归并后的应用数」——界面上一行是一个应用，
		// 若按来源条目数计数，会出现角标 14 而列表只有 12 条的不一致（实测）。
		badger.changeUpdatesBadge(filtered.map { it.packageName }.distinct().size.toString())
	}

	/** 本地增删（忽略某版本、安装成功移除）时保留本轮扫描到的应用数。 */
	private fun setLocalUpdates(updates: List<AppUpdate>) {
		val ignoredVersions = prefs.ignoredVersions.get()
		val filtered = updates.filter { !ignoredVersions.contains(it.id) }
		state.value = UpdatesUiState.Success(
			filtered,
			state.value.scannedAppCount(),
			state.value.systemAppsIncluded()
		)
		badger.changeUpdatesBadge(filtered.map { it.packageName }.distinct().size.toString())
	}
}
