//! 后端文案的多语言支持。
//!
//! 为什么需要它：日志面板的内容是 Rust 生成的（"发现 N 个 winget 软件可更新"等），
//! 前端 i18n 管不到 —— 结果切到英文后，界面全英文、唯独日志还是中文。
//! 这里给后端一小张消息表，语言由前端在启动时和切换语言时推过来（`set_language`），
//! 这样日志面板、通知、日志文件三处拿到的是**同一种语言**的成品文本。
//!
//! 约定：
//! - key 与前端 i18n.ts 的命名空间保持一致（如 `group.npm` 两边同 key 同文案）；
//! - 文案里的 `{}` 是占位符，按 `tr()` 传入参数的顺序依次替换。

use std::sync::atomic::{AtomicU8, Ordering};

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Lang {
    Zh,
    En,
}

/// 0 = 中文，1 = 英文。默认中文（与前端 detectLang 的兜底一致）
static CURRENT: AtomicU8 = AtomicU8::new(0);

/// 语言落盘位置：`%LOCALAPPDATA%\onekey-updater\lang.txt`
///
/// 前端把语言存在 localStorage，但后端的 setup 阶段（前端 JS 还没跑）就要产出
/// 日志文案（"已加载 N 个系统字体"），所以后端必须自己留一份，启动时先恢复。
fn lang_path() -> Option<std::path::PathBuf> {
    let local = std::env::var("LOCALAPPDATA").ok()?;
    Some(
        std::path::PathBuf::from(local)
            .join("onekey-updater")
            .join("lang.txt"),
    )
}

pub fn set_lang(code: &str) {
    let en = code.to_ascii_lowercase().starts_with("en");
    CURRENT.store(u8::from(en), Ordering::Relaxed);

    if let Some(path) = lang_path() {
        if let Some(dir) = path.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let _ = std::fs::write(&path, if en { "en-US" } else { "zh-CN" });
    }
}

/// 启动时恢复上次使用的语言（在 setup 之前调用，越早越好）
pub fn restore() {
    let Some(path) = lang_path() else {
        return;
    };
    let Ok(code) = std::fs::read_to_string(&path) else {
        return;
    };
    let en = code.trim().to_ascii_lowercase().starts_with("en");
    CURRENT.store(u8::from(en), Ordering::Relaxed);
}

pub fn lang() -> Lang {
    if CURRENT.load(Ordering::Relaxed) == 1 {
        Lang::En
    } else {
        Lang::Zh
    }
}

