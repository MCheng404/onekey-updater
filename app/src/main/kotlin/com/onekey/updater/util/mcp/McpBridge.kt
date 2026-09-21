package com.onekey.updater.util.mcp

/**
 * MCP 服务端与数据层之间的窄接口。
 *
 * 为什么不直接在服务端里取 Koin：服务端只应关心「协议如何编解码」，
 * 数据从哪来、怎么算，属于数据层的职责。把这条路用接口切干净之后，
 * 服务端可以单独测试（喂一个假实现即可），数据层换实现也不会牵动协议代码。
 */
interface McpBridge {

    /** 读取当前的待更新列表（按应用归并，与界面显示一致）。会先做一次扫描。 */
    suspend fun listUpdates(): McpListResult

    /** 触发一次扫描，只返回汇总数字，不返回明细。 */
    suspend fun scanUpdates(): McpScanSummary

    /**
     * 安装指定应用。
     * @param source 可选，指定从哪个来源安装；为空则用推荐来源（版本号最高者）。
     */
    suspend fun install(packageName: String, source: String?): McpInstallResult

    fun sources(): List<McpSource>

    /** @return 是否成功切换（来源名不存在时返回 false）。 */
    fun setSourceEnabled(name: String, enabled: Boolean): Boolean

    suspend fun rootStatus(): McpRootStatus

    fun recentLogs(limit: Int): List<String>
}

// ---------------------------------------------------------------------------
// 对外 DTO：字段名即 Agent 看到的 JSON 键名，保持简短、自解释。
// ---------------------------------------------------------------------------

data class McpAppUpdate(
    val name: String,
    val packageName: String,
    val installedVersion: String,
    val targetVersion: String,
    /** 该应用可用的全部来源名。 */
    val sources: List<String>,
    /** 推荐来源（版本号最高的那个）。 */
    val recommendedSource: String,
    val systemApp: Boolean
)

data class McpListResult(
    /** 本轮参与检查的应用数。 */
    val scannedApps: Int,
    val updateCount: Int,
    val updates: List<McpAppUpdate>
)

data class McpScanSummary(
    val scannedApps: Int,
    val updateCount: Int
)

data class McpInstallResult(
    val success: Boolean,
    val message: String
)

data class McpSource(
    val name: String,
    val enabled: Boolean
)

data class McpRootStatus(
    val available: Boolean,
    /** 探测的原始输出，便于 Agent 判断失败原因（例如未授权、无 su 二进制）。 */
    val detail: String
)
