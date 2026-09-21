package com.onekey.updater.util.net

import android.util.Log
import com.onekey.updater.prefs.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * GitHub 加速节点的远程刷新与严格校验。
 *
 * 内置 78 条公益节点（见 [Mirrors]）是**离线兜底**；本类负责在运行时从聚合源拉取**最新**清单，
 * 经过严格校验后替换 [Mirrors.githubProxies]，并缓存到 Prefs，避免只用一份会过时的内置清单。
 *
 * 为什么需要它：聚合站节点随时上下线，硬编码清单迟早失效；但又绝不能「一键打 78 个节点」当探针，
 * 所以刷新只在用户主动触发（设置页「刷新加速节点」）时发生，且单发请求、轻量校验。
 *
 * 校验规则（宁缺毋滥，一条坏链接会让用户某个请求走丢）：
 *   · 必须是 https；
 *   · 必须能解析出合法 host；
 *   · 去重（同前缀只留一条）；
 *   · 剔除明显无效项（空串、非 https、host 为空、格式非法）。
 */
object NodeRefresher {

    private const val TAG = "NodeRefresher"

    /** 默认聚合源：github-fast 仓库的 DEFAULT_NODES（采集自 github.akams.cn）。 */
    const val DEFAULT_SOURCE =
        "https://raw.githubusercontent.com/lopinnn56/github-fast/main/src/lib/nodes-core.js"

    /** 单发请求即可，不必并发；但解析到的节点若用于诊断，诊断自身已做并发上限。 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class RefreshResult(
        val ok: Boolean,
        val count: Int,
        val source: String,
        val detail: String
    )

    /**
     * 从远程源拉取并刷新节点。
     * 成功：校验后写入 Prefs 并更新 [Mirrors.githubProxies]。
     * 失败（网络/解析/无有效节点）：保留现有清单（回退内置或上次缓存），返回 ok=false。
     *
     * @param prefs  用于缓存刷新结果（新增偏好项 githubNodesRemote）
     * @param sourceUrl 聚合源地址，默认 [DEFAULT_SOURCE]
     */
    suspend fun refresh(
        prefs: Prefs,
        sourceUrl: String = DEFAULT_SOURCE
    ): RefreshResult = withContext(Dispatchers.IO) {
        runCatching {
            val raw = fetchRaw(sourceUrl)
            val prefixes = parsePrefixes(raw)
            val validated = validate(prefixes)
            if (validated.isEmpty()) {
                Log.w(TAG, "远程清单解析后无有效节点，保留现有清单（回退内置）。")
                return@withContext RefreshResult(false, 0, sourceUrl, "无有效节点")
            }
            prefs.githubNodesRemote.put(validated)
            Mirrors.applyRemote(validated.map { Mirrors.optionFromPrefix(it) })
            Log.i(TAG, "远程节点刷新成功：${validated.size} 条（源=$sourceUrl）")
            RefreshResult(true, validated.size, sourceUrl, "已更新 ${validated.size} 条")
        }.getOrElse { e ->
            Log.w(TAG, "远程节点刷新失败，保留现有清单（回退内置）。", e)
            RefreshResult(false, 0, sourceUrl, e.message ?: e.javaClass.simpleName)
        }
    }

    /** 启动时把上次缓存的节点恢复进内存（跨进程重启生效）。 */
    fun applyCached(prefs: Prefs) {
        val cached = prefs.githubNodesRemote.get()
        if (cached.isNotEmpty()) {
            Mirrors.applyRemote(cached.map { Mirrors.optionFromPrefix(it) })
        }
    }

    /** 拉取原始文本（聚合源是 ES module 的 .js 文件）。 */
    private fun fetchRaw(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "APKUpdater")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    /**
     * 从 nodes-core.js 的 DEFAULT_NODES 数组里抽取 prefix。
     * 该文件形如 `export const DEFAULT_NODES = [ { name: 'x', prefix: 'https://.../', mode: 'prefix' }, ... ]`，
     * 直接用正则抓 `prefix: '...'` / `prefix: "..."` 即可，不必引入 JS 运行时。
     */
    private fun parsePrefixes(raw: String): List<String> {
        val prefixes = mutableListOf<String>()
        val regex = Regex("""prefix\s*:\s*['"](https?://[^'"]+)['"]""")
        regex.findAll(raw).forEach { m ->
            val url = m.groupValues[1]
            prefixes.add(if (url.endsWith("/")) url else "$url/")
        }
        return prefixes
    }

    /**
     * 严格校验：https、可解析 host、去重、剔除无效项。
     * 不在这里做连通性测试（那是网络诊断的职责，且受并发上限保护）；这里只保证链接本身合法。
     */
    fun validate(raw: List<String>): List<String> {
        val seen = LinkedHashSet<String>() // 保留顺序并去重
        for (candidate in raw) {
            val url = candidate.trim()
            if (url.isEmpty()) continue
            runCatching {
                val u = URL(url)
                // 国内加速节点绝大多数支持 https，且更安全——非 https 直接丢弃
                if (u.protocol != "https") return@runCatching
                val host = u.host
                if (host.isEmpty()) return@runCatching
                val normalized =
                    "https://$host${if (u.path.endsWith("/")) u.path else u.path + "/"}"
                seen.add(normalized.lowercase(Locale.ROOT))
            }.onFailure {
                // 格式非法（比如写死的坏域名），丢弃
                Log.v(TAG, "丢弃非法节点: $url")
            }
        }
        return seen.toList()
    }
}
