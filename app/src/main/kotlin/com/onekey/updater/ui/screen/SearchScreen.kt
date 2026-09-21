package com.onekey.updater.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.updater.R
import com.onekey.updater.data.ui.SearchUiState
import com.onekey.updater.ui.component.AppList
import com.onekey.updater.ui.component.EmptyState
import com.onekey.updater.ui.component.ErrorState
import com.onekey.updater.ui.component.LoadingList
import com.onekey.updater.ui.component.UpdateCard
import com.onekey.updater.viewmodel.SearchViewModel
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Search


/** 输入停顿多久后才真正发起搜索，避免每敲一个字母打一轮全网接口。 */
private const val SEARCH_DEBOUNCE_MS = 600L
private const val MIN_QUERY_LENGTH = 2

@Composable
fun SearchScreen(viewModel: SearchViewModel) {
	val state = viewModel.state().collectAsStateWithLifecycle().value
	val handler = LocalUriHandler.current
	val keyboard = LocalSoftwareKeyboardController.current
	var query by rememberSaveable { mutableStateOf("") }

	// 防抖：上游是「>=3 字符再固定等 1 秒」，输入慢时仍然会打满所有源；
	// 这里改成每次输入重置计时器，停顿 600ms 才发请求。
	LaunchedEffect(query) {
		when {
			query.length >= MIN_QUERY_LENGTH -> {
				delay(SEARCH_DEBOUNCE_MS)
				viewModel.search(query)
			}
			else -> viewModel.search("")
		}
	}

	Column {
		SmallTopAppBar(
			title = stringResource(R.string.tab_search))

		TextField(
			value = query,
			onValueChange = { query = it },
			modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
			label = stringResource(R.string.search_hint),
			useLabelAsPlaceholder = true,
			singleLine = true,
			leadingIcon = {
				Icon(
					imageVector = MiuixIcons.Search,
					contentDescription = null,
					modifier = Modifier.size(20.dp)
				)
			},
			trailingIcon = if (query.isNotEmpty()) {
				{
					IconButton(onClick = { query = "" }) {
						Icon(
							imageVector = MiuixIcons.Close,
							contentDescription = stringResource(R.string.clear_search),
							modifier = Modifier.size(18.dp)
						)
					}
				}
			} else {
				null
			},
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
			keyboardActions = KeyboardActions(onSearch = {
				keyboard?.hide()
				viewModel.search(query)
			})
		)

		when {
			state is SearchUiState.Empty -> EmptyState(stringResource(R.string.search_hint_long))

			state is SearchUiState.Loading -> LoadingList()

			state is SearchUiState.Error -> ErrorState()

			state is SearchUiState.Success && state.updates.isEmpty() ->
				EmptyState(stringResource(R.string.no_search_results))

			state is SearchUiState.Success -> AppList {
				items(state.updates, key = { it.id }) { update ->
					UpdateCard(
						update = update,
						onInstall = { viewModel.install(update, handler) }
					)
				}
			}
		}
	}
}
