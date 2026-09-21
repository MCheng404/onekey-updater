package com.onekey.updater.util.mcp

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.onekey.updater.prefs.Prefs
import com.onekey.updater.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 内嵌的 MCP（Model Context Protocol）服务端。
 *
 * 目的：让 PC 上的 AI Agent 能远程驱动这个更新器 —— 触发扫描、读取待更新列表、
 * 执行安装、读日志。用户在设置页用一个开关启停。
 *
 * === 两个关键设计决定 ===
 *
 * 1. **默认只绑定 127.0.0.1**。设备的 adb 常年连着 PC，用 `adb forward tcp:<port> tcp:<port>`
 *    就能访问，既够用又不必把服务暴露到局域网。只有用户显式打开「允许局域网」才绑定 0.0.0.0，
 *    且那时**强制校验令牌**（回环请求也校验）—— 因为一旦暴露，唯一的安全边界就是令牌。
 *
 * 2. **不引入 HTTP 框架**。Ktor 会让 APK 显著变大，NanoHTTPD 又多一个外部依赖；
 *    这里只需要 POST + JSON，手写一个「读 Content-Length → 解析 → 写响应 → 关闭」
 *    的极简 HTTP/1.1 实现即可，并且能完全控制错误处理。响应统一带 `Connection: close`，
 *    用「每个连接只服务一个请求」换取实现上的确定性（curl 与各 MCP 客户端都支持）。
 *
 * 说明：`protocolVersion` 采用「回显客户端请求的版本」策略，客户端没给时才回落到
 * 2025-06-18。这是为了在没有真实 MCP 客户端联调条件时最大化兼容性 —— 该策略**未经
 * 官方客户端实测**，仅经 curl 验证协议往返正确。
 */
