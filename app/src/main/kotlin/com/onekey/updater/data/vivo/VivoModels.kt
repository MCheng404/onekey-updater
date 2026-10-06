package com.onekey.updater.data.vivo

import com.google.gson.annotations.SerializedName

/**
 * vivo 应用商店更新检查响应。
 *
 * 端点：POST https://update.appstore.vivo.com.cn/port/packages_update/
 *
 * ### 三个必须记住的坑
 * 1. **必须自己判 [code]**。vivo 的客户端代码不校验它、失败时静默返回空数组 ——
 *    我们若照抄，会把「请求被拒」误判成「没有更新」。
 * 2. **[versionCode] 可能是字符串**（如 `"400601"`），解析要兼容两种形态。
 * 3. [size] 单位是 **KB**，不是字节。
 */
data class VivoUpdateResponse(
    /** 0 = 成功。**必须自行判断**，见类注释。 */
    @SerializedName("code") val code: Int = -1,
    @SerializedName("value") val value: List<VivoApp> = emptyList()
)

data class VivoApp(
    @SerializedName("id") val id: Long = 0L,
    /** 为空则整条丢弃。 */
    @SerializedName("package_name") val packageName: String = "",
    @SerializedName("title_zh") val title: String = "",
    @SerializedName("version_name") val versionName: String = "",
    /** 可能是字符串或数字。 */
    @SerializedName("version_code") val versionCode: Any? = null,
    /** 302 入口，最终 CDN 可能是明文 http，需自行升级为 https。 */
    @SerializedName("download_url") val downloadUrl: String = "",
    /** 单位 KB。 */
    @SerializedName("size") val size: Long = 0L,
    @SerializedName("icon_url") val iconUrl: String = "",
    @SerializedName("update_des") val updateDescription: String = "",
    /** 差分描述符串（逗号分隔 token）。vivo 暂不解析，保留以便将来接差分。 */
    @SerializedName("sfPatchs") val sfPatches: String = "",
    @SerializedName("originalMd5") val originalMd5: String = "",
    @SerializedName("md5") val md5: String = ""
)
