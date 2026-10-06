package com.onekey.updater.service

import com.onekey.updater.data.xiaomi.XiaomiUpdateResponse
import retrofit2.http.POST
import retrofit2.http.QueryMap

interface XiaomiService {

    /**
     * 小米应用商店的更新检查。
     *
     * 注意是 **POST**（参数仍放在 query 上）。用 GET 会得到 errCode=1「不支持的接口」，
     * 那个错误信息会让人误以为缺签名。
     *
     * 该端点实测**不需要签名**。
     */
    @POST("apm/updateinfo/v2")
    suspend fun checkUpdates(@QueryMap params: Map<String, String>): XiaomiUpdateResponse
}
