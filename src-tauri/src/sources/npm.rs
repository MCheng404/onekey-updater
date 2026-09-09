use crate::model::{Source, UpdateItem};
use crate::sources::{LogFn, UpdateSource};
use crate::util::{cmd_exists, compare_version, extract_json_object, par_map, run_capture_with_timeout, run_stream};

pub struct NpmSource;

impl UpdateSource for NpmSource {
    fn source(&self) -> Source {
        Source::Npm
    }

    fn available(&self) -> bool {
        cmd_exists("npm")
    }

    fn check(&self, log: LogFn) -> Vec<UpdateItem> {
        log("info", "正在检查 npm 全局包更新...".into());

        let out = match run_capture_with_timeout("npm", &["outdated", "-g", "--json"], 60) {
            Ok(o) => o,
            Err(e) => {
                log("err", format!("npm 检查失败：{}", e));
                return vec![];
            }
        };

        let json_part = match extract_json_object(&out) {
            Some(s) => s,
            None => {
                log("ok", "npm 全局包全部是最新版本".into());
                return vec![];
            }
        };

        let value: serde_json::Value = match serde_json::from_str(json_part) {
            Ok(v) => v,
            Err(e) => {
                log("err", format!("npm 输出解析失败：{}", e));
                return vec![];
            }
        };

        let obj = match value.as_object() {
            Some(o) if !o.is_empty() => o,
            _ => {
                log("ok", "npm 全局包全部是最新版本".into());
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

        let resolved = par_map(metas, 8, |(name, current, latest)| {
            let mut tag = "latest".to_string();
            let mut target = latest.clone();

            if let Some(dev) = npm_view_version(name, "dev") {
                if compare_version(&dev, &target) > 0 {
                    tag = "dev".to_string();
                    target = dev;
                }
            }
            if let Some(beta) = npm_view_version(name, "beta") {
                if compare_version(&beta, &target) > 0 {
                    tag = "beta".to_string();
                    target = beta;
                }
            }

            (name.clone(), current.clone(), tag, target)
        });

        let mut items = Vec::new();
        for (name, current, tag, target) in resolved {
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
            log("ok", "npm 全局包全部是最新版本".into());
        } else {
            log(
                "info",
                format!("发现 {} 个 npm 包可更新", items.len()),
            );
        }

        items
    }

    fn update(&self, item: &UpdateItem, log: LogFn) -> bool {
        let tag = item.tag.clone().unwrap_or_else(|| "latest".to_string());
        let label = format!("{}@{}", item.name, tag);
        log("info", format!("正在更新 {}...", label));

        let code = run_stream("npm", &["install", "-g", &label], |line| {
            log("cmd", line.to_string());
        });

        match code {
            Ok(0) => {
                log("ok", format!("{} 更新完成", label));
                true
            }
            Ok(_) if tag != "latest" => {
                log("warn", format!("{} 安装失败，回退到 @latest...", tag));
                let code = run_stream("npm", &["install", "-g", &format!("{}@latest", item.name)], |line| {
                    log("cmd", line.to_string());
                });
                match code {
                    Ok(0) => {
                        log("ok", format!("{}@latest 回退更新完成", item.name));
                        true
                    }
                    _ => {
                        log("err", format!("{} 更新失败", item.name));
                        false
                    }
                }
            }
            _ => {
                log("err", format!("{} 更新失败", item.name));
                false
            }
        }
    }
}

/// 查询某个 tag 对应的版本号，取不到返回 None
///
/// 优化：用 15 秒超时替代默认 30 秒，加快 npm 检查速度。
fn npm_view_version(pkg: &str, tag: &str) -> Option<String> {
    let spec = format!("{}@{}", pkg, tag);
    let out = run_capture_with_timeout("npm", &["view", &spec, "version"], 15).ok()?;
    let first = out.lines().next()?.trim().to_string();

    if first.is_empty() || first.starts_with("npm ERR") || first.contains("ERR!") {
        return None;
    }
    // 多行输出取最后一行（最新）
    let last = out
        .lines()
        .filter(|l| !l.trim().is_empty())
        .last()
        .unwrap_or(&first)
        .trim()
        .to_string();

    if last.chars().next().map(|c| c.is_ascii_digit()) == Some(true) {
        Some(last)
    } else {
        None
    }
}
