package com.onekey.updater.ui.screen

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.onekey.updater.data.ui.Screen
import com.onekey.updater.ui.theme.AppTheme
import com.onekey.updater.util.Badger
import com.onekey.updater.util.SnackBar
import com.onekey.updater.util.Themer
import com.onekey.updater.viewmodel.AppsViewModel
import com.onekey.updater.viewmodel.MainViewModel
import com.onekey.updater.viewmodel.SearchViewModel
import com.onekey.updater.viewmodel.SettingsViewModel
import com.onekey.updater.viewmodel.UpdatesViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text


@Composable
fun MainScreen(mainViewModel: MainViewModel = koinViewModel()) {
	val appsViewModel: AppsViewModel = koinViewModel()
	val updatesViewModel: UpdatesViewModel = koinViewModel()
	val searchViewModel: SearchViewModel = koinViewModel()
	val settingsViewModel: SettingsViewModel = koinViewModel()

	val navController = rememberNavController()

	// 主题（Int：0 跟随系统 / 1 深色 / 2 浅色）
	val theme = koinInject<Themer>().flow().collectAsStateWithLifecycle().value

	// 首帧触发一次刷新
	LaunchedEffect(Unit) {
		mainViewModel.refresh(appsViewModel, updatesViewModel)
	}

	// 提示消息
	val snackBarHostState = remember { SnackbarHostState() }
	val snackBar = koinInject<SnackBar>()
	LaunchedEffect(snackBar) {
		snackBar.flow().collect { snackBarHostState.showSnackbar(it) }
	}

	// 通知点击进入（冷启动 + 热启动）
	val activity = LocalActivity.current as? ComponentActivity
	LaunchedEffect(activity) {
		mainViewModel.processIntent(activity?.intent, updatesViewModel, navController)
	}
	IntentListener(mainViewModel, updatesViewModel, navController)

	AppTheme(theme) {
		Scaffold(
			snackbarHost = { SnackbarHost(snackBarHostState) },
			bottomBar = { BottomBar(mainViewModel, navController) }
		) { padding ->
			NavHost(
				navController = navController,
				// consumeWindowInsets 是这里的关键：
				// Miuix 的 Scaffold 在没有 topBar 时会把「状态栏内边距」加到 padding 上；
				// 而 Miuix 的 SmallTopAppBar **无条件**再加一次顶部系统栏内边距
				// （defaultWindowInsetsPadding 只管水平方向），于是顶部被顶两次状态栏高度。
				// 1440×3200 / 600dpi 下实测：顶栏容器从 y=338 开始，而状态栏只有 169，
				// 多出的 169px 就是重复计算。消费掉 padding 后，顶栏内部的 windowInsetsPadding
				// 会解析为 0，顶部只保留一次内边距。
				modifier = Modifier.padding(padding).consumeWindowInsets(padding),
				mainViewModel = mainViewModel,
				appsViewModel = appsViewModel,
				updatesViewModel = updatesViewModel,
				searchViewModel = searchViewModel,
				settingsViewModel = settingsViewModel
			)
		}
	}
}

@Composable
fun IntentListener(
	mainViewModel: MainViewModel,
	updatesViewModel: UpdatesViewModel,
	navController: NavController
) {
	val activity = LocalActivity.current as? ComponentActivity ?: return
	DisposableEffect(activity) {
		val listener = Consumer<Intent> {
			mainViewModel.processIntent(it, updatesViewModel, navController)
		}
		activity.addOnNewIntentListener(listener)
		onDispose { activity.removeOnNewIntentListener(listener) }
	}
}

@Composable
fun BottomBar(mainViewModel: MainViewModel, navController: NavController) {
	val badges = koinInject<Badger>().flow().collectAsStateWithLifecycle().value
	val backStackEntry = navController.currentBackStackEntryAsState().value

	NavigationBar {
		mainViewModel.screens.forEach { screen ->
			val selected = backStackEntry?.destination?.route == screen.route
			val badge = badges[screen.route].orEmpty()
			NavigationBarItem(
				selected = selected,
				onClick = { mainViewModel.navigateTo(navController, screen.route) },
				icon = screen.icon,
				label = stringResource(screen.resourceId),
				badge = if (badge.isEmpty()) null else { { Badge { Text(badge) } } }
			)
		}
	}
}

@Composable
fun NavHost(
	navController: NavHostController,
	modifier: Modifier,
	mainViewModel: MainViewModel,
	appsViewModel: AppsViewModel,
	updatesViewModel: UpdatesViewModel,
	searchViewModel: SearchViewModel,
	settingsViewModel: SettingsViewModel
) = NavHost(
	navController = navController,
	startDestination = mainViewModel.getLastRoute(),
	modifier = modifier
) {
	composable(Screen.Apps.route) { AppsScreen(appsViewModel) }
	composable(Screen.Search.route) { SearchScreen(searchViewModel) }
	composable(Screen.Updates.route) { UpdatesScreen(updatesViewModel) }
	composable(Screen.Settings.route) { SettingsScreen(settingsViewModel) }
}
