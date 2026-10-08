package com.onekey.updater.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.updater.R
import com.onekey.updater.ui.component.SettingsGroup
import com.onekey.updater.ui.component.SettingsSubPage
import com.onekey.updater.util.net.Mirrors
import com.onekey.updater.util.net.NetworkDiagnostics
import com.onekey.updater.viewmodel.SettingsViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.WorldClock
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 国内网络优化。
 *
 * 三个镜像（GitHub / F-Droid / Izzy）各自成一组而不是堆在一张卡里：
 * 每个组的「下拉 + 可能出现的自定义输入框」是一套完整配置，
 * 挤在一起会出现「选了哪个自定义输入框属于谁」这种歧义。
 * 网络诊断作为整页最后一个入口 —— 它会产生近百行结果，不该和日常开关混排。
 */
@Composable
fun NetworkSettingsPage(vm: SettingsViewModel, onBack: () -> Unit) {
	val s = vm.state().collectAsStateWithLifecycle().value
	val diagnostics by vm.diagnostics().collectAsStateWithLifecycle()
	var showDiagnostics by remember { mutableStateOf(false) }

	SettingsSubPage(title = stringResource(R.string.settings_china_sources), onBack = onBack) { pad ->
		LazyColumn(modifier = Modifier, contentPadding = pad) {
			item {
				SmallTitle(stringResource(R.string.settings_group_line))
				SettingsGroup {
					SwitchPreference(
						title = stringResource(R.string.use_china_mirror),
						summary = stringResource(R.string.use_china_mirror_summary),
						checked = s.useChinaMirror,
						onCheckedChange = vm::setUseChinaMirror
					)
				}
			}

			item {
				SmallTitle(stringResource(R.string.settings_group_mirror))
				SettingsGroup {
					WindowDropdownPreference(
						title = stringResource(R.string.github_proxy),
						summary = vm.effectiveGithubPrefix(),
						items = Mirrors.githubProxies.map { it.label },
						selectedIndex = s.githubProxyId.coerceIn(0, Mirrors.githubProxies.lastIndex),
						onSelectedIndexChange = { vm.setGithubProxyId(it) }
					)
					if (Mirrors.githubProxies.getOrNull(s.githubProxyId)?.value == Mirrors.CUSTOM) {
						CustomUrlField(
							value = s.githubCustomProxy,
							label = stringResource(R.string.github_custom_proxy),
							onValueChange = vm::setGithubCustomProxy
						)
					}
					SwitchPreference(
						title = stringResource(R.string.github_proxy_downloads),
						checked = s.githubProxyDownloads,
						onCheckedChange = vm::setGithubProxyDownloads
					)
				}
			}

			item {
				SmallTitle(stringResource(R.string.settings_group_repo))
				SettingsGroup {
					WindowDropdownPreference(
						title = stringResource(R.string.fdroid_mirror),
						summary = vm.effectiveFdroidRepo(),
						items = Mirrors.fdroidMirrors.map { it.label },
						selectedIndex = s.fdroidMirrorId.coerceIn(0, Mirrors.fdroidMirrors.lastIndex),
						onSelectedIndexChange = { vm.setFdroidMirrorId(it) }
					)
					if (Mirrors.fdroidMirrors.getOrNull(s.fdroidMirrorId)?.value == Mirrors.CUSTOM) {
						CustomUrlField(
							value = s.fdroidCustomUrl,
							label = stringResource(R.string.fdroid_custom_url),
							onValueChange = vm::setFdroidCustomUrl
						)
					}
					WindowDropdownPreference(
						title = stringResource(R.string.izzy_mirror),
						items = Mirrors.izzyMirrors.map { it.label },
						selectedIndex = s.izzyMirrorId.coerceIn(0, Mirrors.izzyMirrors.lastIndex),
						onSelectedIndexChange = { vm.setIzzyMirrorId(it) }
					)
					if (Mirrors.izzyMirrors.getOrNull(s.izzyMirrorId)?.value == Mirrors.CUSTOM) {
						CustomUrlField(
							value = s.izzyCustomUrl,
							label = stringResource(R.string.izzy_custom_url),
							onValueChange = vm::setIzzyCustomUrl
						)
					}
				}
			}

			item {
				SmallTitle(stringResource(R.string.settings_group_tools))
				SettingsGroup {
					ArrowPreference(
						title = stringResource(R.string.network_diagnostics),
						summary = stringResource(R.string.network_diagnostics_summary),
						startAction = {
							Icon(
								imageVector = MiuixIcons.WorldClock,
								contentDescription = null,
								modifier = Modifier.size(22.dp)
							)
						},
						onClick = {
							showDiagnostics = true
							vm.runDiagnostics()
						}
					)
				}
			}
		}
	}

	if (showDiagnostics) {
		DiagnosticsDialog(
			state = diagnostics,
			onRerun = { vm.runDiagnostics() },
			onOptimize = { vm.applyBestLines() },
			onDismiss = { showDiagnostics = false }
		)
	}
}

