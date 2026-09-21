package com.onekey.updater.util

import android.util.Log
import com.onekey.updater.util.RootShell.quote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 基于 root（su）的静默安装。
 *
 * 上游实现为一行 `pm install -r <app 私有 cache 路径>`，在国内 ROM + 现代 Android 上几乎必然失败，
 * 原因是 `pm install` 实际由 system_server 执行，而 system_server 受 SELinux 限制，
 * **无法读取应用私有目录**（/data/user/0/<pkg>/cache/...）。典型报错：
 *   Failure [INSTALL_FAILED_INVALID_URI: Failed to open ... Permission denied]
 *
 * 正确做法：先把 APK 复制到 /data/local/tmp（644），再执行 pm install，最后清理。
 * 多个 APK（split / xapk）走 `pm install-multiple`。
 */
object RootInstaller {

    private const val TAG = "RootInstaller"
    private const val STAGING_DIR = "/data/local/tmp"

    data class Result(val success: Boolean, val message: String = "")

    suspend fun install(
        apk: File,
        allowDowngrade: Boolean = false,
        grantPermissions: Boolean = false
    ) = install(listOf(apk), allowDowngrade, grantPermissions)

    /**
     * @param apks 待安装的 APK 文件（可位于应用私有目录）
     * @param allowDowngrade 传 -d，允许版本降级
     * @param grantPermissions 传 -g，安装时授予全部运行时权限
     */
    suspend fun install(
        apks: List<File>,
        allowDowngrade: Boolean = false,
        grantPermissions: Boolean = false
    ): Result {
        if (apks.isEmpty()) return Result(false, "没有可安装的 APK")
        val invalid = apks.firstOrNull { !it.exists() || it.length() == 0L }
        if (invalid != null) return Result(false, "APK 文件不存在或为空: ${invalid.name}")

        if (!RootShell.isAvailable()) {
            return Result(false, "未获得 Root 权限")
        }

        val staged = apks.map { "$STAGING_DIR/onekey-${randomUUID()}.apk" }
        return try {
            withContext(Dispatchers.IO) {
                // 1) 暂存到 system_server 可读的公共目录
                val stageCmd = buildString {
                    // set -e 只服务于 cp：暂存失败必须立刻中断。
                    appendLine("set -e")
                    apks.forEachIndexed { i, f ->
                        appendLine("cp ${quote(f.absolutePath)} ${quote(staged[i])}")
                        // chmod 是尽力而为，绝不能让它阻断安装。
                        // 实测（小米 15 / HyperOS / KernelSU）chmod 会间歇性返回 EPERM
                        // （SELinux 策略限制），而暂存文件由 root 创建、installd 以 root 读取，
                        // 权限位本来就非必需 —— 之前这里配合 set -e 会直接让整次安装失败。
                        appendLine("chmod 644 ${quote(staged[i])} 2>/dev/null || true")
                    }
                }
                val stageResult = RootShell.exec(stageCmd)
                if (!stageResult.success) {
                    return@withContext Result(false, "暂存 APK 失败: " + (stageResult.stderr.ifBlank { stageResult.stdout }).trim())
                }

                // 2) 安装到当前前台用户（兼容小米分身等多用户场景）
                val user = currentUserId()
                val flags = buildString {
                    append(" -r")
                    if (allowDowngrade) append(" -d")
                    if (grantPermissions) append(" -g")
                }
                val target = staged.joinToString(" ") { quote(it) }
                val verb = if (staged.size > 1) "install-multiple" else "install"
                val installResult = RootShell.exec("pm $verb$flags --user $user $target")
                val output = installResult.stdout
                Log.i(TAG, "pm $verb (user=$user) -> exit=${installResult.exitCode}\n$output")

                // pm install 在部分 ROM 上即使失败也返回 0，因此不能只看退出码
                val failed = !installResult.success ||
                    output.contains("Failure", ignoreCase = true) ||
                    !output.contains("Success", ignoreCase = true)

                if (failed) Result(false, output.ifBlank { "pm $verb 失败（无输出）" })
                else Result(true, output)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Root 安装异常。", t)
            Result(false, t.message ?: t.javaClass.simpleName)
        } finally {
            // 3) 无论成败都清理暂存文件，避免 /data/local/tmp 堆积
            runCatching {
                RootShell.exec("rm -f " + staged.joinToString(" ") { quote(it) })
            }
            apks.forEach { runCatching { it.delete() } }
        }
    }

    /** 读取当前前台用户 id；失败时回退到 0。 */
    private suspend fun currentUserId(): Int = runCatching {
        // ExecResult.stdout 是整段字符串（不是行列表），因此这里按行切分后再取第一个整数
        RootShell.exec("am get-current-user")
            .stdout
            .lineSequence()
            .firstNotNullOfOrNull { it.trim().toIntOrNull() }
    }.getOrNull() ?: 0

    /** 卸载（供后续功能使用）。 */
    suspend fun uninstall(packageName: String, keepData: Boolean = false): Result {
        val k = if (keepData) " -k" else ""
        val r = RootShell.exec("pm uninstall$k ${quote(packageName)}")
        val out = (r.stdout + System.lineSeparator() + r.stderr).trim()
        return Result(r.success && out.contains("Success", true), out)
    }
}
