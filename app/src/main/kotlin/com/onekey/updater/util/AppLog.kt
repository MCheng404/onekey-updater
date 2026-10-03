package com.onekey.updater.util

import java.util.ArrayDeque
import java.time.LocalTime
import java.time.format.DateTimeFormatter
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

    /**
     * 时间戳。
     *
     * 这里原本是一个共享的 `SimpleDateFormat`，同时有两个问题：
     *  1. **它不是线程安全的**，而 `log()` 会被 MCP 工作线程、IO 线程、APKMirror 的解析线程
     *     并发调用；`synchronized` 只保护了 buffer，格式化发生在锁外 → 数据竞争，
     *     可能输出错乱的时间戳。
     *  2. Locale 在构造时被固化，用户改了系统语言后日志时间仍按旧语言渲染。
     *
     * `DateTimeFormatter` 不可变且线程安全，并且可以每次按当前默认 Locale 取值；
     * minSdk 26 已有 `java.time`，无需 desugaring。
     */
    private fun stamp(): String =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withLocale(Locale.getDefault())
            .format(LocalTime.now())

    fun log(tag: String, message: String) {
        val line = stamp() + " [" + tag + "] " + message
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
