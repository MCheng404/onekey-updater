package com.onekey.updater.ui.screen

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.updater.BuildConfig
import com.onekey.updater.R
import com.onekey.updater.ui.component.SettingsGroup
import com.onekey.updater.ui.component.SettingsSubPage
import com.onekey.updater.viewmodel.SettingsViewModel
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 安装方式。
 *
 * 三件事按重要性排：安装模式（最常改）→ 权限状态（root / Shizuku，决定能不能装）
 * → 安装前确认。原先四者平铺在一屏里，现在顺序即使用频率。
 */
@Composable
fun InstallSettingsPage(vm: SettingsViewModel, onBack: () -> Unit) {
	val s = vm.state().collectAsStateWithLifecycle().value
	val rootState by vm.root().collectAsStateWithLifecycle()

	SettingsSubPage(title = stringResource(R.string.settings_install), onBack = onBack) { pad ->
		LazyColumn(modifier = Modifier, contentPadding = pad) {
			item {
				SmallTitle(stringResource(R.string.settings_group_install_mode))
				SettingsGroup {
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
					SwitchPreference(
						title = stringResource(R.string.confirm_ignore),
						summary = stringResource(R.string.confirm_ignore_summary),
						checked = s.confirmIgnore,
						onCheckedChange = vm::setConfirmIgnore
					)
				}
			}

			item {
				SmallTitle(stringResource(R.string.settings_group_permission))
				SettingsGroup {
					SwitchPreference(
						title = stringResource(R.string.shizuku_enable),
						summary = stringResource(R.string.shizuku_enable_summary),
						checked = s.useShizuku,
						onCheckedChange = vm::setUseShizuku
					)
					ArrowPreference(
						title = stringResource(R.string.shizuku_status),
						summary = vm.shizukuStatus(),
						onClick = { vm.openShizuku() }
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
				}
			}

			item {
				Text(
					text = stringResource(R.string.install_mode_note),
					style = MiuixTheme.textStyles.footnote1,
					color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
					modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp)
				)
			}
		}
	}
}

/**
 * 关于页内容。
 *
 * 搬过来时顺手修掉一个真实隐患：原先点链接直接 `startActivity`，
 * 设备上没装任何浏览器时会抛 ActivityNotFoundException，把设置页带崩。
 * 现在整段包在 runCatching 里，失败只记日志。
 */
@Composable
fun AboutContent() {
	Column(modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp)) {
		AboutRow(stringResource(R.string.about_version), versionText())
		AboutRow(stringResource(R.string.about_package), LocalContext.current.packageName)
		AboutLink(stringResource(R.string.about_upstream), UPSTREAM_URL)
		AboutLink(stringResource(R.string.about_project), PROJECT_URL)
		AboutRow(stringResource(R.string.about_license), "GPL-3.0")
		Text(
			text = stringResource(R.string.about_based_on),
			style = MiuixTheme.textStyles.footnote2,
			color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
			modifier = Modifier.padding(top = 12.dp)
		)
	}
}

internal const val UPSTREAM_URL = "https://github.com/rumboalla/apkupdater"
internal const val PROJECT_URL = "https://github.com/MCheng404/onekey-updater"

internal fun versionText(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

@Composable
private fun AboutRow(label: String, value: String) = Row(
	modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
	horizontalArrangement = Arrangement.SpaceBetween,
	verticalAlignment = Alignment.CenterVertically
) {
	Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
	Text(value, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurface)
}

/**
 * 链接行：**标签在上、地址在下**，不是左右并排。
 *
 * 仓库地址普遍 40+ 字符，在左右并排的布局里必然折行，折行后地址会绕回标签那一行，
 * 读起来像「上游项目」和「项目主页」两行文字混在一起（实测就是这个效果）。
 * 纵向排列后地址独占一行，标签归标签、链接归链接。
 */
@Composable
private fun AboutLink(label: String, url: String) {
	val context = LocalContext.current
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 6.dp)
			// 没有浏览器时 startActivity 会抛 ActivityNotFoundException；
			// 不接住的话整个设置页跟着崩，这里必须兜住。
			.clickable {
				runCatching {
					context.startActivity(
						Intent(Intent.ACTION_VIEW, url.toUri())
							.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
					)
				}.onFailure { Log.w("AboutLink", "无法打开链接: $url", it) }
			}
	) {
		Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
		Text(url, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
	}
}