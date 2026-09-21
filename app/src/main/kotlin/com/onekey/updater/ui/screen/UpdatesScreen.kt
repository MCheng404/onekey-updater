package com.onekey.updater.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.updater.R
import com.onekey.updater.data.ui.UpdatesUiState
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.ui.component.AppList
import com.onekey.updater.ui.component.EmptyState
import com.onekey.updater.ui.component.ErrorState
import com.onekey.updater.ui.component.LoadingList
import com.onekey.updater.ui.component.UpdateGroupCard
import com.onekey.updater.ui.component.groupByPackage
import com.onekey.updater.util.isSystemApp
import com.onekey.updater.viewmodel.UpdatesViewModel
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Refresh
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.theme.MiuixTheme


/** 组合空状态副标题：先报检查数量，再列出生效的过滤条件（「排除商店」不影响更新扫描，故不列出）。 */
@Composable
private fun activeFilterHint(prefs: Prefs): String? {
	// stringResource 是 @Composable，不能放进 buildList 的普通 lambda，必须先求值
	val prefix = stringResource(R.string.filter_hint_prefix)
	val system = stringResource(R.string.filter_hint_system)
	val disabled = stringResource(R.string.filter_hint_disabled)
	val ignored = stringResource(R.string.filter_hint_ignored)

	val active = ArrayList<String>(4)
	if (prefs.excludeSystem.get()) active.add(system)
	if (prefs.excludeDisabled.get()) active.add(disabled)
	if (prefs.ignoredApps.get().isNotEmpty()) active.add(ignored)

	if (active.isEmpty()) return null
	return prefix + active.joinToString("、")
}

@Composable
private fun scannedLine(scannedApps: Int): String =
	stringResource(R.string.scanned_apps, scannedApps)

