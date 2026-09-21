package com.onekey.updater.data.ui


sealed class UpdatesUiState {
	data object Loading: UpdatesUiState()
	data object Error : UpdatesUiState()
	/**
	 * @param scannedApps 本轮真正参与检查的应用数量。
	 *        用来在「暂无更新」时告诉用户「其实检查过 N 个应用」，
	 *        避免空列表被误解为功能失效。
	 */
	data class Success(
		val updates: List<AppUpdate>,
		val scannedApps: Int = 0,
		/** 见 [com.onekey.updater.data.ui.UpdateScan.systemAppsIncluded]。 */
		val systemAppsIncluded: Boolean = false
	) : UpdatesUiState()

	inline fun onLoading(block: (Loading) -> Unit): UpdatesUiState {
		if (this is Loading) block(this)
		return this
	}

	inline fun onError(block: (Error) -> Unit): UpdatesUiState {
		if (this is Error) block(this)
		return this
	}

	inline fun onSuccess(block: (Success) -> Unit): UpdatesUiState {
		if (this is Success) block(this)
		return this
	}

	fun mutableUpdates(): MutableList<AppUpdate> {
		if (this is Success) {
			return updates.toMutableList()
		}
		return mutableListOf()
	}

	/** 当前状态里记录的「参与检查的应用数」；非 Success 时为 0。 */
	fun scannedAppCount(): Int = if (this is Success) scannedApps else 0

	/** 当前状态里记录的「系统应用是否已纳入检查」。 */
	fun systemAppsIncluded(): Boolean = this is Success && systemAppsIncluded

	fun updates(): List<AppUpdate> {
		if (this is Success) {
			return updates
		}
		return emptyList()
	}

}
