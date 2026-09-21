package com.onekey.updater.viewmodel

import androidx.lifecycle.viewModelScope
import com.onekey.updater.data.ui.AppInstallProgress
import com.onekey.updater.data.ui.SearchUiState
import com.onekey.updater.data.ui.removeId
import com.onekey.updater.data.ui.setIsInstalling
import com.onekey.updater.data.ui.setProgress
import com.onekey.updater.repository.SearchRepository
import com.onekey.updater.util.Badger
import com.onekey.updater.util.Downloader
import com.onekey.updater.util.InstallLog
import com.onekey.updater.util.SessionInstaller
import com.onekey.updater.util.SnackBar
import com.onekey.updater.util.Stringer
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.util.launchWithMutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex


class SearchViewModel(
	private val searchRepository: SearchRepository,
	private val badger: Badger,
	downloader: Downloader,
	installer: SessionInstaller,
	prefs: Prefs,
	snackBar: SnackBar,
	stringer: Stringer,
	installLog: InstallLog
) : InstallViewModel(downloader, installer, prefs, snackBar, stringer, installLog) {

	private val mutex = Mutex()
	private val state = MutableStateFlow<SearchUiState>(SearchUiState.Empty)
	private var job: Job? = null

	fun state(): StateFlow<SearchUiState> = state

	/** 搜索词变化时取消上一次请求，避免旧结果覆盖新结果。 */
	fun search(text: String) {
		job?.cancel()
		if (text.isBlank()) {
			state.value = SearchUiState.Empty
			badger.changeSearchBadge("")
			return
		}
		job = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
			state.value = SearchUiState.Loading
			badger.changeSearchBadge("")
			searchRepository.search(text).collect {
				it.onSuccess { apps ->
					state.value = SearchUiState.Success(apps)
					badger.changeSearchBadge(apps.size.toString())
				}.onFailure {
					badger.changeSearchBadge("!")
					state.value = SearchUiState.Error
				}
			}
		}
	}

	override fun onInstallingChanged(id: Int, installing: Boolean) {
		state.value = SearchUiState.Success(
			state.value.mutableUpdates().setIsInstalling(id, installing)
		)
	}

	override fun onInstallProgress(progress: AppInstallProgress) {
		state.value = SearchUiState.Success(state.value.mutableUpdates().setProgress(progress))
	}

	override fun onInstalled(id: Int) {
		state.value = SearchUiState.Success(state.value.mutableUpdates().removeId(id))
	}

	override fun onInstallFailed(id: Int) {
		state.value = SearchUiState.Success(state.value.mutableUpdates().setIsInstalling(id, false))
	}
}
