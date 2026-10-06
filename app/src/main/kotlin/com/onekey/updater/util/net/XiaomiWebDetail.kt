package com.onekey.updater.util.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 小米应用商店**网页版**详情页解析器。
 *
 * ## 为什么需要它
 * 客户端专用的 `/apm/download/{appId}` 被服务端按 `downloadCtl` 管控（实测无论带
 * 真实 OAID、签名、调用方冒充、TLS 都返回 `apks=[]` +「接口刷量」风控）。
 * 但**网页版**是另一条完全公开的通路：详情页把 APK 直链直接嵌在 HTML 里。
 *
 * ## 通路
 * `GET https://sj.qq.com/appdetail/{包名}` → HTML 内嵌 JSON →
 * 找 `cardId:"yybn_game_basic_info"` 的 `itemData[]`，按 `pkg_name` 命中目标应用 →
 * 得到 `download_url` / `version_name` / `md_5` / `apk_size`。
 *
 * ## 两个已实测的关键点
 * 1. **下载必须带 `Referer: https://sj.qq.com/`**。否则 CDN（`imtt2.dd.qq.com`）
 *    返回一段 `<script>function(){...}` 防盗链页面而不是 APK —— 实测带 Referer 后
 *    才是 `Content-Type: application/vnd.android.package-archive`。
 * 2. **URL 必须升级为 https**（页面里给的是 http），CDN 同一对象支持 https。
 */
class XiaomiWebDetail(private val client: OkHttpClient) {

	companion object {
		private const val TAG = "XiaomiWeb"
		private const val DETAIL = "https://sj.qq.com/appdetail/"
		const val REFERER = "https://sj.qq.com/"

		/** 网页 UA。必须是浏览器形态，否则页面会走另一套渲染。 */
		const val WEB_UA =
			"Mozilla/5.0 (Linux; Android 13; 2410DPN6CC) AppleWebKit/537.36 " +
				"(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
	}

	data class Detail(
		val versionName: String,
		val downloadUrl: String,
		val sizeBytes: Long,
		val md5: String
	)

	/**
	 * 取详情页里的 APK 直链。
	 * @return null 表示该应用不在商店里、或页面结构变了。
	 */
	suspend fun fetch(packageName: String): Detail? = withContext(Dispatchers.IO) {
		runCatching {
			val request = Request.Builder()
				.url(DETAIL + packageName)
				.header("User-Agent", WEB_UA)
				.header("Referer", REFERER)
				.build()

			val body = client.newCall(request).execute().use { response ->
				if (!response.isSuccessful) {
					Log.w(TAG, "详情页 HTTP ${response.code}：$packageName")
					return@use null
				}
				response.body?.string().orEmpty()
			} ?: return@runCatching null

			if (body.length < 1024) {
				Log.w(TAG, "详情页内容过短（${body.length} 字节），结构可能已变：$packageName")
				return@runCatching null
			}

			// 以目标包名做锚点，在其所在的 itemData 对象里取同组的字段
			val anchor = "\"pkg_name\":\"$packageName\""
			val at = body.indexOf(anchor)
			if (at < 0) {
				Log.i(TAG, "商店未收录：$packageName")
				return@runCatching null
			}
			// 在该对象范围内取字段（往后 2000 字符足够覆盖同一条记录的所有字段）
			val scope = body.substring(at, minOf(at + 2000, body.length))

			val url = Regex("\"download_url\"\\s*:\\s*\"(https?://[^\"]+)\"").find(scope)?.groupValues?.get(1)
				?: return@runCatching null
			val version = Regex("\"version_name\"\\s*:\\s*\"([^\"]*)\"").find(scope)?.groupValues?.get(1).orEmpty()
			val md5 = Regex("\"md_5\"\\s*:\\s*\"([^\"]*)\"").find(scope)?.groupValues?.get(1).orEmpty()
			val size = Regex("\"apk_size\"\\s*:\\s*\"?(\\d+)").find(scope)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

			Detail(
				versionName = version,
				// 页面给的是 http，CDN 同对象支持 https，必须升级（Android 9+ 禁明文）
				downloadUrl = url.replaceFirst("http://", "https://"),
				sizeBytes = size,
				md5 = md5
			).also {
				Log.i(TAG, "$packageName -> v${it.versionName} ${it.sizeBytes}B md5=${it.md5.take(12)}")
			}
		}.getOrElse {
			Log.w(TAG, "详情页解析失败：$packageName", it)
			null
		}
	}
}