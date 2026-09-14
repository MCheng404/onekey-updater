use crate::model::{Source, UpdateItem};
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
        log("info", "正在检查 OpenClaw 更新...".into());

        let raw = run_capture_with_timeout("openclaw", &["--version"], 15).unwrap_or_default();
        let current = extract_version(&raw);
        log("cmd", format!("当前版本：{}", current));

        // 一次 npm view 拿全部 dist-tag（latest / beta），替代两次独立查询 ——
        // 这是 OpenClaw 源在"检查更新"阶段的主要耗时。
        let tags = npm_dist_tags("openclaw");
        let stable_ver = tags.iter().find(|(t, _)| t == "latest").map(|(_, v)| v.clone());
        let beta_ver = tags.iter().find(|(t, _)| t == "beta").map(|(_, v)| v.clone());

        log("cmd", format!("正式版最新：{}", stable_ver.as_deref().unwrap_or("未知")));
        log("cmd", format!("beta 最新：{}", beta_ver.as_deref().unwrap_or("未知")));

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
                log("warn", "无法获取 OpenClaw 版本信息".into());
                return vec![];
            }
        };

        if compare_version(&current, &latest) >= 0 {
            log("ok", format!("OpenClaw 已是最新版本（{}，{} 通道）", current, tag));
            return vec![];
        }

        log(
            "info",
            format!("OpenClaw 可更新：{} → {} ({})", current, latest, tag),
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
        log("info", format!("正在更新 OpenClaw 到 {}...", tag));

        // 1. 停止 gateway
        log("info", "正在停止 OpenClaw gateway...".into());
        let _ = run_stream("openclaw", &["gateway", "stop"], |line| {
            log("cmd", line.to_string());
        });

        // 2. 安装
        let admin = is_admin();
        if admin {
            log("info", "使用管理员权限安装...".into());
        } else {
            log("warn", "非管理员模式，尝试用户目录安装...".into());
            let home = std::env::var("USERPROFILE").unwrap_or_default();
            let prefix = format!("{}\\.local", home);
            let _ = run_capture_with_timeout("npm", &["config", "set", "prefix", &prefix], 15);
            let old_path = std::env::var("PATH").unwrap_or_default();
            std::env::set_var("PATH", format!("{}\\bin;{}", prefix, old_path));
        }

        let pkg_spec = format!("openclaw@{}", tag);
        let code = run_stream("npm", &["i", "-g", &pkg_spec], |line| {
            log("cmd", line.to_string());
        });

        match code {
            Ok(0) => {
                log("info", "正在重装 gateway 服务...".into());
                let _ = run_stream("openclaw", &["gateway", "install", "--force"], |line| {
                    log("cmd", line.to_string());
                });
                log("info", "正在重启 gateway...".into());
                let _ = run_stream("openclaw", &["gateway", "restart"], |line| {
                    log("cmd", line.to_string());
                });
                log("ok", format!("OpenClaw 更新完成（{}）", tag));
                true
            }
            _ => {
                log("err", "OpenClaw 更新失败".into());
                log("warn", "提示：可能需要以管理员身份运行".into());
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
