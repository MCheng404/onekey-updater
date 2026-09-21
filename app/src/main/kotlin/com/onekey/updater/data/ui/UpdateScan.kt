package com.onekey.updater.data.ui

/**
 * 一次更新扫描的结果。
 *
 * 除了更新条目，还带上**实际参与检查的应用数**。
 * 上游只向上层传 List<AppUpdate>，于是「列表为空」既可能是真的没有更新，
 * 也可能是过滤/数据源问题，界面无从区分，用户只能认为「功能坏了」。
 */
data class UpdateScan(
    val updates: List<AppUpdate>,
    /** 本次真正参与检查的应用数量。 */
    val scannedApps: Int = 0,

    /**
     * 本次扫描是否已把系统应用纳入检查。
     *
     * 必须由数据流带上来，而不是在界面里直接读 `prefs.updateSystemApps` ——
     * 偏好不是可观察状态，在组合期直接读它会拼出「设置已切换但组合未失效」的错配，
     * 而它决定的又是列表结构（提示行 ↔ 卡片列表），实测会导致卡片被测量成异常高度。
     */
    val systemAppsIncluded: Boolean = false
)
