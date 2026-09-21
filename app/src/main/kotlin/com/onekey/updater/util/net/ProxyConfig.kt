package com.onekey.updater.util.net

import com.onekey.updater.prefs.Prefs
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 应用内代理配置。
 *
 * 为什么需要：部分用户（尤其是用国内网络 + 自建代理）希望所有更新检查与下载都走自己的代理，
 * 而不是依赖系统全局代理 —— 系统代理对 App 是否生效取决于厂商实现，不可控。
 *
 * 这里统一在 OkHttp 层设置，因此 Retrofit、Downloader、Coil 三条链路自动全部生效
 * （它们共用同一个 OkHttpClient 实例）。
 *
 * 刻意不做的事：
 *  · 不在这里做连通性校验 —— 代理不可用时表现为请求失败，用户能从诊断界面看到；
 *  · 不缓存 Proxy 对象 —— 偏好一变就要立刻生效，缓存会让"改了没反应"。
 */
object ProxyConfig {

    /** 代理类型选项下标。 */
    const val TYPE_HTTP = 0
    const val TYPE_SOCKS = 1

    /**
     * 按当前偏好构造 OkHttp 用的 Proxy。
     *
     * @return null 表示不使用代理（要么没开启，要么主机/端口不合法）。
     *         返回 null 时调用方**不应**设置 proxy，交给系统默认处理。
     */
    fun from(prefs: Prefs): Proxy? {
        if (!prefs.proxyEnabled.get()) return null
        val host = prefs.proxyHost.get().trim()
        val port = prefs.proxyPort.get()
        // 主机为空或端口越界都视为无效配置：宁可直连，也不要把所有请求打到坏地址上
        if (host.isEmpty() || port !in 1..65535) return null
        val type = if (prefs.proxyType.get() == TYPE_SOCKS) {
            Proxy.Type.SOCKS
        } else {
            Proxy.Type.HTTP
        }
        return runCatching { Proxy(type, InetSocketAddress(host, port)) }.getOrNull()
    }
}
