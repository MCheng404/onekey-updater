package com.onekey.updater.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import top.yukonga.miuix.kmp.basic.Text
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
						// 只读到个位数应用，几乎可以断定是系统没放行「获取已安装的应用信息」
						// （小米等 ROM 会在首次调用 getInstalledPackages 时弹窗，未授权就只返回自身）。
						// 此前这里什么都不提示，用户看到的就是「应用列表是空的、出现得很慢」。
						if (state.apps.size <= 3) {
							item(key = "app-list-permission-hint", contentType = "permissionHint") {
								AppListPermissionHint(state.apps.size)
							}
						}
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

/**
 * 「只读到很少应用」时的提示。
 *
 * 这不是可有可无的文案：MIUI/HyperOS 未放行「获取已安装的应用信息」时，
 * `getInstalledPackages` 只会返回应用自身，于是应用列表几乎为空 —— 用户看到的现象就是
 * 「应用半天不出来 / 没有应用」。给出原因 + 一键跳到系统设置，才有可操作性。
 */
@Composable
private fun AppListPermissionHint(count: Int) {
	val context = LocalContext.current
	Card {
		Column(
			modifier = Modifier.fillMaxWidth().padding(14.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Text(
				text = stringResource(R.string.app_list_permission_title),
				style = MiuixTheme.textStyles.body1
			)
			Text(
				text = stringResource(R.string.app_list_permission_summary, count),
				style = MiuixTheme.textStyles.footnote1,
				color = MiuixTheme.colorScheme.onSurfaceVariantSummary
			)
			Button(
				onClick = {
					runCatching {
						context.startActivity(
							android.content.Intent(
								android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
								android.net.Uri.parse("package:" + context.packageName)
							).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
						)
					}
				},
				modifier = Modifier.fillMaxWidth()
			) {
				Text(stringResource(R.string.app_list_permission_action))
			}
		}
	}
}
