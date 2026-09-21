package com.onekey.updater.repository

import android.util.Log
import com.onekey.updater.data.ui.AppUpdate
import com.onekey.updater.data.ui.UpdateScan
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.util.filterVersionTag
import io.github.g00fy2.versioncompare.Version
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.timeout
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.catch


class UpdatesRepository(
    private val appsRepository: AppsRepository,
    private val apkMirrorRepository: ApkMirrorRepository,
    private val gitHubRepository: GitHubRepository,
    private val fdroidRepository: FdroidRepository,
    private val izzyRepository: FdroidRepository,
    private val aptoideRepository: AptoideRepository,
    private val apkPureRepository: ApkPureRepository,
    private val gitLabRepository: GitLabRepository,
    private val playRepository: PlayRepository,
    private val tencentRepository: TencentRepository,
    private val prefs: Prefs
) {

    /**
     * 渐进式更新扫描。
     *
     * === 相对上游的修复（这是「加载漫长」的主因） ===
     * 上游用的是 `sources.combine { ... }`。`combine` 必须等到**每一个** Flow 都发出至少一个值
     * 才会发出第一个结果，也就是整体耗时等于「最慢的那个源」——而 F-Droid / Izzy 每次都要
     * 下载 14 MB 的 index-v1.jar，Play 源还依赖 Google 登录。结果就是：GitHub 几百毫秒就拿到
     * 结果，用户却要盯着骨架屏等十几秒甚至更久。
     *
     * 现在改为并发执行 + 每有源返回就立刻增量发出：
     * 「槽位」数组记录每个源的最新结果，任何源一回来就重组并 emit，
     * 因此快的源先显示、慢的源后补齐，Play 挂掉也不影响其它源出结果。
     */
    companion object {
        private const val TAG = "UpdatesRepository"

        /** 单个源的硬超时。F-Droid 索引 14 MB，在移动网络下留足余量。 */
        private const val SOURCE_DEADLINE_MS = 60_000L
    }

    fun updates() = flow<UpdateScan> {
        // 扫描范围用专有偏好，而不是「应用」页的展示过滤 —— 否则排除商店应用后更新页会永远是空的。
        val systemIncluded = prefs.updateSystemApps.get()
        appsRepository.getApps(
            includeStoreApps = prefs.updateStoreApps.get(),
            includeSystemApps = systemIncluded
        ).collect { result ->
            result.onSuccess { apps ->
                val filtered = apps.filter { !it.ignored }

                val sources = mutableListOf<Flow<List<AppUpdate>>>()
                if (prefs.useApkMirror.get()) sources.add(apkMirrorRepository.updates(filtered))
                if (prefs.useGitHub.get()) sources.add(gitHubRepository.updates(filtered))
                if (prefs.useFdroid.get()) sources.add(fdroidRepository.updates(filtered))
                if (prefs.useIzzy.get()) sources.add(izzyRepository.updates(filtered))
                if (prefs.useAptoide.get()) sources.add(aptoideRepository.updates(filtered))
                if (prefs.useApkPure.get()) sources.add(apkPureRepository.updates(filtered))
                if (prefs.useGitLab.get()) sources.add(gitLabRepository.updates(filtered))
                if (prefs.usePlay.get()) sources.add(playRepository.updates(filtered))
                if (prefs.useTencent.get()) sources.add(tencentRepository.updates(filtered))

                if (sources.isEmpty()) {
                    emit(UpdateScan(emptyList(), filtered.size, systemIncluded))
                    return@onSuccess
                }

                // 每个源占据固定槽位；后到的结果覆盖该槽位，避免重复累加。
                // 每个源都加硬超时：任一源挂死都不会让整条 Flow 永不结束
                //（否则下拉刷新动画会一直转，且界面永远停在骨架屏）。
                val slots = arrayOfNulls<List<AppUpdate>>(sources.size)
                sources
                    .mapIndexed { index, source ->
                        source.withDeadline(SOURCE_DEADLINE_MS).map { index to it }
                    }
                    .merge()
                    .collect { (index, updates) ->
                        val kept = updates.filter { it.isRealUpgrade() }
                        // 分源计数是排查"为什么没有更新"的第一手信息，
                        // 同时把被版本护栏拦下的条目打出来，便于用户判断是否误判。
                        val dropped = updates.size - kept.size
                        Log.i(
                            TAG,
                            "源[$index/${sources.size - 1}] 返回 " + updates.size +
                                " 条，保留 " + kept.size +
                                if (dropped > 0) "（版本护栏拦下 " + dropped + " 条）" else ""
                        )
                        slots[index] = kept
                        val merged = slots.filterNotNull().flatten()
                        // 首个来源可能是空结果，此时先不要 emit，
                        // 否则会闪一下「暂无可用更新」再被后续结果覆盖。
                        val settled = slots.all { it != null }
                        if (merged.isNotEmpty() || settled) {
                            emit(UpdateScan(merged, filtered.size, systemIncluded))
                        }
                    }
            }.onFailure {
                Log.e("UpdatesRepository", "读取已安装应用失败。", it)
            }
        }
    }.catch {
        Log.e("UpdatesRepository", "扫描更新失败。", it)
    }

}

/**
 * 给单个数据源加硬超时。
 * 超时不是错误——该源只是「没有结果」，不应影响其它源正常出数据。
 */
private fun Flow<List<AppUpdate>>.withDeadline(millis: Long) = this
    .timeout(millis.milliseconds)
    .catch { t ->
        if (t is TimeoutCancellationException) {
            Log.w("UpdatesRepository", "数据源超时（${millis}ms），已跳过。")
        } else {
            Log.e("UpdatesRepository", "数据源异常，已跳过。", t)
        }
        emit(emptyList())
    }

/**
 * 版本护栏：只有真正「更高版本」才作为更新展示。
 *
 * 背景：APKPure 这类源只按包名+versionCode 查询，返回的"最新版本"并不保证比本机高。
 * 实测 TikTok(Asia) 出现 `46.9.3 → 37.5.22`：服务端 `version_code` 反而比本机大，
 * 因此单看 versionCode 会把降级放行。上游完全没有这道校验，用户点下去就是把应用装回旧版。
 *
 * 判定优先级（关键：以用户可见的**版本名**为准，versionCode 只用于打破平局）：
 *   · 版本名明确降级            -> 丢弃
 *   · 版本名明确升级            -> 保留
 *   · 版本名相同（F-Droid 常见，仅 versionCode 递增）-> 用 versionCode 判断
 *   · 版本名不可比（无法解析）  -> 用 versionCode 判断，拿不到就保留
 * 无法判定时一律保留：宁可多提示，不可漏更新。
 */
private fun AppUpdate.isRealUpgrade(): Boolean {
    val old = oldVersion.trim()
    if (old.isEmpty() || old == "?") return true

    val byCode = if (versionCode > 0L && oldVersionCode > 0L) {
        versionCode > oldVersionCode
    } else {
        null
    }

    return when (compareVersionNames(version, old)) {
        1 -> true
        -1 -> false
        0 -> byCode ?: true
        else -> byCode ?: true
    }
}

/**
 * 比较两个版本名：1 表示 current 更新，-1 表示更旧，0 表示相同，null 表示无法比较。
 */
private fun compareVersionNames(current: String, old: String): Int? {
    if (current.isBlank() || old.isBlank()) return null
    return runCatching {
        Version(filterVersionTag(current)).compareTo(Version(filterVersionTag(old)))
    }.getOrNull()
}
