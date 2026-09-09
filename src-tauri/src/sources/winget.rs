use crate::model::{Source, UpdateItem};
use crate::sources::{LogFn, UpdateSource};
use crate::util::{cmd_exists, run_capture, run_stream};

pub struct WingetSource;

impl UpdateSource for WingetSource {
    fn source(&self) -> Source {
        Source::Winget
    }

    fn available(&self) -> bool {
        cmd_exists("winget")
    }

    fn check(&self, log: LogFn) -> Vec<UpdateItem> {
        log("info", "正在检查 winget 软件更新...".into());

        // winget 1.30 的 upgrade 子命令不支持 --output json，
        // 传了只会输出帮助文本。这里只传通用参数，解析走表格。
        let out = match run_capture(
            "winget",
            &[
                "upgrade",
                "--accept-source-agreements",
                "--disable-interactivity",
            ],
        ) {
            Ok(o) => o,
            Err(e) => {
                log("err", format!("winget 检查失败：{}", e));
                return vec![];
            }
        };

        // 优先走 JSON（未来版本若支持则更精确）
        if let Some(items) = try_json(&out) {
            if items.is_empty() {
                log("ok", "winget 软件全部是最新版本".into());
            } else {
                log("info", format!("发现 {} 个 winget 软件可更新", items.len()));
            }
            return items;
        }

        parse_table(&out, log)
    }

    fn update(&self, item: &UpdateItem, log: LogFn) -> bool {
        let id = item.pkg_id.clone().unwrap_or_else(|| item.name.clone());
        log("info", format!("正在更新 {}...", item.name));

        let code = run_stream(
            "winget",
            &[
                "upgrade",
                "--id",
                &id,
                "--silent",
                "--accept-package-agreements",
                "--accept-source-agreements",
                "--disable-interactivity",
            ],
            |line| log("cmd", line.to_string()),
        );

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

/* ============ JSON 路径（未来版本可用） ============ */
fn try_json(out: &str) -> Option<Vec<UpdateItem>> {
    let start = out.find('[')?;
    let end = out.rfind(']')?;
    if end <= start {
        return None;
    }
    let value: serde_json::Value = serde_json::from_str(&out[start..=end]).ok()?;
    let list = value.as_array()?;

    let mut items = Vec::new();
    for entry in list {
        let name = entry.get("Name")?.as_str()?.to_string();
        let id = entry.get("Id")?.as_str()?.to_string();
        let current = entry.get("Version")?.as_str()?.to_string();
        let latest = entry.get("AvailableVersion")?.as_str()?.to_string();

        items.push(UpdateItem {
            id: format!("winget:{}", id),
            source: Source::Winget,
            name,
            current,
            latest,
            tag: None,
            pkg_id: Some(id),
            icon: None,
        });
    }
    Some(items)
}

/* ============ 表格路径（当前版本） ============ */
fn parse_table(out: &str, log: LogFn) -> Vec<UpdateItem> {
    let lines: Vec<&str> = out.lines().collect();

    // 找到表头下方的分隔线（一长串 - ）
    let mut body_start = usize::MAX;
    for (i, line) in lines.iter().enumerate() {
        let t = line.trim();
        if t.len() >= 10 && t.contains('-') && t.chars().all(|c| c == '-' || c == ' ') {
            body_start = i + 1;
            break;
        }
    }

    if body_start == usize::MAX {
        if out.contains("无法识别") || out.contains("使用情况") {
            log("err", "winget 参数不被当前版本支持".into());
        } else {
            log("ok", "winget 软件全部是最新版本".into());
        }
        return vec![];
    }

    let mut items = Vec::new();

    for raw in lines.iter().skip(body_start) {
        if let Some(item) = parse_row(raw) {
            items.push(item);
        }
    }

    if items.is_empty() {
        log("ok", "winget 软件全部是最新版本".into());
    } else {
        log("info", format!("发现 {} 个 winget 软件可更新", items.len()));
    }

    items
}

/// 解析单行表格记录。
///
/// 不依赖列对齐：从行尾往回取固定几列。名称本身含空格，且列被压缩时
/// 名称与 ID 之间可能只剩一个空格，按列切会整体错位，倒序取 token 则不受影响。
fn parse_row(line: &str) -> Option<UpdateItem> {
    let line = line.trim();
    if line.is_empty() {
        return None;
    }

    let tokens: Vec<&str> = line.split_whitespace().collect();
    let n = tokens.len();
    if n < 4 {
        return None;
    }

    // 末列可能是"源"（winget / msstore）
    let has_source = matches!(tokens[n - 1], "winget" | "msstore" | "store" | "local");

    let (id, current, latest, name_end) = if has_source {
        if n < 5 {
            return None;
        }
        (tokens[n - 4], tokens[n - 3], tokens[n - 2], n - 4)
    } else {
        (tokens[n - 3], tokens[n - 2], tokens[n - 1], n - 3)
    };

    if name_end == 0 {
        return None;
    }
    // 过滤总结行等噪声：ID 必须带 . 或 _ 分隔符
    if !id.contains('.') && !id.contains('_') {
        return None;
    }

Some(UpdateItem {
    id: format!("winget:{}", id),
    source: Source::Winget,
    name: tokens[..name_end].join(" "),
    current: current.to_string(),
    latest: latest.to_string(),
    tag: None,
    pkg_id: Some(id.to_string()),
    icon: None,
})
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_normal_row() {
        let row = "64Gram Desktop          64Gram.64Gram      1.2.5     1.2.8    winget";
        let item = parse_row(row).expect("应解析出条目");
        assert_eq!(item.name, "64Gram Desktop");
        assert_eq!(item.pkg_id.as_deref(), Some("64Gram.64Gram"));
        assert_eq!(item.current, "1.2.5");
        assert_eq!(item.latest, "1.2.8");
    }

    #[test]
    fn parses_compressed_row_single_space_before_id() {
        // 名称过长导致列被压缩：名称与 ID 之间只剩一个空格
        let row = "Microsoft Windows Desktop Runtime - 8.0.19 (x64) Microsoft.DotNet.DesktopRuntime.8   8.0.19   8.0.31   winget";
        let item = parse_row(row).expect("应解析出条目");
        assert_eq!(item.name, "Microsoft Windows Desktop Runtime - 8.0.19 (x64)");
        assert_eq!(
            item.pkg_id.as_deref(),
            Some("Microsoft.DotNet.DesktopRuntime.8")
        );
        assert_eq!(item.current, "8.0.19");
        assert_eq!(item.latest, "8.0.31");
    }

    #[test]
    fn parses_name_with_comma_and_parentheses() {
        let row = "Azul Zulu JDK 17.68.17 (17.0.20), 64-bit   Azul.Zulu.17.JDK   17.68.17   17.68.203   winget";
        let item = parse_row(row).expect("应解析出条目");
        assert_eq!(item.name, "Azul Zulu JDK 17.68.17 (17.0.20), 64-bit");
        assert_eq!(item.pkg_id.as_deref(), Some("Azul.Zulu.17.JDK"));
    }

    #[test]
    fn rejects_summary_line() {
        assert!(parse_row("已找到 23 个可用升级。").is_none());
        assert!(parse_row("").is_none());
    }
}

