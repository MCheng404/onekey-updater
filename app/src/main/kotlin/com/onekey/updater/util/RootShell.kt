package com.onekey.updater.util

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Root 能力探测与命令执行。
 *
 * 关键结论（来自真机取证，小米 15 / KernelSU）：
 *   libsu 的 `Shell.getShell()` 走的是「裸 su」（argv="su"，无 -c），拿到的 shell 在 KernelSU 下
 *   **不是 root**，于是 `isRoot` 恒为 false；而原实现把这个 false 永久缓存，导致
 *   「一次失败 = 永远显示拒绝」。libsu 的 `getShell()` 还有一次性失败的永久锁存（isInitMain），
 *   首探失败后再也重试不了。因此这里**不再用 libsu 的 getShell/isRoot 作为判定依据**。
 *
 * 改为 HyperNavBar 风格的一次性进程：
 *   · 探测：`su -c id`，判定标准是 stdout 里出现 `uid=0`（比只看退出码更强——有些 su 包装器
 *     即使不授权也返回 0，但 uid 不会是 0）。
 *   · 执行：`su -c "<命令>"` 一次性进程，避免长驻 shell 的权限/锁存问题。
 *   · **失败绝不永久缓存**：只有成功才缓存为 true；失败一律置空，下次调用重新探测。
 *     libsu 仅作为「su -c 抛异常（少数 root 管理器只支持长驻 shell）」时的兜底，且走非主路径。
 */
object RootShell {

    private const val TAG = "RootShell"

    /** 探测超时：su 卡在授权弹窗时最多等待，超时按「未拿到 root」处理。 */
    private const val PROBE_TIMEOUT_SECONDS = 20L

    /** 执行超时：root 命令异常挂起时最多等待，超时按失败处理并回收进程。 */
    private const val EXEC_TIMEOUT_SECONDS = 60L

    private val mutex = Mutex()

    /**
     * 可用性缓存。
     *   · true  = 已确认可用（成功探测，可复用，避免反复弹授权框）
     *   · null  = 未知 / 上次失败（不缓存失败，下次重新探测）
     * 注意：这里**绝不存 false**——这正是原 bug 的根因，必须改掉。
     */
    @Volatile
    private var available: Boolean? = null

    /** 最近一次探测的原始结果，供设置页展示「为什么失败」。 */
    @Volatile
    var lastProbe: ProbeResult? = null
        private set

    /**
     * 一次探测的结果明细。
     * @param granted 是否拿到 root
     * @param stdout  命令标准输出（原样保留）
     * @param stderr  命令标准错误（原样保留）
     * @param via     走的路径："su -c" 或 "libsu(兜底)"
     * @param error   异常信息（su -c 抛异常时才有）
     */
    data class ProbeResult(
        val granted: Boolean,
        val stdout: String,
        val stderr: String,
        val via: String,
        val error: String?
    )

    /**
     * 是否具备 root。
     * 成功结果会被缓存（避免每次都弹授权框）；失败不缓存，下次重新探测。
     * 首次调用（以及被 [invalidate] 之后）会真正发起一次 `su -c id` 探测。
     */
    suspend fun isAvailable(force: Boolean = false): Boolean = mutex.withLock {
        if (!force && available == true) return@withLock true
        val result = probe()
        // 只有成功才缓存；失败置空，保证「失败不缓存、可重试」
        available = if (result.granted) true else null
        Log.i(
            TAG,
            "Root 探测：${if (result.granted) "已获取" else "未获取"} | via=${result.via}" +
                (result.error?.let { " | 异常=$it" } ?: "") +
                " | stdout=${result.stdout.take(160)}"
        )
        result.granted
    }

    /**
     * 实际探测：主路径 `su -c id`，判定 stdout 含 `uid=0`；
     * 仅当 `su -c` 抛出异常（su 二进制缺失等极少数情况）才回退 libsu 长驻 shell。
     */
    private suspend fun probe(): ProbeResult = withContext(Dispatchers.IO) {
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
            // 限时等待：若 su 卡在授权弹窗（用户一直不点），最多等 20s 后就判定失败并回收进程，
            // 避免 IO 协程被永久挂起。超时也按「未拿到 root」处理。
            val finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val code = if (finished) process.exitValue() else { process.destroy(); -1 }
            // 退出码 0 且 stdout 真出现 uid=0 才算拿到 root。
            // 有些 su 包装器即便未授权也返回 0，但 uid 不会是 0，所以必须校验输出内容。
            val granted = finished && code == 0 && stdout.contains("uid=0")
            ProbeResult(
                granted = granted,
                stdout = stdout.trim(),
                stderr = stderr.trim(),
                via = "su -c",
                error = when {
                    !finished -> "su 超时未返回（可能卡在授权弹窗）"
                    !granted && code != 0 -> "exit=$code"
                    else -> null
                }
            ).also { lastProbe = it }
        }.getOrElse { e ->
            // 兜底：su -c 直接抛异常（su 不存在 / 无法 fork）。这种情况很少见，
            // 仅作为最后手段走 libsu 长驻 shell，并在日志中明确标注走了兜底路径。
            Log.w(TAG, "su -c 探测抛异常，回退 libsu 长驻 shell（非主路径）。", e)
            runCatching {
                val shell = Shell.getShell()
                ProbeResult(
                    granted = shell.isRoot,
                    stdout = "libsu isRoot=${shell.isRoot}",
                    stderr = "",
                    via = "libsu(兜底)",
                    error = null
                ).also { lastProbe = it }
            }.getOrElse { e2 ->
                ProbeResult(
                    granted = false,
                    stdout = "",
                    stderr = "",
                    via = "su -c→libsu",
                    error = "${e.message} | ${e2.message}"
                ).also { lastProbe = it }
            }
        }
    }

    /** 清除缓存的探测结果，下次重新询问（用于用户在设置里重试授权）。 */
    fun invalidate() {
        available = null
    }

    /**
     * 以 root 执行一段 shell 命令（一次性 `su -c` 进程）。
     * @return 执行结果：退出码、标准输出、标准错误、是否成功（退出码 0）。
     */
    suspend fun exec(command: String): ExecResult = withContext(Dispatchers.IO) {
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
            // 与探测同理：命令若卡住（如 pm 异常挂起）最多等 60s，避免 IO 协程被挂死。
            val finished = process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val code = if (finished) process.exitValue() else { process.destroy(); -1 }
            ExecResult(finished && code == 0, code, stdout, stderr)
        }.getOrElse { e ->
            Log.e(TAG, "su -c 执行异常: ${command.take(80)}", e)
            ExecResult(false, -1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    /** 便捷方法：执行并返回是否成功（退出码 0）。 */
    suspend fun execBool(command: String): Boolean = exec(command).success

    /** 把执行结果拼成单行文本，用于错误上报 / 日志。 */
    fun ExecResult.output(): String =
        (stdout + stderr).trim().lineSequence().joinToString(" ").trim()

    /** 安全地把字符串包进单引号，避免路径中的特殊字符破坏脚本（POSIX 安全，可用于 su -c 内层）。 */
    fun quote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"
}

/** 一次 root 命令的执行结果。 */
data class ExecResult(
    val success: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)
