package com.onekey.updater.util.net

import android.util.Log
import com.onekey.updater.prefs.Prefs
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 把「官方地址」解析为当前镜像配置下的「实际请求地址」。
 *
 * 设计要点：镜像切换发生在**网络层**而不是各个 Repository / Service 里，
 * 这样 Retrofit 接口、Downloader、Coil 图片加载三条链路自动统一生效，
 * 新增数据源时也无需再关心镜像逻辑。
 */
class MirrorResolver(private val prefs: Prefs) {

    companion object {
        private const val TAG = "MirrorResolver"
        const val OFFICIAL_FDROID = "https://f-droid.org/repo/"
        const val OFFICIAL_IZZY = "https://apt.izzysoft.de/fdroid/repo/"
        private const val API_HOST = "api.github.com"
    }

    init {
        // 启动时把上次刷新缓存的 GitHub 节点恢复进内存，使远程节点跨进程重启生效。
        // 放在这里而非 DI/App：镜像解析器在 Koin 构建时即创建，prefs 此时已就绪，且本文件归属后端独占。
        NodeRefresher.applyCached(prefs)
    }

    fun resolve(url: String): String {
        // 先按主机名快速排除：绝大多数请求（图片、第三方源）不属于任何镜像域，
        // 没必要为它们读取偏好项。
        val isFdroid = url.startsWith(OFFICIAL_FDROID)
        val isIzzy = url.startsWith(OFFICIAL_IZZY)
        val host = if (!isFdroid && !isIzzy) hostOf(url) else null
        val isGithub = host != null && host in Mirrors.GITHUB_HOSTS
        if (!isFdroid && !isIzzy && !isGithub) return url

        if (!prefs.useChinaMirror.get()) return url

        return runCatching {
            when {
                isFdroid -> fdroidBase().takeIf { it.isNotEmpty() }
                    ?.let { it + url.removePrefix(OFFICIAL_FDROID) } ?: url

                isIzzy -> izzyBase().takeIf { it.isNotEmpty() }
                    ?.let { it + url.removePrefix(OFFICIAL_IZZY) } ?: url

                else -> {
                    // 附件下载可以被单独关闭（有些线路只擅长小请求）
                    if (host != API_HOST && !prefs.githubProxyDownloads.get()) return@runCatching url
                    githubPrefix().takeIf { it.isNotEmpty() }?.let { it + url } ?: url
                }
            }
        }.getOrElse {
            Log.w(TAG, "镜像地址解析失败，回退直连: $url", it)
            url
        }
    }

    /**
     * 备用 GitHub 加速前缀：按顺序返回第 [attempt] 条与 [currentResolved] 不同的候选线路。
     *
     * 之所以要能试多条：节点质量参差不齐，第一条备用线路同样可能不支持 API，
     * 只试一条等于把「换线路」变成了抽奖。
     */
    fun failoverGithubPrefix(currentResolved: String, attempt: Int = 0): String {
        val candidates = Mirrors.githubProxies
            .asSequence()
            .map { it.value }
            .filter { it.isNotEmpty() && it != Mirrors.CUSTOM }
            .map { if (it.endsWith("/")) it else "$it/" }
            .filter { !currentResolved.startsWith(it) }
            .toList()
        return candidates.getOrNull(attempt).orEmpty()
    }

    /** 当前生效的 GitHub 加速前缀；无则返回空串。 */
    fun githubPrefix(): String = normalize(selected(Mirrors.githubProxies, prefs.githubProxyId.get()) {
        prefs.githubCustomProxy.get()
    })

    /** 当前生效的 F-Droid 仓库根地址；使用官方源时返回空串（表示无需改写）。 */
    fun fdroidBase(): String = normalize(
        selected(Mirrors.fdroidMirrors, prefs.fdroidMirrorId.get()) { prefs.fdroidCustomUrl.get() }
    ).takeIf { it.isNotEmpty() && it != OFFICIAL_FDROID }.orEmpty()

