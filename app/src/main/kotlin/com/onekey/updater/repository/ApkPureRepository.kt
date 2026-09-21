package com.onekey.updater.repository

import android.util.Log
import com.onekey.updater.data.apkpure.AppInfoForUpdate
import com.onekey.updater.data.apkpure.AppUpdateResponse
import com.onekey.updater.data.apkpure.DeviceHeader
import com.onekey.updater.data.apkpure.GetAppUpdate
import com.onekey.updater.data.apkpure.toAppUpdate
import com.onekey.updater.data.ui.AppInstalled
import com.onekey.updater.data.ui.getApp
import com.onekey.updater.data.ui.getSignature
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.service.ApkPureService
import com.google.gson.Gson
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow


class ApkPureRepository(
    gson: Gson,
    private val service: ApkPureService,
    private val prefs: Prefs
) {

    private val header = gson.toJson(DeviceHeader())

    companion object {
        private const val TAG = "ApkPureRepository"

        /**
         * 单个请求最多携带多少个应用。
         *
         * 上游是把**全部**已安装应用（180+）塞进一个请求。请求体过大时服务端耗时明显变长，
         * 实测容易出现整批超时 —— 表现就是「APKPure 一条结果都拿不到」，而分批之后
         * 单批可控、某一批失败也不会拖垮整个来源。
         */
        private const val CHUNK_SIZE = 50
    }

    suspend fun updates(apps: List<AppInstalled>) = flow {
        val collected = mutableListOf<AppUpdateResponse>()

        apps.chunked(CHUNK_SIZE).forEachIndexed { index, chunk ->
            val info = chunk.map { AppInfoForUpdate(it.packageName, it.versionCode) }
            val part = runCatching { service.getAppUpdate(header, GetAppUpdate(info)) }
                .onFailure {
                    Log.w(TAG, "第 " + (index + 1) + " 批失败（" + chunk.size + " 个应用）", it)
                }
                .getOrNull()
                ?.app_update_response
                .orEmpty()
            // 逐批记录：以后再出现「拉不到更新」时，看日志就能定位是整体失败还是某一批失败
            Log.i(TAG, "第 " + (index + 1) + "/" + ((apps.size + CHUNK_SIZE - 1) / CHUNK_SIZE) +
                " 批返回 " + part.size + " 条")
            collected += part
        }

        val updates = collected
            .distinctBy { it.package_name }
            .filter { filterSignature(it.sign, apps.getSignature(it.package_name)) }
            .filter { filterAlpha(it) }
            .filter { filterBeta(it) }
            .map { it.toAppUpdate(apps.getApp(it.package_name)) }
        emit(updates)
    }.catch {
        Log.e(TAG, it.message, it)
        emit(emptyList())
    }

    suspend fun search(text: String) = flow {
        val response = service.search(header, text)
        val info = response.data.data.mapNotNull { d ->
            d.data.firstOrNull()?.takeIf { !it.ad }?.app_info?.let {
                AppInfoForUpdate(it.package_name, 0L, false)
            }
        }
        val r = service.getAppUpdate(header, GetAppUpdate(info))
        val updates = r.app_update_response
            .filter { filterAlpha(it) }
            .filter { filterBeta(it) }
            .map { it.toAppUpdate(null) }
        emit(Result.success(updates))
    }.catch {
        Log.e("ApkPureRepository", it.message, it)
        emit(Result.failure(it))
    }

    private fun filterAlpha(update: AppUpdateResponse) = when {
        prefs.ignoreAlpha.get() && update.version_name.contains("alpha", true) -> false
        else -> true
    }

    private fun filterBeta(update: AppUpdateResponse) = when {
        prefs.ignoreBeta.get() && update.version_name.contains("beta", true) -> false
        else -> true
    }

    private fun filterSignature(signatures: List<String>, signature: String) = when {
        signatures.isEmpty() -> true
        signatures.contains(signature) -> true
        else -> false
    }

}