class McpServer(
    private val context: Context,
    private val prefs: Prefs,
    private val bridge: McpBridge,
    private val gson: Gson
) {

    companion object {
        private const val TAG = "McpServer"
        private const val DEFAULT_PROTOCOL_VERSION = "2025-06-18"
        private const val TOKEN_MIN_LENGTH = 16
        private const val TOKEN_LENGTH = 32
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
        private const val MAX_BODY_BYTES = 512 * 1024
        private const val TOOL_TIMEOUT_MS = 120_000L
    }

    private val stateFlow = MutableStateFlow(McpState(port = normalizedPort()))

    private val pool = Executors.newFixedThreadPool(4)
    private val closing = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundPort: Int = 0

    @Volatile
    private var boundAddress: String = "127.0.0.1"

    private val startLock = Any()

    fun state(): StateFlow<McpState> = stateFlow.asStateFlow()

    // ------------------------------------------------------------------ 生命周期

    /**
     * 启动服务。
     *
     * 幂等：已按**当前偏好**运行时直接返回；偏好变了（端口/绑定地址/令牌）则先停再起。
     * 绑定动作放在后台线程执行，方法本身立即返回 —— 因为它会被设置页在主线程调用，
     * 而 `ServerSocket` 绑定属于网络操作。
     */
    fun start() {
        synchronized(startLock) {
            val port = normalizedPort()
            val allowLan = prefs.mcpAllowLan.get()
            val address = if (allowLan) "0.0.0.0" else "127.0.0.1"

            if (serverSocket != null && boundPort == port && boundAddress == address) {
                Log.i(TAG, "已在 $address:$port 运行，忽略重复启动。")
                return
            }

            stopInternal()

            // 令牌为空时自行生成并持久化（契约要求自持久化，UI 不负责）
            val token = ensureToken()
            if (token.length < TOKEN_MIN_LENGTH) {
                // 正常不会走到这里；保留兜底以防偏好被外部改坏
                generateToken()
            }

            closing.set(false)
            stateFlow.value = McpState(running = false, port = port, boundAddress = address)
            AppLog.log(TAG, "正在启动 MCP 服务：$address:$port")

            thread(name = "mcp-accept", isDaemon = true) {
                try {
                    val socket = ServerSocket()
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(InetAddress.getByName(address), port))
                    serverSocket = socket
                    boundPort = port
                    boundAddress = address
                    stateFlow.value = McpState(true, port, address, null)
                    Log.i(TAG, "MCP 服务已监听 $address:$port")
                    AppLog.log(TAG, "MCP 服务已监听 $address:$port")
                    acceptLoop(socket)
                } catch (e: BindException) {
                    fail("端口 $port 已被占用，请改用其它端口", e)
                } catch (e: Exception) {
                    fail("MCP 服务启动失败：" + (e.message ?: e.javaClass.simpleName), e)
                }
            }
        }
    }

    /** 停止服务。幂等，重复调用安全。 */
    fun stop() {
        synchronized(startLock) {
            stopInternal()
            stateFlow.value = McpState(
                running = false,
                port = normalizedPort(),
                boundAddress = if (prefs.mcpAllowLan.get()) "0.0.0.0" else "127.0.0.1",
                error = null
            )
            AppLog.log(TAG, "MCP 服务已停止")
        }
    }

    private fun stopInternal() {
        closing.set(true)
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = 0
    }

    private fun fail(message: String, e: Throwable) {
        Log.e(TAG, message, e)
        AppLog.log(TAG, message)
        serverSocket = null
        stateFlow.value = McpState(
            running = false,
            port = normalizedPort(),
            boundAddress = if (prefs.mcpAllowLan.get()) "0.0.0.0" else "127.0.0.1",
            error = message
        )
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!closing.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (closing.get()) break else continue
            }
            pool.execute {
                runCatching { handle(client) }
                    .onFailure { Log.w(TAG, "处理请求失败", it) }
                runCatching { client.close() }
            }
        }
    }

    /** 释放线程池。仅在应用退出时调用。 */
    fun shutdown() {
        stop()
        pool.shutdownNow()
    }

    // ------------------------------------------------------------------ 令牌

    private fun ensureToken(): String {
        val existing = prefs.mcpToken.get()
        if (existing.length >= TOKEN_MIN_LENGTH) return existing
        return generateToken()
    }

    /** 生成新令牌并**持久化**，返回之（契约要求自持久化）。 */
    fun regenerateToken(): String = generateToken()

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_LENGTH)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.mcpToken.put(token)
        AppLog.log(TAG, "已重新生成 MCP 访问令牌")
        return token
    }

    // ------------------------------------------------------------------ HTTP

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            respond(output, 400, "Bad Request", null)
            return
        }
        val method = parts[0].uppercase()
        val rawPath = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength > MAX_BODY_BYTES) {
            respond(output, 413, "Payload Too Large", null)
            return
        }
        val body = if (contentLength > 0) readN(input, contentLength) else ByteArray(0)

        // 仅回环访问豁免令牌；一旦允许局域网，所有请求（含回环）都必须带令牌。
        val fromLoopback = client.inetAddress?.isLoopbackAddress == true
        val allowLan = prefs.mcpAllowLan.get()
        if (!fromLoopback && !allowLan) {
            Log.w(TAG, "拒绝来自非回环地址的访问：" + client.inetAddress)
            respond(output, 403, "Forbidden", "服务仅监听本机。如需局域网访问请在设置中打开「允许局域网」。")
            return
        }
        if (allowLan || !fromLoopback) {
            if (!authorized(headers, rawPath)) {
                respond(output, 401, "Unauthorized", "令牌无效或缺失。")
                return
            }
        }

        // 健康检查，方便用户用浏览器确认服务是否活着
        if (method == "GET") {
            respond(output, 200, "OK", gson.toJson(mapOf("status" to "ok", "server" to "onekey-updater-mcp")))
            return
        }
        if (method != "POST") {
            respond(output, 405, "Method Not Allowed", null)
            return
        }

        val payload = String(body, Charsets.UTF_8)
        val response = dispatch(payload)
        if (response == null) {
            // 通知（notification）：没有 id，按 JSON-RPC 规范不回响应体
            respond(output, 202, "Accepted", null)
        } else {
            respond(output, 200, "OK", response)
        }
    }

    private fun authorized(headers: Map<String, String>, rawPath: String): Boolean {
        val expected = prefs.mcpToken.get()
        if (expected.length < TOKEN_MIN_LENGTH) return false

        val bearer = headers["authorization"]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)
            ?.trim()
        val queryToken = rawPath.substringAfter("token=", "")
            .substringBefore('&')
            .takeIf { it.isNotEmpty() }

        val supplied = bearer ?: queryToken ?: return false
        // 恒定时间比较，避免通过响应时间侧漏令牌
        return MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    private fun respond(output: OutputStream, code: Int, reason: String, json: String?) {
        val bodyBytes = json?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        if (bodyBytes.isNotEmpty()) output.write(bodyBytes)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val data = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(data, read, n - read)
            if (r < 0) break
            read += r
        }
        return if (read == n) data else data.copyOf(read)
    }

    // ------------------------------------------------------------------ JSON-RPC / MCP

    /** @return 响应 JSON；返回 null 表示这是通知，不应有响应体。 */
    private fun dispatch(payload: String): String? {
        val root: JsonElement = try {
            JsonParser.parseString(payload)
        } catch (e: Exception) {
            return errorResponse(null, -32700, "JSON 解析失败")
        }
        if (!root.isJsonObject) {
            return errorResponse(null, -32600, "仅支持单个 JSON-RPC 请求对象")
        }
        val req = root.asJsonObject
        val id = req.get("id")
        val method = req.get("method")?.takeIf { it.isJsonPrimitive }?.asString

        if (method == null) return errorResponse(id, -32600, "缺少 method 字段")

        // 通知：无 id，处理完不回包
        val isNotification = id == null || id.isJsonNull
        if (method.startsWith("notifications/")) return null

        return try {
            val result = when (method) {
                "initialize" -> initialize(req)
                "ping" -> JsonObject()
                "tools/list" -> toolsList()
                "tools/call" -> toolsCall(req)
                else -> return errorResponse(id, -32601, "未知方法：$method")
            }
            successResponse(id, result)
        } catch (e: Exception) {
            Log.e(TAG, "处理方法 $method 失败", e)
            errorResponse(id, -32603, e.message ?: e.javaClass.simpleName)
        }.also {
            if (isNotification) return null
        }
    }

    private fun initialize(req: JsonObject): JsonObject {
        // 回显客户端请求的协议版本：在没有官方客户端联调条件时，这比硬编码一个版本更不容易失配。
        val requested = req.getAsJsonObject("params")
            ?.get("protocolVersion")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString

        return JsonObject().apply {
            addProperty("protocolVersion", requested?.takeIf { it.isNotBlank() } ?: DEFAULT_PROTOCOL_VERSION)
            add(
                "capabilities",
                JsonObject().apply {
                    add("tools", JsonObject().apply { addProperty("listChanged", false) })
                }
            )
            add(
                "serverInfo",
                JsonObject().apply {
                    addProperty("name", "onekey-updater")
                    addProperty("version", appVersionName())
                }
            )
        }
    }

    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    private fun toolsList(): JsonObject {
        val tools = JsonArray()

        tools.add(tool(
            "list_updates",
            "列出当前待更新应用。会先执行一次完整扫描。同一应用的多个可用来源合并为一条，sources 字段列出全部来源，recommendedSource 是推荐来源。",
            JsonObject().apply { addProperty("type", "object"); add("properties", JsonObject()) }
        ))
        tools.add(tool(
            "scan_updates",
            "触发一次更新扫描，返回参与检查的应用数与发现的更新数。",
            JsonObject().apply { addProperty("type", "object"); add("properties", JsonObject()) }
        ))
        tools.add(tool(
            "install_update",
            "安装指定应用的更新。不传 source 时使用推荐来源。注意：未获取 Root 时会弹出系统安装确认框。",
            JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("packageName", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "要安装的应用包名，例如 com.example.app")
                    })
                    add("source", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "可选，指定更新来源名（见 list_updates 的 sources）")
                    })
                })
                add("required", JsonArray().apply { add("packageName") })
            }
        ))
        tools.add(tool(
            "list_sources",
            "列出全部更新源及其启用状态。",
            JsonObject().apply { addProperty("type", "object"); add("properties", JsonObject()) }
        ))
        tools.add(tool(
            "set_source_enabled",
            "启用或停用某个更新源。",
            JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("source", JsonObject().apply { addProperty("type", "string") })
                    add("enabled", JsonObject().apply { addProperty("type", "boolean") })
                })
                add("required", JsonArray().apply { add("source"); add("enabled") })
            }
        ))
        tools.add(tool(
            "get_root_status",
            "查询 Root 权限是否可用，并返回探测的原始输出，便于判断失败原因。",
            JsonObject().apply { addProperty("type", "object"); add("properties", JsonObject()) }
        ))
        tools.add(tool(
            "get_recent_logs",
            "读取最近的业务日志，用于排查「为什么没有更新」之类的问题。",
            JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("limit", JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "返回条数，默认 80")
                    })
                })
            }
        ))
        return JsonObject().apply { add("tools", tools) }
    }

    private fun tool(name: String, description: String, schema: JsonObject) = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        add("inputSchema", schema)
    }

    private fun toolsCall(req: JsonObject): JsonObject {
        val params = req.getAsJsonObject("params")
            ?: throw IllegalArgumentException("缺少 params")
        val name = params.get("name")?.asString
            ?: throw IllegalArgumentException("缺少工具名")
        val args = params.getAsJsonObject("arguments") ?: JsonObject()

        val text = runBlocking {
            withTimeout(TOOL_TIMEOUT_MS) { runTool(name, args) }
        }
        return JsonObject().apply {
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", text)
                })
            })
            addProperty("isError", false)
        }
    }

    private suspend fun runTool(name: String, args: JsonObject): String = when (name) {
        "list_updates" -> gson.toJson(bridge.listUpdates())
        "scan_updates" -> gson.toJson(bridge.scanUpdates())

        "install_update" -> {
            val pkg = args.get("packageName")?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw IllegalArgumentException("缺少 packageName")
            val source = args.get("source")?.takeIf { it.isJsonPrimitive }?.asString
            gson.toJson(bridge.install(pkg, source))
        }

        "list_sources" -> gson.toJson(bridge.sources())

        "set_source_enabled" -> {
            val source = args.get("source")?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw IllegalArgumentException("缺少 source")
            val enabled = args.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean
                ?: throw IllegalArgumentException("缺少 enabled")
            val ok = bridge.setSourceEnabled(source, enabled)
            gson.toJson(
                if (ok) McpInstallResult(true, "已" + (if (enabled) "启用" else "停用") + "来源：$source")
                else McpInstallResult(false, "未知来源：$source")
            )
        }

        "get_root_status" -> gson.toJson(bridge.rootStatus())

        "get_recent_logs" -> {
            val limit = args.get("limit")?.takeIf { it.isJsonPrimitive }?.asInt ?: 80
            bridge.recentLogs(limit).joinToString("\n")
        }

        else -> throw IllegalArgumentException("未知工具：$name")
    }

    private fun successResponse(id: JsonElement?, result: JsonElement): String {
        val obj = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            if (id != null) add("id", id) else add("id", com.google.gson.JsonNull.INSTANCE)
            add("result", result)
        }
        return gson.toJson(obj)
    }

    private fun errorResponse(id: JsonElement?, code: Int, message: String): String {
        val obj = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            if (id != null && !id.isJsonNull) add("id", id) else add("id", com.google.gson.JsonNull.INSTANCE)
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            })
        }
        return gson.toJson(obj)
    }

    private fun normalizedPort(): Int {
        val raw = runCatching { prefs.mcpPort.get() }.getOrDefault(8765)
        return if (raw in MIN_PORT..MAX_PORT) raw else 8765
    }
}
