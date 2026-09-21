package com.onekey.updater.repository

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.onekey.updater.data.gitlab.GitLabApps
import com.onekey.updater.data.gitlab.GitLabRelease
import com.onekey.updater.data.ui.AppInstalled
import com.onekey.updater.data.ui.AppUpdate
import com.onekey.updater.data.ui.GitLabSource
import com.onekey.updater.data.ui.Link
import com.onekey.updater.data.ui.getApp
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.service.GitLabService
import com.onekey.updater.util.filterVersionTag
import io.github.g00fy2.versioncompare.Version
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.time.Duration.Companion.milliseconds


class GitLabRepository(
    private val service: GitLabService,
    private val prefs: Prefs
) {

    suspend fun updates(apps: List<AppInstalled>) = flow {
        val checks = mutableListOf<Flow<List<AppUpdate>>>()
        GitLabApps.forEach { app ->
            apps.find { it.packageName == app.packageName }?.let {
                checks.add(checkApp(apps, app.user, app.repo, app.packageName, it.version, null))
            }
        }
        if (checks.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        // 与 UpdatesRepository / GitHubRepository 同样：并发 + 槽位增量 emit + 单仓库硬超时，
        // 避免一个卡住的仓库把整个 GitLab 源拖死。
        emitProgressively(checks) { emit(it) }
    }

    private suspend fun checkApp(
        apps: List<AppInstalled>?,
        user: String,
        repo: String,
        packageName: String,
        currentVersion: String,
        extra: Regex?
    ) = flow {
        val releases = service.getReleases(user, repo)
            .filter { Version(filterVersionTag(it.tag_name)) > Version(currentVersion) }

        if (releases.isNotEmpty()) {
            val app = apps?.getApp(packageName)
            emit(listOf(
                AppUpdate(
                name = repo,
                packageName = packageName,
                version = releases[0].tag_name,
                oldVersion = app?.version ?: "?",
                versionCode = 0L,
                oldVersionCode = app?.versionCode ?: 0L,
                source = GitLabSource,
                link = Link.Url(getApkUrl(packageName, releases[0])),
                whatsNew = releases[0].description,
                iconUri = if (apps == null) releases[0].author.avatar_url.toUri() else Uri.EMPTY
            )))
        } else {
            emit(emptyList())
        }
    }.catch {
        emit(emptyList())
        Log.e("GitLabRepository", "Error fetching releases for $packageName.", it)
    }

    suspend fun search(text: String) = flow {
        val checks = mutableListOf<Flow<List<AppUpdate>>>()

        GitLabApps.forEach { app ->
            if (app.repo.contains(text, true) || app.user.contains(text, true) || app.packageName.contains(text, true)) {
                checks.add(checkApp(null, app.user, app.repo, app.packageName, "?", null))
            }
        }

        if (checks.isEmpty()) {
            emit(Result.success(emptyList()))
            return@flow
        }
        emitProgressively(checks) { emit(Result.success(it)) }
    }.catch {
        emit(Result.failure(it))
        Log.e("GitLabRepository", "Error searching.", it)
    }

    private fun getApkUrl(
        packageName: String,
        release: GitLabRelease
    ): String {
        // TODO: Take into account arch
        val source = release.assets.sources.find { it.url.endsWith(".apk", true) }
        if (source != null) return source.url

        val link = release.assets.links.find { it.url.endsWith(".apk", true) }
        if (link != null) return link.url

        return ""
    }

}

private suspend inline fun emitProgressively(
    checks: List<Flow<List<AppUpdate>>>,
    crossinline emitMerged: suspend (List<AppUpdate>) -> Unit
) {
    val slots = arrayOfNulls<List<AppUpdate>>(checks.size)
    checks
        .mapIndexed { index, check -> check.withDeadline(20_000L).map { index to it } }
        .merge()
        .collect { (index, updates) ->
            slots[index] = updates
            val merged = slots.filterNotNull().flatten()
            if (merged.isNotEmpty() || slots.all { it != null }) emitMerged(merged)
        }
}

private fun Flow<List<AppUpdate>>.withDeadline(millis: Long) = this
    .timeout(millis.milliseconds)
    .catch { t ->
        if (t !is TimeoutCancellationException) Log.e("GitLabRepository", "仓库检查异常，已跳过。", t)
        emit(emptyList())
    }