/**
 * 代理。
 *
 * 不做「跟随系统」：系统代理对 App 是否生效取决于厂商实现，不可控；
 * 用户想要的是「我配了就一定走」。实现在 OkHttp 层，因此更新检查、下载、图标加载
 * 三条链路一起生效，不需要在各处分别设置。
 */
@Composable
fun ProxySettingsPage(vm: SettingsViewModel, onBack: () -> Unit) {
	val s = vm.state().collectAsStateWithLifecycle().value

	SettingsSubPage(title = stringResource(R.string.settings_proxy), onBack = onBack) { pad ->
		LazyColumn(modifier = Modifier, contentPadding = pad) {
			item {
				SettingsGroup {
					SwitchPreference(
						title = stringResource(R.string.proxy_enabled),
						summary = stringResource(R.string.proxy_enabled_summary),
						checked = s.proxyEnabled,
						onCheckedChange = vm::setProxyEnabled
					)
				}
			}

			// 关闭时不渲染后面的输入框：留着三个灰掉的输入框只会让人以为还能填
			if (s.proxyEnabled) {
				item {
					SmallTitle(stringResource(R.string.settings_group_proxy_detail))
					SettingsGroup {
						WindowDropdownPreference(
							title = stringResource(R.string.proxy_type),
							summary = stringResource(R.string.proxy_type_summary),
							items = listOf(
								stringResource(R.string.proxy_type_http),
								stringResource(R.string.proxy_type_socks)
							),
							selectedIndex = s.proxyType.coerceIn(0, 1),
							onSelectedIndexChange = { vm.setProxyType(it) }
						)
						CustomUrlField(
							value = s.proxyHost,
							label = stringResource(R.string.proxy_host),
							onValueChange = vm::setProxyHost
						)
						ProxyPortField(s.proxyPort, vm)
					}
				}
			}
		}
	}
}

@Composable
private fun CustomUrlField(value: String, label: String, onValueChange: (String) -> Unit) = TextField(
	value = value,
	onValueChange = onValueChange,
	modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
	label = label,
	useLabelAsPlaceholder = true,
	singleLine = true
)

@Composable
private fun ProxyPortField(current: Int, vm: SettingsViewModel) {
	// 本地文本态：输入过程中不逐字符落库，避免"刚敲一位就被当成端口提交"
	var text by remember(current) { mutableStateOf(if (current in 1..65535) current.toString() else "") }

	TextField(
		value = text,
		onValueChange = { input ->
			val cleaned = input.filter { it.isDigit() }.take(5)
			text = cleaned
			val v = cleaned.toIntOrNull()
			if (v != null && v in 1..65535) {
				vm.setProxyPort(v)
			} else if (cleaned.length >= 4) {
				// 只在明显非法时才提示，避免输入途中反复刷 snackbar
				vm.notifyProxyPortInvalid()
			}
		},
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
		label = stringResource(R.string.proxy_port),
		useLabelAsPlaceholder = true,
		singleLine = true,
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
	)
}

// ---------------------------------------------------------------- 网络诊断