/// (key, 中文, English)
static TABLE: &[(&str, &str, &str)] = &[
    // ---- 通用 ----
    ("common.unknown", "未知", "unknown"),
    // ---- 分组名（与前端 group.* 同 key 同文案）----
    ("group.npm", "npm 全局包", "npm Global Packages"),
    ("group.winget", "winget 软件", "winget Software"),
    ("group.pip", "pip 包", "pip Packages"),
    ("group.openclaw", "OpenClaw", "OpenClaw"),
    // ---- 通用动作 ----
    (
        "update.starting",
        "正在更新 {}...",
        "Updating {}...",
    ),
    ("update.done", "{} 更新完成", "{} updated"),
    ("update.failed", "{} 更新失败", "{} update failed"),
    // ---- 调度层（lib.rs）----
    (
        "log.threadPanic",
        "某个更新源线程异常终止，已跳过",
        "An update source thread died unexpectedly; skipped",
    ),
    ("log.skipMissing", "跳过 {}（未安装）", "Skipping {} (not installed)"),
    (
        "log.fontsFailed",
        "未能读取系统字体（PowerShell 调用失败）",
        "Could not read system fonts (PowerShell call failed)",
    ),
    ("log.fontsLoaded", "已加载 {} 个系统字体", "Loaded {} system fonts"),
    // ---- npm ----
    (
        "npm.checking",
        "正在检查 npm 全局包更新...",
        "Checking npm global packages for updates...",
    ),
    ("npm.checkFailed", "npm 检查失败：{}", "npm check failed: {}"),
    (
        "npm.parseFailed",
        "npm 输出解析失败：{}",
        "Failed to parse npm output: {}",
    ),
    (
        "npm.allLatest",
        "npm 全局包全部是最新版本",
        "All npm global packages are up to date",
    ),
    (
        "npm.skipDelegated",
        "跳过 {}（由专用源处理）",
        "Skipping {} (handled by a dedicated source)",
    ),
    (
        "npm.skipNotNewer",
        "{} 目标 {} 不高于已装 {}，忽略",
        "{}: target {} is not newer than installed {}; ignored",
    ),
    ("npm.found", "发现 {} 个 npm 包可更新", "Found {} npm package(s) to update"),
    (
        "npm.fallback",
        "{} 安装失败，回退到 @latest...",
        "{} install failed; falling back to @latest...",
    ),
    (
        "npm.fallbackDone",
        "{}@latest 回退更新完成",
        "{}@latest updated via fallback",
    ),
    (
        "npm.retryTemp",
        "直接自更新失败，改用临时 npm（npx npm@{}）重试...",
        "Self-update failed; retrying with a temporary npm (npx npm@{})...",
    ),
    (
        "npm.doneViaTemp",
        "{} 更新完成（经由临时 npm）",
        "{} updated (via temporary npm)",
    ),
    (
        "npm.brokenTree",
        "npm 自身依赖树疑似损坏（Cannot find module）。",
        "npm's own dependency tree looks broken (Cannot find module).",
    ),
    (
        "npm.brokenTreeFix",
        "修复方法：用另一份 Node 自带的 npm 执行 install -g npm@latest，或重新安装 Node.js。",
        "Fix: run install -g npm@latest with the npm bundled with another Node install, or reinstall Node.js.",
    ),
    // ---- pip ----
    ("pip.checking", "正在检查 pip 包更新...", "Checking pip packages for updates..."),
    ("pip.checkFailed", "pip 检查失败：{}", "pip check failed: {}"),
    (
        "pip.parseFailed",
        "pip 输出解析失败：{}",
        "Failed to parse pip output: {}",
    ),
    ("pip.allLatest", "pip 包全部是最新版本", "All pip packages are up to date"),
    ("pip.found", "发现 {} 个 pip 包可更新", "Found {} pip package(s) to update"),
    // ---- winget ----
    (
        "winget.checking",
        "正在检查 winget 软件更新...",
        "Checking winget packages for updates...",
    ),
    ("winget.checkFailed", "winget 检查失败：{}", "winget check failed: {}"),
    (
        "winget.allLatest",
        "winget 软件全部是最新版本",
        "All winget packages are up to date",
    ),
    (
        "winget.found",
        "发现 {} 个 winget 软件可更新",
        "Found {} winget package(s) to update",
    ),
    (
        "winget.badArgs",
        "winget 参数不被当前版本支持",
        "The installed winget version does not support these arguments",
    ),
    // ---- OpenClaw ----
    (
        "openclaw.checking",
        "正在检查 OpenClaw 更新...",
        "Checking OpenClaw for updates...",
    ),
    ("openclaw.current", "当前版本：{}", "Current version: {}"),
    ("openclaw.stable", "正式版最新：{}", "Latest stable: {}"),
    ("openclaw.betaLatest", "beta 最新：{}", "Latest beta: {}"),
    (
        "openclaw.noVersion",
        "无法获取 OpenClaw 版本信息",
        "Could not retrieve OpenClaw version info",
    ),
    (
        "openclaw.upToDate",
        "OpenClaw 已是最新版本（{}，{} 通道）",
        "OpenClaw is up to date ({} on the {} channel)",
    ),
    (
        "openclaw.available",
        "OpenClaw 可更新：{} → {} ({})",
        "OpenClaw can be updated: {} → {} ({})",
    ),
    (
        "openclaw.updating",
        "正在更新 OpenClaw 到 {}...",
        "Updating OpenClaw to {}...",
    ),
    (
        "openclaw.stopping",
        "正在停止 OpenClaw gateway...",
        "Stopping the OpenClaw gateway...",
    ),
    ("openclaw.adminInstall", "使用管理员权限安装...", "Installing with administrator rights..."),
    (
        "openclaw.userInstall",
        "非管理员模式，尝试用户目录安装...",
        "Not running as administrator; trying a user-directory install...",
    ),
    (
        "openclaw.reinstall",
        "正在重装 gateway 服务...",
        "Reinstalling the gateway service...",
    ),
    ("openclaw.restarting", "正在重启 gateway...", "Restarting the gateway..."),
    ("openclaw.done", "OpenClaw 更新完成（{}）", "OpenClaw updated ({})"),
    (
        "openclaw.gatewayFailed",
        "{} 已安装，但 gateway 未能重启（端口可能被占用），请手动执行 openclaw gateway install --force",
        "{} installed, but the gateway did not restart (the port may be in use). Run openclaw gateway install --force manually.",
    ),
    ("openclaw.failed", "OpenClaw 更新失败", "OpenClaw update failed"),
    (
        "openclaw.adminHint",
        "提示：可能需要以管理员身份运行",
        "Hint: you may need to run as administrator",
    ),
    // ---- 子进程与杂项错误 ----
    // 这些会**嵌在**已翻译的日志里（如「pip 检查失败：pip 执行超时（45秒）」），
    // 所以必须一起本地化，否则英文模式下仍会夹半句中文。
    ("util.execFailed", "执行 {} 失败: {}", "Failed to run {}: {}"),
    ("util.timeout", "{} 执行超时（{}秒）", "{} timed out after {}s"),
    ("util.spawnFailed", "启动 {} 失败: {}", "Failed to start {}: {}"),
    ("util.waitFailed", "等待 {} 失败: {}", "Failed waiting for {}: {}"),
    (
        "util.waitExitFailed",
        "等待 {} 结束失败: {}",
        "Failed waiting for {} to exit: {}",
    ),
    (
        "util.writeScriptFailed",
        "写入临时脚本失败: {}",
        "Failed to write the temporary script: {}",
    ),
    ("lib.logDirFailed", "无法定位日志目录", "Cannot locate the log directory"),
    (
        "lib.exePathFailed",
        "获取可执行文件路径失败: {}",
        "Cannot resolve the executable path: {}",
    ),
    ("lib.regWriteFailed", "写入注册表失败", "Failed to write the registry value"),
    (
        "notify.noLocalAppData",
        "无法获取 LOCALAPPDATA",
        "LOCALAPPDATA is not available",
    ),
    (
        "notify.windowMissing",
        "通知窗口未配置",
        "Notification window is not configured",
    ),
    (
        "notify.noPrimaryMonitor",
        "无法获取主显示器",
        "Cannot get the primary monitor",
    ),
    ("font.fileMissing", "字体文件不存在：{}", "Font file not found: {}"),
];

