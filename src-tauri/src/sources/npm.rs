use crate::model::{Source, UpdateItem};
use crate::tr;
use crate::sources::{LogFn, UpdateSource};
use crate::util::{cmd_exists, compare_version, extract_json_object, par_map, run_capture_with_timeout, run_stream};

pub struct NpmSource;

/// 由专用源负责更新的 npm 全局包。
///
/// `openclaw` 有独立的 `OpenClawSource`（升级前后会正确地停止 / 重装 / 重启
/// gateway），npm 源再列一次不仅重复，还会走错更新流程。这里跳过它们，
/// 让唯一正确的源接管。
const DELEGATED_PKGS: &[&str] = &["openclaw"];

impl UpdateSource for NpmSource {
    fn source(&self) -> Source {
        Source::Npm
    }

    fn available(&self) -> bool {
        cmd_exists("npm")
    }

    fn check(&self, log: LogFn) -> Vec<UpdateItem> {
        log("info", tr!("npm.checking"));

        let out = match run_capture_with_timeout("npm", &["outdated", "-g", "--json"], 45) {
            Ok(o) => o,
            Err(e) => {
                log("err", tr!("npm.checkFailed", e));
                return vec![];
            }
        };

        let json_part = match extract_json_object(&out) {
            Some(s) => s,
            None => {
                log("ok", tr!("npm.allLatest"));
                return vec![];
            }
        };

        let value: serde_json::Value = match serde_json::from_str(json_part) {
            Ok(v) => v,
            Err(e) => {
                log("err", tr!("npm.parseFailed", e));
                return vec![];
            }
        };

        let obj = match value.as_object() {
            Some(o) if !o.is_empty() => o,
            _ => {
                log("ok", tr!("npm.allLatest"));
                return vec![];
            }
        };

        // 先收集待查包，再并行探测 dev/beta tag
        // （每个包 2 次 npm view 网络调用，串行会非常慢）
        let metas: Vec<(String, String, String)> = obj
            .iter()
            .map(|(name, info)| {
                let current = info
                    .get("current")
                    .and_then(|v| v.as_str())
                    .unwrap_or("?")
                    .to_string();
                let latest = info
                    .get("latest")
                    .and_then(|v| v.as_str())
                    .unwrap_or("?")
                    .to_string();
                (name.clone(), current, latest)
            })
            .collect();

        // 提速：每个包只发一次 npm view（拿全部 dist-tag），
        // 原来是 dev / beta 各发一次 —— N 个包的 npm 进程数从 2N 降到 N。
        let resolved = par_map(metas, 12, |(name, current, latest)| {
            let mut tag = "latest".to_string();
            let mut target = latest.clone();

            let tags = npm_dist_tags(name);
            if let Some(dev) = tags.iter().find(|(t, _)| t == "dev").map(|(_, v)| v) {
                if compare_version(dev, &target) > 0 {
                    tag = "dev".to_string();
                    target = dev.clone();
                }
            }
            if let Some(beta) = tags.iter().find(|(t, _)| t == "beta").map(|(_, v)| v) {
                if compare_version(beta, &target) > 0 {
                    tag = "beta".to_string();
                    target = beta.clone();
                }
            }

            (name.clone(), current.clone(), tag, target)
        });

        let mut items = Vec::new();
        for (name, current, tag, target) in resolved {
            // 交给专用源（如 OpenClawSource）处理的包不在这里重复列出
            if DELEGATED_PKGS.iter().any(|p| name.eq_ignore_ascii_case(p)) {
                log("cmd", tr!("npm.skipDelegated", name));
                continue;
            }

            // `npm outdated` 偶尔会误报：当 registry 的 latest dist-tag 低于已装
            // 版本时（例如已装 openclaw 2026.9.4，而 registry 的 latest 还停在
            // 2026.6.35），npm 仍会把它列为"可更新"。再叠加 beta/dev tag 探测后，
            // 解析出的目标版本可能恰好等于、甚至低于当前版本 —— 这类条目必须丢掉，
            // 否则列表里会出现「2026.9.4 → 2026.9.4」这种没有意义的"更新"。
            if current.chars().any(|c| c.is_ascii_digit())
                && compare_version(&target, &current) <= 0
            {
                log(
                    "cmd",
                    tr!("npm.skipNotNewer", name, target, current),
                );
                continue;
            }

            items.push(UpdateItem {
                id: format!("npm:{}", name),
                source: Source::Npm,
                name,
                current,
                latest: target,
                tag: Some(tag),
                pkg_id: None,
                icon: None,
            });
        }

        if items.is_empty() {
            log("ok", tr!("npm.allLatest"));
        } else {
            log(
                "info",
                tr!("npm.found", items.len()),
            );
        }

        items
    }

