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
