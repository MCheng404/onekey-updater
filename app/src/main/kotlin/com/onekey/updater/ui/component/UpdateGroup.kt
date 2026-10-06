package com.onekey.updater.ui.component

import com.onekey.updater.data.ui.AppUpdate
import com.onekey.updater.domain.AppGrouping

/**
 * 同一应用的多个来源候选归并为一组。
 *
 * === 为什么需要它 ===
 * 上游把每个来源的结果平铺成一个列表项，于是「同一个应用在 3 个来源都有更新」会显示成
 * 3 条独立记录（各自带自己的版本号和来源图标），列表又长又乱，用户还得自己判断该选哪条。
 * AppUpdate.id 里包含来源名，所以它们在列表里天然是不同条目。
 *
 * 这里按包名归并：一个应用只占一行，多个来源作为该行下的可切换选项。
 *
 * === 规则去哪了 ===
 * 归并与「推荐哪个来源」的规则已抽到 [AppGrouping]（domain 层）。
 * 此前 UI 与 MCP 各写了一套且规则不一致，会导致同一应用在两边显示的推荐来源不同。
 */

/** 把平铺的更新列表归并成「一个应用一组」，每组内部按推荐度排序。 */
fun List<AppUpdate>.groupByPackage(): List<List<AppUpdate>> = AppGrouping.group(this)

/** 同一组内所有候选的共同信息（已安装版本 / 应用名）。 */
fun List<AppUpdate>.installedVersion(): String = firstOrNull()?.oldVersion.orEmpty()
