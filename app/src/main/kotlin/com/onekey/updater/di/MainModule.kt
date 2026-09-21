package com.onekey.updater.di

import androidx.work.WorkManager
import com.onekey.updater.BuildConfig
import com.onekey.updater.R
import com.onekey.updater.data.ui.FdroidSource
import com.onekey.updater.data.ui.IzzySource
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.repository.ApkMirrorRepository
import com.onekey.updater.repository.ApkPureRepository
import com.onekey.updater.repository.AppsRepository
import com.onekey.updater.repository.AptoideRepository
import com.onekey.updater.repository.FdroidRepository
import com.onekey.updater.repository.GitHubRepository
import com.onekey.updater.repository.GitLabRepository
import com.onekey.updater.repository.PlayRepository
import com.onekey.updater.repository.SearchRepository
import com.onekey.updater.repository.TencentRepository
import com.onekey.updater.repository.UpdatesRepository
import com.onekey.updater.service.ApkMirrorService
import com.onekey.updater.service.ApkPureService
import com.onekey.updater.service.AptoideService
import com.onekey.updater.service.FdroidService
import com.onekey.updater.service.GitHubService
import com.onekey.updater.service.TencentService
import com.onekey.updater.service.GitLabService
import com.onekey.updater.util.Badger
import com.onekey.updater.util.Clipboard
import com.onekey.updater.util.Downloader
import com.onekey.updater.util.InstallLog
import com.onekey.updater.util.SessionInstaller
import com.onekey.updater.util.mcp.McpBridge
import com.onekey.updater.util.mcp.McpBridgeImpl
import com.onekey.updater.util.mcp.McpServer
import com.onekey.updater.util.SnackBar
import com.onekey.updater.util.Stringer
import com.onekey.updater.util.Themer
import com.onekey.updater.util.UpdatesNotification
import com.onekey.updater.util.addUserAgentInterceptor
import com.onekey.updater.util.isAndroidTv
import com.onekey.updater.util.net.MirrorInterceptor
import com.onekey.updater.util.net.ProxyConfig
import com.onekey.updater.util.net.MirrorResolver
import com.onekey.updater.util.net.NetworkDiagnostics
import com.onekey.updater.util.play.PlayHttpClient
import com.onekey.updater.viewmodel.AppsViewModel
import com.onekey.updater.viewmodel.MainViewModel
import com.onekey.updater.viewmodel.SearchViewModel
import com.onekey.updater.viewmodel.SettingsViewModel
import com.onekey.updater.viewmodel.UpdatesViewModel
import com.google.gson.GsonBuilder
import com.kryptoprefs.preferences.KryptoBuilder
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit


