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

                val merged = slots.filterNotNull().flatten().sortedBy { it.name }
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
