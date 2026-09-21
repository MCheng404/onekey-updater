package com.onekey.updater.ui.screen

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.updater.BuildConfig
import com.onekey.updater.R
import com.onekey.updater.data.ui.SettingsSnapshot
import com.onekey.updater.ui.theme.ThemePref
import com.onekey.updater.util.InstallMode
import com.onekey.updater.util.net.Mirrors
import com.onekey.updater.util.net.NetworkDiagnostics
import com.onekey.updater.viewmodel.SettingsViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.WorldClock
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme


@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
	val settings = viewModel.state().collectAsStateWithLifecycle().value

	Column {
		SmallTopAppBar(
			title = stringResource(R.string.tab_settings))

		LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
			item { SourcesSection(settings, viewModel) }
			item { Divider() }
			item { FilterSection(settings, viewModel) }
			item { Divider() }
			item { UpdateScopeSection(settings, viewModel) }
			item { Divider() }
			item { InstallSection(settings, viewModel) }
			item { Divider() }
			item { ChinaNetworkSection(settings, viewModel) }
			item { Divider() }
			item { McpSection(settings, viewModel) }
			item { Divider() }
			item { AlarmSection(settings, viewModel) }
			item { Divider() }
			item { UiSection(settings, viewModel) }
			item { Divider() }
			item { ToolsSection(viewModel) }
			item { Divider() }
			item { AboutSection() }
		}
	}
}

@Composable
private fun Divider() = HorizontalDivider(
	Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
	color = MiuixTheme.colorScheme.dividerLine
)

/** 常用开关的简写，避免每个开关都写一遍 checked/onCheckedChange。 */
@Composable
private fun Toggle(
	title: String,
	summary: String? = null,
	checked: Boolean,
	onChange: (Boolean) -> Unit
) = SwitchPreference(
	title = title,
	summary = summary,
	checked = checked,
	onCheckedChange = onChange
)

// ---------------------------------------------------------------- 更新来源

@Composable
private fun SourcesSection(s: SettingsSnapshot, vm: SettingsViewModel) {
	SmallTitle(stringResource(R.string.settings_sources))
	Toggle(stringResource(R.string.source_github), checked = s.useGitHub, onChange = vm::setUseGitHub)
	Toggle(stringResource(R.string.source_fdroid), checked = s.useFdroid, onChange = vm::setUseFdroid)
	Toggle(stringResource(R.string.source_izzy), checked = s.useIzzy, onChange = vm::setUseIzzy)
	Toggle(stringResource(R.string.source_gitlab), checked = s.useGitLab, onChange = vm::setUseGitLab)
	Toggle(stringResource(R.string.source_aptoide), checked = s.useAptoide, onChange = vm::setUseAptoide)
	Toggle(stringResource(R.string.source_apkpure), checked = s.useApkPure, onChange = vm::setUseApkPure)
	Toggle(
		stringResource(R.string.source_apkmirror),
		summary = stringResource(R.string.source_apkmirror_summary),
		checked = s.useApkMirror,
		onChange = vm::setUseApkMirror
	)
	Toggle(
		stringResource(R.string.source_play),
		summary = stringResource(R.string.source_play_summary),
		checked = s.usePlay,
		onChange = vm::setUsePlay
	)
}

// ---------------------------------------------------------------- 过滤

@Composable
private fun FilterSection(s: SettingsSnapshot, vm: SettingsViewModel) {
	SmallTitle(stringResource(R.string.settings_filter))
	Toggle(
		stringResource(R.string.ignore_alpha),
		checked = s.ignoreAlpha,
		onChange = vm::setIgnoreAlpha
	)
	Toggle(
		stringResource(R.string.ignore_beta),
		checked = s.ignoreBeta,
		onChange = vm::setIgnoreBeta
	)
	Toggle(
		stringResource(R.string.ignore_preRelease),
		checked = s.ignorePreRelease,
		onChange = vm::setIgnorePreRelease
	)
	Toggle(
		stringResource(R.string.use_safe_stores),
		summary = stringResource(R.string.use_safe_stores_summary),
		checked = s.useSafeStores,
		onChange = vm::setUseSafeStores
	)
}

// ---------------------------------------------------------------- 更新范围

