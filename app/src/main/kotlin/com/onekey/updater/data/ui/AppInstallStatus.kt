package com.onekey.updater.data.ui

data class AppInstallStatus(
    val success: Boolean,
    val id: Int,
    val snack: Boolean = true,
    /** 失败原因等附加信息，供 UI 提示；成功时为 null。 */
    val message: String? = null
)