    /** 当前生效的 IzzyOnDroid 仓库根地址；官方源返回空串。 */
    fun izzyBase(): String = normalize(
        selected(Mirrors.izzyMirrors, prefs.izzyMirrorId.get()) { prefs.izzyCustomUrl.get() }
    ).takeIf { it.isNotEmpty() && it != OFFICIAL_IZZY }.orEmpty()

    private fun selected(options: List<MirrorOption>, index: Int, custom: () -> String): String {
        val option = options.getOrNull(index) ?: return ""
        return if (option.value == Mirrors.CUSTOM) custom().trim() else option.value
    }

    private fun normalize(value: String): String = when {
        value.isEmpty() -> ""
        value.endsWith("/") -> value
        else -> "$value/"
    }

    private fun hostOf(url: String): String? = runCatching {
        url.substringAfter("://").substringBefore('/').substringBefore(':')
    }.getOrNull()
}

/**
 * 应用镜像解析的 OkHttp 拦截器。
 *
 * 除了改写地址，还负责一层次要但关键的容错：**GitHub API 未认证限流自动换线路**。
 *
 * 原因：GitHubRepository 按「已安装应用」逐个仓库查询 release，一次扫描就是十几次 API 请求，
 * 而未认证配额只有 60 次/小时/IP，且移动网络的出口 IP 往往是与他人共享的（运营商 CGNAT）。
 * 实测在 5G 下直连 api.github.com 稳定返回 403，导致整个 GitHub 源静默失效。
 * 因为加速线路走的是对方出口 IP，等于换了一份配额，所以这里在命中限流时自动重试一次。
 */
class MirrorInterceptor(prefs: Prefs) : Interceptor {

    companion object {
        /** 备用 GitHub 线路最多再试几条。 */
        private const val MAX_GITHUB_FAILOVER = 2
    }

    private val resolver = MirrorResolver(prefs)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val original = request.url.toString()
        val resolved = resolver.resolve(original)

        val response = runCatching {
            if (resolved == original) {
                chain.proceed(request)
            } else {
                chain.proceed(request.newBuilder().url(resolved).build())
            }
        }.getOrElse {
            Log.w("MirrorInterceptor", "改写后的地址不可用，回退直连: $resolved", it)
            chain.proceed(request)
        }

        if (!isGithubApiFailure(response, original)) return response

        // 换线路重试，最多再试 2 条：单个节点的 API 兼容性不可控，只试一条等于抽奖。
        var last = response
        for (attempt in 0 until MAX_GITHUB_FAILOVER) {
            val fallback = resolver.failoverGithubPrefix(resolved, attempt)
            if (fallback.isEmpty()) break
            Log.w("MirrorInterceptor", "GitHub API 线路不可用（HTTP " + last.code + "），换 $fallback 重试。")
            last.close()
            val retried = runCatching {
                chain.proceed(request.newBuilder().url(fallback + original).build())
            }.getOrElse {
                Log.e("MirrorInterceptor", "线路 $fallback 请求抛出异常。", it)
                return chain.proceed(request)
            }
            if (!isGithubApiFailure(retried, original)) return retried
            last = retried
        }
        return last
    }

    /**
     * 该响应是否说明「这条 GitHub API 线路不可用」。
     *
     * 原先只认「403 + x-ratelimit-remaining: 0」这一种限流特征，但这太窄：
     * 实测很多加速节点**根本不支持 api.github.com**（例如 ghproxy.net 直接回 403，
     * 且不带任何限流响应头），于是自动切换不触发，整个 GitHub 源静默返回 0 条。
     * 现在把 api.github.com 上的 403 与 5xx 一律视为「换一条线路」的信号。
     */
    private fun isGithubApiFailure(response: Response, originalUrl: String): Boolean {
        // 必须用**原始** URL 判断，而不是 response.request.url：
        // 走到这里时请求已经被改写成 "https://<代理>//https://api.github.com/..."，
        // 其 host 是代理域名。早先按 response.request.url.host 判断，导致配了代理之后
        // 这个条件永远不成立、自动切换从不触发（实测 GitHub 源整源返回 0 条）。
        if (!originalUrl.contains("api.github.com")) return false
        return response.code == 403 || response.code >= 500
    }
}
