package com.onekey.updater.util.mcp

import android.content.Context
import com.onekey.updater.data.ui.ApkMirrorSource
import com.onekey.updater.data.ui.ApkPureSource
import com.onekey.updater.data.ui.AppUpdate
import com.onekey.updater.data.ui.UpdateScan
import com.onekey.updater.data.ui.AptoideSource
import com.onekey.updater.data.ui.FdroidSource
import com.onekey.updater.data.ui.GitHubSource
import com.onekey.updater.data.ui.GitLabSource
import com.onekey.updater.data.ui.IzzySource
import com.onekey.updater.data.ui.Link
import com.onekey.updater.data.ui.PlaySource
import com.onekey.updater.data.ui.TencentSource
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.repository.UpdatesRepository
import com.onekey.updater.util.AppLog
import com.onekey.updater.util.Downloader
import com.onekey.updater.util.RootInstaller
import com.onekey.updater.util.RootShell
import com.onekey.updater.util.SessionInstaller
import com.onekey.updater.util.isRootInstall
import com.onekey.updater.util.isSystemApp
import com.onekey.updater.util.filterVersionTag
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * [McpBridge] 的数据层实现。
 *
 * 这里刻意**不复用 ViewModel**：ViewModel 的生命周期绑在界面上，而 MCP 服务可能在
 * 没有任何界面时运行（后台被 Agent 调用）。因此直接依赖仓库与安装原语，
 * 安装分支的判断逻辑与 `InstallViewModel` 保持一致（Root 优先 → 否则走系统安装会话）。
 */
