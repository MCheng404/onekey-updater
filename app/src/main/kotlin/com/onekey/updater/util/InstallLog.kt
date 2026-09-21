package com.onekey.updater.util

import com.onekey.updater.data.ui.AppInstallProgress
import com.onekey.updater.data.ui.AppInstallStatus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 安装事件总线。
 *
 * === 相对上游的修复 ===
 * 上游写的是 `MutableSharedFlow<T>(100)`。`MutableSharedFlow` 的**第一个位置参数是 replay 而非
 * 缓冲区大小**，因此它实际语义是「缓存最近 100 条并重放给每个新订阅者」。
 * ViewModel 在 init 中订阅时会收到一批历史事件，从而对早已结束的安装再次触发
 * finish/cancel 回调（出现莫名的状态跳变与重复 unlock）。
 *
 * 改为 replay=0 + 显式缓冲 + DROP_OLDEST：新订阅者只收到订阅之后的事件，且发射方永不阻塞。
 */
class InstallLog {

    private val status = MutableSharedFlow<AppInstallStatus>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val progress = MutableSharedFlow<AppInstallProgress>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 当前正在安装的应用 id；无安装时为 0。 */
    @Volatile
    var currentInstallId: Int = 0
        private set

    fun status() = status.asSharedFlow()

    fun progress() = progress.asSharedFlow()

    /** 用户在外面取消了安装（例如从通知栏/系统界面返回）。 */
    fun cancelCurrentInstall() {
        val id = currentInstallId
        if (id != 0) emitStatus(AppInstallStatus(false, id, snack = false, message = "安装已取消"))
    }

    fun emitStatus(newStatus: AppInstallStatus) {
        if (newStatus.id != 0) currentInstallId = newStatus.id
        status.tryEmit(newStatus)
    }

    /** 由 SessionInstaller 在拉起确认界面时标记当前安装目标。 */
    fun markCurrentInstall(id: Int) {
        currentInstallId = id
    }

    fun emitProgress(newProgress: AppInstallProgress) = progress.tryEmit(newProgress)
}
