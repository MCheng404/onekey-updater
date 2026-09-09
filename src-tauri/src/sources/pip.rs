use crate::model::{Source, UpdateItem};
use crate::sources::{LogFn, UpdateSource};
use crate::util::{cmd_exists, run_capture_with_timeout, run_stream};

pub struct PipSource;

impl UpdateSource for PipSource {
    fn source(&self) -> Source {
        Source::Pip
    }

    fn available(&self) -> bool {
        cmd_exists("pip")
    }

    fn check(&self, log: LogFn) -> Vec<UpdateItem> {
        log("info", "正在检查 pip 包更新...".into());

        let out = match run_capture_with_timeout("pip", &["list", "--outdated", "--format=json"], 60) {
            Ok(o) => o,
            Err(e) => {
                log("err", format!("pip 检查失败：{}", e));
                return vec![];
            }
        };

        let arr = match extract_array(&out) {
            Some(s) => s,
            None => {
                log("ok", "pip 包全部是最新版本".into());
                return vec![];
            }
        };

        let value: serde_json::Value = match serde_json::from_str(arr) {
            Ok(v) => v,
            Err(e) => {
                log("err", format!("pip 输出解析失败：{}", e));
                return vec![];
            }
        };

        let list = match value.as_array() {
            Some(a) if !a.is_empty() => a,
            _ => {
                log("ok", "pip 包全部是最新版本".into());
                return vec![];
            }
        };

        let mut items = Vec::new();

        for entry in list.iter() {
            let name = entry
                .get("name")
                .and_then(|v| v.as_str())
                .unwrap_or("?")
                .to_string();
            let current = entry
                .get("version")
                .and_then(|v| v.as_str())
                .unwrap_or("?")
                .to_string();
            let latest = entry
                .get("latest_version")
                .and_then(|v| v.as_str())
                .unwrap_or("?")
                .to_string();

            items.push(UpdateItem {
                id: format!("pip:{}", name),
                source: Source::Pip,
                name,
                current,
                latest,
                tag: None,
                pkg_id: None,
                icon: None,
            });
        }

        if items.is_empty() {
            log("ok", "pip 包全部是最新版本".into());
        } else {
            log("info", format!("发现 {} 个 pip 包可更新", items.len()));
        }

        items
    }

    fn update(&self, item: &UpdateItem, log: LogFn) -> bool {
        log("info", format!("正在更新 {}...", item.name));

        let code = run_stream("pip", &["install", "--upgrade", &item.name], |line| {
            log("cmd", line.to_string());
        });

        match code {
            Ok(0) => {
                log("ok", format!("{} 更新完成", item.name));
                true
            }
            _ => {
                log("err", format!("{} 更新失败", item.name));
                false
            }
        }
    }
}

fn extract_array(s: &str) -> Option<&str> {
    let start = s.find('[')?;
    let end = s.rfind(']')?;
    if end > start {
        Some(&s[start..=end])
    } else {
        None
    }
}
