package com.onekey.updater.repository

import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import android.util.Log
import com.onekey.updater.BuildConfig
import com.onekey.updater.data.github.GitHubApps
import com.onekey.updater.data.github.GitHubRelease
import com.onekey.updater.data.github.GitHubReleaseAsset
import com.onekey.updater.data.ui.AppInstalled
import com.onekey.updater.data.ui.AppUpdate
import com.onekey.updater.data.ui.GitHubSource
import com.onekey.updater.data.ui.Link
import com.onekey.updater.data.ui.getApp
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.service.GitHubService
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
import java.util.Scanner


class GitHubRepository(
    private val service: GitHubService,
    private val prefs: Prefs
) {

    companion object {
        private const val TAG = "GitHubRepository"

        /** 单个仓库的硬超时。 */
        private const val CHECK_DEADLINE_MS = 20_000L
    }

    /**
     * 已安装应用的 GitHub 更新检查。
     *
     * === 相对上游的修复 ===
     * 上游这里同样是 `checks.combine { ... }`：必须等**每一个**仓库的请求都返回才 emit，
     * 也就是说任意一个仓库卡住（慢网络、限流重试、仓库已删除）都会让整个 GitHub 源
     * 一条结果都出不来。这与 [UpdatesRepository] 的问题是同一个反模式。
     *
     * 改为并发 + 槽位增量 emit + 单仓库硬超时：某个仓库出问题只影响它自己。
     */
    suspend fun updates(apps: List<AppInstalled>) = flow {
        val checks = mutableListOf(selfCheck())

        GitHubApps.forEachIndexed { i, app ->
            if (i != 0) {
                apps.find { it.packageName == app.packageName }?.let {
                    checks.add(checkApp(apps, app.user, app.repo, app.packageName, it.version, app.extra))
                }
            }
        }

        if (checks.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        val slots = arrayOfNulls<List<AppUpdate>>(checks.size)
        checks
            .mapIndexed { index, check ->
                check.withDeadline(CHECK_DEADLINE_MS).map { index to it }
            }
            .merge()
            .collect { (index, updates) ->
                slots[index] = updates
                val merged = slots.filterNotNull().flatten()
                if (merged.isNotEmpty() || slots.all { it != null }) emit(merged)
            }
    }.catch {
        emit(emptyList())
        Log.e(TAG, "获取 release 失败。", it)
    }

    suspend fun search(text: String) = flow {
        val checks = mutableListOf<Flow<List<AppUpdate>>>()

        GitHubApps.forEach { app ->
            if (app.repo.contains(text, true) || app.user.contains(text, true) || app.packageName.contains(text, true)) {
                checks.add(checkApp(null, app.user, app.repo, app.packageName, "?", null))
            }
        }

        if (checks.isEmpty()) {
            emit(Result.success(emptyList()))
            return@flow
        }

        // 与 updates() 同样的改造：不再等所有仓库，谁先回来谁先显示
        val slots = arrayOfNulls<List<AppUpdate>>(checks.size)
        checks
            .mapIndexed { index, check ->
                check.withDeadline(CHECK_DEADLINE_MS).map { index to it }
            }
            .merge()
            .collect { (index, updates) ->
                slots[index] = updates
                val merged = slots.filterNotNull().flatten()
                if (merged.isNotEmpty() || slots.all { it != null }) emit(Result.success(merged))
            }
    }.catch {
        emit(Result.failure(it))
        Log.e(TAG, "搜索失败。", it)
    }

    private fun selfCheck() = flow {
        val releases = service.getReleases().filter { filterPreRelease(it) }
        val versions = getVersions(releases[0].name)

        if (versions.second > BuildConfig.VERSION_CODE.toLong()) {
            emit(listOf(AppUpdate(
                name = "APKUpdater",
                packageName = BuildConfig.APPLICATION_ID,
                version = versions.first,
                oldVersion = BuildConfig.VERSION_NAME,
                versionCode = versions.second,
                oldVersionCode = BuildConfig.VERSION_CODE.toLong(),
                source = GitHubSource,
                link = Link.Url(releases[0].assets[0].browser_download_url),
                whatsNew = releases[0].body
            )))
        } else {
            // We need to emit empty so it can be combined later
            emit(listOf())
        }
    }.catch {
        emit(emptyList())
        Log.e(TAG, "检查自身更新失败。", it)
    }

    private fun checkApp(
        apps: List<AppInstalled>?,
        user: String,
        repo: String,
        packageName: String,
        currentVersion: String,
        extra: Regex?
    ) = flow {
        val r = service.getReleases(user, repo)
        val releases = if (packageName == "com.onekey.updater.ci") {
            // TODO: Find a better way to do this
            r.filter { it.name.contains("CI-Release-3.x")}
        } else {
            r.filter { filterPreRelease(it) }.filter { findApkAsset(it.assets).isNotEmpty() }
        }

        Log.i(
            TAG,
            "检查 $packageName: 候选 release " + releases.size +
                (if (releases.isEmpty()) "" else "，最新 tag=" + releases[0].tag_name +
                    "，本机=" + currentVersion +
                    "，判定=" + (Version(filterVersionTag(releases[0].tag_name)) > Version(currentVersion)))
        )

        if (releases.isNotEmpty() && Version(filterVersionTag(releases[0].tag_name)) > Version(currentVersion)) {
            val app = apps?.getApp(packageName)
            emit(listOf(AppUpdate(
                name = repo,
                packageName = packageName,
                version = releases[0].tag_name,
                oldVersion = app?.version ?: "?",
                versionCode = 0L,
                oldVersionCode = app?.versionCode ?: 0L,
                source = GitHubSource,
                link = findApkAssetArch(releases[0].assets, extra).let { Link.Url(it.browser_download_url, it.size) },
                whatsNew = releases[0].body,
                iconUri = if (apps == null) releases[0].author.avatar_url.toUri() else Uri.EMPTY
            )))
        } else {
            emit(emptyList())
        }
    }.catch {
        emit(emptyList())
        Log.e(TAG, "检查 $packageName 失败。", it)
    }

    private fun getVersions(name: String) = runCatching {
        val scanner = Scanner(name)
        val version = scanner.next()
        val versionCode = scanner.next().trim('(', ')').toLong()
        Pair(version, versionCode)
    }.getOrDefault(Pair(name, 0L))

    private fun filterPreRelease(release: GitHubRelease) = when {
        prefs.ignorePreRelease.get() && release.prerelease -> false
        else -> true
    }

    private fun findApkAsset(assets: List<GitHubReleaseAsset>) = assets
        .filter { it.browser_download_url.endsWith(".apk", true) }
        .maxByOrNull { it.size }
        ?.browser_download_url
        .orEmpty()

    private fun findApkAssetArch(
        assets: List<GitHubReleaseAsset>,
        extra: Regex?
    ): GitHubReleaseAsset {
        val apks = assets
            .filter { it.browser_download_url.endsWith(".apk", true) }
            .filter { filterExtra(it, extra) }

        when {
            apks.isEmpty() -> return GitHubReleaseAsset(0L, "")
            apks.size == 1 -> return apks.first()
            else -> {
                // Try to match exact arch
                Build.SUPPORTED_ABIS.forEach { arch ->
                    apks.forEach { apk ->
                        if (apk.browser_download_url.contains(arch, true)) {
                            return apk
                        }
                    }
                }
                // Try to match arm64
                if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
                    apks.forEach { apk ->
                        if (apk.browser_download_url.contains("arm64", true)) {
                            return apk
                        }
                    }
                }
                // Try to match x64
                if (Build.SUPPORTED_ABIS.contains("x86_64")) {
                    apks.forEach { apk ->
                        if (apk.browser_download_url.contains("x64", true)) {
                            return apk
                        }
                    }
                }
                // Try to match arm
                if (Build.SUPPORTED_ABIS.contains("armeabi-v7a")) {
                    apks.forEach { apk ->
                        if (apk.browser_download_url.contains("arm", true)) {
                            return apk
                        }
                    }
                }
                // If no match, return biggest apk in the hope it's universal
                return apks.maxByOrNull { it.size } ?: GitHubReleaseAsset(0L, "")
            }
        }
    }

    private fun filterExtra(asset: GitHubReleaseAsset, extra: Regex?) = when(extra) {
        null -> true
        else -> asset.browser_download_url.matches(extra)
    }

}

/** 给单个仓库检查加硬超时：超时只让该仓库空手而归，不拖累其它仓库。 */
private fun Flow<List<AppUpdate>>.withDeadline(millis: Long) = this
    .timeout(millis.milliseconds)
    .catch { t ->
        if (t !is TimeoutCancellationException) Log.e("GitHubRepository", "仓库检查异常，已跳过。", t)
        emit(emptyList())
    }
