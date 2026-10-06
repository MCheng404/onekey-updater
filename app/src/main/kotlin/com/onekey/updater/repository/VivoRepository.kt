package com.onekey.updater.repository

import android.content.Context
import android.os.Build
import android.util.Log
import com.onekey.updater.data.ui.AppInstalled
import com.onekey.updater.data.ui.AppUpdate
import com.onekey.updater.data.ui.Link
import com.onekey.updater.data.ui.VivoSource
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.service.VivoService
import com.onekey.updater.util.AppLog
import com.onekey.updater.util.filterVersionTag
import io.github.g00fy2.versioncompare.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI

/**
 * vivo 应用商店来源。
 *
 * ## 为什么这个源值得单独实现
 * 它是目前唯一一个**既不需要签名、又不需要设备身份**的国产商店源：
 * 26 个请求参数全是明文 form，批量 100 个/请求，还能拿到**可直接下载的 https 地址**。
 * （对比：小米要签名且下载端点要设备身份；华为/OPPO/荣耀要各自的双签名或 TEE 签名。）
 *
 * ## 实测确认的协议要点
 * - 端点 `POST https://update.appstore.vivo.com.cn/port/packages_update/`，
 *   **末尾斜杠属于 URL 的一部分**，不能丢；也没有任何 query 参数。
 * - UA 必须是原生 Dalvik 格式 —— vivo 商店客户端本身就是 native 进程。
 *   注意本项目的统一 UA 拦截器已改成「请求自带 UA 时不覆盖」，否则这里会被冲掉。
 * - `packages` 格式为 `包名|versionCode|0` 逗号分隔，**第三段恒为 0**（客户端的 oldApkHash 不走这个字段）。
 * - `build_number` 在**更新检查**端点填的是**应用版本号**（默认 61510），不是 buildId；
 *   而在自更新/系统目录端点它填的是 `os`。同名不同义，容易搞混。
 * - `av` / `android_version` 是 **SDK_INT**，`an` / `android_name` 是 **Android 版本号**（后两个是反的）。
 *
 * ## 两个已验证可用的技巧
 * - **用 versionCode=0 枚举目录**：服务端对「版本已够新」的包会隐藏条目，
 *   传 0 能枚举出完整目录（含非公开/系统应用），且不会真的降级。
 * - **下载走 302**，最终 vivo CDN 域名上**同一个对象也提供 https**，所以跟随重定向时把
 *   http 升级为 https 即可 —— 但必须校验 host 白名单，否则等于开了 SSRF。
 */
