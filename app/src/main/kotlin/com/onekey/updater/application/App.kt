package com.onekey.updater.application

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import android.util.Log
import com.onekey.updater.di.mainModule
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.util.mcp.McpServer
import com.onekey.updater.util.Migrations
import com.topjohnwu.superuser.Shell
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin

class App : Application(), SingletonImageLoader.Factory, KoinComponent {

	override fun onCreate() {
		super.onCreate()

		// 超时单位是秒。上游设为 10 秒，而 `pm install` 安装大 APK 很容易超过 10 秒，
		// 会被 libsu 直接掐断并返回失败。这里改为 0（不设超时），由上层协程控制等待时长。
		Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(0))

		startKoin {
			androidLogger()
			androidContext(this@App)
			modules(mainModule)
		}

		// 一次性配置迁移：让新增的国内镜像默认值对老用户也生效
		runCatching { Migrations.run(get()) }
			.onFailure { Log.e("App", "配置迁移失败。", it) }

		// 装/卸/更新应用后让应用清单缓存失效。
		// 用广播而不是在每个安装调用点手写 invalidate()：安装路径有多条
		// （Root 静默 / 会话安装 / MCP 远程安装），漏掉任何一条都会读到过期清单。
		registerPackageWatcher()

		// 内嵌 MCP 服务：仅在用户开启时启动。
		// 端口/绑定地址/令牌都由服务自己读偏好决定，这里不传参——
		// 否则会出现「设置页改了端口、服务还在用旧端口」的不一致。
		runCatching {
			if (get<Prefs>().mcpEnabled.get()) get<McpServer>().start()
		}.onFailure { Log.e("App", "MCP 服务启动失败。", it) }
	}

	/**
	 * 监听应用的安装/卸载/更新，失效应用清单缓存。
	 *
	 * 用广播而不是在每个安装调用点手写 invalidate()：安装路径有多条
	 * （Root 静默安装 / 系统会话安装 / MCP 远程安装），漏掉任何一条都会读到过期清单。
	 */
	private fun registerPackageWatcher() {
		val filter = android.content.IntentFilter().apply {
			addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
			addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
			addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
			addAction(android.content.Intent.ACTION_PACKAGE_CHANGED)
			addDataScheme("package")
		}
		runCatching {
			androidx.core.content.ContextCompat.registerReceiver(
				this,
				object : android.content.BroadcastReceiver() {
					override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
						runCatching { get<com.onekey.updater.repository.AppsRepository>().invalidate() }
							.onFailure { Log.w("App", "应用清单缓存失效失败", it) }
					}
				},
				filter,
				androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
			)
		}.onFailure { Log.w("App", "注册包变化监听失败（缓存仍会按 TTL 过期）", it) }
	}

	override fun newImageLoader(context: PlatformContext) = ImageLoader
		.Builder(this)
		.components { add(OkHttpNetworkFetcherFactory(callFactory = { get<OkHttpClient>() })) }
		//.logger(DebugLogger())
		.build()

}