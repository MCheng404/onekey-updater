package com.onekey.updater.util

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_MUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import com.onekey.updater.BuildConfig
import com.onekey.updater.data.ui.AppInstallProgress
import com.onekey.updater.data.ui.AppInstallStatus
import com.onekey.updater.prefs.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.minutes

/**
 * APK 安装器。
 *
 * === 相对上游的修复 ===
 *
 * 【P0】用户取消确认框后永久卡死
 *   上游把「终结信号」寄托在 `PendingIntent` 广播上：只有 STATUS_SUCCESS / STATUS_FAILURE
 *   才 resume 协程并释放锁。但 PackageInstaller 在用户点「取消」时**不会**发送终结广播，
 *   只回调 `SessionCallback.onFinished(sessionId, false)`，而上游的 onFinished 里什么也没做。
 *   结果：continuation 永不 resume → ViewModel 中 isInstalling 永为 true（一直转圈），
 *   且安装互斥锁永不释放 → 之后所有安装都卡在 lock() 上，安装确认界面再也拉不起来。
 *
 *   修复：以 `onFinished` 作为权威终结信号、状态广播作为补充，两者汇聚到同一个幂等的
 *   `finishWith()`；并额外增加「会话看门狗」（会话消失即视为终结），消除全部挂死路径。
 *
 * 【P0】缺失的 EXTRA_STATUS 被误判为 STATUS_SUCCESS
 *   `Bundle.getInt(key)` 在 key 不存在时返回 0，而 `PackageInstaller.STATUS_SUCCESS == 0`。
 *   上游没做 containsKey 判断，任何缺少该 extra 的广播都会被当成「安装成功」。
 *
 * 【P1】root 安装读取应用私有目录必然失败（见 RootInstaller）
 *
 * 【P1】xapk 安装：未忽略大小写、未把 base.apk 排前、未支持 root、异常路径下临时文件泄漏。
 */
