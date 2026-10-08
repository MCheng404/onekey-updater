package com.onekey.updater.util.net

import kotlinx.coroutines.sync.withLock
import android.util.Log
import java.net.URLDecoder
import java.security.MessageDigest
import java.net.URLEncoder
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 小米应用商店请求签名。
 *
 * 产出 URL 末尾的 `_n`（nonce）、`_s`（签名）、`_v`（版本）三个参数。
 *
 * ## 关键认知：签名与设备身份无关
 * `oaId` / `dctx` / `tzNonce` / `tzSign` **都不在 [SIGNED_KEYS] 白名单里**，
 * 也就是说签名纯粹是「参数 + 盐」的本地计算，不需要任何设备密钥或 TEE 签名。
 * 这就是为什么版本检查端点不签名也能拿到数据，而请求 `_s` 仍然被服务端校验。
 *
 * ## 签名串的构造（[arrange]）
 * 1. 把 URL 的 host 段逐字符反转，遇到第 3 个 `/` 为止，然后写入换行；
 * 2. 过滤掉 `_n` / `_s` / `_v` 后追加 `_n=<nonce>`；
 * 3. 把白名单里的 key 用 `;` 拼成 `_p`；
 * 4. 参数按 key 忽略大小写升序排序；
 * 5. 每个参数内把 `=` 换成 `&`，并把 `=` 之后的每一段**逐字符反转**；
 * 6. 截掉第一个换行之前的部分。
 *
 * 算法按 nonce 里时间戳 `% 4` 轮换 MD5 / SHA-256 / SHA-1 / SHA-384，
 * 用 `SALT + nonce` 作 key 对待签串做 HMAC，最后用**自定义字母表**做 Base64。
 */
object XiaomiSigner {

	/** 与官方客户端一致的盐。 */
	private const val SALT = "good luck!"

	/** 小米私有 Base64 字母表（替换标准 Base64 的字母顺序，且不补 =）。 */
	private const val TABLE =
		"leDTKhmg4MafVFp73x6djvLiHn2G9XPruARBwS0q1OzNJt8WobZsQcYyEICk5U-_"

	/** 参与签名的参数名白名单。**身份类字段（oaId/dctx/tz*）不在其中**，故签名与身份无关。 */
	private val SIGNED_KEYS = setOf(
		"activedTimeInterval", "ad", "adExchangeFlag", "adFlag", "apkChannel", "appId",
		"bottomTab", "carrier", "clientId", "co", "count", "cpuArchitecture", "device",
		"deviceType", "digestParams", "downloadingAppInfo", "excludedAppIds", "ext_apkChannel",
		"ext_marketType", "flag", "folderName", "get", "gpId", "h5", "id", "imei",
		"installDay", "installError", "instance_id", "international", "keyword", "la",
		"launchDay", "lo", "marketVersion", "miuiBigVersionCode", "miuiBigVersionName",
		"model", "_n", "needLruCache", "network", "newUser", "oldApkHash", "oldVersionCode",
		"os", "packageName", "packageNameList", "page", "pageConfigVersion", "pageRef",
		"pageSize", "pageTag", "params", "pos", "posChain", "previousAppIds",
		"proxyTimeout", "query", "reason", "recentInstallCompleteAppInfo", "ref",
		"refPosition", "refresh", "refs", "resolution", "ro", "sco", "sdk", "searchScope",
		"shouldNativeInterceptRequest", "sid", "sla", "sourcePackage", "stamp",
		"targetVersionCode", "type", "update", "versionCode", "webResVersion", "zoneSuffix",
		"aiQuery"
	)

	private val HMAC_ALGS = listOf("HmacMD5", "HmacSHA256", "HmacSHA1", "HmacSHA384")

	/** 生成 nonce：`{毫秒时间戳}_{0..999}`。 */
	fun newNonce(): String = "${System.currentTimeMillis()}_${(0..999).random()}"

