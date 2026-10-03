use crate::model::{Source, UpdateItem};
use crate::tr;
use crate::sources::{LogFn, UpdateSource};
use crate::util::{
    cmd_exists, compare_version, extract_json_object, is_admin, run_capture_with_timeout, run_stream,
};

pub struct OpenclawSource;

impl UpdateSource for OpenclawSource {
    fn source(&self) -> Source {
        Source::Openclaw
    }

    fn available(&self) -> bool {
        cmd_exists("openclaw")
    }

    fn check(&self, log: LogFn) -> Vec<UpdateItem> {
        // 注意：check_one 已调用 source.available()，这里不需要重复检查
        log("info", tr!("openclaw.checking"));

        let raw = run_capture_with_timeout("openclaw", &["--version"], 15).unwrap_or_default();
        let current = extract_version(&raw);
        log("cmd", tr!("openclaw.current", current));

        // 探测"装的位置"与"用的位置"是否一致。
        //
        // `openclaw --version` 走 PATH，只反映**当前生效**的那一份；而
        // `npm i -g` 按 npm 的 global prefix 装，**两者可以不是同一个目录**。
        // 一旦不一致（多半是历史上有人改过 prefix，或多份安装并存），
        // 就会出现"更新装成功了，但永远显示可更新"的死循环 —— 用户点更新、
        // npm 报成功、版本号纹丝不动，app 还查不出原因。
        if let Some(warn) = check_install_location_mismatch(log) {
            log("warn", warn);
        }

        // 一次 npm view 拿全部 dist-tag（latest / beta），替代两次独立查询 ——
        // 这是 OpenClaw 源在"检查更新"阶段的主要耗时。
        let tags = npm_dist_tags("openclaw");
        let stable_ver = tags.iter().find(|(t, _)| t == "latest").map(|(_, v)| v.clone());
        let beta_ver = tags.iter().find(|(t, _)| t == "beta").map(|(_, v)| v.clone());

        let stable_text = stable_ver.clone().unwrap_or_else(|| tr!("common.unknown"));
        let beta_text = beta_ver.clone().unwrap_or_else(|| tr!("common.unknown"));
        log("cmd", tr!("openclaw.stable", stable_text));
        log("cmd", tr!("openclaw.betaLatest", beta_text));

        // 确定目标版本和 tag：beta > stable 时用 beta，否则用正式版
        let (latest, tag) = match (&stable_ver, &beta_ver) {
            (Some(s), Some(b)) => {
                if compare_version(b, s) > 0 {
                    (b.clone(), "beta")
                } else {
                    (s.clone(), "latest")
                }
            }
            (Some(s), None) => (s.clone(), "latest"),
            (None, Some(b)) => (b.clone(), "beta"),
            (None, None) => {
                log("warn", tr!("openclaw.noVersion"));
                return vec![];
            }
        };

        if compare_version(&current, &latest) >= 0 {
            log("ok", tr!("openclaw.upToDate", current, tag));
            return vec![];
        }

        log(
            "info",
            tr!("openclaw.available", current, latest, tag),
        );

        vec![UpdateItem {
            id: "openclaw:self".to_string(),
            source: Source::Openclaw,
            name: "OpenClaw".to_string(),
            current,
            latest,
            tag: Some(tag.to_string()),
            pkg_id: None,
            icon: None,
        }]
    }

