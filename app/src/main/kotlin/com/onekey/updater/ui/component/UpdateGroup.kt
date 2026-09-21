package com.onekey.updater.ui.component

import com.onekey.updater.data.ui.AppUpdate
import com.onekey.updater.util.filterVersionTag
import io.github.g00fy2.versioncompare.Version

/**
 * 同一应用的多个来源候选归并为一组。
 *
 * === 为什么需要它 ===
 * 上游把每个来源的结果平铺成一个列表项，于是「同一个应用在 3 个来源都有更新」会显示成
 * 3 条独立记录（各自带自己的版本号和来源图标），列表又长又乱，用户还得自己判断该选哪条。
 * AppUpdate.id 里包含来源名，所以它们在列表里天然是不同条目。
 *
 * 这里按包名归并：一个应用只占一行，多个来源作为该行下的可切换选项。
 */

/**
 * 来源推荐优先级。数值越小越靠前，用于「候选版本相同」时的默认选择。
 * 大致按「签名可信度 + 版本权威性」排序：官方仓库 > 源码发布 > 第三方聚合站。
 */
private val SOURCE_PRIORITY = listOf(
    "GitHub",
    "F-Droid (Main)",
    "F-Droid (Izzy)",
    "GitLab",
    "Aptoide",
    "ApkPure",
    "Play",
    "ApkMirror"
)

/**
 * 候选排序：先比 versionCode（数值最可靠），再比版本号，最后比来源优先级。
 * 排第一的即为默认选中项。
 */
private val CANDIDATE_ORDER =
    compareByDescending<AppUpdate> { it.versionCode }
        .thenByDescending { runCatching { Version(filterVersionTag(it.version)) }.getOrNull() }
        .thenBy { SOURCE_PRIORITY.indexOf(it.source.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }

/** 把平铺的更新列表归并成「一个应用一组」，每组内部按推荐度排序。 */
fun List<AppUpdate>.groupByPackage(): List<List<AppUpdate>> = this
    .groupBy { it.packageName }
    .values
    .map { candidates -> candidates.sortedWith(CANDIDATE_ORDER) }
    .sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER) { group ->
            group.first().name.ifEmpty { group.first().packageName }
        }
    )

/** 同一组内所有候选的共同信息（已安装版本 / 应用名）。 */
fun List<AppUpdate>.installedVersion(): String = firstOrNull()?.oldVersion.orEmpty()
