package com.onekey.updater.service

import com.onekey.updater.data.aptoide.ListAppUpdatesResponse
import com.onekey.updater.data.aptoide.ListAppsUpdatesRequest
import com.onekey.updater.data.aptoide.ListSearchAppsRequest
import com.onekey.updater.data.aptoide.ListSearchAppsResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface AptoideService {

    @POST("listSearchApps")
    suspend fun searchApps(@Body request: ListSearchAppsRequest): ListSearchAppsResponse

    @POST("listAppsUpdates")
    suspend fun findUpdates(
        @Body request: ListAppsUpdatesRequest,
        @Header("X-Bypass-Cache") bypassCache: Boolean = true,
        @Query("aab") showAabs: Boolean = false
    ): ListAppUpdatesResponse

}