val mainModule = module {

	single { GsonBuilder().create() }

	// 1 GB 缓存对这类应用过大，且会造成存储告警；降到 128 MB
	single { Cache(androidContext().cacheDir, 128L * 1024 * 1024) }

	single {
		HttpLoggingInterceptor().apply {
			// 上游固定用 BODY 级别：会把每个请求/响应体写进 logcat，
			// 既有隐私问题（APK 二进制流）也显著拖慢扫描。仅 debug 打 BASIC。
			level = if (BuildConfig.DEBUG) {
				HttpLoggingInterceptor.Level.BASIC
			} else {
				HttpLoggingInterceptor.Level.NONE
			}
		}
	}

	// 镜像解析器：把官方地址改写为当前配置的国内线路
	single { MirrorResolver(get()) }

	/**
	 * 应用主 HTTP 客户端。
	 * 注意 MirrorInterceptor 的位置——它把所有官方域名透明改写为镜像/加速地址，
	 * 因此 Retrofit 接口、Downloader、Coil 三条链路自动统一生效。
	 */
	single {
		OkHttpClient.Builder()
			.cache(get())
			.addInterceptor(MirrorInterceptor(get()))
			.addUserAgentInterceptor("APKUpdater-v" + BuildConfig.VERSION_NAME)
			.connectTimeout(20, TimeUnit.SECONDS)
			.readTimeout(60, TimeUnit.SECONDS)
			// 代理在 OkHttp 层设置：Retrofit / Downloader / Coil 三条链路自动全部生效
			.apply { ProxyConfig.from(get()).let { if (it != null) proxy(it) } }
			.build()
	}

	/** 裸客户端：网络诊断专用，绝不能被镜像截器改写，否则测的不是真实线路。 */
	single(named("plain")) {
		OkHttpClient.Builder()
			.addUserAgentInterceptor("APKUpdater-v" + BuildConfig.VERSION_NAME)
			.connectTimeout(8, TimeUnit.SECONDS)
			// 诊断也要走代理：否则测出来的是「直连时哪条线路快」，与用户实际使用不符
			.apply { ProxyConfig.from(get()).let { if (it != null) proxy(it) } }
			.build()
	}

	single { NetworkDiagnostics(get(named("plain"))) }

	single {
		Retrofit.Builder()
			.client(get())
			.baseUrl("https://www.apkmirror.com")
			.addConverterFactory(GsonConverterFactory.create(get()))
			.build()
			.create(ApkMirrorService::class.java)
	}

	single {
		Retrofit.Builder()
			.client(get())
			.baseUrl("https://api.github.com")
			.addConverterFactory(GsonConverterFactory.create(get()))
			.build()
			.create(GitHubService::class.java)
	}

	single {
		Retrofit.Builder()
			.client(get())
			.baseUrl("https://upage.html5.qq.com")
			.addConverterFactory(GsonConverterFactory.create(get()))
			.build()
			.create(TencentService::class.java)
	}

	single { TencentRepository(get(), get()) }

	single {
		Retrofit.Builder()
			.client(get())
			.baseUrl("https://gitlab.com")
			.addConverterFactory(GsonConverterFactory.create(get()))
			.build()
			.create(GitLabService::class.java)
	}

	// baseUrl 保持不变：实际仓库地址由 MirrorInterceptor 按用户选择改写
	single {
		Retrofit.Builder()
			.client(get())
			.baseUrl(MirrorResolver.OFFICIAL_FDROID)
			.addConverterFactory(GsonConverterFactory.create(get()))
			.build()
			.create(FdroidService::class.java)
	}

	single {
		val client = OkHttpClient.Builder()
			.cache(get())
			.addInterceptor(MirrorInterceptor(get()))
			.addUserAgentInterceptor(AptoideRepository.UserAgent)
			.build()

		Retrofit.Builder()
			.client(client)
			.baseUrl("https://ws75.aptoide.com/api/7/")
			.addConverterFactory(GsonConverterFactory.create(get()))
			.build()
			.create(AptoideService::class.java)
	}

	single {
		Retrofit.Builder()
			.client(get())
			.baseUrl("https://tapi.pureapk.com/")
			.addConverterFactory(GsonConverterFactory.create(get()))
			.build()
			.create(ApkPureService::class.java)
	}

	single {
		val client = OkHttpClient.Builder()
			.followRedirects(true)
			.cache(get())
			.addInterceptor(MirrorInterceptor(get()))
			.build()
		val auroraClient = OkHttpClient.Builder()
			.followRedirects(true)
			.cache(get())
			.addUserAgentInterceptor(
				"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
			)
			.build()
		val apkPureClient = OkHttpClient.Builder()
			.followRedirects(true)
			.cache(get())
			.addUserAgentInterceptor("APKPure/3.19.39 (Aegon)")
			.build()
		val dir = File(androidContext().cacheDir, "downloads").apply { mkdirs() }
		Downloader(client, apkPureClient, auroraClient, dir)
	}

	single { ApkMirrorRepository(get(), get(), androidContext().packageManager) }

	single { AppsRepository(androidContext(), get()) }

	single { GitHubRepository(get(), get()) }

	single { GitLabRepository(get(), get()) }

	single { ApkPureRepository(get(), get(), get()) }

	single { AptoideRepository(androidContext(), get(), get()) }

	single { PlayRepository(get(), get(), get(), get()) }

	// F-Droid 索引（约 14 MB）落到磁盘缓存，避免每次刷新都重新下载
	single(named("fdroidIndexDir")) { File(androidContext().cacheDir, "fdroid").apply { mkdirs() } }

	single(named("main")) {
		FdroidRepository(get(), MirrorResolver.OFFICIAL_FDROID, FdroidSource, get(), get(named("fdroidIndexDir")))
	}

	single(named("izzy")) {
		FdroidRepository(get(), MirrorResolver.OFFICIAL_IZZY, IzzySource, get(), get(named("fdroidIndexDir")))
	}

	single {
		UpdatesRepository(
			get(), get(), get(), get(named("main")), get(named("izzy")),
			get(), get(), get(), get(), get(), get()
		)
	}

	single { SearchRepository(get(), get(named("main")), get(named("izzy")), get(), get(), get(), get(), get(), get()) }

	single { KryptoBuilder.nocrypt(get(), androidContext().getString(R.string.app_name)) }

	single { Prefs(get(), androidContext().isAndroidTv()) }

	single { UpdatesNotification(androidContext()) }

	single { Clipboard(androidContext()) }

	single { SessionInstaller(androidContext(), get(), get()) }

	single { SnackBar() }

	single { Badger() }

	single { Themer(get()) }

	single { Stringer(androidContext()) }

	single { InstallLog() }

	// ---------- 内嵌 MCP 服务 ----------
	// 对外只暴露 McpBridge 接口：服务端（协议层）不直接依赖仓库与安装器，
	// 这样协议代码可以喂假实现单独测试。
	single<McpBridge> { McpBridgeImpl(androidContext(), get(), get(), get(), get()) }

	single { McpServer(androidContext(), get(), get(), get()) }

	single { PlayHttpClient(get()) }

	viewModel { MainViewModel(get(), get(), get()) }

	viewModel { AppsViewModel(get(), get(), get()) }

	viewModel { UpdatesViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }

	viewModel { SearchViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }

	viewModel { SettingsViewModel(get(), get(), get(), WorkManager.getInstance(androidContext()), get(), get(), get(), get(), get(), get()) }

}
