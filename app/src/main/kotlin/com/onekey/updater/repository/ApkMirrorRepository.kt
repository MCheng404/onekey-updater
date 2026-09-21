package com.onekey.updater.repository

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import com.onekey.updater.data.apkmirror.AppExistsRequest
import com.onekey.updater.data.apkmirror.AppExistsResponseApk
import com.onekey.updater.data.apkmirror.AppExistsResponseData
import com.onekey.updater.data.apkmirror.toAppUpdate
import com.onekey.updater.data.ui.ApkMirrorSource
import com.onekey.updater.data.ui.AppInstalled
import com.onekey.updater.data.ui.AppUpdate
import com.onekey.updater.data.ui.Link
import com.onekey.updater.data.ui.getApp
import com.onekey.updater.data.ui.getPackageNames
import com.onekey.updater.data.ui.getSignature
import com.onekey.updater.data.ui.getVersionCode
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.service.ApkMirrorService
import com.onekey.updater.util.combine
import com.onekey.updater.util.isAndroidTv
import com.onekey.updater.util.orFalse
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import org.jsoup.Jsoup


class ApkMirrorRepository(
    private val service: ApkMirrorService,
    private val prefs: Prefs,
    packageManager: PackageManager
) {

    /**
     * 兜底用的模糊架构关键字。
     *
     * 注意：这个值只用于「这个变体是否可能与本机兼容」的粗判，
     * **不决定挑哪个变体** —— 真正的变体选择见 [abiScore]。
     * 原来的判断顺序把 32 位放在 64 位之前（先查 armeabi-v7a 再查 arm64-v8a），
     * 在 arm64 设备上两者都会命中，于是永远落到 32 位分支。
     */
    private companion object {
        const val TAG = "ApkMirrorRepository"
    }

    private val arch = when {
        Build.SUPPORTED_ABIS.any { it.contains("x86_64") } -> "x86"
        Build.SUPPORTED_ABIS.any { it.contains("x86") } -> "x86"
        Build.SUPPORTED_ABIS.any { it.contains("arm64") } -> "arm"
        else -> "arm"
    }

    /**
     * 变体的架构优先度，数字越大越优先。
     *
     * 为什么必须有它：同一发布的 `armeabi-v7a` 与 `arm64-v8a` 变体
     * **versionCode 完全相同**，而原来的挑选逻辑只有 `maxByOrNull { it.versionCode }`，
     * 并列时返回列表里先出现的那个 —— APKMirror 的列表顺序经常把 v7a 排在前面，
     * 于是 arm64 设备被下到了 32 位包（这正是「跳转到了 arm-v7」的原因）。
     *
     * 依据用 `Build.SUPPORTED_ABIS` 的**顺序**：它本身就是按「由优到劣」排列的
     * （arm64 设备上是 [arm64-v8a, armeabi-v7a, armeabi]），所以下标越小越优先。
     */
    private fun abiScore(apk: AppExistsResponseApk): Int {
        val arches = apk.arches
        if (arches.isEmpty()) return 0
        // universal / noarch 视为中性：能装，但不比明确匹配本机主 ABI 的更优
        if (arches.any { it.equals("universal", true) || it.equals("noarch", true) }) return 0
        var best = -1
        arches.forEach { a ->
            val index = Build.SUPPORTED_ABIS.indexOfFirst { it.equals(a, ignoreCase = true) }
            if (index >= 0) {
                val score = Build.SUPPORTED_ABIS.size - index
                if (score > best) best = score
            }
        }
        return best
    }

    private val isAndroidTV = packageManager.isAndroidTv()
    private val api = Build.VERSION.SDK_INT

    suspend fun updates(apps: List<AppInstalled>) = flow {
        apps.chunked(100)
            .map { appExists(it.getPackageNames()) }
            .combine { all -> emit(parseUpdates(all.flatMap { it }, apps)) }
            .collect()
    }

    suspend fun search(text: String) = flow {
        val baseUrl = "https://www.apkmirror.com"
        val searchQuery = "/?post_type=app_release&searchtype=app&s="
        val doc = Jsoup.connect("$baseUrl$searchQuery$text").get()
        val row = doc.select("div.appRow")
        val a = row.select("a.byDeveloper")
        val h5 = row.select("h5.appRowTitle").take(a.size)
        val img = row.select("img")
        a.removeAt(0)
        img.removeAt(0)
        val result = (0 until a.size).map {
            AppUpdate(
                name = h5[it].attr("title"),
                link = Link.Url("$baseUrl${h5[it].selectFirst("a")?.attr("href")}"),
                iconUri = "$baseUrl${img[it].attr("src")}".replace("=32", "=128").toUri(),
                version = "?",
                oldVersion = "?",
                versionCode = 0L,
                oldVersionCode = 0L,
                source = ApkMirrorSource,
                packageName = a[it].text() // Developer name in this case
            )
        }
        emit(Result.success(result))
    }.catch {
        emit(Result.failure(it))
        Log.e("ApkMirrorRepository", "Error searching.", it)
    }

    private fun appExists(apps: List<String>) = flow {
        emit(service.appExists(AppExistsRequest(apps, buildIgnoreList())).data)
    }.catch {
        emit(emptyList())
        Log.e("ApkMirrorRepository", "Error getting updates.", it)
    }

    private fun parseUpdates(updates: List<AppExistsResponseData>, apps: List<AppInstalled>)
    = updates
        .filter { it.exists == true }
        .mapNotNull { data ->
            data.apks
                .asSequence()
                .filter { filterSignature(it, apps.getSignature(data.pname))}
                .filter { filterArch(it) }
                .filter { it.versionCode > apps.getVersionCode(data.pname) }
                .filter { filterMinApi(it) }
                .filter { filterAndroidTv(it) }
                .filter { filterWearOS(it) }
                // 先比版本号，版本号相同再比架构优先度 ——
                // 同一发布的 arm64-v8a 与 armeabi-v7a 变体 versionCode 相同，
                // 只有补上第二个比较键才不会随机落到 32 位包上。
                .maxWithOrNull(
                    compareBy<AppExistsResponseApk> { it.versionCode }.thenBy { abiScore(it) }
                )
                ?.also {
                    Log.i(
                        TAG,
                        "选中变体 " + data.pname + " v" + it.versionCode +
                            " arches=" + it.arches + " 本机ABI=" + Build.SUPPORTED_ABIS.toList() +
                            " link=" + it.link
                    )
                }
                ?.toAppUpdate(apps.getApp(data.pname)!!, data.release)
        }

    private fun filterSignature(apk: AppExistsResponseApk, signature: String?) = when {
        apk.signaturesSha1.isNullOrEmpty() -> true
        apk.signaturesSha1.contains(signature) -> true
        else -> false
    }

    /**
     * 这个变体是否与本机 ABI 兼容。
     *
     * 最后一档兜底不能写成 `a.contains(arch)` —— 那样 "armeabi-v7a".contains("arm") 为真，
     * 在**只支持 arm64-v8a 的设备**上会把纯 32 位变体当兼容放行，结果下载到装不上的包
     * （实测 Edge Canary 只有 armeabi-v7a 变体时就被选中了）。
     * 这里改为只接受**通用关键字**（如 "arm" / "x86"），本机没有的具体 ABI 一律不算兼容。
     */
    private fun filterArch(app: AppExistsResponseApk) = when {
        app.arches.isEmpty() -> true
        app.arches.any { it.equals("universal", true) || it.equals("noarch", true) } -> true
        app.arches.any { a -> Build.SUPPORTED_ABIS.any { it.equals(a, ignoreCase = true) } } -> true
        app.arches.any { a -> a.equals(arch, ignoreCase = true) } -> true
        else -> false
    }

    private fun filterAndroidTv(apk: AppExistsResponseApk): Boolean {
        if (!isAndroidTV) {
            // Filter out standalone AndroidTV apps if we are not an AndroidTV device
            if(apk.capabilities?.contains("leanback_standalone").orFalse()) {
                return false
            }
        } else {
            // Filter out apps that don't have leanback if we are an AndroidTV device
            return (apk.capabilities?.contains("leanback_standalone").orFalse()
                    || apk.capabilities?.contains("leanback").orFalse())
        }
        return true
    }

    private fun filterWearOS(apk: AppExistsResponseApk): Boolean {
        // For the moment filter out all standalone Wear OS apps
        if (apk.capabilities?.contains("wear_standalone").orFalse()) {
            return false
        }
        return true
    }

    private fun filterMinApi(apk: AppExistsResponseApk) = runCatching {
        when {
            apk.minapi.toInt() > api -> false
            else -> true
        }
    }.getOrDefault(true)

    private fun buildIgnoreList() = mutableListOf<String>().apply {
        if (prefs.ignoreAlpha.get()) add("alpha")
        if (prefs.ignoreBeta.get()) add("beta")
    }

}