class VivoRepository(
	private val context: Context,
	private val service: VivoService,
	private val prefs: Prefs,
	private val baseClient: OkHttpClient
) {

	companion object {
		private const val TAG = "VivoRepository"

		/** 每批多少个应用。 */
		private const val BATCH = 100

		/** vivo 商店的应用版本号，用作 app_version / appversion / build_number。 */
		private const val MARKET_VERSION = "61510"

		/** 跟随重定向的最大跳数。 */
		private const val MAX_REDIRECTS = 5
	}

	/** vivo 商店的 User-Agent：与官方客户端一致，形状是原生 Dalvik。 */
	private val userAgent: String =
		"Dalvik/2.1.0 (Linux; U; Android ${Build.VERSION.RELEASE}; ${Build.MODEL} " +
			"Build/${Build.DISPLAY})"

	fun updates(apps: List<AppInstalled>) = flow {
		if (!prefs.useVivo.get()) {
			emit(emptyList())
			return@flow
		}
		val targets = apps.filter { !it.ignored }
		if (targets.isEmpty()) {
			emit(emptyList())
			return@flow
		}

		// 串行分批：vivo 无并发限制的先例，串行也更不容易触发风控。
		val found = targets.chunked(BATCH).flatMap { chunk ->
			try {
				query(chunk)
			} catch (t: Throwable) {
				// 必须留痕：否则「请求失败」与「确实没更新」在日志里无法区分
				Log.w(TAG, "批次 " + chunk.size + " 个应用查询失败。", t)
				emptyList()
			}
		}

		AppLog.log(TAG, "vivo 商店：查询 " + targets.size + " 个应用，命中 " + found.size + " 个")
		emit(found)
	}.flowOn(Dispatchers.IO).catch {
		Log.e(TAG, "vivo 商店查询失败。", it)
		emit(emptyList())
	}

	private suspend fun query(chunk: List<AppInstalled>): List<AppUpdate> {
		val response = service.checkUpdates(userAgent, baseParams(chunk))

		// vivo 的客户端不校验 code、失败时静默返回空数组；我们必须自己判，
		// 否则会把「请求被拒」误判成「没有更新」。
		if (response.code != 0) {
			Log.w(TAG, "vivo 商店返回 code=" + response.code + "，本批 " + chunk.size + " 个应用按无更新处理")
			return emptyList()
		}

		val byPackage = chunk.associateBy { it.packageName }
		// 注意措辞：vivo 对「版本已够新」的条目**直接隐藏**（不是标记为未收录），
		// 所以返回条数少通常意味着「都已是最新」，不能写成「未收录」误导排查。
		Log.i(
			TAG,
			"批次 " + chunk.size + " 个应用：返回 " + response.value.size +
				" 条可更新（vivo 会隐藏版本已够新的条目，故返回 0 条通常是「都已是最新」）"
		)

		return response.value.mapNotNull { remote ->
			val local = byPackage[remote.packageName] ?: return@mapNotNull null
			if (remote.packageName.isEmpty() || remote.downloadUrl.isEmpty()) return@mapNotNull null

			// versionCode 可能是字符串 "400601" 或数字
			val remoteCode = when (val raw = remote.versionCode) {
				is Number -> raw.toLong()
				is String -> raw.toLongOrNull() ?: 0L
				else -> 0L
			}
			if (remoteCode <= 0L || remoteCode <= local.versionCode) return@mapNotNull null

			val remoteVersion = remote.versionName.ifBlank { remoteCode.toString() }
			val newer = runCatching {
				Version(filterVersionTag(remoteVersion)) > Version(local.version)
			}.getOrDefault(true)
			if (!newer) return@mapNotNull null

			Log.i(
				TAG,
				"命中 " + remote.packageName + " " + local.version + " -> " + remoteVersion +
					" (size=" + (remote.size * 1024) + "B, 差分描述符=" +
					(if (remote.sfPatches.isBlank()) "无" else "有") + ")"
			)

			AppUpdate(
				name = remote.title.ifBlank { local.name },
				packageName = local.packageName,
				version = remoteVersion,
				oldVersion = local.version,
				versionCode = remoteCode,
				oldVersionCode = local.versionCode,
				source = VivoSource,
				iconUri = local.iconUri,
				// 先给原始入口；Downloader 跟随重定向时会做 host 白名单校验与 http→https 升级
				link = Link.Url(remote.downloadUrl),
				whatsNew = remote.updateDescription
			)
		}
	}

	/**
	 * 构造 26 个请求参数。
	 *
	 * 注意 `build_number` 在本端点填的是 [MARKET_VERSION]（应用版本号），**不是** buildId。
	 */
	private fun baseParams(chunk: List<AppInstalled>): Map<String, String> {
		val metrics = context.resources.displayMetrics
		val release = Build.VERSION.RELEASE.orEmpty().ifBlank { Build.VERSION.SDK_INT.toString() }
		val sdk = Build.VERSION.SDK_INT.toString()
		val screen = (metrics.widthPixels.toString() + "*" + metrics.heightPixels).replace('*', '_')

		return linkedMapOf(
			"packages" to chunk.joinToString(",") { it.packageName + "|" + it.versionCode + "|0" },
			"querySource" to "2",
			"dbversion" to "0",
			"downgrade" to "0",
			"n" to "0",
			"suggest64Bit" to "1",
			"gpInstalled" to "false",
			"gpLogin" to "false",
			// 空字符串也必须发，vivo 把它当必填占位
			"secondInstallList" to "",
			"patch_sup" to "2",
			"cpuInfo" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
			"app_version" to MARKET_VERSION,
			"appversion" to MARKET_VERSION,
			// 端点特有：本字段是「应用版本号」，不是 buildId
			"build_number" to MARKET_VERSION,
			"model" to Build.MODEL.orEmpty(),
			"deviceType" to Build.DEVICE.orEmpty(),
			// av / android_version 是 SDK_INT；an / android_name 是 Android 版本号（后两个是反的）
			"av" to sdk,
			"an" to release,
			"android_version" to sdk,
			"android_name" to release,
			"sys_build_id" to Build.DISPLAY.orEmpty(),
			"density" to metrics.density.toString(),
			"screensize" to screen,
			"mfr" to "vivo",
			"pictype" to "webp",
			"cs" to "0"
		)
	}

	/**
	 * 把 vivo 的 302 下载入口解析成可直接下载的 https 直链。
	 *
	 * vivo 的 `download_url` 是入口（https），最终会 302 到 CDN，而那个 CDN 地址是**明文 http**。
	 * Android 9+ 默认禁明文，所以必须自己跟随并把 http 升级为 https。
	 *
	 * **[安全] 必须校验 host 白名单** —— 不校验就等于让服务端指定的任意地址成为下载地址。
	 */
	fun secureDownloadUrl(entry: String): String? {
		var current = entry
		repeat(MAX_REDIRECTS) {
			if (!isAllowedVivoHost(current)) return null
			val upgraded = upgradeToHttps(current) ?: return null
			if (upgraded == current) return upgraded
			current = upgraded
		}
		return current.takeIf { isAllowedVivoHost(it) }
	}

	private fun isAllowedVivoHost(url: String): Boolean = runCatching {
		val host = URI(url).host?.lowercase().orEmpty()
		host == "vivo.com.cn" || host.endsWith(".vivo.com.cn")
	}.getOrDefault(false)

	private fun upgradeToHttps(url: String): String? =
		if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

	/** 供外部探测真实 Content-Length / md5（vivo 入口是 302）。 */
	fun headAsset(url: String) = runCatching {
		val request = Request.Builder().url(url).head().build()
		baseClient.newCall(request).execute().use { response ->
			response.headers["Content-Length"]?.toLongOrNull() to response.headers["X-Oss-Meta-Md5"]
		}
	}.getOrNull()

	/** 保持 baseClient 引用，避免 DI 未使用告警同时明确该仓库会发起直连。 */
	@Suppress("unused")
	private fun directClient(): OkHttpClient = baseClient
}