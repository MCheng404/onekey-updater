package com.onekey.updater.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.onekey.updater.data.ui.AppInstalled
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.transform.iconUri
import com.onekey.updater.transform.toAppInstalled
import com.onekey.updater.util.RootAppList
import com.onekey.updater.util.getSignatureSha1
import com.onekey.updater.util.getSignatureSha256
import com.onekey.updater.util.orFalse
import com.onekey.updater.util.AppLog
import com.onekey.updater.util.clearSystemAppCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow


class AppsRepository(
	private val context: Context,
	private val prefs: Prefs
) {

	companion object {
		private const val TAG = "AppsRepository"

		/**
		 * 原始应用清单的缓存时长。
		 *
		 * 为什么要缓存：`getInstalledPackages(MATCH_ALL + GET_SIGNING_CERTIFICATES)` 是本项目
		 * 最贵的一次调用（182 个应用的签名要跨进程取回），而它会被读两遍——
		 * 「应用」页一次、「更新」页的扫描又一次，两者过滤条件还不同。
		 * 冷启动时这直接体现为「应用出现得很慢」。
		 * 缓存原始清单后，过滤在本地做，第二次调用零成本。
		 */
		private const val CACHE_TTL_MS = 60_000L
	}

	private val cacheMutex = Mutex()

	@Volatile
	private var cachedPackages: List<PackageInfo>? = null

	@Volatile
	private var cachedPackagesAt = 0L

	/** 安装来源记忆：`getInstallerPackageName` 每个应用一次 IPC，而它在一次扫描里会被问好几遍。 */
	private val installerCache = ConcurrentHashMap<String, String>()

	/**
	 * 取原始应用清单（带签名），带 TTL 缓存与单飞（并发调用只会真正扫一次）。
	 *
	 * 返回值是**未过滤**的完整清单，过滤由各调用点按自己的语义在本地完成。
	 */
	private suspend fun installedPackages(): List<PackageInfo> = cacheMutex.withLock {
		val cached = cachedPackages
		if (cached != null &&
			SystemClock.elapsedRealtime() - cachedPackagesAt < CACHE_TTL_MS
		) {
			return@withLock cached
		}
		val fresh = context.packageManager
			.getInstalledPackages(PackageManager.MATCH_ALL + getSignatureFlag())
		cachedPackages = fresh
		cachedPackagesAt = SystemClock.elapsedRealtime()
		AppLog.log(TAG, "应用清单已刷新（" + fresh.size + " 个包）")
		fresh
	}

	/** 安装来源（带记忆）。返回空串表示未知。 */
	private fun installerOf(packageName: String): String =
		installerCache.getOrPut(packageName) {
			@Suppress("DEPRECATION")
			runCatching { context.packageManager.getInstallerPackageName(packageName) }.getOrNull().orEmpty()
		}

	/**
	 * 丢弃缓存。安装/卸载/更新应用后必须调用，否则会读到过期清单（TTL 内）。
	 */
	fun invalidate() {
		cachedPackages = null
		cachedPackagesAt = 0L
		installerCache.clear()
		clearSystemAppCache()
	}

	/**
	 * 读取已安装应用。
	 *
	 * @param includeStoreApps 是否包含「由应用商店安装」的应用。
	 * @param includeSystemApps 是否包含系统应用（含「出厂预装且已被更新」的应用）。
	 *
	 * 之所以要区分：排除商店/系统/已禁用这三个开关在界面上是**「应用」页的展示过滤**
	 * （顶栏三个图标，文案就是「排除…」），却被上游直接拿来决定「为哪些应用检查更新」。
	 *
	 * 后果（实测）：本机 Play 安装的 Nagram / Edge Canary / TikTok 全被排除在更新扫描之外，
	 * 于是「更新」页永远是空的 —— 用户遇到的就是这个：所有更新源都打开了，却没有任何应用。
	 * 而这三个应用恰恰是最可能有更新的那批。
	 *
	 * 因此更新扫描一律传 true，只保留真正有语义的过滤：ignored（用户逐条忽略）、
	 * 系统应用（无法侧载更新）、已禁用应用。
	 */
	suspend fun getApps(
		includeStoreApps: Boolean = false,
		includeSystemApps: Boolean = false
	) = flow {
		// 优先尝试 root + 内置 dex 一次性枚举（label/系统标记更准、枚举更独立稳）；
		// 任意失败（无 root / 无 dex / 解析失败）一律回退到下方 PackageManager 路径。
		val apps = tryRootApps(includeStoreApps, includeSystemApps)
			?: getAppsViaPackageManager(includeStoreApps, includeSystemApps)
		emit(Result.success(apps))
	}.catch {
		Log.e("AppsRepository", "Error getting apps.", it)
		emit(Result.failure(it))
	}

	/**
	 * root + dex 路径：用 [RootAppList] 拿到「包名 + 显示名 + 系统标记」的清单，
	 * 再一次性向 PackageManager 取签名/图标（与旧路径成本相同，但枚举交给 dex，避免
	 * 让普通 PM 枚举承担系统/第三方判定的开销）。签名/图标按包名合并回去。
	 *
	 * 返回 null 表示此路径不可用，调用方应回退 PackageManager。本函数内部已吞掉所有异常。
	 */
	private suspend fun tryRootApps(
		includeStoreApps: Boolean,
		includeSystemApps: Boolean
	): List<AppInstalled>? {
		val entries = RootAppList.fetchApps(context) ?: return null
		return runCatching {
			// 一次性取签名/图标（含 MATCH_ALL + 签名 flag），成本与旧路径一致。
			val piMap = context.packageManager
				.getInstalledPackages(PackageManager.MATCH_ALL + getSignatureFlag())
				.associateBy { it.packageName }
			entries.asSequence()
				// includeSystemApps 为 true 时绕过「排除系统应用」；否则剔除系统应用。
				// 用 dex 的 system 标记（= FLAG_SYSTEM 或 FLAG_UPDATED_SYSTEM_APP），
				// 与 AppInfoCache.isSystemApp 语义一致。
				.filter {
					includeSystemApps || !excludeSystem() || !it.system
				}
				// 已停用应用无法安装更新，默认排除（excludeDisabled 为真时）。
				.filter { !excludeDisabled() || it.enabled }
				// 排除应用商店安装的（默认排除，除非 includeStoreApps 或关闭该开关）。
				.filter {
					includeStoreApps || !excludeStore() ||
						!isAppStore(installerOf(it.packageName))
				}
				.map { entry ->
					val pi = piMap[entry.packageName]
					AppInstalled(
						name = entry.label.ifBlank { entry.packageName },
						packageName = entry.packageName,
						version = entry.versionName.ifBlank { pi?.versionName.orEmpty() },
						versionCode = if (entry.versionCode != 0L) entry.versionCode
						else (if (Build.VERSION.SDK_INT >= 28) pi?.longVersionCode ?: 0L
						else pi?.versionCode?.toLong() ?: 0L),
						iconUri = iconUri(entry.packageName, pi?.applicationInfo?.icon ?: 0),
						ignored = ignoredApps().contains(entry.packageName),
						signature = pi?.getSignatureSha1().orEmpty(),
						signatureSha256 = pi?.getSignatureSha256().orEmpty()
					)
				}
				.sortedBy { it.name }
				.sortedBy { it.ignored }
				.toList()
		}.getOrNull()
	}

	/**
	 * 原有 PackageManager 路径（root/dex 不可用时的回退，逻辑保持原样）。
	 */
	private suspend fun getAppsViaPackageManager(
		includeStoreApps: Boolean,
		includeSystemApps: Boolean
	): List<AppInstalled> {
		return installedPackages()
			.asSequence()
			// includeSystemApps 为 true 时绕过「排除系统应用」，
			// 但仍受 excludeDisabled（已停用应用无法安装更新）约束。
			.filter {
				includeSystemApps ||
					!excludeSystem() ||
					(it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM == 0
			}
			.filter {
				includeSystemApps ||
					!excludeSystem() ||
					(it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
			}
			.filter { !excludeDisabled() || it.applicationInfo?.enabled != false }
			.filter {
				includeStoreApps ||
					!excludeStore() ||
					!isAppStore(installerOf(it.packageName))
			}
			.map { it.toAppInstalled(context, ignoredApps()) }
			.sortedBy { it.name }
			.sortedBy { it.ignored }
			.toList()
	}

	private fun excludeSystem() = prefs.excludeSystem.get()
	private fun excludeDisabled() = prefs.excludeDisabled.get()
	private fun excludeStore() = prefs.excludeStore.get()
	private fun ignoredApps() = prefs.ignoredApps.get()

	@Suppress("DEPRECATION")
	private fun getSignatureFlag(): Int {
		return if (Build.VERSION.SDK_INT >= 28) {
			PackageManager.GET_SIGNING_CERTIFICATES
		} else {
			PackageManager.GET_SIGNATURES
		}
	}

	@Suppress("DEPRECATION")
	private fun getInstallerPackageName(packageName: String): String {
		return if (Build.VERSION.SDK_INT < 30) {
			context.packageManager.getInstallerPackageName(packageName).orEmpty()
		} else {
			context.packageManager.getInstallSourceInfo(packageName).installingPackageName.orEmpty()
		}
	}

	// Checks if Play Store or Amazon Store
	private fun isAppStore(name: String?) = name?.contains("com.android.vending").orFalse()
		|| name?.contains("com.amazon").orFalse()

}
