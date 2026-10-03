package com.onekey.updater.util

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Shizuku 通道。
 *
 * ## 为什么用 `Shizuku.newProcess()` 而不是自己写 UserService + AIDL
 * `newProcess()` 返回的是一个**远程 Process** —— 同样有 `waitFor`、输入输出流、
 * `exitValue`、`destroy`，接口与本地 `Process` 一致。因此 [RootInstaller] 里那套
 * 已经踩坑调通的「暂存 → pm install → 会话式分卷安装」逻辑可以原样复用，
 * 只是把命令执行器换掉。不必引入自定义 IPC，也少一处无法在本机验证的代码。
 *
 * ## 能力边界（务必清楚）
 * Shizuku 以 **shell(uid 2000)** 或 root 身份执行命令，因此 `pm install` 可用 ——
 * 这正是没有 root 时想要静默安装的诉求。但它**不能**做只有 root 能做的事，
 * 例如读取 `/data/adb`、改动系统分区。本项目只用它执行 `pm install*`，不越界。
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"

    /** 命令执行超时。与 RootShell 同量级，避免卡住安装流程。 */
    private const val EXEC_TIMEOUT_SECONDS = 60L

    /** Shizuku 是否已安装、已启动、且本应用已获得它的授权。 */
    fun isRunning(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.getBinder()?.isBinderAlive == true
    }.onFailure {
        Log.w(TAG, "查询 Shizuku 状态失败。", it)
    }.getOrDefault(false)

    /** 供界面展示，例如「v13.1.5」。未运行时返回空串。 */
    fun version(): String = if (isRunning()) runCatching { Shizuku.getVersion().toString() }.getOrDefault("") else ""

    /**
     * 跳转到 Shizuku 管理器。
     * 未安装时返回 false，调用方据此提示用户自行安装。
     */
    fun openManager(context: Context): Boolean {
        val intent = Intent("moe.shizuku.privileged.api.manager.action.application_details")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /**
     * 以 Shizuku 身份执行一条 shell 命令。
     *
     * 返回结构与 [RootShell.exec] 一致，便于两处代码互换使用。
     */
    suspend fun exec(command: String): ExecResult = withContext(Dispatchers.IO) {
        if (!isRunning()) {
            return@withContext ExecResult(false, -1, "", "Shizuku 未运行")
        }
        runCatching {
            // 第三参数 dir 传 "/"：Shizuku 以 shell 身份运行，工作目录给个一定存在的路径
            val process = Shizuku.newProcess(arrayOf("/system/bin/sh", "-c", command), null, "/")
            var output = ""
            // 与 RootShell 同样的坑：流必须在独立线程读，
            // 否则进程迟迟不退出时 waitFor 的超时永远等不到执行。
            val reader = Thread {
                output = runCatching {
                    process.inputStream.bufferedReader().use { it.readText() }
                }.getOrDefault("")
            }.apply { isDaemon = true }
            reader.start()

            val finished = process.waitFor(EXEC_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) process.destroy() else reader.join(1000)
            val code = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
            ExecResult(code == 0, code, output.trim(), "")
        }.getOrElse {
            Log.w(TAG, "Shizuku 执行命令失败。", it)
            ExecResult(false, -1, "", it.message ?: it.javaClass.simpleName)
        }
    }
}