    fn update(&self, item: &UpdateItem, log: LogFn) -> bool {
        let tag = item.tag.clone().unwrap_or_else(|| "latest".to_string());
        log("info", tr!("openclaw.updating", tag));

        // 非管理员时装到用户目录下的专用前缀。
        //
        // 这里刻意**不用** `npm config set prefix`：那是永久改写用户 .npmrc 的
        // 全局配置，一旦装失败也不回滚 —— 用户此后所有全局 npm 安装都会被搬到
        // 那个目录，而它未必在 PATH 上，于是全局命令"集体消失"（真实发生过）。
        // 也**不用** `std::env::set_var` 改 PATH：多线程程序里它是不安全的
        // （Rust 文档明确标注），而且只对本进程有效、退出即失效，对用户毫无意义。
        // 改为每次调用带 `--prefix`，并直接用绝对路径调用装出来的 openclaw。
        let admin = is_admin();
        let mut prefix: Option<String> = None;
        if admin {
            log("info", tr!("openclaw.adminInstall"));
        } else {
            log("warn", tr!("openclaw.userInstall"));
            let home = std::env::var("USERPROFILE").unwrap_or_default();
            if !home.is_empty() {
                let p = format!("{}\\.openclaw-updater", home);
                if std::fs::create_dir_all(&p).is_ok() {
                    prefix = Some(p);
                }
            }
        }

        // Windows 上 npm 把全局可执行文件**直接放在 {prefix} 下**，
        // 不是 {prefix}\bin（那是 Unix 布局）—— 拼错这个子目录，
        // 装完的新 openclaw 根本不在我们以为的位置。
        let cli = match prefix.as_deref() {
            Some(p) => format!("{}\\openclaw.cmd", p),
            None => "openclaw".to_string(),
        };

        // 1. 停止 gateway。必须带 --force：不带的话 CLI 会拒绝停掉正在运行的
        //    gateway，旧进程继续占着端口，新装完的也起不来。
        log("info", tr!("openclaw.stopping"));
        if run_stream(&cli, &["gateway", "stop", "--force"], |line| {
            log("cmd", line.to_string());
        })
        .map(|c| c != 0)
        .unwrap_or(true)
        {
            log("warn", tr!("openclaw.stopFailed"));
        }

        // 2. 安装
        let pkg_spec = format!("openclaw@{}", tag);
        // ⚠️ 必须显式放行安装脚本。新版 npm 默认拦截 install scripts，而 openclaw 的
        // postinstall（装 bundled plugins）与几个原生依赖（koffi / tree-sitter-bash）
        // 全靠它 —— 不放行的话包"装上了"但不完整，gateway 也起不来。
        let mut argv: Vec<String> = vec![
            "i".to_string(),
            "-g".to_string(),
            pkg_spec,
            "--allow-scripts=openclaw,@google/genai,koffi,tree-sitter-bash,protobufjs".to_string(),
        ];
        if let Some(p) = prefix.as_deref() {
            argv.push("--prefix".to_string());
            argv.push(p.to_string());
        }
        let argv_ref: Vec<&str> = argv.iter().map(|s| s.as_str()).collect();
        let code = run_stream("npm", &argv_ref, |line| {
            log("cmd", line.to_string());
        });

        match code {
            Ok(0) => {
                log("info", tr!("openclaw.reinstall"));
                // 网关这两步的退出码必须参与最终结果判定。
                let install_ok = run_stream(&cli, &["gateway", "install", "--force"], |line| {
                    log("cmd", line.to_string());
                })
                .map(|c| c == 0)
                .unwrap_or(false);

                log("info", tr!("openclaw.restarting"));
                let restart_ok = run_stream(&cli, &["gateway", "restart", "--force"], |line| {
                    log("cmd", line.to_string());
                })
                .map(|c| c == 0)
                .unwrap_or(false);

                if install_ok && restart_ok {
                    log("ok", tr!("openclaw.done", tag));
                    true
                } else {
                    // 网关没起来就不能算成功：前端会把 ok=true 的项移出列表、
                    // 计入"全部成功"，用户就彻底看不到"装上了但没跑起来"。
                    // 这比返回 false 更糟 —— 假成功会让人以为一切正常。
                    log("warn", tr!("openclaw.gatewayFailed", tag));
                    false
                }
            }
            _ => {
                log("err", tr!("openclaw.failed"));
                // 已经是管理员了还失败，就别再提示"请以管理员身份运行" ——
                // 那会把用户引向 UAC，真正的原因多半是 Node 版本不满足。
                if admin {
                    log("warn", tr!("openclaw.nodeHint"));
                } else {
                    log("warn", tr!("openclaw.adminHint"));
                }
                false
            }
        }
    }
}

/// 一次调用拿到包的全部 dist-tag（latest / beta / dev …）。
///
/// 原来 latest 和 beta 各发一次 `npm view`，现在合并成一次，
/// 少起一个 npm 进程（约省 1–2 秒）。超时 12 秒。
fn npm_dist_tags(pkg: &str) -> Vec<(String, String)> {
    let out = match run_capture_with_timeout("npm", &["view", pkg, "dist-tags", "--json"], 12) {
        Ok(o) => o,
        Err(_) => return Vec::new(),
    };
    let json = match extract_json_object(&out) {
        Some(s) => s,
        None => return Vec::new(),
    };
    let Ok(value) = serde_json::from_str::<serde_json::Value>(json) else {
        return Vec::new();
    };
    let Some(obj) = value.as_object() else {
        return Vec::new();
    };

    let mut tags: Vec<(String, String)> = Vec::new();
    for (k, v) in obj {
        if let Some(s) = v.as_str() {
            tags.push((k.clone(), s.to_string()));
        }
    }
    tags
}

