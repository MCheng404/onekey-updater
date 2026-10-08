package com.onekey.updater.repository

import android.content.Context
import android.os.Build
import android.util.Log
import com.onekey.updater.data.ui.AppInstalled
import com.onekey.updater.data.ui.AppUpdate
import com.onekey.updater.data.ui.Link
import com.onekey.updater.data.ui.XiaomiSource
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.service.XiaomiService
import com.onekey.updater.util.AppLog
import com.onekey.updater.util.net.XiaomiIdentity
import com.onekey.updater.util.net.XiaomiSigner
import com.onekey.updater.util.filterVersionTag
import io.github.g00fy2.versioncompare.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.UUID

/**
 * 小米应用商店来源。
 *
 * ## 当前只能「查版本」，不能「下载安装」
 * 取下载地址要走另一个端点 `/apm/download/{appId}`，而它需要**设备身份**
 * （`oaId` 真实值 / `dctx` / `tzNonce`+`tzSign`），这些来自绑定
 * `com.xiaomi.xmsf`（小米服务框架）走身份协议；拿不到时服务端返回
 * `apks=[]` 并附「接口刷量」风控提示。
 *
 * 所以本源产出的条目 [Link] 是 [Link.Empty] —— **能看版本，装不了**。
 * 这也是它默认关闭、且在设置里写明原因的理由：宁可让用户主动开启，
 * 也不要出现「列表里有、点了却装不了」的隐性坑。
 *
 * ## 差分更新的可用性
 * 实测小米只对本机**就是从小米商店安装**的应用提供差分
 * （判据是响应里的 `releaseKeyHash` 与本机已装 APK 的哈希是否一致），
 * 侧载或从其他商店安装的一律没有差分。
 *
 * ## 踩过的坑（避免后人重走）
 *  · 端点必须用 **POST**，用 GET 会一直返回 `errCode:1 不支持的接口`。
 *    那个错误码极具误导性，看起来像「缺签名」，其实只是方法不对。
 *  · 该端点**不需要签名**。
 *  · **必须从设备本身发起请求**。同样参数从 PC 发起会拿到降级数据：
 *    `apkSize` 是假值、`listApp` 为空、附带风控提示。
 *  · `model` / `device` / `os` / `sdk` 必填，缺任一返回 `errCode:4 参数不能为空`。
 *  · 是**批量**接口（包名与版本号均逗号分隔），优于应用宝的逐包查询。
 */