@Composable
private fun UpdateScopeSection(s: SettingsSnapshot, vm: SettingsViewModel) {
	SmallTitle(stringResource(R.string.settings_update_scope))

	Toggle(
		stringResource(R.string.update_system_apps),
		summary = stringResource(R.string.update_system_apps_summary),
		checked = s.updateSystemApps,
		onChange = vm::setUpdateSystemApps
	)
	Toggle(
		stringResource(R.string.update_store_apps),
		summary = stringResource(R.string.update_store_apps_summary),
		checked = s.updateStoreApps,
		onChange = vm::setUpdateStoreApps
	)

	// 这两项与「应用」页顶部三个图标的区别很容易混淆，必须写清楚
	Text(
		text = stringResource(R.string.update_scope_note),
		style = MiuixTheme.textStyles.footnote1,
		color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
		modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
	)
}

// ---------------------------------------------------------------- 安装

@Composable
private fun InstallSection(s: SettingsSnapshot, vm: SettingsViewModel) {
	val rootState by vm.root().collectAsStateWithLifecycle()

	SmallTitle(stringResource(R.string.settings_options))

	WindowDropdownPreference(
		title = stringResource(R.string.settings_install_mode),
		summary = stringResource(R.string.settings_install_mode_summary),
		items = listOf(
			stringResource(R.string.install_mode_auto),
			stringResource(R.string.install_mode_session),
			stringResource(R.string.install_mode_root)
		),
		selectedIndex = s.installMode.coerceIn(0, 2),
		onSelectedIndexChange = { vm.setInstallMode(it) }
	)

	ArrowPreference(
		title = stringResource(R.string.root_status),
		summary = when (rootState) {
			SettingsViewModel.RootState.Granted -> stringResource(R.string.root_granted)
			SettingsViewModel.RootState.Denied -> stringResource(R.string.root_denied)
			SettingsViewModel.RootState.Checking -> stringResource(R.string.root_checking)
			SettingsViewModel.RootState.Unknown -> stringResource(R.string.root_unknown)
		},
		onClick = { vm.requestRoot() }
	)

	Text(
		text = stringResource(R.string.install_mode_note),
		style = MiuixTheme.textStyles.footnote1,
		color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
		modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
	)
}

// ---------------------------------------------------------------- 国内网络

