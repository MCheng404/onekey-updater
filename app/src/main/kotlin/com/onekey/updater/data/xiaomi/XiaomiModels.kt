package com.onekey.updater.data.xiaomi

import com.google.gson.annotations.SerializedName

/**
 * 腾讯无、这里是**小米应用商店**更新检查接口的响应模型。
 *
 * 端点：POST https://updateinfo.market.xiaomi.com/apm/updateinfo/v2?{参数}
 *  —— **必须是 POST**，用 GET 会一直返回 errCode=1「不支持的接口」，
 *    这个错误码极具误导性（看起来像"要签名"，其实只是方法不对）。
 *
 * 实测要点（都是踩过的坑）：
 *  · 该端点**不需要签名**；
 *  · **必须从设备本身发起请求**。同样的参数从 PC 发起会拿到降级数据
 *    （apkSize 返回 661219883 这种假值、listApp 为空、并带「接口刷量」风控提示），
 *    从设备发起才拿得到真实的 apkSize / appId / 版本号；
 *  · 参数里必须带 `model` / `device` / `os` / `sdk`，否则返回 errCode=4「参数不能为空」；
 *  · 是**批量**接口（包名与版本号都逗号分隔），比应用宝的逐包查询好得多。
 */
data class XiaomiUpdateResponse(
    /**
     * 业务错误码。**必须判断**，否则风控会被静默当成「没有更新」。
     *
     * 小米在被拦时返回的是 **HTTP 200 + 业务错误**：
     * `{"errDesc":"接口刷量并不会影响推荐算法…","errCode":1}`。
     * 若不判 `errCode`，这个响应会被解析成「listApp 为空」，
     * 表现就是"扫完了但一条更新都没有"，完全看不出是被拦了。
     * 已实测到的错误码：1 = 不支持的接口（常见于用错 HTTP 方法）、
     * 4 = 参数不能为空（缺 model/device/os/sdk）。
     */
    @SerializedName("errCode") val errCode: Int = 0,
    @SerializedName("errDesc") val errDesc: String = "",
    /** 商店里没有的应用，可用来过滤。 */
    @SerializedName("invalidPackage") val invalidPackages: List<String> = emptyList(),
    /** 支持 64 位的应用。 */
    @SerializedName("support64Pkgs") val support64Packages: List<String> = emptyList(),
    /** 有更新的条目。传当前版本号时会是空数组——那是正确的「已是最新」，不是故障。 */
    @SerializedName("listApp") val listApp: List<XiaomiApp> = emptyList(),
    @SerializedName("serverTimestamp") val serverTimestamp: Long = 0L
)

data class XiaomiApp(
    @SerializedName("packageName") val packageName: String = "",
    @SerializedName("appId") val appId: Long = 0L,
    @SerializedName("versionCode") val versionCode: Long = 0L,
    @SerializedName("versionName") val versionName: String = "",
    @SerializedName("displayName") val displayName: String = "",
    @SerializedName("apkSize") val apkSize: Long = 0L,
    @SerializedName("diffFileSize") val diffFileSize: Long = 0L,
    /**
     * 服务端持有的那个版本的哈希（32 位十六进制）。
     * 实测：只有当本机已装版本就是从小米商店装的（即其哈希与此一致）时才会给差分，
     * 所以**增量更新只对从小米商店安装的应用有效**。
     */
    @SerializedName("releaseKeyHash") val releaseKeyHash: String = "",
    @SerializedName("changeLog") val changeLog: String = "",
    @SerializedName("briefShow") val briefShow: String = "",
    @SerializedName("developer") val developer: String = ""
)