/// 取当前语言的文案，并依次替换 `{}` 占位符。
/// 漏配 key 时回退成 key 本身（一眼能看出漏了，不会静默显示中文）。
pub fn tr(key: &str, args: &[String]) -> String {
    let template = match TABLE.iter().find(|(k, _, _)| *k == key) {
        Some((_, zh, en)) => {
            if lang() == Lang::En {
                *en
            } else {
                *zh
            }
        }
        None => key,
    };

    let mut out = template.to_string();
    for a in args {
        match out.find("{}") {
            Some(pos) => out.replace_range(pos..pos + 2, a),
            None => break,
        }
    }
    out
}

/// 便捷宏：`tr!("pip.found", items.len())`
#[macro_export]
macro_rules! tr {
    ($key:expr $(, $arg:expr)*) => {
        $crate::i18n::tr($key, &[$($arg.to_string()),*])
    };
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn substitutes_placeholders_in_order() {
        set_lang("zh-CN");
        assert_eq!(tr("pip.found", &["3".to_string()]), "发现 3 个 pip 包可更新");
        set_lang("en-US");
        assert_eq!(tr("pip.found", &["3".to_string()]), "Found 3 pip package(s) to update");
    }

    #[test]
    fn missing_key_falls_back_to_key() {
        assert_eq!(tr("no.such.key", &[]), "no.such.key");
    }

    #[test]
    fn table_has_no_empty_translations() {
        for (k, zh, en) in TABLE {
            assert!(!zh.is_empty(), "{} 缺中文", k);
            assert!(!en.is_empty(), "{} 缺英文", k);
            assert_eq!(
                zh.matches("{}").count(),
                en.matches("{}").count(),
                "{} 中英占位符数量不一致",
                k
            );
        }
    }

    #[test]
    fn table_keys_are_unique() {
        let mut keys: Vec<&str> = TABLE.iter().map(|(k, _, _)| *k).collect();
        keys.sort_unstable();
        let n = keys.len();
        keys.dedup();
        assert_eq!(n, keys.len(), "存在重复 key");
    }
}
