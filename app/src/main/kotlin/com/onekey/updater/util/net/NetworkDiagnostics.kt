package com.onekey.updater.util.net

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 线路连通性诊断。
 *
 * 国内网络下「哪个源能用」是随时变化的，写死任何一个地址都会在某天失效。
 * 因此这里把每条线路做成可实测的探针，用户可以在设置里一键跑一遍并据此切换。
 *
 * 注意：诊断必须走**不带镜像拦截器**的裸 client，否则测的就不是真实线路。
 */
class NetworkDiagnostics(private val plainClient: OkHttpClient) {

    enum class Kind { GITHUB, FDROID, SOURCE }

    data class Probe(val label: String, val url: String, val kind: Kind)

    data class Result(
        val label: String,
        val ok: Boolean,
        val millis: Long,
        val detail: String,
        val kind: Kind,
        /** 该结果对应的下拉选项下标；-1 表示不可作为线路切换目标。 */
        val optionIndex: Int = -1
    )

    companion object {
        /**
         * 单个探针的超时。
         *
         * 原为 8 秒 —— 那时 GitHub 只有 4 条线路，无所谓。接入 78 条之后，
         * 最坏情况变成 88 个探针 × 8 秒 ÷ 并发，实测要等约 60 秒。
         * 这里降到 5 秒：超过 5 秒还没响应的线路本来也不值得选，
         * 用它换整体等待时间减半是划算的。
         */
        private const val PROBE_TIMEOUT_MS = 5_000
        private const val UA = "APKUpdater"

        /**
         * 诊断并发上限。GitHub 节点现在有 78+ 条，若一次性全开，既吃用户流量也拖慢整体耗时。
         * 这里限制同时最多 8 个探针在飞，其余排队；既能快速给出「最快可用线路」，又不至于打爆网络。
         */
        /** 并发上限。从 8 提到 16：探针都是轻量请求，数量却从 4 条涨到 88 条。 */
        private const val MAX_CONCURRENCY = 16

        private const val GITHUB_API = "https://api.github.com/repos/rumboalla/apkupdater/releases/latest"

        private val SOURCES = listOf(
            Probe("F-Droid 官方", "https://f-droid.org/repo/index-v1.jar", Kind.FDROID),
            Probe("APKPure", "https://tapi.pureapk.com/", Kind.SOURCE),
            Probe("Aptoide", "https://ws75.aptoide.com/api/7/", Kind.SOURCE),
            Probe("APKMirror", "https://www.apkmirror.com/", Kind.SOURCE),
            Probe("GitLab", "https://gitlab.com/api/v4/projects", Kind.SOURCE),
            Probe("腾讯应用宝", "https://upage.html5.qq.com/wechat-apkinfo", Kind.SOURCE),
            Probe("GitHub 直连", GITHUB_API, Kind.GITHUB)
        )
    }

    /** 逐条构建探针（含用户自定义线路）。 */
    private fun buildProbes(customPrefix: String): List<Pair<Probe, Int>> {
        val list = mutableListOf<Pair<Probe, Int>>()
        Mirrors.githubProxies.forEachIndexed { index, option ->
            if (option.value == Mirrors.CUSTOM) {
                if (customPrefix.isNotBlank()) {
                    val prefix = if (customPrefix.endsWith("/")) customPrefix else "$customPrefix/"
                    list += Probe("自定义加速", "$prefix$GITHUB_API", Kind.GITHUB) to index
                }
            } else if (option.value.isNotEmpty()) {
                list += Probe(option.label, "${option.value}$GITHUB_API", Kind.GITHUB) to index
            }
        }
        Mirrors.fdroidMirrors.forEachIndexed { index, option ->
            if (option.value == Mirrors.CUSTOM) return@forEachIndexed
            if (option.value.isEmpty()) return@forEachIndexed
            list += Probe(option.label, "${option.value}index-v1.jar", Kind.FDROID) to index
        }
        SOURCES.forEach { list += it to -1 }
        return list
    }

    suspend fun run(customGithubPrefix: String): List<Result> = coroutineScope {
        val probes = buildProbes(customGithubPrefix)
        val semaphore = Semaphore(MAX_CONCURRENCY)
        probes
            .map { (probe, index) ->
                async(Dispatchers.IO) { semaphore.withPermit { probe(probe, index) } }
            }
            .awaitAll()
    }

    private suspend fun probe(probe: Probe, optionIndex: Int): Result = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        try {
            val request = Request.Builder()
                .url(probe.url)
                .header("User-Agent", UA)
                // 只取头两个字节：F-Droid 索引有 14 MB，全量拉取会把诊断变成一次下载
                .header("Range", "bytes=0-1")
                .build()

            plainClient.newBuilder()
                .callTimeout(PROBE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    val millis = SystemClock.elapsedRealtime() - started
                    val rateLimited = response.code == 403 &&
                        response.header("x-ratelimit-remaining") == "0"
                    when {
                        rateLimited -> Result(
                            probe.label, false, millis,
                            "被限流", probe.kind, optionIndex
                        )
                        response.isSuccessful || response.code == 206 -> Result(
                            probe.label, true, millis, "HTTP ${response.code}", probe.kind, optionIndex
                        )
                        // 第三方源探测的是站点根路径，返回 404/405 恰恰说明「能连通」。
                        // 若按 isSuccessful 判定，APKPure、Aptoide 会被误报成不可用（实测如此）。
                        probe.kind == Kind.SOURCE -> Result(
                            probe.label, true, millis, "HTTP ${response.code}", probe.kind, optionIndex
                        )
                        else -> Result(
                            probe.label, false, millis, "HTTP ${response.code}", probe.kind, optionIndex
                        )
                    }
                }
        } catch (t: Throwable) {
            Result(
                probe.label, false,
                SystemClock.elapsedRealtime() - started,
                t.javaClass.simpleName, probe.kind, optionIndex
            )
        }
    }
}
