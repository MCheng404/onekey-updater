package com.onekey.updater.util.mcp

/**
 * 内嵌 MCP 服务的运行状态。
 *
 * 界面会把 [error] **原文直出**给用户（不做二次加工），所以这里放的必须是
 * 人能读懂的中文原因，例如「端口 8765 已被占用」，而不是异常类名或堆栈。
 */
data class McpState(
    val running: Boolean = false,
    val port: Int = 8765,
    /** 实际绑定的地址：默认 "127.0.0.1"，仅在允许局域网时为 "0.0.0.0"。 */
    val boundAddress: String = "127.0.0.1",
    val error: String? = null
)
