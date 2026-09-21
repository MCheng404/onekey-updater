package com.onekey.updater.util

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

/** 下载失败。携带可直接展示给用户的原因。 */
class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 边下边装的流。`length` 来自 Content-Length，未知时为 -1。 */
class DownloadStream(
    val stream: InputStream,
    val length: Long
) : Closeable {
    override fun close() = stream.close()
}

/**
 * 下载器。
 *
 * === 相对上游的修复 ===
 *  1. 上游 `download()` 在 HTTP 非 2xx 时**静默返回一个空文件**，调用方拿到 0 字节文件去
 *     `pm install`，最终报一个与真实原因无关的安装失败。现在改为抛出带原因的 DownloadException。
 *  2. 上游 `downloadStream()` 失败返回 null，调用点写的是 `!!`，触发 NPE 后又被外层
 *     runCatching 吞掉，表现为「什么都没发生」。现在显式抛异常。
 *  3. 无重试、无超时区分、无 Content-Length 校验（下载被截断时不会被发现）。
 *  4. 镜像/加速线路由 MirrorInterceptor 在网络层统一处理，本类无需关心。
 */
class Downloader(
    private val client: OkHttpClient,
    private val apkPureClient: OkHttpClient,
    private val auroraClient: OkHttpClient,
    private val dir: File
) {

    companion object {
        private const val TAG = "Downloader"
        private const val MAX_ATTEMPTS = 3
    }

    /** 下载到缓存文件；失败抛 [DownloadException]，不会返回空文件。 */
    fun download(
        url: String,
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): File {
        val file = File(dir, randomUUID())
        val response = executeWithRetry(url)
        response.use { res ->
            if (!res.isSuccessful) {
                throw DownloadException("HTTP ${res.code}（${res.message}）: $url")
            }
            val body = res.body
            val expected = body.contentLength()
            var written = 0L
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress?.invoke(written, expected)
                    }
                }
            }
            if (written == 0L) {
                file.delete()
                throw DownloadException("下载内容为空: $url")
            }
            if (expected > 0 && written != expected) {
                file.delete()
                throw DownloadException("下载被截断: $written/$expected 字节")
            }
            Log.i(TAG, "下载完成 $url -> ${file.name} ($written 字节)")
        }
        return file
    }

    /** 以流的形式下载（边下边装）。失败抛 [DownloadException]。 */
    fun downloadStream(url: String): DownloadStream {
        val response = executeWithRetry(url)
        if (!response.isSuccessful) {
            val code = response.code
            val message = response.message
            response.close()
            throw DownloadException("HTTP $code（$message）: $url")
        }
        val body = response.body
        return DownloadStream(body.byteStream(), body.contentLength())
    }

    /** 按域名选择专用 client（APKPure / Aurora 需要特定 UA）。 */
    private fun clientFor(url: String): OkHttpClient = when {
        url.contains("apkpure", true) || url.contains("pureapk", true) -> apkPureClient
        url.contains("aurora", true) -> auroraClient
        else -> client
    }

    private fun executeWithRetry(url: String): Response {
        var last: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val request = Request.Builder().url(url).build()
                return clientFor(url).newCall(request).execute()
            } catch (io: IOException) {
                last = io
                Log.w(TAG, "第 ${attempt + 1}/$MAX_ATTEMPTS 次下载失败: $url", io)
            }
        }
        throw DownloadException("网络请求失败: ${last?.message ?: "未知原因"}（$url）", last)
    }
}