@Composable
fun DiagnosticsDialog(
	state: SettingsViewModel.DiagnosticsState,
	onRerun: () -> Unit,
	onOptimize: () -> Unit,
	onDismiss: () -> Unit
) = OverlayDialog(
	show = true,
	title = stringResource(R.string.network_diagnostics),
	summary = stringResource(R.string.network_diagnostics_summary),
	onDismissRequest = onDismiss
) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		// 结果区必须自己限高并可滚动：诊断一次会返回近百条线路，
		// 之前结果直接把下面的按钮挤出弹窗、而且整体推不动，用户根本点不到按钮。
		// 现在把滚动限制在结果区内部，按钮固定在其下方。
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(max = 360.dp)
				.verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(6.dp)
		) {
			when (state) {
				SettingsViewModel.DiagnosticsState.Idle ->
					Text(stringResource(R.string.diagnostics_hint))

				SettingsViewModel.DiagnosticsState.Running ->
					Text(stringResource(R.string.diagnostics_running))

				is SettingsViewModel.DiagnosticsState.Done -> {
					// 分组 + 最快优先 + 每组只显示前若干条。
					// 接入 78 个 GitHub 节点后，平铺列表会长到没法看；而这个界面的目的
					// 只是「挑一条能用的、最快的」，所以按类别收起、按延迟排序最有价值。
					val byKind = state.results.groupBy { it.kind }
					listOf(
						NetworkDiagnostics.Kind.GITHUB,
						NetworkDiagnostics.Kind.FDROID,
						NetworkDiagnostics.Kind.SOURCE
					).forEach { kind ->
						val items = byKind[kind] ?: return@forEach
						val sorted = items.sortedWith(compareBy({ !it.ok }, { it.millis }))
						val best = sorted.firstOrNull { it.ok }
						val usable = sorted.count { it.ok }

						Text(
							text = diagnosticsKindLabel(kind) + "  ·  " +
								stringResource(R.string.diagnostics_usable_count, usable, items.size),
							style = MiuixTheme.textStyles.footnote2,
							color = MiuixTheme.colorScheme.primary,
							modifier = Modifier.padding(top = 10.dp)
						)
						Text(
							text = if (best != null) {
								stringResource(R.string.diagnostics_fastest, best.label, best.millis)
							} else {
								stringResource(R.string.diagnostics_none_usable)
							},
							style = MiuixTheme.textStyles.footnote1,
							color = MiuixTheme.colorScheme.onSurfaceVariantSummary
						)

						sorted.take(DIAGNOSTICS_MAX_ROWS).forEach { DiagnosticsRow(it) }
						if (sorted.size > DIAGNOSTICS_MAX_ROWS) {
							Text(
								text = stringResource(
									R.string.diagnostics_more_hidden,
									sorted.size - DIAGNOSTICS_MAX_ROWS
								),
								style = MiuixTheme.textStyles.footnote2,
								color = MiuixTheme.colorScheme.onSurfaceVariantSummary
							)
						}
					}
				}
			}
		}

		// 按钮合并为「优化线路」：原先后要用户分别点「使用最快 GitHub 线路」和
		// 「使用最快 F-Droid 线路」两次，而且按钮被结果挤下去还推不动。
		// 现在一次把两类都调到实测最快的线路。
		Row(
			modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
			horizontalArrangement = Arrangement.spacedBy(10.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			TextButton(text = stringResource(R.string.diagnostics_rerun), onClick = onRerun)
			TextButton(text = stringResource(R.string.diagnostics_optimize), onClick = onOptimize)
		}
		Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
			Text(stringResource(R.string.close))
		}
	}
}

@Composable
private fun DiagnosticsRow(result: NetworkDiagnostics.Result) = Row(
	modifier = Modifier.fillMaxWidth(),
	verticalAlignment = Alignment.CenterVertically
) {
	Text(
		text = if (result.ok) "✓" else "✕",
		color = if (result.ok) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
		style = MiuixTheme.textStyles.body1
	)
	Text(
		text = "  " + result.label,
		style = MiuixTheme.textStyles.body2,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
		modifier = Modifier.weight(1f)
	)
	Text(
		text = if (result.ok) result.millis.toString() + " ms" else result.detail,
		style = MiuixTheme.textStyles.footnote1,
		color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
		maxLines = 1,
		modifier = Modifier.widthIn(max = 130.dp)
	)
}

/** 诊断结果每组最多显示多少条。 */
private const val DIAGNOSTICS_MAX_ROWS = 8

@Composable
private fun diagnosticsKindLabel(kind: NetworkDiagnostics.Kind): String = when (kind) {
	NetworkDiagnostics.Kind.GITHUB -> stringResource(R.string.diagnostics_group_github)
	NetworkDiagnostics.Kind.FDROID -> stringResource(R.string.diagnostics_group_fdroid)
	NetworkDiagnostics.Kind.SOURCE -> stringResource(R.string.diagnostics_group_source)
}