class SessionInstaller(
    private val context: Context,
    private val installLog: InstallLog,
    private val prefs: Prefs
) {
    companion object {
        const val INSTALL_ACTION = "installAction"
        private const val TAG = "SessionInstaller"

        /** 用户确认框最长等待时长，兜底防止未知 ROM 行为把协程挂死。 */
        private val INSTALL_TIMEOUT = 30.minutes

        /** 看门狗轮询间隔。 */
        private const val WATCHDOG_INTERVAL_MS = 1_500L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 串行化会话安装（PackageInstaller 同一时刻只应有一个前台会话）。 */
    private val sessionLock = Mutex()

    init {
        // 清理上次进程遗留的僵尸会话，否则 createSession 可能因配额耗尽而失败
        runCatching {
            val installer = context.packageManager.packageInstaller
            installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        }
    }

    suspend fun install(id: Int, packageName: String, stream: InputStream, size: Long = -1L) =
        install(id, packageName, listOf(stream), size)

    suspend fun install(
        id: Int,
        packageName: String,
        streams: List<InputStream>,
        size: Long = -1L
    ): Boolean = sessionLock.withLock {
        installSession(id, packageName, streams, size)
    }

    @SuppressLint("RequestInstallPackagesPolicy")
    private suspend fun installSession(
        id: Int,
        packageName: String,
        streams: List<InputStream>,
        size: Long
    ): Boolean {
        val packageInstaller = context.packageManager.packageInstaller
        val action = "$INSTALL_ACTION.$id"

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 24) params.setAppPackageName(packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        // 显式声明体积，避免部分 ROM 使用默认配额导致大包安装失败
        if (size > 0L) params.setSize(size)

        val currentSessionId = packageInstaller.createSession(params)
        Log.i(TAG, "createSession id=$currentSessionId package=$packageName size=$size")

        val terminal = CompletableDeferred<Boolean>()
        val failureReason = AtomicReference<String?>(null)
        val userActionRequested = CompletableDeferred<Unit>()

        /** 幂等的终结入口：广播与 SessionCallback 谁先到谁生效。 */
        fun finishWith(success: Boolean, reason: String? = null) {
            if (reason != null) failureReason.compareAndSet(null, reason)
            terminal.complete(success)
        }

        // ---- 权威终结信号：会话回调（用户取消时只有这里会收到回调）----
        val callback = object : PackageInstaller.SessionCallback() {
            override fun onCreated(sessionId: Int) {}
            override fun onBadgingChanged(sessionId: Int) {}
            override fun onActiveChanged(sessionId: Int, active: Boolean) {}
            override fun onProgressChanged(sessionId: Int, progress: Float) {}
            override fun onFinished(sessionId: Int, success: Boolean) {
                if (sessionId != currentSessionId) return
                Log.i(TAG, "onFinished session=$sessionId success=$success")
                if (!success) failureReason.compareAndSet(null, "安装被取消或安装失败")
                finishWith(success)
            }
        }
        packageInstaller.registerSessionCallback(callback, Handler(Looper.getMainLooper()))

        var receiver: BroadcastReceiver? = null
        var committed = false

        try {
            // ---- 状态广播：负责「拉起用户确认界面」，部分 ROM 也用它回传终结状态 ----
            receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent?) {
                    val extras = intent?.extras ?: return
                    // 关键修复：缺 key 时 getInt 返回 0 == STATUS_SUCCESS，必须显式判断
                    if (!extras.containsKey(PackageInstaller.EXTRA_STATUS)) {
                        Log.w(TAG, "收到缺少 EXTRA_STATUS 的广播，忽略。")
                        return
                    }
                    when (val status = extras.getInt(PackageInstaller.EXTRA_STATUS)) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            Log.i(TAG, "STATUS_PENDING_USER_ACTION，准备拉起确认界面")
                            installLog.markCurrentInstall(id)
                            userActionRequested.complete(Unit)
                            val confirm = intent.getInstallIntentExtra()
                            if (confirm == null) {
                                finishWith(false, "系统未返回安装确认 Intent")
                                return
                            }
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(confirm) }.onFailure {
                                // 后台场景会被 Android 10+ 的后台启动限制拦截
                                Log.e(TAG, "拉起安装确认界面失败。", it)
                                finishWith(
                                    false,
                                    "系统阻止了后台拉起安装界面，请打开 App 后重试，或在设置中开启 Root 静默安装"
                                )
                            }
                        }
                        PackageInstaller.STATUS_SUCCESS -> {
                            Log.i(TAG, "STATUS_SUCCESS")
                            finishWith(true)
                        }
                        else -> {
                            val msg = extras.getString(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            Log.w(TAG, "安装失败 status=$status msg=$msg")
                            finishWith(false, msg ?: installStatusToString(status))
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, IntentFilter(action))
            }

            // ---- 写入 APK 流 ----
            installLog.emitProgress(AppInstallProgress(id, 0L, size.takeIf { it > 0 }))
            packageInstaller.openSession(currentSessionId).use { session ->
                var written = 0L
                var lastEmitAt = 0L
                streams.forEach { stream ->
                    session.openWrite("$packageName-${randomUUID()}", 0, -1).use { output ->
                        stream.use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                // 写入本身是 8 KB 一次，若每次都回调会让 UI 每帧重组列表；
                                // 这里按 200 ms 节流，并把最终值补齐在循环外。
                                val now = System.currentTimeMillis()
                                if (now - lastEmitAt >= 200L) {
                                    lastEmitAt = now
                                    installLog.emitProgress(AppInstallProgress(id, written))
                                }
                            }
                        }
                        session.fsync(output)
                    }
                }
                installLog.emitProgress(AppInstallProgress(id, written))
                if (written == 0L) {
                    finishWith(false, "下载内容为空")
                    return false
                }

                val intent = Intent(action).apply { setPackage(context.packageName) }
                val pending = PendingIntent.getBroadcast(
                    context,
                    currentSessionId,
                    intent,
                    FLAG_UPDATE_CURRENT or FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
                committed = true
                Log.i(TAG, "session $currentSessionId 已 commit，等待系统回执")
            }

            // ---- 等待终结 ----
            val success = withTimeoutOrNull(INSTALL_TIMEOUT) {
                val watchdog = scope.launch {
                    // 只在「需要用户操作」后才启动：用户点取消时部分 ROM 不回调 onFinished，
                    // 此时「会话已消失」即为终结信号。
                    userActionRequested.await()
                    while (isActive && !terminal.isCompleted) {
                        delay(WATCHDOG_INTERVAL_MS)
                        val gone = runCatching { packageInstaller.getSessionInfo(currentSessionId) }
                            .isFailure
                        if (gone) {
                            delay(2_000)
                            if (!terminal.isCompleted) {
                                Log.w(TAG, "看门狗：会话 $currentSessionId 已消失但无回执，判定为取消")
                                finishWith(false, "安装已取消")
                            }
                            break
                        }
                    }
                }
                try {
                    terminal.await()
                } finally {
                    watchdog.cancel()
                }
            } ?: run {
                Log.w(TAG, "安装超时（$INSTALL_TIMEOUT）")
                finishWith(false, "安装超时")
                false
            }

            installLog.emitStatus(
                AppInstallStatus(
                    success = success,
                    id = id,
                    snack = true,
                    message = if (success) null else failureReason.get()
                )
            )
            return success
        } catch (t: Throwable) {
            Log.e(TAG, "安装流程异常。", t)
            installLog.emitStatus(
                AppInstallStatus(false, id, true, t.message ?: t.javaClass.simpleName)
            )
            return false
        } finally {
            runCatching { receiver?.let { context.unregisterReceiver(it) } }
            runCatching { packageInstaller.unregisterSessionCallback(callback) }
            if (!committed) runCatching { packageInstaller.abandonSession(currentSessionId) }
        }
    }

    /** xapk / apks 分卷包：解包后安装。base.apk 必须排在最前。 */
    /**
     * 安装 xapk / apks 分卷包。
     *
     * @return 是否安装成功。**必须把结果返回给调用方** —— MCP 的 install_update 依赖它，
     *         此前该函数只 emit 状态、不返回值，调用方只能硬编码"成功"，
     *         于是安装实际失败时也回报成功（实测踩到）。
     */
    suspend fun installXapk(id: Int, packageName: String, stream: InputStream, size: Long = -1L): Boolean {
        val archive = File(context.cacheDir, "${randomUUID()}.xapk")
        val extracted = mutableListOf<File>()
        try {
            stream.use { input -> archive.outputStream().use { input.copyTo(it) } }

            ZipFile(archive).use { zip ->
                val entries = zip.entries().toList()
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                if (entries.isEmpty()) {
                    installLog.emitStatus(AppInstallStatus(false, id, true, "压缩包内没有找到 APK"))
                    return false
                }
                // base.apk 必须排在最前，否则部分 ROM 会拒绝 split 安装
                val ordered = entries.sortedWith(
                    compareByDescending<ZipEntry> {
                        it.name.substringAfterLast('/').equals("base.apk", ignoreCase = true)
                    }.thenByDescending { it.size }
                )
                ordered.forEachIndexed { index, entry ->
                    val out = File(context.cacheDir, "${randomUUID()}-$index.apk")
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    extracted.add(out)
                }
            }

            if (prefs.isRootInstall()) {
                val result = RootInstaller.install(extracted)
                installLog.emitStatus(
                    AppInstallStatus(result.success, id, true, result.message.takeIf { !result.success })
                )
                if (!result.success) Log.e(TAG, "root xapk 安装失败: ${result.message}")
                return result.success
            } else {
                installLog.emitStatus(
                    AppInstallStatus(
                        false, id, true,
                        "xapk/apks 分卷包需要 Root 权限才能静默安装，请在设置中开启"
                    )
                )
                return false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "xapk 安装异常。", t)
            installLog.emitStatus(AppInstallStatus(false, id, true, t.message))
            return false
        } finally {
            runCatching { archive.delete() }
            extracted.forEach { runCatching { it.delete() } }
        }
    }

    /** Root 静默安装（单文件）。 */
    suspend fun rootInstall(file: File): Boolean {
        val result = RootInstaller.install(file)
        if (!result.success) Log.e(TAG, "root 安装失败: ${result.message}")
        return result.success
    }

    fun checkPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val uri = "package:${BuildConfig.APPLICATION_ID}".toUri()
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                runCatching { context.startActivity(intent) }
                return false
            }
        }
        return true
    }

    private fun installStatusToString(status: Int) = when (status) {
        PackageInstaller.STATUS_FAILURE -> "安装失败"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "安装被阻止"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "安装被中止"
        PackageInstaller.STATUS_FAILURE_INVALID -> "APK 无效"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "与已安装应用冲突"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "与设备不兼容"
        else -> "未知错误（$status）"
    }

    /** 释放资源（应用退出时调用）。 */
    fun shutdown() = scope.cancel()
}

private fun Intent.getInstallIntentExtra(): Intent? = runCatching {
    if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_INTENT)
    }
}.getOrNull()
