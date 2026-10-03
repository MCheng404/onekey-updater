package com.onekey.updater.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

	/** Material You：跟随系统动态取色（Android 12+ 取壁纸主色，低版本回落到品牌蓝）。 */
	const val DYNAMIC = 3

	/** 纯黑（AMOLED）：深色配色 + 纯黑底， OLED 屏上省电且无灰边。 */
	const val DARK_PURE = 4
}

/** 纯黑背景色。 */
val PureBlack = Color(0xFF000000)

/**
 * 系统动态色（Material You）。
 *
 * 读 `android.R.color.system_accent1_500` —— 这是系统按**壁纸**算出的强调色，
 * Android 12+ 才有。低于 12 时该资源不存在，直接回落到品牌蓝。
 */
@Composable
fun systemKeyColor(): Color {
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return KeyColor
	val context = LocalContext.current
	return remember(context) {
		runCatching { Color(context.resources.getColor(android.R.color.system_accent1_500, context.theme)) }
			.getOrDefault(KeyColor)
	}
}

@Composable
fun isDarkTheme(theme: Int): Boolean = when (theme) {
	ThemePref.DARK, ThemePref.DARK_PURE -> true
	ThemePref.LIGHT -> false
	else -> isSystemInDarkTheme()
}

private fun colorSchemeModeOf(theme: Int): ColorSchemeMode = when (theme) {
	ThemePref.DARK, ThemePref.DARK_PURE -> ColorSchemeMode.MonetDark
	ThemePref.LIGHT -> ColorSchemeMode.MonetLight
	// 动态取色需要跟随系统明暗
	ThemePref.DYNAMIC -> ColorSchemeMode.MonetSystem
	else -> ColorSchemeMode.MonetSystem
}

/** 该主题是否使用纯黑底。 */
fun isPureBlackTheme(theme: Int) = theme == ThemePref.DARK_PURE

@Composable
fun AppTheme(
	theme: Int,
	content: @Composable () -> Unit
) {
	val mode = colorSchemeModeOf(theme)
	val pureBlack = isPureBlackTheme(theme)
	// 动态取色模式下把种子色换成系统壁纸主色；其余保持品牌蓝。
	// seedColor 放进 remember 的 key，改主题或系统取色变化都会重建 controller。
	val seed = systemKeyColor().takeIf { theme == ThemePref.DYNAMIC } ?: KeyColor

	// ThemeController 的 colorSchemeMode 对外是只读属性，切换主题只能重建实例，
	// 因此把影响它的量都放进 key。
	val controller = remember(mode, seed) {
		ThemeController(
			colorSchemeMode = mode,
			keyColor = seed,
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

	if (pureBlack) {
		// Miuix 的深色 token 里 surface 并不是纯黑（有一层浅灰），
		// 所以纯黑主题要把**窗口底色**和**内容底色**都压成 #000000，
		// 否则卡片周围仍会露出一圈灰。
		val view2 = LocalView.current
		SideEffect {
			val activity = view2.context as? Activity ?: return@SideEffect
			activity.window.setBackgroundDrawableResource(android.R.color.black)
		}
		MiuixTheme(controller = controller) {
			Box(Modifier.fillMaxSize().background(PureBlack)) { content() }
		}
	} else {
		MiuixTheme(controller = controller, content = content)
	}
}
