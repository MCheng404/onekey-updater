package com.onekey.updater.service

import com.onekey.updater.data.vivo.VivoUpdateResponse
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.POST

interface VivoService {

    /**
     * vivo 应用商店更新检查。
     *
     * vivo 的更新检查**不需要签名、不需要设备身份**，26 个参数全是明文 form。
     * 注意 User-Agent 必须是 Dalvik 原生格式（vivo 客户端本身就是 native 进程）。
     */
    @FormUrlEncoded
    @POST("port/packages_update/")
    suspend fun checkUpdates(
        @Header("User-Agent") userAgent: String,
        @FieldMap fields: Map<String, String>
    ): VivoUpdateResponse
}
