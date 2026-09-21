package com.onekey.updater.data.ui

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.onekey.updater.R
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Update

sealed class Screen(
	val route: String,
	@StringRes val resourceId: Int,
	val icon: ImageVector
) {
	data object Apps : Screen("apps", R.string.tab_apps, MiuixIcons.GridView)
	data object Search : Screen("search", R.string.tab_search, MiuixIcons.Search)
	data object Updates : Screen("updates", R.string.tab_updates, MiuixIcons.Update)
	data object Settings : Screen("settings", R.string.tab_settings, MiuixIcons.Settings)
}