@Composable
private fun ChinaNetworkSection(s: SettingsSnapshot, vm: SettingsViewModel) {
	val diagnostics by vm.diagnostics().collectAsStateWithLifecycle()
	var showDiagnostics by remember { mutableStateOf(false) }

	SmallTitle(stringResource(R.string.settings_china_sources))

	Toggle(
		stringResource(R.string.use_china_mirror),
		summary = stringResource(R.string.use_china_mirror_summary),
		checked = s.useChinaMirror,
		onChange = vm::setUseChinaMirror
	)

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

	Toggle(
		stringResource(R.string.github_proxy_downloads),
		checked = s.githubProxyDownloads,
		onChange = vm::setGithubProxyDownloads
	)

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

	if (showDiagnostics) {
		DiagnosticsDialog(
			state = diagnostics,
			onRerun = { vm.runDiagnostics() },
			onApplyGithub = { vm.applyBestGithubProxy() },
			onApplyFdroid = { vm.applyBestFdroidMirror() },
			onDismiss = { showDiagnostics = false }
		)
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

// ---------------------------------------------------------------- MCP 服务

@Composable
private fun McpSection(s: SettingsSnapshot, vm: SettingsViewModel) {
	val mcp by vm.mcpState().collectAsStateWithLifecycle()
	// 端口用本地文本态：只有落进合法范围才提交，避免「每次按键都重绑服务」的抖动；
	// 非法或输入中途的片段不写预存、也不重启。
	var portText by remember(s.mcpPort) { mutableStateOf(s.mcpPort.toString()) }
	var showToken by remember { mutableStateOf(false) }

	SmallTitle(stringResource(R.string.mcp_service))

	SwitchPreference(
		title = stringResource(R.string.mcp_enable),
		summary = stringResource(R.string.mcp_enable_summary),
		checked = s.mcpEnabled,
		onCheckedChange = vm::setMcpEnabled
	)

	TextField(
		value = portText,
		onValueChange = { input ->
			val cleaned = input.filter { it.isDigit() }.take(5)
			portText = cleaned
			val v = cleaned.toIntOrNull()
			// 合法即提交；4 位以上仍非法（如 70000）才提示，避免输入途中反复刷 snackbar
			if (v != null && v in 1024..65535) {
				vm.setMcpPort(v)
			} else if (v != null && cleaned.length >= 4) {
				vm.notifyMcpPortInvalid()
			}
		},
		label = stringResource(R.string.mcp_port),
		useLabelAsPlaceholder = true,
		singleLine = true,
		enabled = s.mcpEnabled,
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
	)

	SwitchPreference(
		title = stringResource(R.string.mcp_allow_lan),
		summary = stringResource(R.string.mcp_allow_lan_summary),
		checked = s.mcpAllowLan,
		enabled = s.mcpEnabled,
		onCheckedChange = vm::setMcpAllowLan
	)

	ArrowPreference(
		title = stringResource(R.string.mcp_token),
		summary = if (s.mcpToken.isBlank()) {
			stringResource(R.string.mcp_token_unset)
		} else {
			maskToken(s.mcpToken)
		},
		onClick = { showToken = true }
	)

	// 实时状态：运行中（含监听地址）/ 已停止 / 出错（原文直出，绝不静默）
	val (statusText, statusColor) = when {
		mcp.error != null -> (stringResource(R.string.mcp_status_error) + "：" + mcp.error) to MiuixTheme.colorScheme.error
		mcp.running -> (stringResource(R.string.mcp_status_running) + " · ${mcp.boundAddress}:${mcp.port}") to MiuixTheme.colorScheme.primary
		else -> stringResource(R.string.mcp_status_stopped) to MiuixTheme.colorScheme.onSurfaceVariantSummary
	}
	Text(
		text = statusText,
		style = MiuixTheme.textStyles.footnote1,
		color = statusColor,
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
	)

	// PC 侧连接命令：点一下复制
	val adbCmd = "adb forward tcp:${s.mcpPort} tcp:${s.mcpPort}"
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 8.dp)
			.clickable { vm.copyToClipboard(adbCmd) },
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(
			text = stringResource(R.string.mcp_adb_hint),
			style = MiuixTheme.textStyles.footnote1,
			color = MiuixTheme.colorScheme.onSurfaceVariantSummary
		)
		Spacer(Modifier.width(6.dp))
		Text(
			text = adbCmd,
			style = MiuixTheme.textStyles.footnote2,
			color = MiuixTheme.colorScheme.primary
		)
	}

	if (showToken) {
		McpTokenDialog(
			token = s.mcpToken,
			onDismiss = { showToken = false },
			onCopy = { vm.copyToClipboard(s.mcpToken) },
			onRegenerate = { vm.regenerateMcpToken() }
		)
	}
}

/** 令牌打码：保留首尾各 2 位，其余用 • 遮挡，避免长令牌在列表里占满一行。 */
private fun maskToken(token: String): String = if (token.length <= 6) {
	"•".repeat(token.length)
} else {
	token.take(2) + "•".repeat(token.length - 4) + token.takeLast(2)
}

@Composable
private fun McpTokenDialog(
	token: String,
	onDismiss: () -> Unit,
	onCopy: () -> Unit,
	onRegenerate: () -> Unit
) = OverlayDialog(
	show = true,
	title = stringResource(R.string.mcp_token_dialog_title),
	summary = stringResource(R.string.mcp_token_dialog_summary),
	onDismissRequest = onDismiss
) {
	Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Text(
			text = token.ifBlank { stringResource(R.string.mcp_token_unset) },
			style = MiuixTheme.textStyles.body2,
			color = MiuixTheme.colorScheme.onSurface
		)
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(10.dp)
		) {
			TextButton(text = stringResource(R.string.mcp_token_copy), onClick = onCopy)
			TextButton(text = stringResource(R.string.mcp_token_regenerate), onClick = onRegenerate)
		}
		Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
			Text(stringResource(R.string.close))
		}
	}
}

// ---------------------------------------------------------------- 定时检查

