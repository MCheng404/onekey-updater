package com.onekey.updater.service

import com.onekey.updater.data.xiaomi.XiaomiUpdateResponse
import retrofit2.http.POST
import retrofit2.http.Url

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
    @POST
    suspend fun checkUpdates(@Url url: String): XiaomiUpdateResponse
}