class McpBridgeImpl(
    private val context: Context,
    private val updatesRepository: UpdatesRepository,
    private val downloader: Downloader,
    private val installer: SessionInstaller,
    private val prefs: Prefs
) : McpBridge {

    /** 串行化扫描：Agent 可能连续调 list_updates 与 scan_updates，没必要跑两遍。 */
    private val scanMutex = Mutex()

    /** 最近一次扫描得到的候选，按包名索引 —— install 需要从中取出真正的 Link。 */
    private val lastCandidates = ConcurrentHashMap<String, List<AppUpdate>>()

    override suspend fun listUpdates(): McpListResult {
        val scan = runScan()
        val groups = scan.updates.groupBy { it.packageName }
        val items = groups.map { (pkg, candidates) ->
            // 推荐来源 = 版本号最高者（versionCode 可用时优先，数值最可靠）
            val best = candidates.maxWithOrNull(
                compareBy<AppUpdate> { it.versionCode }
                    .thenBy { runCatching { filterVersionTag(it.version) }.getOrDefault("") }
            ) ?: candidates.first()
            McpAppUpdate(
                name = best.name.ifEmpty { pkg },
                packageName = pkg,
                installedVersion = best.oldVersion.ifEmpty { "?" },
                targetVersion = best.version,
                sources = candidates.map { it.source.name }.distinct(),
                recommendedSource = best.source.name,
                systemApp = context.isSystemApp(pkg)
            )
        }.sortedBy { it.name.lowercase() }

        AppLog.log("McpBridge", "list_updates：检查 " + scan.scannedApps + " 个应用，发现 " + items.size + " 个可更新")
        return McpListResult(scan.scannedApps, items.size, items)
    }

    override suspend fun scanUpdates(): McpScanSummary {
        val scan = runScan()
        val grouped = scan.updates.map { it.packageName }.distinct().size
        AppLog.log("McpBridge", "scan_updates：检查 " + scan.scannedApps + " 个应用，发现 " + grouped + " 个可更新")
        return McpScanSummary(scan.scannedApps, grouped)
    }

    private suspend fun runScan(): UpdateScan = scanMutex.withLock {
        // 取最后一次发射：扫描是渐进式 emit，只有最后一次才包含全部来源的结果
        val result = updatesRepository.updates().lastOrNull()
            ?: UpdateScan(emptyList(), 0)
        lastCandidates.clear()
        result.updates.groupBy { it.packageName }.forEach { (pkg, list) ->
            lastCandidates[pkg] = list
        }
        result
    }

    override suspend fun install(packageName: String, source: String?): McpInstallResult {
        val candidates = lastCandidates[packageName]
            ?: runScan().let { lastCandidates[packageName] }
            ?: return McpInstallResult(false, "未在待更新列表中找到该应用：$packageName。请先调用 list_updates。")

        val target = if (source.isNullOrBlank()) {
            candidates.maxByOrNull { it.versionCode } ?: candidates.first()
        } else {
            candidates.firstOrNull { it.source.name.equals(source, ignoreCase = true) }
                ?: return McpInstallResult(
                    false,
                    "来源 $source 对 $packageName 不可用。可用来源：" +
                        candidates.joinToString("、") { it.source.name }
                )
        }

        AppLog.log("McpBridge", "install：$packageName → " + target.version + "（来源 " + target.source.name + "）")
        return try {
            installLink(target)
        } catch (t: Throwable) {
            AppLog.log("McpBridge", "install 失败：$packageName · " + (t.message ?: t.javaClass.simpleName))
            McpInstallResult(false, t.message ?: t.javaClass.simpleName)
        }
    }

    /** 与 InstallViewModel 的分支保持一致：Root 优先，否则走系统安装会话。 */
    private suspend fun installLink(update: AppUpdate): McpInstallResult = when (val link = update.link) {
        Link.Empty -> McpInstallResult(false, "该来源没有提供下载地址")

        is Link.Url -> if (prefs.isRootInstall()) {
            val file = downloader.download(link.link)
            val result = RootInstaller.install(file)
            McpInstallResult(result.success, if (result.success) "Root 静默安装成功" else result.message)
        } else {
            if (!installer.checkPermission()) {
                // 无界面场景下只能明确告知，让 Agent/用户去授权
                return McpInstallResult(false, "缺少「安装未知应用」权限，请在设备上授予后重试")
            }
            val ok = downloader.downloadStream(link.link).use { download ->
                installer.install(update.id, update.packageName, download.stream, download.length)
            }
            McpInstallResult(ok, if (ok) "安装成功" else "安装被取消或失败，详情见设备上的提示")
        }

        is Link.Xapk -> if (prefs.isRootInstall()) {
            // 必须用真实结果：此前这里硬编码 true，导致安装实际失败（会话创建/写入报错）
            // 时也向调用方回报「已提交 Root 安装」，谎报成功。
            val ok = installer.installXapk(
                update.id, update.packageName, downloader.downloadStream(link.link).stream
            )
            McpInstallResult(
                ok,
                if (ok) "分卷包（xapk）安装成功" else "分卷包安装失败，详情见设备上的提示"
            )
        } else {
            McpInstallResult(false, "xapk/apks 分卷包需要 Root 权限才能静默安装")
        }

        is Link.Play -> {
            if (!installer.checkPermission()) {
                return McpInstallResult(false, "缺少「安装未知应用」权限，请在设备上授予后重试")
            }
            val files = link.getInstallFiles()
            if (files.isEmpty()) return McpInstallResult(false, "Play 来源未返回任何安装文件")
            val streams = files.map { downloader.downloadStream(it.url) }
            val ok = try {
                installer.install(
                    update.id,
                    update.packageName,
                    streams.map { it.stream },
                    files.sumOf { it.size }
                )
            } finally {
                streams.forEach { runCatching { it.close() } }
            }
            McpInstallResult(ok, if (ok) "安装成功" else "安装被取消或失败")
        }
    }

    // ------------------------------------------------------------------ 来源开关

    /**
     * 来源名 → 偏好项。名称与 `data/ui/Source.kt` 里定义的一致 ——
     * MCP 对外暴露的就是这些名字（list_updates 的 sources 字段也用它们）。
     */
    private fun sourceToggles(): List<Triple<String, () -> Boolean, (Boolean) -> Unit>> = listOf(
        Triple(GitHubSource.name, { prefs.useGitHub.get() }, { v -> prefs.useGitHub.put(v) }),
        Triple(FdroidSource.name, { prefs.useFdroid.get() }, { v -> prefs.useFdroid.put(v) }),
        Triple(IzzySource.name, { prefs.useIzzy.get() }, { v -> prefs.useIzzy.put(v) }),
        Triple(GitLabSource.name, { prefs.useGitLab.get() }, { v -> prefs.useGitLab.put(v) }),
        Triple(AptoideSource.name, { prefs.useAptoide.get() }, { v -> prefs.useAptoide.put(v) }),
        Triple(ApkPureSource.name, { prefs.useApkPure.get() }, { v -> prefs.useApkPure.put(v) }),
        Triple(PlaySource.name, { prefs.usePlay.get() }, { v -> prefs.usePlay.put(v) }),
        Triple(ApkMirrorSource.name, { prefs.useApkMirror.get() }, { v -> prefs.useApkMirror.put(v) }),
        Triple(TencentSource.name, { prefs.useTencent.get() }, { v -> prefs.useTencent.put(v) })
    )

    override fun sources(): List<McpSource> =
        sourceToggles().map { McpSource(it.first, it.second()) }

    override fun setSourceEnabled(name: String, enabled: Boolean): Boolean {
        val entry = sourceToggles().firstOrNull { it.first.equals(name, ignoreCase = true) }
            ?: return false
        entry.third(enabled)
        AppLog.log("McpBridge", "来源 " + entry.first + (if (enabled) " 已启用" else " 已停用"))
        return true
    }

    override suspend fun rootStatus(): McpRootStatus {
        val available = RootShell.isAvailable(force = true)
        val probe = RootShell.lastProbe
        // 把探测原始输出一并交给 Agent —— 失败原因（未授权 / 无 su / 拿到的 shell 不是 root）
        // 只有从这里才看得出来，比只回一个 false 有用得多
        val detail = if (probe == null) {
            "（尚未探测）"
        } else {
            "via=" + probe.via +
                "; stdout=" + probe.stdout.trim() +
                "; stderr=" + probe.stderr.trim() +
                (probe.error?.let { "; error=" + it } ?: "")
        }
        AppLog.log("McpBridge", "rootStatus：available=" + available + " · " + detail)
        return McpRootStatus(available, detail)
    }

    override fun recentLogs(limit: Int): List<String> = AppLog.recent(limit)
}
