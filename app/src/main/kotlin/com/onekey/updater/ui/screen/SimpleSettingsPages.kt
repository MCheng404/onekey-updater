package com.onekey.updater.ui.screen

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.updater.R
import com.onekey.updater.ui.component.SettingsGroup
import com.onekey.updater.ui.component.SettingsSubPage
import com.onekey.updater.ui.theme.ThemePref
import com.onekey.updater.viewmodel.SettingsViewModel
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

/**
 * 更新过滤。
 *
 * 原本「过滤」和「更新范围」是两个平铺分组，其实是一件事的两面：
 * 都是决定「哪些应用/哪些版本出现在更新列表里」。合并成一页后，
 * 用户不必先判断某个开关属于哪一组。
 */
@Composable
fun FilterSettingsPage(vm: SettingsViewModel, onBack: () -> Unit) {
	val s = vm.state().collectAsStateWithLifecycle().value

	SettingsSubPage(title = stringResource(R.string.settings_filter), onBack = onBack) { pad ->
		LazyColumn(modifier = Modifier, contentPadding = pad) {
			item {
				SmallTitle(stringResource(R.string.sources_group_version))
				SettingsGroup {
					SwitchPreference(
						title = stringResource(R.string.ignore_alpha),
						checked = s.ignoreAlpha,
						onCheckedChange = vm::setIgnoreAlpha
					)
					SwitchPreference(
						title = stringResource(R.string.ignore_beta),
						checked = s.ignoreBeta,
						onCheckedChange = vm::setIgnoreBeta
					)
					SwitchPreference(
						title = stringResource(R.string.ignore_preRelease),
						checked = s.ignorePreRelease,
						onCheckedChange = vm::setIgnorePreRelease
					)
				}
			}

			item {
				SmallTitle(stringResource(R.string.settings_group_app_type))
				SettingsGroup {
					SwitchPreference(
						title = stringResource(R.string.update_system_apps),
						summary = stringResource(R.string.update_system_apps_summary),
						checked = s.updateSystemApps,
						onCheckedChange = vm::setUpdateSystemApps
					)
					SwitchPreference(
						title = stringResource(R.string.update_store_apps),
						summary = stringResource(R.string.update_store_apps_summary),
						checked = s.updateStoreApps,
						onCheckedChange = vm::setUpdateStoreApps
					)
					SwitchPreference(
						title = stringResource(R.string.use_safe_stores),
						summary = stringResource(R.string.use_safe_stores_summary),
						checked = s.useSafeStores,
						onCheckedChange = vm::setUseSafeStores
					)
				}
			}
		}
	}
}

/**
 * 外观。
 *
 * 只剩主题与文本动画两项。原来的「竖屏列数 / 横屏列数」滑块已在上轮删除 ——
 * 它们只写进偏好、没有任何地方读取（应用页早已是单列列表），拖动完全不产生效果。
 */
@Composable
fun AppearanceSettingsPage(vm: SettingsViewModel, onBack: () -> Unit) {
	val s = vm.state().collectAsStateWithLifecycle().value

	SettingsSubPage(title = stringResource(R.string.settings_ui), onBack = onBack) { pad ->
		LazyColumn(modifier = Modifier, contentPadding = pad) {
			item {
				SettingsGroup {
					WindowDropdownPreference(
						title = stringResource(R.string.theme),
						items = listOf(
							stringResource(R.string.theme_system),
							stringResource(R.string.theme_dark),
							stringResource(R.string.theme_light),
							stringResource(R.string.theme_dynamic),
							stringResource(R.string.theme_dark_pure)
						),
						selectedIndex = s.theme.coerceIn(ThemePref.SYSTEM, ThemePref.DARK_PURE),
						onSelectedIndexChange = vm::setTheme
					)
					SwitchPreference(
						title = stringResource(R.string.play_text_animations),
						checked = s.playTextAnimations,
						onCheckedChange = vm::setPlayTextAnimations
					)
				}
			}
		}
	}
}

/**
 * 定时检查。
 *
 * 通知权限仍然只在开关打开的瞬间申请 —— 上游在冷启动就申请，
 * 会直接把用户甩到系统设置页，反而更容易被拒绝。
 */
@Composable
fun AlarmSettingsPage(vm: SettingsViewModel, onBack: () -> Unit) {
	val s = vm.state().collectAsStateWithLifecycle().value
	val notificationPermission = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestPermission()
	) {}

	SettingsSubPage(title = stringResource(R.string.settings_alarm), onBack = onBack) { pad ->
		LazyColumn(modifier = Modifier, contentPadding = pad) {
			item {
				SettingsGroup {
					SwitchPreference(
						title = stringResource(R.string.settings_alarm),
						checked = s.enableAlarm,
						onCheckedChange = { enabled ->
							if (enabled) {
								notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
							}
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
			}
		}
	}
}

/**
 * 关于。
 *
 * 原先这是主页面里一个**没有 onClick 的 ArrowPreference**：界面上画了一个表示
 * 「可进入」的箭头，点击却什么都不发生，用户看到的就是「关于打不开」。
 * 现在它是主页面最后一个入口，点开是本页。
 */
@Composable
fun AboutSettingsPage(onBack: () -> Unit) {
	SettingsSubPage(title = stringResource(R.string.about), onBack = onBack) { pad ->
		LazyColumn(modifier = Modifier, contentPadding = pad) {
			item { AboutContent() }
		}
	}
}