package com.onekey.updater.data.ui

import com.aurora.gplayapi.data.models.PlayFile


sealed class Link {
    data object Empty: Link()
    /**
     * 直链下载。
     *
     * @param referer 部分 CDN 开启防盗链，缺少 Referer 会返回一段 JS 而不是文件
     *        （实测 imtt2.dd.qq.com 就是如此，带上 `https://sj.qq.com/` 才给 APK）。
     */
    data class Url(val link: String, val size: Long = 0L, val referer: String = ""): Link()
    data class Xapk(val link: String, val referer: String = ""): Link()
    data class Play(val getInstallFiles: () -> List<PlayFile>): Link()
}
