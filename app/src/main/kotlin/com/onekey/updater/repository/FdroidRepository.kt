package com.onekey.updater.repository

import android.os.Build
import android.util.Log
import com.onekey.updater.data.fdroid.FdroidApp
import com.onekey.updater.data.fdroid.FdroidData
import com.onekey.updater.data.fdroid.FdroidUpdate
import com.onekey.updater.data.fdroid.toAppUpdate
import com.onekey.updater.data.ui.AppInstalled
import com.onekey.updater.data.ui.Source
import com.onekey.updater.data.ui.getApp
import com.onekey.updater.data.ui.getVersionCode
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.service.FdroidService
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.jar.JarInputStream


/**
 * F-Droid / IzzyOnDroid 仓库。
 *
 * === 相对上游的修复（这是「加载漫长」的另一个主因） ===
 * 上游每次调用 `updates()` / `search()` 都会重新下载整个 `index-v1.jar`（约 14 MB），
 * 并用 Gson 解析其中十几 MB 的 JSON。启用 F-Droid + Izzy 后，每刷新一次就是
 * 28 MB 下载 + 两次大 JSON 解析；搜索每敲几个字又各来一遍。
 *
 * 现在分两层缓存：
 *  1. **磁盘缓存**：索引文件落到 cacheDir，默认 6 小时内直接复用；
 *  2. **内存缓存**：解析后的 [FdroidData] 按文件修改时间缓存，同一索引只解析一次。
 */
class FdroidRepository(
    private val service: FdroidService,
    private val url: String,
    private val source: Source,
    private val prefs: Prefs,
    private val cacheDir: File
) {
    companion object {
        private const val TAG = "FdroidRepository"
        private const val INDEX_FILE = "index-v1.jar"

        /** 索引有效期。F-Droid 官方索引更新频率远低于此。 */
        private const val INDEX_TTL_MS = 6 * 60 * 60 * 1000L
    }

    private val arch = Build.SUPPORTED_ABIS.toSet()
    private val api = Build.VERSION.SDK_INT

    private val indexFile: File by lazy {
        File(cacheDir, "fdroid-index-${url.hashCode()}.jar")
    }

    private val indexMutex = Mutex()

    @Volatile
    private var parsed: FdroidData? = null

    @Volatile
    private var parsedFromTimestamp: Long = -1L

    suspend fun updates(apps: List<AppInstalled>) = flow {
        val data = index()
        val appNames = apps.map { it.packageName }
        val updates = data.apps
            .asSequence()
            .filter { appNames.contains(it.packageName) }
            .filter { filterSignature(apps.getApp(it.packageName)!!, it) }
            .map { FdroidUpdate(data.packages[it.packageName]!![0], it) }
            .filter { it.apk.versionCode > apps.getVersionCode(it.app.packageName) }
            .parseUpdates(apps)
        emit(updates)
    }.flowOn(Dispatchers.IO).catch {
        emit(emptyList())
        Log.e(TAG, "查找更新失败（$source）。", it)
    }

    suspend fun search(text: String) = flow {
        val data = index()
        val updates = data.apps
            .asSequence()
            .map { FdroidUpdate(data.packages[it.packageName]!![0], it) }
            .filter {
                it.app.name.contains(text, true) ||
                    it.app.packageName.contains(text, true) ||
                    it.apk.apkName.contains(text, true)
            }
            .parseUpdates(null)
        emit(Result.success(updates))
    }.flowOn(Dispatchers.IO).catch {
        emit(Result.failure(it))
        Log.e(TAG, "搜索失败（$source）。", it)
    }

    /** 强制丢弃缓存（下次调用重新下载并解析）。 */
    suspend fun invalidate() = indexMutex.withLock {
        parsed = null
        parsedFromTimestamp = -1L
        indexFile.delete()
    }

    /**
     * 取得已解析的索引。并发调用会被串行化，避免重复下载 / 重复解析。
     */
    private suspend fun index(): FdroidData = indexMutex.withLock {
        val fresh = indexFile.exists() &&
            System.currentTimeMillis() - indexFile.lastModified() < INDEX_TTL_MS

        if (!fresh) {
            runCatching {
                Log.i(TAG, "下载索引: ${url}$INDEX_FILE")
                val body = service.getJar("$url$INDEX_FILE")
                indexFile.parentFile?.mkdirs()
                val temp = File(indexFile.parentFile, "${indexFile.name}.part")
                body.byteStream().use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                // 先写临时文件再改名，避免中断留下半截索引被当作有效缓存
                if (!temp.renameTo(indexFile)) {
                    temp.copyTo(indexFile, overwrite = true)
                    temp.delete()
                }
                Unit
            }.onFailure {
                // 协程被取消（例如页面离开）不是错误，直接向上传播
                if (it is CancellationException) throw it
                Log.e(TAG, "索引下载失败，尝试使用本地缓存。", it)
                if (!indexFile.exists()) throw it
            }
        }

        val timestamp = indexFile.lastModified()
        parsed?.takeIf { parsedFromTimestamp == timestamp }?.let { return@withLock it }

        val data = JarInputStream(indexFile.inputStream()).use { jar -> jarToJson(jar) }
        parsed = data
        parsedFromTimestamp = timestamp
        Log.i(TAG, "索引解析完成（$source，${data.apps.size} 个应用）")
        data
    }

    private fun Sequence<FdroidUpdate>.parseUpdates(apps: List<AppInstalled>?) = this
        .filter { it.apk.minSdkVersion <= api }
        .filter { filterArch(it) }
        .filter { filterAlpha(it) }
        .filter { filterBeta(it) }
        .map { it.toAppUpdate(apps?.getApp(it.app.packageName), source, url) }
        .toList()

    private fun filterSignature(installed: AppInstalled, update: FdroidApp) = when {
        update.allowedAPKSigningKeys.isEmpty() -> true
        update.allowedAPKSigningKeys.contains(installed.signatureSha256) -> true
        else -> false
    }

    private fun filterAlpha(update: FdroidUpdate) = when {
        prefs.ignoreAlpha.get() && update.apk.versionName.contains("alpha", true) -> false
        else -> true
    }

    private fun filterBeta(update: FdroidUpdate) = when {
        prefs.ignoreBeta.get() && update.apk.versionName.contains("beta", true) -> false
        else -> true
    }

    private fun filterArch(update: FdroidUpdate) = when {
        update.apk.nativecode.isEmpty() -> true
        update.apk.nativecode.intersect(arch).isNotEmpty() -> true
        else -> false
    }

    private fun jarToJson(jar: JarInputStream): FdroidData {
        var entry = jar.nextJarEntry
        while (entry != null) {
            if (entry.name == "index-v1.json") {
                return Gson().fromJson(jar.reader(), FdroidData::class.java)
            }
            entry = jar.nextJarEntry
        }
        return FdroidData()
    }
}
