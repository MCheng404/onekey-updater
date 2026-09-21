package com.onekey.updater.data.apkmirror

data class AppExistsResponse(
	val data: List<AppExistsResponseData> = emptyList(),
	val headers: AppExistsResponseHeaders? = null,
	val status: Int? = null
)
