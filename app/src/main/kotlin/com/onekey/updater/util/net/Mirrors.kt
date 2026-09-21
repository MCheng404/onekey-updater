package com.onekey.updater.util.net

import java.net.URL

/**
 * 一条可选的镜像 / 加速线路。
 *
 * @param label 展示名
 * @param value 线路值：加速前缀（以 / 结尾）或仓库根地址（以 / 结尾）；空串表示「直连官方」
 * @param note  补充说明（例如是否已验证）
 */
data class MirrorOption(
    val label: String,
    val value: String,
    val note: String = ""
)

/**
 * 国内可用线路清单。
 *
 * GitHub 加速前缀 = **内置 78 条公益节点（离线兜底）** + **运行时远程刷新**。
 * 这 78 条来自 github.com/lopinnn56/github-fast 的 DEFAULT_NODES，已逐条确认是 https 前缀；
 * 即使完全断网也能用。运行时用户可触发 [com.onekey.updater.util.net.NodeRefresher] 从聚合源拉取
 * 更新、校验后替换本清单（并缓存到 Prefs），详见该类的说明。
 *
 * F-Droid / Izzy 仓库镜像保持原有已验证清单不变。
 */
object Mirrors {

    /** 「直连官方」在下拉列表中的下标占位。 */
    const val DIRECT = 0

    /** 自定义线路的特殊标记值。 */
    const val CUSTOM = "__custom__"

    private val DIRECT_OPTION = MirrorOption("直连 GitHub", "", "不经过任何代理")
    private val CUSTOM_OPTION = MirrorOption("自定义…", CUSTOM, "在下方填写自己的加速前缀")

