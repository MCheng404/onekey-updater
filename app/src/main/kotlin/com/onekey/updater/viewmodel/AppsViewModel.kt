package com.onekey.updater.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.updater.data.ui.AppsUiState
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.repository.AppsRepository
import com.onekey.updater.util.Badger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppsViewModel(
	private val repository: AppsRepository,
	private val prefs: Prefs,
	private val badger: Badger
) : ViewModel() {

	private val mutex = Mutex()
	private val state = MutableStateFlow<AppsUiState>(buildLoadingState())
	private val refreshing = MutableStateFlow(false)

	fun state(): StateFlow<AppsUiState> = state

	fun refreshing(): StateFlow<Boolean> = refreshing.asStateFlow()

	fun refresh(load: Boolean = true) = viewModelScope.launch(Dispatchers.IO) {
		mutex.withLock {
			refreshing.value = true
			try {
				if (load) state.value = buildLoadingState()
				badger.changeAppsBadge("")
				repository.getApps().collect {
					it.onSuccess { list ->
						// 继承上游的排序：先按名称，再让被忽略的应用沉底
						val apps = list
							.sortedWith(
								compareBy(String.CASE_INSENSITIVE_ORDER) { app ->
									app.name.ifEmpty { app.packageName }
								}
							)
							.sortedBy { it.ignored }
						state.value = AppsUiState.Success(
							apps,
							prefs.excludeSystem.get(),
							prefs.excludeStore.get(),
							prefs.excludeDisabled.get()
						)
						Log.i(
							"AppsViewModel",
							"已加载 " + apps.size + " 个应用（排除系统=" + prefs.excludeSystem.get() +
								", 排除商店=" + prefs.excludeStore.get() + ", 排除停用=" + prefs.excludeDisabled.get() + "）"
						)
						badger.changeAppsBadge(apps.size.toString())
					}.onFailure { ex ->
						state.value = AppsUiState.Error
						badger.changeAppsBadge("!")
						Log.e("AppsViewModel", "读取已安装应用失败。", ex)
					}
				}
			} finally {
				refreshing.value = false
			}
		}
	}

	fun onSystemClick() = toggleFilter { prefs.excludeSystem.put(!prefs.excludeSystem.get()) }

	fun onAppStoreClick() = toggleFilter { prefs.excludeStore.put(!prefs.excludeStore.get()) }

	fun onDisabledClick() = toggleFilter { prefs.excludeDisabled.put(!prefs.excludeDisabled.get()) }

	fun ignore(packageName: String) = viewModelScope.launch(Dispatchers.IO) {
		val ignored = prefs.ignoredApps.get().toMutableList()
		if (ignored.contains(packageName)) ignored.remove(packageName) else ignored.add(packageName)
		prefs.ignoredApps.put(ignored)
		refresh(false)
	}

	private fun toggleFilter(block: () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
		block()
		refresh(false)
	}

	private fun buildLoadingState() = AppsUiState.Loading(
		prefs.excludeSystem.get(),
		prefs.excludeStore.get(),
		prefs.excludeDisabled.get()
	)
}
