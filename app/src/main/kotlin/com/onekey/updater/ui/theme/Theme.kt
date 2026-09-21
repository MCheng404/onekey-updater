package com.onekey.updater.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 主题种子色（HyperOS 品牌蓝）。
 * Miuix 会以它为种子生成整套 token 化的浅色/深色配色。
 */
val KeyColor = Color(0xFF3482FF)

/** 主题偏好：0 = 跟随系统，1 = 深色，2 = 浅色。 */
object ThemePref {
	const val SYSTEM = 0
	const val DARK = 1
	const val LIGHT = 2
}

@Composable
fun isDarkTheme(theme: Int): Boolean = when (theme) {
	ThemePref.DARK -> true
	ThemePref.LIGHT -> false
	else -> isSystemInDarkTheme()
}

private fun colorSchemeModeOf(theme: Int): ColorSchemeMode = when (theme) {
	ThemePref.DARK -> ColorSchemeMode.MonetDark
	ThemePref.LIGHT -> ColorSchemeMode.MonetLight
	else -> ColorSchemeMode.MonetSystem
}

@Composable
fun AppTheme(
	theme: Int,
	content: @Composable () -> Unit
) {
	val mode = colorSchemeModeOf(theme)
	// ThemeController 的 colorSchemeMode 对外是只读属性，切换主题只能重建实例，
	// 因此用 mode 作为 remember 的 key。
	val controller = remember(mode) {
		ThemeController(
			colorSchemeMode = mode,
			keyColor = KeyColor,
			paletteStyle = ThemePaletteStyle.TonalSpot
		)
	}

	// 状态栏 / 导航栏图标明暗跟随主题
	val view = LocalView.current
	val dark = isDarkTheme(theme)
	if (!view.isInEditMode) {
		SideEffect {
			val activity = view.context as? Activity ?: return@SideEffect
			val controllerInsets = WindowCompat.getInsetsController(activity.window, view)
			controllerInsets.isAppearanceLightStatusBars = !dark
			controllerInsets.isAppearanceLightNavigationBars = !dark
		}
	}

	MiuixTheme(controller = controller, content = content)
}
