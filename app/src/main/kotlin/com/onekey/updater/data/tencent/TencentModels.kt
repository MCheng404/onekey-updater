package com.onekey.updater.data.tencent

import com.google.gson.annotations.SerializedName

/**
 * 腾讯应用宝（sj.qq.com）的 APK 信息接口数据模型。
 *
 * 接口：POST https://upage.html5.qq.com/wechat-apkinfo
 * body：{"packagename": "<包名>"}
 *
 * 实测结论（决定了下方的设计）：
 *  · **不支持批量**：把包名用逗号拼在一起会返回空记录（app_detail_records = {}），
 *    因此只能逐个包名请求；
 *  · 单请求约 200ms；返回的下载地址是 **http**（imtt.dd.qq.com 不支持 https 握手）。
 */
data class TencentRequest(val packagename: String)

data class TencentResponse(
    /** 0 表示成功。 */
    val ret: Int = -1,
    @SerializedName("app_detail_records")
    val records: Map<String, TencentRecord>? = null
)

data class TencentRecord(
    @SerializedName("app_info") val info: TencentAppInfo? = null,
    @SerializedName("apk_all_data") val apk: TencentApk? = null
)

data class TencentAppInfo(
    val name: String = "",
    @SerializedName("package_name") val packageName: String = "",
    val author: String = ""
)

data class TencentApk(
    @SerializedName("version_name") val versionName: String = "",
    @SerializedName("version_code") val versionCode: Long = 0L,
    val url: String = "",
    @SerializedName("size_byte") val sizeByte: Long = 0L,
    @SerializedName("apk_md5") val md5: String = ""
)
