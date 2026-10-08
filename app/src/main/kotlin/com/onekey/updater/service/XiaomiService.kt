package com.onekey.updater.service

import com.onekey.updater.data.xiaomi.XiaomiUpdateResponse
import retrofit2.http.POST
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded

interface XiaomiService {

    /**
     * 小米应用商店的更新检查。
     *
     * 注意是 **POST**（参数仍放在 query 上）。用 GET 会得到 errCode=1「不支持的接口」，
     * 那个错误信息会让人误以为缺签名。
     *
     * 该端点实测**不需要签名**。
     */
    /**
     * 注意：这里用 `@Url` 接收**已签名**的完整 URL，而不是 `@QueryMap`。
     * 小米要求 URL 尾部带 `_n`/`_s`/`_v`，签名必须覆盖最终拼出来的完整 query，
     * 所以没法交给 Retrofit 自己拼 —— 只能先拼好、签好、再整体传进来。
     */
    /**
     * 注意必须用 **@FieldMap（POST 表单体）**，不能把参数放 query。
     *
     * 官方客户端的签名是把 `_n`/`_s`/`_v` 放进表单体一起提交的；
     * 实测同样内容放 query 时，服务端不下发 `miuiApp`（MIUI 自带应用）通道 ——
     * 时钟、录音机这类应用因此永远查不到更新。
     */
    @FormUrlEncoded
    @POST("apm/updateinfo/v2")
    suspend fun checkUpdates(@FieldMap fields: Map<String, String>): XiaomiUpdateResponse
}
