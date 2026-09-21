package com.onekey.updater.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.onekey.updater.data.ui.Screen
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.util.InstallLog
import com.onekey.updater.util.UpdatesNotification
import kotlinx.coroutines.launch


class MainViewModel(
	private val prefs: Prefs,
	private val installLog: InstallLog,
	private val updatesNotification: UpdatesNotification
) : ViewModel() {

	val screens = listOf(Screen.Apps, Screen.Search, Screen.Updates, Screen.Settings)

	/**
	 * 触发一次全量刷新。
	 *
	 * 刷新动画不再由这里集中管理——它属于各自列表页（AppsViewModel / UpdatesViewModel），
	 * 这样「更新页先出结果就先收起动画」不必等应用列表也跑完，
	 * 也避免了上游那种「Flow 不结束则下拉动画永远转」的问题。
	 */
	fun refresh(appsViewModel: AppsViewModel, updatesViewModel: UpdatesViewModel) = viewModelScope.launch {
		appsViewModel.refresh(false)
		updatesViewModel.refresh(false)
	}

	/**
	 * 处理来自通知的 Intent。
	 *
	 * 上游这里还负责解析 PackageInstaller 的安装结果广播并据此解锁安装锁——
	 * 那条链路（Activity + ActivityResultLauncher）在用户取消时会把锁永久占住，
	 * 现在安装结果完全由 SessionInstaller 内部通过 SessionCallback 收敛，已整体删除。
	 */
	fun processIntent(
		intent: Intent?,
		updatesViewModel: UpdatesViewModel,
		navController: NavController
	) {
		if (intent?.action == UpdatesNotification.UpdateAction) {
			navigateTo(navController, Screen.Updates.route)
			updatesViewModel.refresh()
		}
	}

	fun navigateTo(navController: NavController, route: String) = navController.navigate(route) {
		popUpTo(navController.graph.findStartDestination().id) { saveState = true }
		launchSingleTop = true
		restoreState = true
		prefs.lastTab.put(route)
	}

	fun getLastRoute() = prefs.lastTab.get()

	fun cancelCurrentInstall() = installLog.cancelCurrentInstall()

	/** 供设置页在开启定时检查时调用。 */
	fun notificationHelper() = updatesNotification
}