/// 从 `openclaw --version` 输出里提取版本号（优先四位年份版本，如 2026.9.3）
fn extract_version(raw: &str) -> String {
    let b = raw.as_bytes();
    let n = b.len();
    let mut i = 0;
    let mut fallback: Option<String> = None;

    while i < n {
        if b[i].is_ascii_digit() {
            let mut j = i;
            while j < n
                && (b[j].is_ascii_alphanumeric()
                    || b[j] == b'.'
                    || b[j] == b'-'
                    || b[j] == b'+'
                    || b[j] == b'_')
            {
                j += 1;
            }
            let token = raw[i..j].to_string();
            let four_digits = j - i >= 4 && (i..i + 4).all(|k| b[k].is_ascii_digit());
            if four_digits && token.contains('.') {
                return token;
            }
            if fallback.is_none() {
                fallback = Some(token);
            }
            i = j;
        } else {
            i += 1;
        }
    }

    fallback.unwrap_or_else(|| raw.trim().to_string())
}

/// 比较「npm 全局安装位置」与「PATH 实际命中位置」，不一致时给出告警文案。
///
/// 背景：`openclaw --version` 走 PATH，只反映**当前生效**的那一份；
/// `npm i -g` 却按 npm 的 global prefix 装。两者可以不是同一个目录。
/// 一旦不一致，就会出现最难排查的那种症状 ——
/// "更新装成功了，但版本号永远不变、永远提示可更新"。
///
/// 只做提示，不阻断检查：prefix 不一致本身不是错误状态
/// （比如用户有意用非默认 prefix），但它几乎一定是"更新不生效"的根因，
/// 必须让用户看见。
fn check_install_location_mismatch(log: LogFn) -> Option<String> {
    // 1) npm 的全局 prefix（决定新包装到哪）
    let prefix = run_capture_with_timeout("npm", &["prefix", "-g"], 15).ok()?;
    let prefix = prefix
        .trim()
        .trim_end_matches([char::from(b'\\'), char::from(b'/')])
        .to_string();
    if prefix.is_empty() {
        return None;
    }

    // 2) PATH 上实际命中的 openclaw（决定"用的"是哪一份）
    // ⚠️ 这里**不能**再包一层 cmd：base_command 已经生成 `cmd /D /C <program> <args>`，
    // 写成 ("cmd", &["/C", "where", ...]) 会变成 `cmd /D /C cmd /C where ...` ——
    // 内层 cmd 被当作"要执行的程序名"而不是命令，输出不是 where 的结果，
    // 退出码非 0，下面的 `?` 直接早退，告警永远不会打印（实测过：静默失效）。
    let which_out = run_capture_with_timeout("where", &["openclaw"], 15).ok()?;
    let on_path: Vec<String> = which_out
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .map(|l| l.to_string())
        .collect();
    let first = on_path.first()?;

    // 3) 对比（抽成纯函数 install_locations_match，便于真正单测）
    if install_locations_match(&prefix, first) {
        return None; // 一致
    }

    // 不一致：报出双方具体位置，便于用户自行处理
    let _ = log;
    Some(tr!(
        "openclaw.installMismatch",
        first,
        prefix,
        on_path.join(", ")
    ))
}


/// 判断「PATH 命中的 openclaw」是否位于「npm 全局 prefix」之下。
///
/// 抽成纯函数是为了能真正单测：这段逻辑一旦出错，症状是**静默不告警** ——
/// 错配时什么都不说，用户永远查不到根因。第一版就栽在这里：
/// 把 `where` 外面又包了一层 shell 控制指令，被 base_command 再包一层之后
/// 内层那个变成了「要执行的程序名」，`where` 永远失败，`?` 直接早退，
/// 告警一次都没打印出来（实机验证时才发现日志里 0 次告警）。
fn install_locations_match(prefix: &str, on_path_first: &str) -> bool {
    let sep = char::from(b'\\').to_string();
    let normalize = |s: &str| s.to_lowercase().replace('/', &sep);
    normalize(on_path_first).starts_with(&normalize(prefix))
}

#[cfg(test)]
mod tests {
    use super::install_locations_match;

    #[test]
    fn same_dir_is_a_match() {
        assert!(install_locations_match(
            r"C:\Users\Cookies\AppData\Roaming\npm",
            r"C:\Users\Cookies\AppData\Roaming\npm\openclaw",
        ));
    }

    /// 这正是「装新的、用旧的」：新包装到 .local，PATH 命中的却是 AppData
    #[test]
    fn another_prefix_is_a_mismatch() {
        assert!(!install_locations_match(
            r"C:\Users\Cookies\.local",
            r"C:\Users\Cookies\AppData\Roaming\npm\openclaw",
        ));
    }

    #[test]
    fn case_and_slash_differences_still_match() {
        assert!(install_locations_match(
            "c:/users/cookies/appdata/roaming/npm",
            r"C:\Users\Cookies\AppData\Roaming\npm\openclaw.cmd",
        ));
    }

    #[test]
    fn trailing_separator_on_prefix_is_tolerated() {
        assert!(install_locations_match(
            r"C:\Users\Cookies\AppData\Roaming\npm\",
            r"C:\Users\Cookies\AppData\Roaming\npm\openclaw",
        ));
    }
}
