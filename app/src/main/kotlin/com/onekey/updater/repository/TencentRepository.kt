package com.onekey.updater.repository

import android.util.Log
import com.onekey.updater.data.tencent.TencentRequest
import com.onekey.updater.data.tencent.TencentResponse
import com.onekey.updater.data.ui.AppInstalled
import com.onekey.updater.data.ui.AppUpdate
import com.onekey.updater.data.ui.Link
import com.onekey.updater.data.ui.TencentSource
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.service.TencentService
import com.onekey.updater.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import androidx.core.net.toUri

/**
 * 腾讯应用宝来源。
 *
 * === 为什么必须限流限量 ===
 * 应用宝只提供**逐包**查询接口（实测逗号拼接会返回空记录），没有批量接口。
 * 一次完整扫描有 180+ 个应用，若不加限制就是 180+ 次请求 ——
 * 既慢（并发过高反而被限），又是对公益接口的不礼貌使用。
 * 因此这里：单请求 8 秒超时、并发上限 4、总量上限 80 个应用。
 * 代价是「不是所有应用都会被应用宝覆盖」，这是刻意的取舍，不是疏漏。
 */
class TencentRepository(
    private val service: TencentService,
    private val prefs: Prefs
) {

    companion object {
        private const val TAG = "TencentRepository"

        /** 单次扫描最多查询多少个应用。 */
        private const val MAX_QUERIES = 80

        /** 并发上限。 */
        private const val CONCURRENCY = 4

        private const val PER_REQUEST_TIMEOUT_MS = 8_000L
    }

    fun updates(apps: List<AppInstalled>) = flow {
        if (!prefs.useTencent.get()) {
            emit(emptyList())
            return@flow
        }

        val targets = apps.take(MAX_QUERIES)
        val semaphore = Semaphore(CONCURRENCY)

        val found = coroutineScope {
            targets.map { app ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val response = withTimeoutOrNull(PER_REQUEST_TIMEOUT_MS) {
                            runCatching { service.info(TencentRequest(app.packageName)) }.getOrNull()
                        }
                        response?.let { toUpdate(app, it) }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        AppLog.log(TAG, "应用宝：查询 " + targets.size + " 个应用，命中 " + found.size + " 个")
        emit(found)
    }.flowOn(Dispatchers.IO).catch {
        Log.e(TAG, "应用宝查询失败。", it)
        emit(emptyList())
    }

    private fun toUpdate(app: AppInstalled, response: TencentResponse): AppUpdate? {
        if (response.ret != 0) return null
        val apk = response.records?.get(app.packageName)?.apk ?: return null
        if (apk.versionName.isBlank() || apk.url.isBlank()) return null
        // 版本护栏：只保留确实更高的版本（应用宝的 version_code 也可能与厂商自带方案不一致，
        // 因此版本号与 versionCode 都要过一遍）
        if (apk.versionCode <= app.versionCode) return null

        return AppUpdate(
            name = response.records[app.packageName]?.info?.name.orEmpty().ifBlank { app.name },
            packageName = app.packageName,
            version = apk.versionName,
            oldVersion = app.version,
            versionCode = apk.versionCode,
            oldVersionCode = app.versionCode,
            source = TencentSource,
            iconUri = "".toUri(),
            link = Link.Url(apk.url)
        )
    }
}
