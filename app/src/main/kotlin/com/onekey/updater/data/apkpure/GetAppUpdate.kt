package com.onekey.updater.data.apkpure

import kotlin.random.Random


data class GetAppUpdate(
    val app_info_for_update: List<AppInfoForUpdate> = emptyList(),
    val android_id: String = Random.nextLong().toString(16),
    val application_id: String = "com.apkpure.aegon",
    val cached_size: Long = -1
)