	/** 给已带查询串的 URL 追加 `_n` / `_s` / `_v`，返回可直接请求的 URL。 */
	/**
	 * 表单签名：把 `_n` / `_s` / `_v` 追加进**待提交的字段表**。
	 *
	 * 官方客户端的 updateinfo 请求用的是表单体签名，不是 URL query 签名。
	 * 两者不能混用：同样内容放 query 时服务端照收不误（能查到普通商店应用），
	 * 但 **miuiApp（MIUI 自带应用）通道不下发**，时钟/录音机这类应用因此永远查不到。
	 *
	 * @param encodedUrl 已 URL 编码的端点（参与签名的是编码后的形态）
	 * @param fields 待签字段，会被就地追加三个签名字段
	 */
	fun signForm(encodedUrl: String, fields: MutableMap<String, String>) {
		val nonce = System.currentTimeMillis().toString() + "_" + (0..999).random()
		// 签名覆盖「追加 _n 之前」的字段集
		val sep = if (encodedUrl.contains("?")) "&" else "?"
		val signatureUrl = if (fields.isEmpty()) encodedUrl else {
			encodedUrl + sep + fields.entries.joinToString("&") { (k, v) ->
				"$k=" + URLEncoder.encode(v, "UTF-8")
			}
		}
		fields["_n"] = nonce
		fields["_s"] = signature(signatureUrl, nonce)
		fields["_v"] = "1"
	}

	fun signedUrl(baseUrl: String): String {
		val nonce = newNonce()
		val signature = signature(baseUrl, nonce)
		val sep = if (baseUrl.contains('?')) "&" else "?"
		return baseUrl + sep + "_n=" + enc(nonce) + "&_s=" + enc(signature) + "&_v=1"
	}

	// ------------------------------------------------------------------ 内部

	private fun signature(url: String, nonce: String): String {
		val timestamp = nonce.substringBefore('_').toLongOrNull() ?: 0L
		val arranged = arrange(urlDecode(url), nonce)
		return xiaomiBase64(hmac(HMAC_ALGS[(timestamp % 4).toInt()], (SALT + nonce), arranged))
	}

	private fun arrange(decodedUrl: String, nonce: String): String {
		val q = decodedUrl.indexOf('?')
		val urlPart = if (q >= 0) decodedUrl.substring(0, q) else decodedUrl
		val query = if (q >= 0 && q + 1 < decodedUrl.length) decodedUrl.substring(q + 1) else ""

		// 1) 反转 host 段（到第 3 个 / 为止），并在签名起点前插入换行
		val builder = StringBuilder()
		var slashes = 0
		var reversingHost = true
		for (ch in urlPart) {
			if (ch == '/' && reversingHost) {
				slashes++
				if (slashes == 3) {
					builder.append('\n').append(ch)
					reversingHost = false
				} else {
					builder.insert(0, ch)
				}
			} else if (reversingHost) {
				builder.insert(0, ch)
			} else {
				builder.append(ch)
			}
		}
		builder.append('\n')

		// 2) 组装参与签名的参数
		val params = query.split('&')
			.filter { it.isNotEmpty() && !it.startsWith("_n=") && !it.startsWith("_s=") && !it.startsWith("_v=") }
			.toMutableList()
		params += "_n=$nonce"

		// 3) 白名单 key 拼成 _p
		val plist = params.filter { SIGNED_KEYS.contains(it.substringBefore('=')) }
			.joinToString(";") { it.substringBefore('=') }
		params += "_p=$plist"

		// 4) 按 key 忽略大小写排序
		params.sortWith { a, b -> a.substringBefore('=').compareTo(b.substringBefore('='), ignoreCase = true) }

		// 5) 逐参数拼接：'=' 变 '&'，值段逐字符反转
		params.forEachIndexed { index, param ->
			val key = param.substringBefore('=')
			val signed = param.contains('=') && SIGNED_KEYS.contains(key)
			if (!signed) {
				if (index == params.lastIndex && builder.endsWith("=")) builder.deleteCharAt(builder.lastIndex)
				return@forEachIndexed
			}
			val segments = param.split('=')
			builder.append(segments.first())
			for (i in 1 until segments.size) {
				builder.append('&').append(segments[i].reversed())
			}
			if (index != params.lastIndex) builder.append('=')
		}

		val text = builder.toString()
		return text.substringAfter('\n')
	}

	private fun hmac(algorithm: String, key: String, data: String): ByteArray = runCatching {
		val mac = Mac.getInstance(algorithm)
		mac.init(SecretKeySpec(key.toByteArray(), algorithm))
		mac.doFinal(data.toByteArray())
	}.getOrElse { ByteArray(0) }