@Composable
private fun AlarmSection(s: SettingsSnapshot, vm: SettingsViewModel) {
	// 通知权限只在真正需要时申请（定时检查/批量安装结果依赖通知反馈）。
	// 上游在冷启动就申请，会直接把用户甩到系统设置页。
	val notificationPermission = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestPermission()
	) {}

	SmallTitle(stringResource(R.string.settings_alarm))

	Toggle(
		stringResource(R.string.settings_alarm),
		checked = s.enableAlarm,
		onChange = { enabled ->
			if (enabled) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
			vm.setEnableAlarm(enabled)
		}
	)
	WindowDropdownPreference(
		title = stringResource(R.string.frequency),
		items = listOf(
			stringResource(R.string.settings_alarm_daily),
			stringResource(R.string.settings_alarm_3day),
			stringResource(R.string.settings_alarm_weekly)
		),
		selectedIndex = s.alarmFrequency.coerceIn(0, 2),
		enabled = s.enableAlarm,
		onSelectedIndexChange = { vm.setAlarmFrequency(it) }
	)
	SliderPreference(
		value = s.alarmHour.toFloat(),
		onValueChange = { vm.setAlarmHour(it.toInt()) },
		title = stringResource(R.string.settings_hour),
		summary = "${s.alarmHour}:00",
		valueRange = 0f..23f,
		steps = 22,
		enabled = s.enableAlarm
	)
}

// ---------------------------------------------------------------- 界面

@Composable
private fun UiSection(s: SettingsSnapshot, vm: SettingsViewModel) {
	SmallTitle(stringResource(R.string.settings_ui))

	WindowDropdownPreference(
		title = stringResource(R.string.theme),
		items = listOf(
			stringResource(R.string.theme_system),
			stringResource(R.string.theme_dark),
			stringResource(R.string.theme_light)
		),
		selectedIndex = s.theme.coerceIn(ThemePref.SYSTEM, ThemePref.LIGHT),
		onSelectedIndexChange = vm::setTheme
	)
	SliderPreference(
		value = s.portraitColumns.toFloat(),
		onValueChange = { vm.setPortraitColumns(it.toInt()) },
		title = stringResource(R.string.settings_portrait_columns),
		summary = s.portraitColumns.toString(),
		valueRange = 1f..6f,
		steps = 4
	)
	SliderPreference(
		value = s.landscapeColumns.toFloat(),
		onValueChange = { vm.setLandscapeColumns(it.toInt()) },
		title = stringResource(R.string.settings_landscape_columns),
		summary = s.landscapeColumns.toString(),
		valueRange = 2f..8f,
		steps = 5
	)
	Toggle(
		stringResource(R.string.play_text_animations),
		checked = s.playTextAnimations,
		onChange = vm::setPlayTextAnimations
	)
}

// ---------------------------------------------------------------- 工具与关于

@Composable
private fun ToolsSection(vm: SettingsViewModel) {
	SmallTitle(stringResource(R.string.settings_utils))
	ArrowPreference(
		title = stringResource(R.string.copy_app_list),
		onClick = { vm.copyAppList() }
	)
}

@Composable
private fun AboutSection() {
	SmallTitle(stringResource(R.string.about))
	ArrowPreference(
		title = stringResource(R.string.app_name),
		summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
	)
	if (BuildConfig.APPLICATION_ID != "com.onekey.updater") {
		ArrowPreference(title = stringResource(R.string.app_cd), summary = BuildConfig.APPLICATION_ID)
	}
}

// ---------------------------------------------------------------- 网络诊断

@Composable
private fun DiagnosticsDialog(
	state: SettingsViewModel.DiagnosticsState,
	onRerun: () -> Unit,
	onApplyGithub: () -> Unit,
	onApplyFdroid: () -> Unit,
	onDismiss: () -> Unit
) = OverlayDialog(
	show = true,
	title = stringResource(R.string.network_diagnostics),
	summary = stringResource(R.string.network_diagnostics_summary),
	onDismissRequest = onDismiss
) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		when (state) {
			SettingsViewModel.DiagnosticsState.Idle ->
				Text(stringResource(R.string.diagnostics_hint))

			SettingsViewModel.DiagnosticsState.Running ->
				Text(stringResource(R.string.diagnostics_running))

			is SettingsViewModel.DiagnosticsState.Done -> state.results.forEach { result ->
				DiagnosticsRow(result)
			}
		}

		Row(
			modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
			horizontalArrangement = Arrangement.spacedBy(10.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			TextButton(text = stringResource(R.string.diagnostics_rerun), onClick = onRerun)
			TextButton(text = stringResource(R.string.diagnostics_apply_github), onClick = onApplyGithub)
			TextButton(text = stringResource(R.string.diagnostics_apply_fdroid), onClick = onApplyFdroid)
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
