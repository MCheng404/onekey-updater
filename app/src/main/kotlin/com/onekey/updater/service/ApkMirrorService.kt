package com.onekey.updater.service

import com.onekey.updater.data.apkmirror.AppExistsRequest
import com.onekey.updater.data.apkmirror.AppExistsResponse
import com.onekey.updater.BuildConfig
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST


interface ApkMirrorService {

    @Headers(
        "User-Agent: APKUpdater-v" + BuildConfig.VERSION_NAME,
        "Authorization: Basic YXBpLWFwa3VwZGF0ZXI6cm01cmNmcnVVakt5MDRzTXB5TVBKWFc4",
        "Content-Type: application/json"
    )
    @POST("/wp-json/apkm/v1/app_exists/")
	suspend fun appExists(
        @Body request: AppExistsRequest
    ): AppExistsResponse

}