	/** 小米私有 Base64：字母表被换成 [TABLE]，且不做 padding。 */
	private fun xiaomiBase64(data: ByteArray): String {
		if (data.isEmpty()) return ""
		val out = StringBuilder()
		var i = 0
		while (i + 2 < data.size) {
			val b1 = data[i].toInt() and 0xff
			val b2 = data[i + 1].toInt() and 0xff
			val b3 = data[i + 2].toInt() and 0xff
			out.append(TABLE[(b1 shr 2) and 0x3f])
			out.append(TABLE[((b1 shl 4) or (b2 shr 4)) and 0x3f])
			out.append(TABLE[((b2 shl 2) or (b3 shr 6)) and 0x3f])
			out.append(TABLE[b3 and 0x3f])
			i += 3
		}
		when (data.size - i) {
			1 -> {
				val b1 = data[i].toInt() and 0xff
				out.append(TABLE[(b1 shr 2) and 0x3f])
				out.append(TABLE[(b1 shl 4) and 0x3f])
			}
			2 -> {
				val b1 = data[i].toInt() and 0xff
				val b2 = data[i + 1].toInt() and 0xff
				out.append(TABLE[(b1 shr 2) and 0x3f])
				out.append(TABLE[((b1 shl 4) or (b2 shr 4)) and 0x3f])
				out.append(TABLE[(b2 shl 2) and 0x3f])
			}
		}
		return out.toString()
	}

	private fun enc(value: String): String = urlEncode(value)

	private fun urlDecode(value: String): String = runCatching {
		URLDecoder.decode(value, "UTF-8")
	}.getOrElse { value }

	private fun urlEncode(value: String): String = runCatching {
		// 用 URLEncoder 后把空格改成 %20（小米的编码约定与 + 不同）
		java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
	}.getOrElse { value }
}

/** 小米商店的设备身份：真实 OAID 与服务端下发的 dctx。 */
object XiaomiIdentity {

	private const val TAG = "XiaomiIdentity"
	@Volatile
	private var cachedOaId: String? = null

	/** oaId 单飞锁：多个批次并发时会同时未命中缓存，导致重复反射取 OAID。 */
	private val oaIdLock = kotlinx.coroutines.sync.Mutex()

	/**
	 * dctx 是否已被服务端拒绝过。
	 *
	 * 实测：即便带上**本应用真实的 OAID**，服务端也不会给第三方更新器下发 dctx。
	 * 不记下来的话，一次扫描会对着 /apm/expId 白跑十几次（实测 42 次），
	 * 既慢又像在刷接口——反而可能招来风控。
	 */
	private val dctxGate = kotlinx.coroutines.sync.Mutex()

	/** 本进程是否已尝试换取 dctx（无论成败），避免反复白跑。 */
	@Volatile
	private var dctxAttempted = false

	/**
	 * 取本应用在该设备上的真实 OAID。
	 *
	 * 小米系统在 `com.android.id.impl.IdProviderImpl` 里提供它，**按应用分发**，
	 * 所以只能由应用自己反射取，无法由外部进程代劳。
	 * 非小米 ROM 上该类不存在 → 回落为一个基于随机种子的稳定值（而不是硬件标识，
	 * 避免读取不必要的设备标识）。
	 */
	suspend fun oaId(context: android.content.Context, prefs: com.onekey.updater.prefs.Prefs): String {
		cachedOaId?.let { return it }
		return oaIdLock.withLock {
			cachedOaId?.let { return@withLock it }
			prefs.xiaomiOaId.get().ifBlank { null }?.let {
				cachedOaId = it
				return@withLock it
			}

			val real = runCatching {
				val clazz = Class.forName("com.android.id.impl.IdProviderImpl")
				val impl = clazz.getDeclaredConstructor().newInstance()
				clazz.getMethod("getOAID", android.content.Context::class.java)
					.invoke(impl, context) as? String
			}.getOrNull()?.trim().orEmpty()

			val value = if (real.isNotEmpty()) {
				Log.i(TAG, "取到真实 OAID（长度 ${real.length}）")
				prefs.xiaomiOaId.put(real)
				real
			} else {
				// 稳定回落：基于持久化随机种子，跨进程稳定但不读取硬件标识
				val seed = java.util.UUID.randomUUID().toString()
				val fallback = md5("oaid:" + seed).take(16)
				Log.w(TAG, "未取到真实 OAID（非小米 ROM 或被限制），使用稳定回落值")
				prefs.xiaomiOaId.put(fallback)
				fallback
			}
			cachedOaId = value
			value
		}
	}

