package com.onekey.updater.ui.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.updater.R
import com.onekey.updater.ui.component.SettingsGroup
import com.onekey.updater.ui.component.SettingsSubPage
import com.onekey.updater.viewmodel.SettingsViewModel
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 更新来源。
 *
 * 11 个开关是整个设置里最长的一组，所以单独成页，并且**按「国内 / 国外」分成两张卡**。
 * 不分组的话 11 个同名式样的开关连在一起，用户根本不知道自己开的是哪几个；
 * 分组后「国内 4 个全开、国外 2 个」这种状态一眼可读。
 *
 * 顺序也做了调整：国内来源排前面。国内用户 90% 只用国内源，
 * 让他们先看到用得上的那几个，而不是从 GitHub 翻起。
 */
@Composable
fun SourcesSettingsPage(vm: SettingsViewModel, onBack: () -> Unit) {
	val s = vm.state().collectAsStateWithLifecycle().value

	SettingsSubPage(title = stringResource(R.string.settings_sources), onBack = onBack) { pad ->
		LazyColumn(modifier = Modifier, contentPadding = pad) {
			item {
				SmallTitle(stringResource(R.string.sources_group_domestic))
				SettingsGroup {
					SwitchPreference(
						title = stringResource(R.string.source_tencent),
						summary = stringResource(R.string.source_tencent_summary),
						checked = s.useTencent,
						onCheckedChange = vm::setUseTencent
					)
					SwitchPreference(
						title = stringResource(R.string.source_vivo),
						summary = stringResource(R.string.source_vivo_summary),
						checked = s.useVivo,
						onCheckedChange = vm::setUseVivo
					)
					SwitchPreference(
						title = stringResource(R.string.source_xiaomi),
						summary = stringResource(R.string.source_xiaomi_summary),
						checked = s.useXiaomi,
						onCheckedChange = vm::setUseXiaomi
					)
					SwitchPreference(
						title = stringResource(R.string.source_apkmirror),
						summary = stringResource(R.string.source_apkmirror_summary),
						checked = s.useApkMirror,
						onCheckedChange = vm::setUseApkMirror
					)
				}
			}

			item {
				SmallTitle(stringResource(R.string.sources_group_global))
				SettingsGroup {
					SwitchPreference(
						title = stringResource(R.string.source_github),
						checked = s.useGitHub,
						onCheckedChange = vm::setUseGitHub
					)
					SwitchPreference(
						title = stringResource(R.string.source_fdroid),
						checked = s.useFdroid,
						onCheckedChange = vm::setUseFdroid
					)
					SwitchPreference(
						title = stringResource(R.string.source_izzy),
						checked = s.useIzzy,
						onCheckedChange = vm::setUseIzzy
					)
					SwitchPreference(
						title = stringResource(R.string.source_gitlab),
						checked = s.useGitLab,
						onCheckedChange = vm::setUseGitLab
					)
					SwitchPreference(
						title = stringResource(R.string.source_aptoide),
						checked = s.useAptoide,
						onCheckedChange = vm::setUseAptoide
					)
					SwitchPreference(
						title = stringResource(R.string.source_apkpure),
						checked = s.useApkPure,
						onCheckedChange = vm::setUseApkPure
					)
					SwitchPreference(
						title = stringResource(R.string.source_play),
						summary = stringResource(R.string.source_play_summary),
						checked = s.usePlay,
						onCheckedChange = vm::setUsePlay
					)
				}
			}
		}
	}
}