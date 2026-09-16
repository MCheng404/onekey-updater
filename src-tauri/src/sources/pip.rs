use crate::model::{Source, UpdateItem};
use crate::tr;
use crate::sources::{LogFn, UpdateSource};
use crate::util::{cmd_exists, extract_json_array, run_capture_with_timeout, run_stream};

pub struct PipSource;

impl UpdateSource for PipSource {
    fn source(&self) -> Source {
        Source::Pip
    }

    fn available(&self) -> bool {
        cmd_exists("pip")
    }

    fn check(&self, log: LogFn) -> Vec<UpdateItem> {
        log("info", tr!("pip.checking"));

        // --disable-pip-version-check：省掉"检查 pip 自身是否有新版"的那次网络往返
        let out = match run_capture_with_timeout(
            "pip",
            &["list", "--outdated", "--format=json", "--disable-pip-version-check"],
            45,
        ) {
            Ok(o) => o,
            Err(e) => {
                log("err", tr!("pip.checkFailed", e));
                return vec![];
            }
        };

        let arr = match extract_json_array(&out) {
            Some(s) => s,
            None => {
                log("ok", tr!("pip.allLatest"));
                return vec![];
            }
        };

        let value: serde_json::Value = match serde_json::from_str(arr) {
            Ok(v) => v,
            Err(e) => {
                log("err", tr!("pip.parseFailed", e));
                return vec![];
            }
        };

        let list = match value.as_array() {
            Some(a) if !a.is_empty() => a,
            _ => {
                log("ok", tr!("pip.allLatest"));
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
            log("ok", tr!("pip.allLatest"));
        } else {
            log("info", tr!("pip.found", items.len()));
        }

        items
    }

    fn update(&self, item: &UpdateItem, log: LogFn) -> bool {
        log("info", tr!("update.starting", item.name));

        let code = run_stream("pip", &["install", "--upgrade", &item.name], |line| {
            log("cmd", line.to_string());
        });

        match code {
            Ok(0) => {
                log("ok", tr!("update.done", item.name));
                true
            }
            _ => {
                log("err", tr!("update.failed", item.name));
                false
            }
        }
    }
}