class XiaomiRepository(
	private val context: Context,
	private val service: XiaomiService,
	private val prefs: Prefs,
	private val webDetail: com.onekey.updater.util.net.XiaomiWebDetail
) {

	companion object {
		private const val TAG = "XiaomiRepository"

		/**
		 * MIUI 系统应用通道的标记包。
		 *
		 * 小米的 `updateinfo/v2` 响应里有 `listApp` 与 `miuiApp` **两个平行数组**，
		 * 而 `miuiApp` 只有在请求的包名列表里出现这个包时才会下发（服务端按包名匹配）。
		 * 版本号填 0，含义是「不假装知道已装版本」—— 它只是请求上下文，
		 * 响应回来后与真实已装包对账，标记包本身不会成为可见更新。
		 */
		const val MIUI_ANCHOR = "com.miui.core"

		/**
		 * 小米应用商店风格的 User-Agent。
		 *
		 * 关键在 `Build/<Build.ID>` 这一段必须与**真机**一致（服务端按它识别设备）。
		 * 取不到 Build.ID 时退回 `Build.UNKNOWN` 之外的可用值，不留空 ——
		 * 空 UA 会退化成 OkHttp 默认的 `okhttp/4.x`，服务端一律不认。
		 */
		@JvmStatic
		fun xiaomiUserAgent(): String {
			val release = Build.VERSION.RELEASE.orEmpty().ifBlank { Build.VERSION.SDK_INT.toString() }
			val model = Build.MODEL.orEmpty().ifBlank { "Android" }
			// Build.ID 在个别 ROM 上为空，兜底用系统构建号，至少保证 UA 结构完整
			val buildId = Build.ID.orEmpty().ifBlank { Build.DISPLAY.orEmpty() }.ifBlank { "UNKNOWN" }
			return "Dalvik/2.1.0 (Linux; U; Android $release; $model Build/$buildId)"
		}

		/**
		 * 单批多少个应用。
		 *
		 * 完整参数集（设备指纹 + 能力开关）本身约 1400 字节，
		 * 再叠加逗号分隔的包名与版本号会顶到 URL 长度上限，故分批。
		 */
		private const val BATCH = 40
	}

	/** 本次运行内稳定的伪随机 id，充当 instance_id / sid。 */
	private val instanceId: String = UUID.randomUUID().toString().replace("-", "").take(16)

	fun updates(apps: List<AppInstalled>) = flow {
		if (!prefs.useXiaomi.get()) {
			emit(emptyList())
			return@flow
		}
		val targets = apps.filter { !it.ignored }
		if (targets.isEmpty()) {
			emit(emptyList())
			return@flow
		}

		val found = coroutineScope {
			targets.chunked(BATCH).map { chunk ->
				async(Dispatchers.IO) {
					// 不要静默吞异常：否则"请求失败"和"确实没有更新"在日志里长得一模一样，
					// 排查时根本无法区分，这一点在接入阶段已经吃过亏。
					try {
						query(chunk)
					} catch (t: Throwable) {
						Log.w(TAG, "第 " + targets.indexOf(chunk.first()) + " 批查询失败。", t)
						emptyList()
					}
				}
			}.awaitAll().flatten()
		}

		AppLog.log(
			TAG,
			"小米商店：查询 " + targets.size + " 个应用，命中 " + found.size + " 个（仅版本检查，暂不能下载）"
		)
		emit(found)
	}.flowOn(Dispatchers.IO).catch {
		Log.e(TAG, "小米商店查询失败。", it)
		emit(emptyList())
	}

	private suspend fun query(chunk: List<AppInstalled>): List<AppUpdate> {
		// dctx 是服务端下发的加密设备上下文，下载地址与 miuiApp 通道都依赖它。
		// 实测结论：即便带上**本应用真实的 OAID**，服务端也不会给第三方更新器下发 dctx。
		// 因此这里只在「还没试过」时试一次（ensureDctx 内有进程内闸门）——
		// 否则一次扫描会对着 /apm/expId 打几十次，既慢又像刷接口，反而可能招来风控。
		val dctx = XiaomiIdentity.cachedDctx(prefs).ifBlank {
			XiaomiIdentity.ensureDctx(prefs) { baseParams(chunk, emptyList()) }.orEmpty()
		}
		// 注意：User-Agent 由 di/MainModule 里小米的**独立客户端**注入。
		// 这里不能再用 @Header 传 —— OkHttp 拦截器在 Retrofit 组装请求之后才跑，
		// 会把 @Header 覆盖掉（这正是之前 miuiApp 通道一直空的原因）。
		val response = service.checkUpdates(baseParams(chunk, listOf(dctx)))

		// 小米被风控/拦参时返回的是 HTTP 200 + errCode，而不是 4xx/5xx ——
		// 不显式判断的话，这个响应会被解析成「listApp 为空」，表现与「确实没有更新」
		// 完全一样，排查时只能靠猜。这里显式失败并把服务端原话记进日志。
		if (response.errCode != 0) {
			Log.w(
				TAG,
				"小米商店拒绝本批（${chunk.size} 个应用）：errCode=" + response.errCode +
					" errDesc=" + response.errDesc
			)
			return emptyList()
		}

		val byPackage = chunk.associateBy { it.packageName }
		Log.i(
			TAG,
			"批次 " + chunk.size + " 个应用：返回条目 " + response.listApp.size +
				"，商店未收录 " + response.invalidPackages.size
		)

		val entries = response.listApp.map { it to false } + response.miuiApp.map { it to true }
		if (response.miuiApp.isNotEmpty()) {
			Log.i(TAG, "miuiApp 通道返回 " + response.miuiApp.size + " 条 MIUI 系统应用更新")
		}

		return entries.mapNotNull { (remote, isSystem) ->
			val local = byPackage[remote.packageName] ?: return@mapNotNull null
			if (remote.versionCode <= local.versionCode) return@mapNotNull null

			val remoteVersion = remote.versionName.ifBlank { remote.versionCode.toString() }
			// 版本护栏：沿用全局规则——版本名优先，versionCode 只用于同名打破平局。
			val newerByName = runCatching {
				Version(filterVersionTag(remoteVersion)) > Version(local.version)
			}.getOrDefault(true)
			if (!newerByName) return@mapNotNull null

			Log.i(
				TAG,
				"命中 " + remote.packageName + " " + local.version + " -> " + remoteVersion +
					" (appId=" + remote.appId + ", apkSize=" + remote.apkSize +
					", diff=" + remote.diffFileSize + ", releaseKeyHash=" + remote.releaseKeyHash + ")"
			)

			AppUpdate(
				name = remote.displayName.ifBlank { local.name },
				packageName = local.packageName,
				version = remoteVersion,
				oldVersion = local.version,
				versionCode = remote.versionCode,
				oldVersionCode = local.versionCode,
				source = XiaomiSource,
				iconUri = local.iconUri,
				// 客户端的 /apm/download 被 downloadCtl 管控（实测带真实 OAID、完整签名、
				// 调用方冒充、Android TLS 一律返回 apks=[]），因此改从**网页版详情页**取直链 ——
				// 那是另一条完全公开、无需签名的通路。
				link = resolveDownload(local.packageName),
				whatsNew = remote.changeLog.ifBlank { remote.briefShow }
			)
		}
	}

	/**
	 * 经网页版详情页解析下载地址。
	 *
	 * 页面给的 URL 是 http（CDN 同对象支持 https），且 CDN 开启防盗链 ——
	 * 缺 `Referer: https://sj.qq.com/` 会返回一段 JS 而不是 APK，因此 referer 必须带上。
	 */
	private suspend fun resolveDownload(packageName: String): Link {
		val detail = webDetail.fetch(packageName)
		if (detail == null || detail.downloadUrl.isBlank()) {
			Log.i(TAG, "未取到 $packageName 的下载地址，该条目仅供查看版本")
			return Link.Empty
		}
		return Link.Url(
			detail.downloadUrl,
			detail.sizeBytes,
			com.onekey.updater.util.net.XiaomiWebDetail.REFERER
		)
	}

	/**
	 * 构造请求参数。
	 *
	 * [model] / [device] / [os] / [sdk] 是必填项，缺任一服务端返回
	 * `errCode:4 参数不能为空`。其余字段是设备指纹与能力开关，服务端会拿它们做
	 * 真实性校验 —— 缺失未必报错，但会被降级处理，所以这里尽量给全。
	 */
	/**
	 * 构造请求参数。
	 *
	 * @param dctx 服务端下发的设备上下文，非空才会带上。
	 */
	private suspend fun baseParams(chunk: List<AppInstalled>, dctx: List<String>): Map<String, String> {
		val metrics = context.resources.displayMetrics
		val release = Build.VERSION.RELEASE.orEmpty().ifBlank { Build.VERSION.SDK_INT.toString() }
		val model = Build.MODEL.orEmpty().ifBlank { "Android" }

		return buildMap {
			// —— 设备指纹（四个必填项）——
			put("model", model)
			put("device", "Android")
			put("os", release)
			put("sdk", Build.VERSION.SDK_INT.toString())
			put("androidVersion", release)
			put("osV2", release)
			put("osBigVersionCode", Build.VERSION.SDK_INT.toString())
			put("osBigVersionName", release)
			put("cpuArchitecture", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
			put("resolution", metrics.widthPixels.toString() + "x" + metrics.heightPixels)
			put("densityDpi", metrics.densityDpi.toString())
			put("densityScaleFactor", metrics.density.toString())
			put("co", "CN")
			put("la", "zh_CN")
			put("lo", "CN")
			put("ro", "unknown")
			put("network", "unknown")
			put("newUser", "false")
			put("deviceType", "0")
			put("hasGMSCore", "false")
			put("activedTimeInterval", "1")
			put("installDay", "1")
			put("launchDay", "1")
			put("instance_id", instanceId)
			put("marketVersion", "V816")
			put("miuiBigVersionCode", "V816")
			put("miuiBigVersionName", "V816")
			put("pageConfigVersion", "1")
			put("webResVersion", "1")
			put("hybridFrameworkVersion", "1")
			put("supportedIslandVersion", "1")
			// 设备标识：优先用本应用真实的 OAID（按应用分发，只能反射取），
			// 取不到才回落到稳定占位值 —— 服务端不认可占位值，也就不会下发 dctx。
			put("oaId", XiaomiIdentity.oaId(context, prefs))

			// —— 能力开关 ——
			put("clientConfigVersion", "447")
			put("clientFlag", "2")
			put("ARCoreApkVersion", "-1")
			put("childMode", "0")
			put("debugMode", "false")
			put("downloadRestriction", "1")
			put("downloadRestrictionMode", "0")
			put("isMiuiLite", "false")
			put("isSupportIsland", "true")
			put("isSupportMessageBox", "true")
			put("isSupportQuickGameInstall", "true")
			put("isSupportUninstall", "true")
			put("isTangoEnabled", "true")
			put("minorsMode", "false")
			put("needBlockWelfare", "true")
			put("privacyCompliance", "true")
			put("rankTypeV2", "true")
			put("rustRuntimeVersion", "1.6.0")
			put("supportAgent", "true")
			put("supportBundle", "1")
			put("supportDownloaderUpdate", "1")
			put("supportOperateIcon", "true")
			put("supportPatchVer", "0,1,2,3")
			put("supportSmallApk", "true")
			put("useExpId", "2369654,2508437")

			// —— 调用方与查询内容 ——
			put("ref", "update")
			put("callerPackageName", "com.xiaomi.market")
			put("sourcePackage", "com.xiaomi.market")

			put("oldApkHash", "")
			// 追加 MIUI 标记包以激活 miuiApp 通道（版本号 0 = 不假装知道已装版本）
			put("packageName", (chunk.map { it.packageName } + MIUI_ANCHOR).joinToString(","))
			put("versionCode", (chunk.map { it.versionCode.toString() } + "0").joinToString(","))
			dctx.firstOrNull()?.takeIf { it.isNotBlank() }?.let { put("dctx", it) }
			put("apkSource", "")
			put("splits", "")
			put("installedByMarket", "")
		}
	}
}