	/**
	 * 换 `dctx`：GET `https://app.market.xiaomi.com/apm/expId`，从响应里取。
	 *
	 * dctx 是**服务端下发的加密设备上下文**，不需要任何特权权限。
	 * 但服务端只在认可身份（真实 oaId）时才下发，否则响应里没有这个字段。
	 */
	suspend fun refreshDctx(
		prefs: com.onekey.updater.prefs.Prefs,
		common: Map<String, String>
	): String? {
		val query = (common + mapOf("xmsfVersion" to DEFAULT_XMSF_VERSION))
			.entries.joinToString("&") { (k, v) -> k + "=" + java.net.URLEncoder.encode(v, "UTF-8") }
		val url = XiaomiSigner.signedUrl("https://app.market.xiaomi.com/apm/expId?" + query)
		return runCatching {
			val client = okhttp3.OkHttpClient()
			val request = okhttp3.Request.Builder().url(url)
				.header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 17)")
				.build()
			client.newCall(request).execute().use { response ->
				// 非 2xx 必须显式失败：错误页会被 lenient 解析成"空数据"
				if (!response.isSuccessful) return@use null
				val text = response.body?.string().orEmpty()
				val dctx = Regex("\"dctx\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1).orEmpty()
				if (dctx.isNotEmpty()) {
					prefs.xiaomiDctx.put(dctx)
					Log.i(TAG, "已获取 dctx（长度 ${dctx.length}）")
				} else {
					Log.w(TAG, "服务端未下发 dctx（身份可能未被认可）")
				}
				dctx.ifEmpty { null }
			}
		}.getOrElse {
			Log.w(TAG, "获取 dctx 失败。", it)
			null
		}
	}

	/** 读取已缓存的 dctx。 */
	fun cachedDctx(prefs: com.onekey.updater.prefs.Prefs): String = prefs.xiaomiDctx.get()

	/**
	 * 保证进程内只尝试换取一次 dctx。
	 *
	 * 实测结论：带**本应用真实 OAID** 去请求 `/apm/expId`，服务端**依然不下发** dctx
	 * （响应里没有该字段）。也就是这条「无特权换 dctx」的路并不存在。
	 * 若不记住这件事，一次扫描会对着 `/apm/expId` 白跑十几轮（实测 42 次）——
	 * 既拖慢扫描，又像是刷接口，反而可能招来风控。
	 *
	 * @return dctx，或 null 表示「不可用且本进程已确认过」。
	 */
	suspend fun ensureDctx(
		prefs: com.onekey.updater.prefs.Prefs,
		common: suspend () -> Map<String, String>
	): String? {
		prefs.xiaomiDctx.get().ifBlank { null }?.let { return it }
		// 关键：不能只看「缓存里有没有」—— dctx 永远拿不到时，每次调用都会重试。
		// 必须记住「本进程已试过且被拒」，否则一次扫描会打几十次 /apm/expId。
		if (dctxAttempted) return null
		return dctxGate.withLock {
			// 锁内必须再查一次：外层检查与置位之间存在窗口，
			// 并发批次里第二个调用方会从窗口钻进来（实测因此仍会多打一次）。
			if (dctxAttempted && prefs.xiaomiDctx.get().isBlank()) return@withLock null
			prefs.xiaomiDctx.get().ifBlank { null } ?: run {
				dctxAttempted = true
				Log.i(TAG, "首次尝试换取 dctx（失败则本进程不再重试）")
				refreshDctx(prefs, common())
			}
		}
	}

	/** 无 xmsf 时的上报默认值。 */
	private const val DEFAULT_XMSF_VERSION = "70005022"

	private fun md5(text: String): String = runCatching {
		val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray())
		digest.joinToString("") { "%02x".format(it) }
	}.getOrDefault("")
}