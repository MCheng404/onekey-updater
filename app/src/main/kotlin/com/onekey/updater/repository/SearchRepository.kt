package com.onekey.updater.repository

import android.util.Log
import com.onekey.updater.data.ui.AppUpdate
import com.onekey.updater.prefs.Prefs
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.timeout
import kotlin.time.Duration.Companion.milliseconds

/**
 * 跨源搜索。
 *
 * 与 [UpdatesRepository] 同样的改造：把 `combine`（等所有源）换成
 * 并发 + 增量 emit（谁先回来谁先显示），并给每个源加硬超时。
 */
class SearchRepository(
    private val apkMirrorRepository: ApkMirrorRepository,
    private val fdroidRepository: FdroidRepository,
    private val izzyRepository: FdroidRepository,
    private val aptoideRepository: AptoideRepository,
    private val gitHubRepository: GitHubRepository,
    private val apkPureRepository: ApkPureRepository,
    private val gitLabRepository: GitLabRepository,
    private val playRepository: PlayRepository,
    private val prefs: Prefs
) {

    companion object {
        private const val TAG = "SearchRepository"
        private const val SOURCE_DEADLINE_MS = 30_000L
    }

    fun search(text: String) = flow {
        val sources = mutableListOf<Flow<Result<List<AppUpdate>>>>()
        if (prefs.useApkMirror.get()) sources.add(apkMirrorRepository.search(text))
        if (prefs.useFdroid.get()) sources.add(fdroidRepository.search(text))
        if (prefs.useIzzy.get()) sources.add(izzyRepository.search(text))
        if (prefs.useAptoide.get()) sources.add(aptoideRepository.search(text))
        if (prefs.useGitHub.get()) sources.add(gitHubRepository.search(text))
        if (prefs.useApkPure.get()) sources.add(apkPureRepository.search(text))
        if (prefs.useGitLab.get()) sources.add(gitLabRepository.search(text))
        if (prefs.usePlay.get()) sources.add(playRepository.search(text))

        if (sources.isEmpty()) {
            emit(Result.success(emptyList()))
            return@flow
        }

        val slots = arrayOfNulls<List<AppUpdate>>(sources.size)
        var firstError: Throwable? = null

        sources
            .mapIndexed { index, source ->
                source.withDeadline(SOURCE_DEADLINE_MS).map { index to it }
            }
            .merge()
            .collect { (index, result) ->
                result.onSuccess { slots[index] = it }
                    .onFailure {
                        if (firstError == null) firstError = it
                        slots[index] = emptyList()
                    }

                val merged = slots.filterNotNull().flatten()
                    // **必须按包名去重**：同一个应用可能同时出现在多个来源里，
                    // 而界面用 AppUpdate.id 作 LazyColumn 的 key，重复 key 会让 Compose 直接抛异常。
                    // 这正是「结果非常多时才崩」的成因 —— 结果越多，撞上重复的概率越高。
                    // 保留哪一个：版本号更高的那个（更可能是可安装的最新版）。
                    .groupBy { it.packageName }
                    .map { (_, same) -> same.maxByOrNull { it.versionCode } ?: same.first() }
                    // 相关度排序：搜包名时，精确匹配必须排第一，否则用户要找的应用被埋在中间
                    .sortedWith(
                        compareByDescending<AppUpdate> { relevance(it, text) }
                            .thenBy { it.name.lowercase() }
                    )
                val settled = slots.all { it != null }
                if (merged.isNotEmpty() || settled) {
                    val error = firstError
                    if (merged.isEmpty() && error != null) {
                        emit(Result.failure(error))
                    } else {
                        emit(Result.success(merged))
                    }
                }
            }
    }.catch {
        emit(Result.failure(it))
        Log.e(TAG, "搜索失败。", it)
    }
}

private fun Flow<Result<List<AppUpdate>>>.withDeadline(millis: Long) = this
    .timeout(millis.milliseconds)
    .catch { t ->
        if (t !is TimeoutCancellationException) Log.e("SearchRepository", "源异常，已跳过。", t)
        emit(Result.success(emptyList()))
    }

/**
 * 搜索结果的相关度（越大越靠前）。
 *
 * 排序依据是「用户输入与结果的对应强度」，而不是来源顺序或名称字母序：
 * 搜 `com.tencent.mm` 时精确命中包名的结果必须排在最前，
 * 搜 `wechat` 时名称完全相等也要排在名称只是「包含」的结果之前。
 */
private fun relevance(update: AppUpdate, query: String): Int {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return 0
    val pkg = update.packageName.lowercase()
    val name = update.name.lowercase()
    return when {
        pkg == q -> 100          // 搜的正是这个包
        name.equals(q, true) -> 90
        pkg.startsWith(q) -> 80
        name.startsWith(q) -> 70
        pkg.contains(q) -> 60
        name.contains(q) -> 50
        else -> 0                 // 命中不了，排在所有有关联结果之后
    }
}