    /**
     * GitHub 加速前缀（内置兜底清单，78 条公益节点）。
     * 来源：github.com/lopinnn56/github-fast 内置 DEFAULT_NODES（采集自 github.akams.cn 聚合站）。
     * 这些是离线兜底——网络不通时照样能选；运行时的远程刷新清单见 [applyRemote]。
     */
    private val BUILTIN_GITHUB_PROXIES = listOf(
        MirrorOption("ghproxy.net", "https://ghproxy.net/"),
        MirrorOption("gh.dpik.top", "https://gh.dpik.top/"),
        MirrorOption("github.tbap.top", "https://github.tbap.top/"),
        MirrorOption("cdn.gh-proxy.com", "https://cdn.gh-proxy.com/"),
        MirrorOption("ghfile.geekertao.top", "https://ghfile.geekertao.top/"),
        MirrorOption("github.dpik.top", "https://github.dpik.top/"),
        MirrorOption("github-proxy.memory-echoes.cn", "https://github-proxy.memory-echoes.cn/"),
        MirrorOption("gh.bugdey.us.kg", "https://gh.bugdey.us.kg/"),
        MirrorOption("jiashu.1win.eu.org", "https://jiashu.1win.eu.org/"),
        MirrorOption("gh.927223.xyz", "https://gh.927223.xyz/"),
        MirrorOption("cdn.akaere.online", "https://cdn.akaere.online/"),
        MirrorOption("gh.felicity.ac.cn", "https://gh.felicity.ac.cn/"),
        MirrorOption("down.mxw.qzz.io", "https://down.mxw.qzz.io/"),
        MirrorOption("github.mxw.qzz.io", "https://github.mxw.qzz.io/"),
        MirrorOption("gh.inkchills.cn", "https://gh.inkchills.cn/"),
        MirrorOption("gh.acmsz.top", "https://gh.acmsz.top/"),
        MirrorOption("gitproxy.mrhjx.cn", "https://gitproxy.mrhjx.cn/"),
        MirrorOption("gh.ddlc.top", "https://gh.ddlc.top/"),
        MirrorOption("gh-proxy.com", "https://gh-proxy.com/"),
        MirrorOption("gh-proxy.net", "https://gh-proxy.net/"),
        MirrorOption("j.1lin.dpdns.org", "https://j.1lin.dpdns.org/"),
        MirrorOption("github.starrlzy.cn", "https://github.starrlzy.cn/"),
        MirrorOption("git.yylx.win", "https://git.yylx.win/"),
        MirrorOption("ghm.078465.xyz", "https://ghm.078465.xyz/"),
        MirrorOption("ghf.xn--eqrr82bzpe.top", "https://ghf.xn--eqrr82bzpe.top/"),
        MirrorOption("tvv.tw", "https://tvv.tw/"),
        MirrorOption("j.1win.ggff.net", "https://j.1win.ggff.net/"),
        MirrorOption("gitproxy.127731.xyz", "https://gitproxy.127731.xyz/"),
        MirrorOption("gh.catmak.name", "https://gh.catmak.name/"),
        MirrorOption("gh.b52m.cn", "https://gh.b52m.cn/"),
        MirrorOption("down.mxw.xx.kg", "https://down.mxw.xx.kg/"),
        MirrorOption("gh.jjj.gv.uy", "https://gh.jjj.gv.uy/"),
        MirrorOption("slink.ltd", "https://slink.ltd/"),
        MirrorOption("github.tmby.shop", "https://github.tmby.shop/"),
        MirrorOption("ghpr.cc", "https://ghpr.cc/"),
        MirrorOption("gh.tryxd.cn", "https://gh.tryxd.cn/"),
        MirrorOption("gitproxy.click", "https://gitproxy.click/"),
        MirrorOption("github.chenc.dev", "https://github.chenc.dev/"),
        MirrorOption("gh.sixyin.com", "https://gh.sixyin.com/"),
        MirrorOption("gh.monlor.com", "https://gh.monlor.com/"),
        MirrorOption("ghpxy.hwinzniej.top", "https://ghpxy.hwinzniej.top/"),
        MirrorOption("git.669966.xyz", "https://git.669966.xyz/"),
        MirrorOption("ghfast.top", "https://ghfast.top/"),
        MirrorOption("gh.jasonzeng.dev", "https://gh.jasonzeng.dev/"),
        MirrorOption("github.geekery.cn", "https://github.geekery.cn/"),
        MirrorOption("gp.zkitefly.eu.org", "https://gp.zkitefly.eu.org/"),
        MirrorOption("fastgit.cc", "https://fastgit.cc/"),
        MirrorOption("ghproxy.1888866.xyz", "https://ghproxy.1888866.xyz/"),
        MirrorOption("ghp.arslantu.xyz", "https://ghp.arslantu.xyz/"),
        MirrorOption("github.ednovas.xyz", "https://github.ednovas.xyz/"),
        MirrorOption("ghproxy.imciel.com", "https://ghproxy.imciel.com/"),
        MirrorOption("ghproxy.cxkpro.top", "https://ghproxy.cxkpro.top/"),
        MirrorOption("github.xxlab.tech", "https://github.xxlab.tech/"),
        MirrorOption("gh.idayer.com", "https://gh.idayer.com/"),
        MirrorOption("free.cn.eu.org", "https://free.cn.eu.org/"),
        MirrorOption("gh.chjina.com", "https://gh.chjina.com/"),
        MirrorOption("ghp.keleyaa.com", "https://ghp.keleyaa.com/"),
        MirrorOption("proxy.yaoyaoling.net", "https://proxy.yaoyaoling.net/"),
        MirrorOption("ghproxy.monkeyray.net", "https://ghproxy.monkeyray.net/"),
        MirrorOption("gh.noki.icu", "https://gh.noki.icu/"),
        MirrorOption("g.blfrp.cn", "https://g.blfrp.cn/"),
        MirrorOption("githubdog.com", "https://githubdog.com/"),
        MirrorOption("gh.meali.top", "https://gh.meali.top/"),
        MirrorOption("777.z321.cc.cd", "https://777.z321.cc.cd/"),
        MirrorOption("gg.z321.cc.cd", "https://gg.z321.cc.cd/"),
        MirrorOption("g.z321.cc.cd", "https://g.z321.cc.cd/"),
        MirrorOption("js.jiangss.shop", "https://js.jiangss.shop/"),
        MirrorOption("gap.andyjin.website", "https://gap.andyjin.website/"),
        MirrorOption("gh.my-website.ccwu.cc", "https://gh.my-website.ccwu.cc/"),
        MirrorOption("github.ikgy.top", "https://github.ikgy.top/"),
        MirrorOption("gh.07150721.xyz", "https://gh.07150721.xyz/"),
        MirrorOption("cfgh.ikgy.top", "https://cfgh.ikgy.top/"),
        MirrorOption("xsadwsd.kdns.fr", "https://xsadwsd.kdns.fr/"),
        MirrorOption("gh.ruan.dpdns.org", "https://gh.ruan.dpdns.org/"),
        MirrorOption("ghproxy.felicity.land", "https://ghproxy.felicity.land/"),
        MirrorOption("github.nswrz.cn", "https://github.nswrz.cn/"),
        MirrorOption("gh.zhai.edu.pl", "https://gh.zhai.edu.pl/"),
        MirrorOption("gh.qfmc0721.cc.cd", "https://gh.qfmc0721.cc.cd/")
    )