    fn update(&self, item: &UpdateItem, log: LogFn) -> bool {
        let tag = item.tag.clone().unwrap_or_else(|| "latest".to_string());
        let label = format!("{}@{}", item.name, tag);
        log("info", tr!("update.starting", label));

        // 输出同时收集下来，失败时用来判断根因
        // （npm 自身依赖树损坏时只会打印 Cannot find module）
        let mut captured: Vec<String> = Vec::new();
        let mut ok = npm_install(&label, log, &mut captured);

        // 非 latest tag 装不上时，回退到正式版
        if !ok && tag != "latest" {
            log("warn", tr!("npm.fallback", tag));
            captured.clear();
            ok = npm_install(&format!("{}@latest", item.name), log, &mut captured);
            if ok {
                log("ok", tr!("npm.fallbackDone", item.name));
                return true;
            }
        }

        if !ok {
            // 更新 npm 自身是特殊情况：正在运行的 npm 一边执行 install、
            // 一边替换自己所在的 node_modules 树，reify 阶段很容易失败。
            // 改用 `npx npm@x` 拉起的"外部 npm"来装即可绕开（实测有效）。
            if item.name == "npm" {
                let spec = if item.latest.is_empty() || item.latest == "?" {
                    "latest".to_string()
                } else {
                    item.latest.clone()
                };
                log(
                    "warn",
                    tr!("npm.retryTemp", spec),
                );
                captured.clear();
                let code = run_stream(
                    "npx",
                    &["--yes", &format!("npm@{}", spec), "install", "-g", &label],
                    |line| {
                        captured.push(line.to_string());
                        log("cmd", line.to_string());
                    },
                );
                if matches!(code, Ok(0)) {
                    log("ok", tr!("npm.doneViaTemp", label));
                    return true;
                }
            }

            // 出现模块缺失 → 基本可以断定是 npm 自身依赖树残缺
            if captured
                .iter()
                .any(|l| l.contains("MODULE_NOT_FOUND") || l.contains("Cannot find module"))
            {
                log("warn", tr!("npm.brokenTree"));
                log(
                    "warn",
                    tr!("npm.brokenTreeFix"),
                );
            }

            log("err", tr!("update.failed", item.name));
            return false;
        }

        log("ok", tr!("update.done", label));
        true
    }
}

/// 执行一次 npm 全局安装。输出既写入日志，也收集到 `captured` 供失败诊断。
fn npm_install(label: &str, log: LogFn, captured: &mut Vec<String>) -> bool {
    let code = run_stream("npm", &["install", "-g", label], |line| {
        captured.push(line.to_string());
        log("cmd", line.to_string());
    });
    matches!(code, Ok(0))
}

/// 一次调用拿到包的全部 dist-tag（形如 `{"latest":"1.2.3","beta":"1.3.0-beta.1"}`）。
///
/// 相比"每个 tag 一次 npm view"，N 个包的 npm 进程数从 2N 降到 N。
/// 超时用 12 秒（默认 30），网络慢时也不至于拖住整个检查。
/// 取不到时返回空表，调用方按"没有额外 tag"处理。
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
        // 只收形如版本号的字符串，避免把 npm 的报错文本当成版本
        if let Some(s) = v.as_str() {
            if s.chars().next().map(|c| c.is_ascii_digit()) == Some(true) {
                tags.push((k.clone(), s.to_string()));
            }
        }
    }
    tags
}
