package com.onekey.updater.viewmodel

import android.util.Log
import androidx.compose.ui.platform.UriHandler
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.updater.R
import com.onekey.updater.data.ui.ApkMirrorSource
import com.onekey.updater.data.ui.AppInstallProgress
import com.onekey.updater.data.ui.AppInstallStatus
import com.onekey.updater.data.ui.AppUpdate
import com.onekey.updater.data.ui.Link
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.util.Downloader
import com.onekey.updater.util.InstallLog
import com.onekey.updater.util.RootInstaller
import com.onekey.updater.util.SessionInstaller
import com.onekey.updater.util.SnackBar
import com.onekey.updater.util.Stringer
import com.onekey.updater.util.isRootInstall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * 安装流程基类。
 *
 * 与上游的差异：
 *  - 删除了 `installer.finish()` 这套「手动解锁」机制。上游的安装互斥锁需要在
 *    Activity 的 onActivityResult 里被动解锁，用户中途取消就永久锁死；现在互斥由
 *    SessionInstaller 内部用 Mutex + 超时保证，ViewModel 不再参与。
 *  - 所有失败统一收敛到 [InstallLog] 的 status 流（包括下载异常），
 *    UI 因此总能拿到失败原因，而不是「什么都没发生」。
 *  - 安装方式由 [Prefs.installMode] 决定，AUTO 会在有 Root 时走静默安装。
 */
abstract class InstallViewModel(
    private val downloader: Downloader,
    private val installer: SessionInstaller,
    private val prefs: Prefs,
    private val snackBar: SnackBar,
    private val stringer: Stringer,
    protected val installLog: InstallLog
) : ViewModel() {

    companion object {
        private const val TAG = "InstallViewModel"
    }

    init {
        subscribeToInstallStatus()
        subscribeToInstallProgress { onInstallProgress(it) }
    }

    /**
     * 安装入口。
     * ApkMirror 对下载有 Cloudflare 保护，直接抓取必然失败，因此仍交给浏览器打开。
     */
    fun install(update: AppUpdate, uriHandler: UriHandler) {
        if (update.source == ApkMirrorSource) {
            (update.link as? Link.Url)?.let { uriHandler.openUri(it.link) }
            return
        }
        viewModelScope.launch { performInstall(update) }
    }

    /** 顺序安装的单元：设置安装标记 -> 执行 -> 失败也走统一状态出口。 */
    protected suspend fun performInstall(update: AppUpdate) = withContext(Dispatchers.IO) {
        onInstallingChanged(update.id, true)
        try {
            installLink(update)
        } catch (t: Throwable) {
            Log.e(TAG, "安装 ${update.packageName} 失败。", t)
            // 统一走 status 流，保证 UI 只在一个地方处理结果
            installLog.emitStatus(
                AppInstallStatus(
                    success = false,
                    id = update.id,
                    snack = true,
                    message = t.message ?: t.javaClass.simpleName
                )
            )
        }
    }

    private suspend fun installLink(update: AppUpdate) {
        when (val link = update.link) {
            Link.Empty -> throw IllegalStateException(stringer.get(R.string.no_download_link))

            is Link.Url -> if (prefs.isRootInstall()) {
                val file = downloader.download(link.link)
                val result = RootInstaller.install(file)
                installLog.emitStatus(
                    AppInstallStatus(result.success, update.id, true, result.message.takeIf { !result.success })
                )
            } else {
                requireInstallPermission()
                downloader.downloadStream(link.link).use { download ->
                    installer.install(update.id, update.packageName, download.stream, download.length)
                }
            }

            is Link.Xapk -> if (prefs.isRootInstall()) {
                installer.installXapk(update.id, update.packageName, downloader.downloadStream(link.link).stream)
            } else {
                throw IllegalStateException(stringer.get(R.string.xapk_requires_root))
            }

            is Link.Play -> {
                requireInstallPermission()
                val files = link.getInstallFiles()
                if (files.isEmpty()) throw IllegalStateException(stringer.get(R.string.no_download_link))
                val size = files.sumOf { it.size }
                val streams = files.map { downloader.downloadStream(it.url) }
                try {
                    installer.install(update.id, update.packageName, streams.map { it.stream }, size)
                } finally {
                    streams.forEach { runCatching { it.close() } }
                }
            }
        }
    }

    /** 未授予「安装未知应用」时拉起系统设置页并中断本次安装。 */
    private fun requireInstallPermission() {
        if (!installer.checkPermission()) {
            throw IllegalStateException(stringer.get(R.string.need_install_permission))
        }
    }

    private fun subscribeToInstallStatus() = installLog.status().onEach { status ->
        onInstallingChanged(status.id, false)
        if (status.snack) {
            val base = if (status.success) {
                stringer.get(R.string.install_success)
            } else {
                stringer.get(R.string.install_failure)
            }
            snackBar.snackBar(
                viewModelScope,
                status.message?.let { "$base · $it" } ?: base
            )
        }
        if (status.success) onInstalled(status.id) else onInstallFailed(status.id)
    }.launchIn(viewModelScope)

    private fun subscribeToInstallProgress(block: (AppInstallProgress) -> Unit) =
        installLog.progress().onEach(block).launchIn(viewModelScope)

    /** 安装标记变化；子类据此更新对应列表项。 */
    protected abstract fun onInstallingChanged(id: Int, installing: Boolean)

    protected abstract fun onInstallProgress(progress: AppInstallProgress)

    /** 安装成功；默认实现是从列表中移除该项。 */
    protected abstract fun onInstalled(id: Int)

    /** 安装失败或取消。 */
    protected abstract fun onInstallFailed(id: Int)
}
