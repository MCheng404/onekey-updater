package com.onekey.updater.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 通过内置的 get-app-list dex，在 root 下一次性枚举已安装应用。
 *
 * 为什么用这条路径（而不是只用 PackageManager）：
 *   · 显示名更准：dex 直接解析应用自身资源拿到「微信」而不是包名；
 *   · 系统/第三方判定与系统 `pm` 逐包一致（FLAG_SYSTEM / FLAG_UPDATED_SYSTEM_APP），
 *     正好和 [AppInfoCache.isSystemApp] 语义对齐；
 *   · 枚举走系统进程 Context，独立于普通 PM 调用，更稳。
 *
 * 关于 root 的取法：严格沿用 [RootShell] 的「su -c」一次性进程方案，绝不用 libsu 的
 * `getShell()` 长驻 shell——那在 KernelSU 下拿到的是非 root shell（见 RootShell 的说明），
 * 会让 app_process 因权限不足拿不到完整包列表。
 *
 * 优雅回退（硬性要求）：本类**绝不抛异常**给上层。任何一步失败——无 root、assets 里没有
 * dex、staging 失败、app_process 执行失败、JSON 解析失败——都返回 null，由 [AppsRepository]
 * 回退到现有的 PackageManager 路径，保证「应用列表永远能拿到、绝不崩溃」。
 */
object RootAppList {

    private const val TAG = "RootAppList"

    /** 内置 dex 资源名（需由构建把 applist-v1.1.0.dex 放成这个名字）。 */
    private const val DEX_ASSET = "applist.dex"

    /** dex 落地到这个 root 可读的位置再交给 app_process。 */
    private const val DEX_DEST = "/data/local/tmp/applist.dex"

    /** app_process 入口类（get-app-list 的固定类名）。 */
    private const val ENTRY_CLASS = "com.yule.applist.AppList"

    /**
     * 一条应用记录（来自 dex 的 JSON，已含「包名 + 显示名 + 系统/第三方标记」）。
     * @param system true 表示系统应用或「系统应用但已被更新覆盖」（口径与 isSystemApp 一致）
     */
    data class Entry(
        val packageName: String,
        val label: String,
        val system: Boolean,
        val versionName: String,
        val versionCode: Long,
        val enabled: Boolean
    )

    /**
     * 拉取已安装应用清单（root + dex 路径）。
     * @return 解析成功的记录列表；任意失败返回 null。
     */
    suspend fun fetchApps(context: Context): List<Entry>? = withContext(Dispatchers.IO) {
        // 1) 没 root 直接放弃，回退 PM。避免无谓地弹授权框。
        if (!RootShell.isAvailable()) return@withContext null

        // 2) 取内置 dex 字节（没打包此资源就回退）。
        val dexBytes = runCatching { context.assets.open(DEX_ASSET).use { it.readBytes() } }
            .getOrElse { e ->
                Log.w(TAG, "assets 中未找到 $DEX_ASSET，回退 PackageManager。", e)
                return@withContext null
            }

        // 3) 把 dex 落到 root 可读的位置（app 进程先把字节写到自己的 cache，再 su -c cp 过去）。
        if (!stageDex(context, dexBytes)) return@withContext null

        // 4) su -c 启动 app_process，输出 JSON（-t all 含系统/用户/停用；-f json 便于解析）。
        val cmd = "CLASSPATH=$DEX_DEST app_process /system/bin $ENTRY_CLASS -t all -f json"
        val result = RootShell.exec(cmd)
        if (!result.success) {
            Log.w(
                TAG,
                "app_process 执行失败，回退 PackageManager。exit=${result.exitCode} " +
                    "err=${result.stderr.take(200)}"
            )
            return@withContext null
        }

        // 5) 解析 JSON（只取 apps 数组）。
        runCatching { parseApps(result.stdout) }.getOrElse { e ->
            Log.w(TAG, "applist JSON 解析失败，回退 PackageManager。", e)
            null
        }
    }

    /**
     * 把 dex 字节落到 /data/local/tmp。
     * 之所以不直接在 app 私有目录跑：root 进程读取 /data/data/... 受 SELinux 限制不一，
     * 统一 cp 到 world 可读的 /data/local/tmp 最稳。cp 本身由 su -c 完成（root 写）。
     */
    private suspend fun stageDex(context: Context, bytes: ByteArray): Boolean {
        val cacheFile = File(context.cacheDir, DEX_ASSET)
        runCatching { cacheFile.writeBytes(bytes) }.getOrElse { e ->
            Log.w(TAG, "写入 dex 缓存失败。", e)
            return false
        }
        val r = RootShell.exec(
            "cp ${RootShell.quote(cacheFile.absolutePath)} $DEX_DEST && chmod 644 $DEX_DEST"
        )
        if (!r.success) {
            Log.w(TAG, "su -c cp dex 失败：${r.stderr.take(200)}")
            return false
        }
        return true
    }

    /** 从 dex 的 JSON 文档里抽出 apps 数组，逐条映射成 [Entry]。 */
    private fun parseApps(stdout: String): List<Entry> {
        // JSON 文档是纯净对象；保险起见掐头去尾只取第一个 { 到最后一个 }。
        val text = stdout.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) error("applist 输出不是合法 JSON")
        val root = JSONObject(text.substring(start, end + 1))
        val arr: JSONArray = root.getJSONArray("apps")
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Entry(
                    packageName = o.getString("package"),
                    label = o.optString("label", ""),
                    system = o.optBoolean("system", false),
                    versionName = o.optString("versionName", ""),
                    versionCode = o.optLong("versionCode", 0L),
                    enabled = o.optBoolean("enabled", true)
                )
            )
        }
        return out
    }
}
