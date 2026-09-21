package com.onekey.updater.data.ui


sealed class SearchUiState {

    /** 尚未输入搜索词。与「搜了但没结果」是两种不同状态，UI 需要区分提示。 */
    data object Empty : SearchUiState()

    data object Loading : SearchUiState()

    data object Error : SearchUiState()

    data class Success(val updates: List<AppUpdate>) : SearchUiState()

    inline fun onEmpty(block: (Empty) -> Unit): SearchUiState {
        if (this is Empty) block(this)
        return this
    }

    inline fun onLoading(block: (Loading) -> Unit): SearchUiState {
        if (this is Loading) block(this)
        return this
    }

    inline fun onError(block: (Error) -> Unit): SearchUiState {
        if (this is Error) block(this)
        return this
    }

    inline fun onSuccess(block: (Success) -> Unit): SearchUiState {
        if (this is Success) block(this)
        return this
    }

    fun mutableUpdates(): MutableList<AppUpdate> =
        if (this is Success) updates.toMutableList() else mutableListOf()

    fun updates(): List<AppUpdate> = if (this is Success) updates else emptyList()
}
