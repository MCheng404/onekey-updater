package com.onekey.updater.domain

import com.onekey.updater.data.ui.AppUpdate
import com.onekey.updater.util.filterVersionTag
import io.github.g00fy2.versioncompare.Version

/**
 * 「同一个应用有多个来源候选」的业务规则。**唯一真相来源。**
 *
 * === 为什么要单独抽一层 ===
 * 之前这套规则被实现了两遍，而且**两遍不一致**：
 *  · 界面侧 `ui/component/UpdateGroup.kt` 用三级比较器
 *    （versionCode → 版本号 → 来源优先级）；
 *  · MCP 侧 `util/mcp/McpBridgeImpl.kt` 只按 `versionCode` 选推荐来源。
 * 结果就是同一个应用，**界面显示的推荐来源和 MCP 报告的推荐来源可能对不上** ——
 * 同一份数据有两个解释，任何"以界面为准"的排查都会被 MCP 的输出带偏。
 *
 * 这一层是纯 Kotlin（不依赖 Android 与 UI 框架），界面与 MCP 都可以直接用。
 * 以后调整推荐规则只改这里一处。
 */
object AppGrouping {

    /**
     * 来源推荐优先级，数值越小越靠前。
     *
     * 大致按「签名可信度 + 版本权威性」排序：官方仓库 / 源码发布 > 厂商商店 > 第三方聚合站。
     *
     * 注意：不在表中的来源会取到 [Int.MAX_VALUE]（排在最后），所以**新增来源时必须补进来**——
     * 之前加「腾讯应用宝」时就漏了，导致它即使版本最新也不会被选为推荐来源。
     */
    val SOURCE_PRIORITY: List<String> = listOf(
        "GitHub",
        "F-Droid (Main)",
        "F-Droid (Izzy)",
        "GitLab",
        "Tencent MyApp",
        "Xiaomi Store",
        "vivo App Store",
        "Play",
        "Aptoide",
        "ApkPure",
        "ApkMirror"
    )

    private fun priorityOf(sourceName: String): Int =
        SOURCE_PRIORITY.indexOf(sourceName).let { if (it < 0) Int.MAX_VALUE else it }

    /**
     * 候选排序：先比 versionCode（数值最可靠），再比版本号，最后比来源优先级。
     * 排第一的即为默认选中项。
     */
    val CANDIDATE_ORDER: Comparator<AppUpdate> =
        compareByDescending<AppUpdate> { it.versionCode }
            .thenByDescending { runCatching { Version(filterVersionTag(it.version)) }.getOrNull() }
            .thenBy { priorityOf(it.source.name) }

    /** 这组候选里应该默认选哪个（推荐来源）。候选为空时返回 null。 */
    fun recommended(candidates: List<AppUpdate>): AppUpdate? =
        candidates.minWithOrNull(CANDIDATE_ORDER)

    /**
     * 把平铺的更新列表归并成「一个应用一组」，每组内部按推荐度排序，组间按应用名排序。
     */
    fun group(updates: List<AppUpdate>): List<List<AppUpdate>> = updates
        .groupBy { it.packageName }
        .values
        .map { candidates -> candidates.sortedWith(CANDIDATE_ORDER) }
        .sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { group ->
                group.first().name.ifEmpty { group.first().packageName }
            }
        )
}
