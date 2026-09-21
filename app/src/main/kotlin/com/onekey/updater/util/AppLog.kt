package com.onekey.updater.util

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 进程内的轻量业务日志环形缓冲。
 *
 * 为什么需要它：MCP 的 `get_recent_logs` 要能让远端的 Agent 自查「为什么没有更新」。
 * 直接读 logcat 需要 root，而本应用在未授权时读不到自己的日志；
 * 因此这里自己留一份关键事件，保证在任何情况下都能给出可用的上下文。
 *
 * 只记录**业务事件**（扫描结果、安装动作、Root 探测、MCP 请求），不记录高频内容。
 */
object AppLog {

    private const val CAPACITY = 300

    private val buffer = ArrayDeque<String>(CAPACITY)

    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(tag: String, message: String) {
        val line = formatter.format(Date()) + " [" + tag + "] " + message
        synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(line)
        }
    }

    /** 取最近 [limit] 条，按时间正序（最旧的在前）。 */
    fun recent(limit: Int): List<String> = synchronized(buffer) {
        if (limit <= 0 || limit >= buffer.size) buffer.toList() else buffer.toList().takeLast(limit)
    }

    fun clear() = synchronized(buffer) { buffer.clear() }
}