    /**
     * 当前生效的 GitHub 加速前缀列表。
     * 构成 = 直连占位 +（远程刷新清单 或 内置 78 条）+ 自定义占位。
     *
     * 关键约束：本属性在每次 HTTP 请求（[com.onekey.updater.util.net.MirrorResolver.resolve]）
     * 和网络诊断时都被读取，因此必须是**纯内存只读**——绝不在此做网络或重活，所有计算
     * （含远程刷新、校验）都提前在 [applyRemote] / [NodeRefresher] 里完成。
     */
    @Volatile
    var githubProxies: List<MirrorOption> = listOf(DIRECT_OPTION) + BUILTIN_GITHUB_PROXIES + listOf(CUSTOM_OPTION)
        private set

    /**
     * 用远程刷新得到的、已校验清单替换内置节点集合（直连与自定义项始终保留）。
     * 空清单时忽略，保留现有列表。
     */
    fun applyRemote(nodes: List<MirrorOption>) {
        if (nodes.isEmpty()) return
        githubProxies = listOf(DIRECT_OPTION) + nodes + listOf(CUSTOM_OPTION)
    }

    /** 回退到内置 78 条（远程刷新失败时用）。 */
    fun resetToBuiltin() {
        githubProxies = listOf(DIRECT_OPTION) + BUILTIN_GITHUB_PROXIES + listOf(CUSTOM_OPTION)
    }

    /** 由前缀构造一个展示项：展示名取其 host，便于用户在下拉里辨认。 */
    fun optionFromPrefix(prefix: String): MirrorOption {
        val host = runCatching { URL(prefix).host }.getOrDefault(prefix)
        return MirrorOption(host, prefix, "远程刷新")
    }

    val fdroidMirrors = listOf(
        MirrorOption("F-Droid 官方", "https://f-droid.org/repo/", "官方源，国内直连较慢"),
        MirrorOption("清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo/", "已验证，最快"),
        MirrorOption("南京大学 NJU", "https://mirror.nju.edu.cn/fdroid/repo/", "已验证"),
        MirrorOption("自定义…", CUSTOM, "在下方填写完整的 repo 根地址")
    )

    val izzyMirrors = listOf(
        MirrorOption("IzzyOnDroid 官方", "https://apt.izzysoft.de/fdroid/repo/", "暂无国内镜像"),
        MirrorOption("自定义…", CUSTOM, "在下方填写完整的 repo 根地址")
    )

    /** 会被加速前缀接管的主机。 */
    val GITHUB_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "codeload.github.com",
        "objects.githubusercontent.com",
        "raw.githubusercontent.com",
        "gist.githubusercontent.com",
        "github-releases.githubusercontent.com"
    )
}
