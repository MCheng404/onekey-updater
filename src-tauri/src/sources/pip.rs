use crate::model::{Source, UpdateItem};
use crate::tr;
use crate::sources::{LogFn, UpdateSource};
use crate::util::{cmd_exists, extract_json_array, run_capture_status, run_stream};

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
        // 退出码在这里帮不上忙，实测过：
        //   无更新           → exit=0，输出 []
        //   源不可达(超时)    → exit=0，输出 []   ← pip 静默降级，不报错
        //   参数非法         → exit=2
        // 也就是说 pip 在"源挂了"时同样给不出任何可判别的信号，
        // 唯一可靠的仍是**能否解析出合法 JSON 数组**。
        // 空数组 `[]` = 确实没有可更新项（报绿色 OK）；
        // 解析不出数组 = 真失败（registry 损坏 / pip 自身异常），必须说清楚。
        let (_code, out) = match run_capture_status(
            "pip",
            &["list", "--outdated", "--format=json", "--disable-pip-version-check"],
            45,
        ) {
            Ok(v) => v,
            Err(e) => {
                log("err", tr!("pip.checkFailed", e));
                return vec![];
            }
        };

        let arr = match extract_json_array(&out) {
            Some(s) => s,
            None => {
                // 空数组是合法 JSON，能解析 → 才是真的"全部最新"
                match serde_json::from_str::<serde_json::Value>(out.trim()) {
                    Ok(v) if v.as_array().map(|a| a.is_empty()).unwrap_or(false) => {
                        log("ok", tr!("pip.allLatest"));
                    }
                    _ => {
                        let detail = out
                            .lines()
                            .map(str::trim)
                            .find(|l| !l.is_empty() && *l != "[")
                            .unwrap_or("unknown error")
                            .chars()
                            .take(120)
                            .collect::<String>();
                        log("err", tr!("pip.checkFailed", detail));
                    }
                }
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
