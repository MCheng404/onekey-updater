package com.onekey.updater.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.updater.R
import com.onekey.updater.data.ui.AppsUiState
import com.onekey.updater.ui.component.AppList
import com.onekey.updater.ui.component.EmptyState
import com.onekey.updater.ui.component.ErrorState
import com.onekey.updater.ui.component.InstalledCard
import com.onekey.updater.ui.component.LoadingList
import com.onekey.updater.viewmodel.AppsViewModel
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme


@Composable
fun AppsScreen(viewModel: AppsViewModel) {
	val state = viewModel.state().collectAsStateWithLifecycle().value
	val refreshing = viewModel.refreshing().collectAsStateWithLifecycle().value

	Column {
		SmallTopAppBar(
			title = stringResource(R.string.tab_apps),
			actions = {
				// 三个过滤开关：系统应用 / 应用商店 / 已停用
				FilterIcon(
					on = state.excludeSystem(),
					onRes = R.drawable.ic_system,
					offRes = R.drawable.ic_system_off,
					description = stringResource(R.string.exclude_system_apps),
					onClick = { viewModel.onSystemClick() }
				)
				FilterIcon(
					on = state.excludeAppStore(),
					onRes = R.drawable.ic_appstore,
					offRes = R.drawable.ic_appstore_off,
					description = stringResource(R.string.exclude_app_store),
					onClick = { viewModel.onAppStoreClick() }
				)
				FilterIcon(
					on = state.excludeDisabled(),
					onRes = R.drawable.ic_disabled,
					offRes = R.drawable.ic_disabled_off,
					description = stringResource(R.string.exclude_disabled_apps),
					onClick = { viewModel.onDisabledClick() }
				)
			}
		)

		PullToRefresh(
			isRefreshing = refreshing,
			onRefresh = { viewModel.refresh(load = false) }
		) {
			when (state) {
				is AppsUiState.Loading -> LoadingList()

				AppsUiState.Error -> ErrorState()

				is AppsUiState.Success -> if (state.apps.isEmpty()) {
					EmptyState(stringResource(R.string.no_apps))
				} else {
					AppList {
						items(state.apps, key = { it.packageName }) { app ->
							InstalledCard(app) { viewModel.ignore(app.packageName) }
						}
					}
				}
			}
		}
	}
}

private fun AppsUiState.excludeSystem() = when (this) {
	is AppsUiState.Loading -> excludeSystem
	is AppsUiState.Success -> excludeSystem
	else -> false
}

private fun AppsUiState.excludeAppStore() = when (this) {
	is AppsUiState.Loading -> excludeAppStore
	is AppsUiState.Success -> excludeAppStore
	else -> false
}

private fun AppsUiState.excludeDisabled() = when (this) {
	is AppsUiState.Loading -> excludeDisabled
	is AppsUiState.Success -> excludeDisabled
	else -> false
}

@Composable
private fun FilterIcon(
	on: Boolean,
	onRes: Int,
	offRes: Int,
	description: String,
	onClick: () -> Unit
) = IconButton(onClick = onClick) {
	Icon(
		painter = painterResource(if (on) onRes else offRes),
		contentDescription = description,
		tint = if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
		modifier = Modifier.size(22.dp)
	)
}

/** 预留：应用列表刷新时的居中指示器（当前由 PullToRefresh 承担）。 */
@Composable
internal fun AppsLoadingIndicator() = CircularProgressIndicator(progress = null, size = 32.dp)
