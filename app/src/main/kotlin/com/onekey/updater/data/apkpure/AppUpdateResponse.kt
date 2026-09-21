package com.onekey.updater.data.apkpure

import android.net.Uri
import androidx.core.net.toUri
import com.onekey.updater.data.ui.ApkPureSource
import com.onekey.updater.data.ui.AppInstalled
import com.onekey.updater.data.ui.AppUpdate
import com.onekey.updater.data.ui.Link


data class AppUpdateResponse(
    val package_name: String,
    val version_code: Long,
    val version_name: String,
    val sign: List<String>,
    val whatsnew: String,
    val description_short: String,
    val label: String,
    val asset: AppUpdateResponseAsset,
    val icon: AppUpdateResponseIcon
)

fun AppUpdateResponse.toAppUpdate(
    app: AppInstalled?
) = AppUpdate(
    label,
    package_name,
    version_name,
    app?.version.orEmpty(),
    version_code,
    app?.versionCode ?: 0L,
    ApkPureSource,
    if (app == null) icon.thumbnail.url.toUri() else Uri.EMPTY,
    if (asset.url.contains("/XAPK")) Link.Xapk(asset.url.replace("http://", "https://")) else Link.Url(asset.url.replace("http://", "https://")),
    if (app == null) description_short else whatsnew
)
