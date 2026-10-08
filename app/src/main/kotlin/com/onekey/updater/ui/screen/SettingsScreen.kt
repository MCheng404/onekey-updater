package com.onekey.updater.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.updater.BuildConfig
import com.onekey.updater.R
import com.onekey.updater.data.ui.SettingsRoute
import com.onekey.updater.data.ui.SettingsSnapshot
import com.onekey.updater.ui.component.SettingsGroup
import com.onekey.updater.viewmodel.SettingsViewModel
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 设置主页面：现在只有「入口」，没有任何具体设置项。
 *
 * ### 为什么这么改
 * 原来这里平铺了 11 个分组、约 40 个控件，其中真正每天会碰的不到 5 个
 * （安装方式、定时检查、主题）。其余是「配一次就不再看」的参数
 * （各镜像线路、代理、MCP 端口、11 个来源开关）。把它们和常用项放在同一层级的后果是：
 * 打开设置要先滚动过 20 行才能摸到「安装方式」。
 *
 * 改法参照 AppMarket（MIUI 系）的做法：**主页面只做索引，细节进二级页**。
 * 另外把每个分组的控件套进 [SettingsGroup]（Card）—— Miuix 的 Preference 默认平铺，
 * 40 条控件在屏幕上连成一片，根本看不出哪几项是一组。
 *
 * ### 副标题的取舍
 * 每个入口都带一句 summary，说明「进去能看到什么、当前是什么状态」，
 * 例如「安装方式 · 自动」「已启用 3 / 11 个来源」。这样即使不进二级页，
 * 也能在这里判断要不要进 —— 这是把 40 项收成 9 项后必须补的信息密度。
 */
@Composable
fun SettingsScreen(
	viewModel: SettingsViewModel,
	onNavigate: (String) -> Unit
) {
	val s = viewModel.state().collectAsStateWithLifecycle().value
	val mcp by viewModel.mcpState().collectAsStateWithLifecycle()

	// 11 个来源开关的启用数，两个入口共用一个统计
	val sourceFlags = listOf(
		s.useGitHub, s.useFdroid, s.useIzzy, s.useGitLab, s.useAptoide, s.useApkPure,
		s.useVivo, s.useXiaomi, s.useTencent, s.useApkMirror, s.usePlay
	)
	val onCount = sourceFlags.count { it }
	val sourceCountText = stringResource(R.string.sources_enabled_count, onCount, sourceFlags.size)

	Column {
		SmallTopAppBar(title = stringResource(R.string.tab_settings))

		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			state = rememberLazyListState(),
			contentPadding = PaddingValues(bottom = 32.dp)
		) {
			// ---- 更新来源：11 个开关全塞进一个列表没人受得了，移进二级页 ----
			item {
				SmallTitle(stringResource(R.string.settings_group_update))
				SettingsGroup {
					ArrowPreference(
						title = stringResource(R.string.settings_sources),
						summary = sourceCountText,
						onClick = { onNavigate(SettingsRoute.SOURCES) }
					)
					ArrowPreference(
						title = stringResource(R.string.settings_filter),
						summary = filterSummary(s),
						onClick = { onNavigate(SettingsRoute.FILTER) }
					)
				}
			}

			// ---- 安装 ----
			item {
				SmallTitle(stringResource(R.string.settings_group_install))
				SettingsGroup {
					ArrowPreference(
						title = stringResource(R.string.settings_install),
						summary = stringResource(
							when (s.installMode) {
								0 -> R.string.install_mode_auto
								1 -> R.string.install_mode_session
								else -> R.string.install_mode_root
							}
						),
						onClick = { onNavigate(SettingsRoute.INSTALL) }
					)
				}
			}

			// ---- 网络 ----
			item {
				SmallTitle(stringResource(R.string.settings_group_network))
				SettingsGroup {
					ArrowPreference(
						title = stringResource(R.string.settings_china_sources),
						summary = stringResource(
							R.string.network_summary, viewModel.effectiveGithubPrefix()
						),
						onClick = { onNavigate(SettingsRoute.NETWORK) }
					)
					ArrowPreference(
						title = stringResource(R.string.settings_proxy),
						summary = if (s.proxyEnabled) {
							proxySummary(s)
						} else {
							stringResource(R.string.proxy_off_summary)
						},
						onClick = { onNavigate(SettingsRoute.PROXY) }
					)
				}
			}

			// ---- 自动化 ----
			item {
				SmallTitle(stringResource(R.string.settings_group_automation))
				SettingsGroup {
					ArrowPreference(
						title = stringResource(R.string.settings_alarm),
						summary = if (s.enableAlarm) {
							stringResource(R.string.alarm_on_summary, s.alarmHour)
						} else {
							stringResource(R.string.alarm_off_summary)
						},
						onClick = { onNavigate(SettingsRoute.ALARM) }
					)
					ArrowPreference(
						title = stringResource(R.string.mcp_service),
						summary = when {
							mcp.error != null -> stringResource(R.string.mcp_status_error)
							mcp.running -> stringResource(
								R.string.mcp_running_summary, mcp.boundAddress, mcp.port
							)
							else -> stringResource(R.string.mcp_status_stopped)
						},
						onClick = { onNavigate(SettingsRoute.MCP) }
					)
				}
			}

			// ---- 其他 ----
			item {
				SmallTitle(stringResource(R.string.settings_group_other))
				SettingsGroup {
					ArrowPreference(
						title = stringResource(R.string.settings_ui),
						summary = stringResource(R.string.appearance_summary, themeLabel(s.theme)),
						onClick = { onNavigate(SettingsRoute.APPEARANCE) }
					)
					ArrowPreference(
						title = stringResource(R.string.app_name),
						summary = versionText(),
						onClick = { onNavigate(SettingsRoute.ABOUT) }
					)
					// 复制应用列表是独立工具，不属于任何设置分组，也不值得单开一页，
					// 所以留在主页面末尾。原先它独占一个「工具」分组标题，
					// 为一个条目单开标题反而显得空。
					ArrowPreference(
						title = stringResource(R.string.copy_app_list),
						onClick = { viewModel.copyAppList() }
					)
				}
			}
		}
	}
}

/** 过滤摘要：把三个「是否忽略某类版本」压成一句人话，全关时显示「不过滤」。 */
@Composable
private fun filterSummary(s: SettingsSnapshot): String {
	val active = buildList {
		if (s.ignoreAlpha) add(stringResource(R.string.ignore_alpha))
		if (s.ignoreBeta) add(stringResource(R.string.ignore_beta))
		if (s.ignorePreRelease) add(stringResource(R.string.ignore_preRelease))
	}
	return if (active.isEmpty()) {
		stringResource(R.string.filter_none_summary)
	} else {
		active.joinToString("、")
	}
}

/** 代理摘要：只显示类型与端口，不回显主机（主机可能很长，会把摘要撑到两行）。 */
@Composable
private fun proxySummary(s: SettingsSnapshot): String {
	val type = stringResource(
		if (s.proxyType == 1) R.string.proxy_type_socks else R.string.proxy_type_http
	)
	val port = s.proxyPort
	return if (port in 1..65535) "$type · $port" else type
}

/** 主题名：与外观二级页的下拉选项文案保持一致，避免两处各写一份。 */
@Composable
private fun themeLabel(theme: Int): String = stringResource(
	when (theme) {
		1 -> R.string.theme_dark
		2 -> R.string.theme_light
		3 -> R.string.theme_dynamic
		4 -> R.string.theme_dark_pure
		else -> R.string.theme_system
	}
)