@Composable
fun UpdatesScreen(viewModel: UpdatesViewModel) {
	val state = viewModel.state().collectAsStateWithLifecycle().value
	val refreshing = viewModel.refreshing().collectAsStateWithLifecycle().value
	val handler = LocalUriHandler.current
	val prefs: Prefs = koinInject()

	Column {
		SmallTopAppBar(
			title = stringResource(R.string.tab_updates),
			actions = {
				IconButton(onClick = { viewModel.installAll() }) {
					Icon(
						imageVector = MiuixIcons.Download,
						contentDescription = stringResource(R.string.install_all),
						tint = MiuixTheme.colorScheme.primary,
						modifier = Modifier.size(22.dp)
					)
				}
				IconButton(onClick = { viewModel.refresh() }) {
					Icon(
						imageVector = MiuixIcons.Refresh,
						contentDescription = stringResource(R.string.refresh_updates),
						modifier = Modifier.size(22.dp)
					)
				}
			}
		)

		PullToRefresh(
			isRefreshing = refreshing,
			// 下拉刷新不显示骨架屏，避免列表整体闪一下
			onRefresh = { viewModel.refresh(load = false) }
		) {
			when (state) {
				UpdatesUiState.Loading -> LoadingList()

				UpdatesUiState.Error -> ErrorState()

				is UpdatesUiState.Success -> if (state.updates.isEmpty()) {
					// 上游这里只有一句"没有更新"，于是「真的没有更新」和「过滤/数据源出问题」
					// 在界面上完全无法区分，用户只能认为功能坏了。
					// 现在先说明本轮实际检查了多少个应用，再列出生效中的过滤条件。
					EmptyState(
						text = stringResource(R.string.no_updates),
						detail = scannedLine(state.scannedApps),
						hint = activeFilterHint(prefs)
					)
				} else {
					// 按包名归并：一个应用一行，多个来源作为该行下可切换的选项，
					// 而不是把同一应用的多个来源拆成多条列表项。
					val groups = remember(state.updates) { state.updates.groupByPackage() }
					val context = LocalContext.current

					// 自动识别系统应用并分段。识别结果按包名缓存（见 isSystemApp），
					// 否则滚动时每行一次跨进程查询会直接掉帧。
					val (systemGroups, userGroups) = remember(groups) {
						groups.partition { context.isSystemApp(it.first().packageName) }
					}

					// === 为什么每项都要显式声明 contentType ===
					// 列表里混了三类结构完全不同的项：分段标题、分隔线/说明文字、应用卡片。
					// 不声明 contentType 时，LazyColumn 认为它们「可以互相复用组合槽」，
					// 于是卡片可能被按标题那种纯文本项去测量 —— 实测表现为
					// 「系统应用段的第一张卡片被撑到 1900px 高、内容错位，滚动后才恢复」。
					// 声明后，卡片只会与卡片复用，结构不同的项各自独立。
					AppList {
						if (userGroups.isNotEmpty()) {
							item(key = "header-user", contentType = TYPE_HEADER) {
								SectionHeader(
									stringResource(R.string.section_user_apps),
									userGroups.size
								)
							}
							items(
								items = userGroups,
								key = { "user:" + it.first().packageName },
								contentType = { TYPE_CARD }
							) { candidates ->
								UpdateGroupCard(
									candidates = candidates,
									onInstall = { viewModel.install(it, handler) },
									onIgnore = { viewModel.ignoreVersion(it.id) }
								)
							}
						}

						// 两段之间画明显分界线（只在前面确实有内容时才画，避免顶部凭空一条线）
						if (userGroups.isNotEmpty()) {
							item(key = "separator", contentType = TYPE_SEPARATOR) { SectionSeparator() }
						}

						item(key = "header-system", contentType = TYPE_HEADER) {
							SectionHeader(
								stringResource(R.string.section_system_apps),
								systemGroups.size
							)
						}

						when {
							// 未开启「允许更新系统应用」时，明确告诉用户去哪开，
							// 而不是让系统应用「凭空消失」。
							// 读的是扫描结果带回来的状态，不是直接读偏好 ——
							// 偏好不可观察，直接读会与设置切换不同步。
							!state.systemAppsIncluded -> item(
								key = "system-off",
								contentType = TYPE_NOTE
							) { SectionNote(stringResource(R.string.system_section_disabled)) }

							systemGroups.isEmpty() -> item(
								key = "system-empty",
								contentType = TYPE_NOTE
							) { SectionNote(stringResource(R.string.system_section_empty)) }

							else -> items(
								items = systemGroups,
								key = { "system:" + it.first().packageName },
								contentType = { TYPE_CARD }
							) { candidates ->
								UpdateGroupCard(
									candidates = candidates,
									onInstall = { viewModel.install(it, handler) },
									onIgnore = { viewModel.ignoreVersion(it.id) }
								)
							}
						}
					}
				}
			}
		}
	}
}

// LazyColumn 的组合槽复用分组标识；结构不同的项必须分开，否则会被错误复用（见上方注释）
private const val TYPE_HEADER = "sectionHeader"
private const val TYPE_SEPARATOR = "separator"
private const val TYPE_NOTE = "sectionNote"
private const val TYPE_CARD = "updateCard"

/** 分段标题，例如「用户应用 · 3」。 */
@Composable
private fun SectionHeader(label: String, count: Int) = Row(
	modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
	verticalAlignment = Alignment.CenterVertically
) {
	Text(
		text = label,
		style = MiuixTheme.textStyles.body2,
		color = MiuixTheme.colorScheme.primary
	)
	Spacer(Modifier.width(6.dp))
	Text(
		text = count.toString(),
		style = MiuixTheme.textStyles.footnote1,
		color = MiuixTheme.colorScheme.onSurfaceVariantSummary
	)
}

/**
 * 用户应用与系统应用之间的分界线。
 * 用 2dp 实线 + 上下留白，比默认的 1dp 分隔线明显得多，一眼能看出分段。
 */
@Composable
private fun SectionSeparator() = Box(
	Modifier
		.fillMaxWidth()
		.padding(horizontal = 8.dp, vertical = 14.dp)
		.height(2.dp)
		.background(MiuixTheme.colorScheme.dividerLine)
)

/** 分段内的说明文字，用于「未开启」或「暂无更新」等状态。 */
@Composable
private fun SectionNote(text: String) = Text(
	text = text,
	style = MiuixTheme.textStyles.footnote1,
	color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
	modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
)
