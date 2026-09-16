use crate::model::{Source, UpdateItem};
use crate::tr;
use crate::sources::{LogFn, UpdateSource};
use crate::util::{cmd_exists, extract_json_array, run_capture, run_stream};

pub struct WingetSource;

impl UpdateSource for WingetSource {
    fn source(&self) -> Source {
        Source::Winget
    }

    fn available(&self) -> bool {
        cmd_exists("winget")
    }

    fn check(&self, log: LogFn) -> Vec<UpdateItem> {
        log("info", tr!("winget.checking"));

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
                log("err", tr!("winget.checkFailed", e));
                return vec![];
            }
        };

        // 优先走 JSON（未来版本若支持则更精确）
        if let Some(items) = try_json(&out) {
            if items.is_empty() {
                log("ok", tr!("winget.allLatest"));
            } else {
                log("info", tr!("winget.found", items.len()));
            }
            return items;
        }

        parse_table(&out, log)
    }

    fn update(&self, item: &UpdateItem, log: LogFn) -> bool {
        let id = item.pkg_id.clone().unwrap_or_else(|| item.name.clone());
        log("info", tr!("update.starting", item.name));

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

/* ============ JSON 路径（未来版本可用） ============ */
fn try_json(out: &str) -> Option<Vec<UpdateItem>> {
    let arr = extract_json_array(out)?;
    let value: serde_json::Value = serde_json::from_str(arr).ok()?;
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
        if looks_like_usage_help(&out) {
            log("err", tr!("winget.badArgs"));
        } else {
            log("ok", tr!("winget.allLatest"));
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
        log("ok", tr!("winget.allLatest"));
    } else {
        log("info", tr!("winget.found", items.len()));
    }

    items
}

/// 判断输出是不是 winget 的帮助/用法文本（即"当前版本的 winget 不认这些参数"）。
///
/// ⚠️ **不能只认中文提示**：原先写死 `contains("无法识别") || contains("使用情况")`，
/// 在英文或其它语言的系统上这两个词永远不会出现 → 会误判成"全部是最新版本"，
/// 等于对用户撒谎。所以这里分成两道：
///  1) 多语言关键词，覆盖最常见的几种；
///  2) 与语言无关的结构特征 —— 帮助文本里一定有以 `-` 开头的选项行（如 `  -v,--version`）。
/// 第 2 条是真正的兜底：换任何语言都成立。
fn looks_like_usage_help(out: &str) -> bool {
    const MARKERS: &[&str] = &[
        "无法识别", "使用情况", // 简体中文
        "無法辨識", "使用方式", // 繁體中文
        "Usage", "usage:", // 英文
        "Неизвестн", "Использование", // 俄文
        "認識され", "使用法", // 日文
    ];
    if MARKERS.iter().any(|m| out.contains(m)) {
        return true;
    }

    out.lines().any(|line| {
        let t = line.trim_start();
        // 以 - 开头，且不是表格分隔线（一整行都是 -）
        t.starts_with('-') && !t.chars().all(|c| c == '-' || c == ' ')
    })
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
    fn detects_usage_help_regardless_of_locale() {
        // 英文系统的"参数不认识"输出 —— 老实现只认中文，这里必须也认出来
        let en = "Unrecognized command\n\
                  Usage: winget [<command>]\n  \
                  -v,--version  Show the version";
        assert!(looks_like_usage_help(en), "英文用法文本没被识别");

        let zh = "无法识别输入。\n使用情况: winget [<命令>]";
        assert!(looks_like_usage_help(zh), "中文用法文本没被识别");

        // 正常表格输出不能被误判（分隔线是"整行都是 -"，要排除掉）
        let table = "名称  ID  版本  可用  源\n\
                     ---------------------------------\n\
                     Foo  Bar.Baz  1.0  2.0  winget";
        assert!(!looks_like_usage_help(table), "正常表格被误判成用法文本");

        // "没有可用升级"也不能被误判
        let none = "No installed package found matching input criteria.";
        assert!(!looks_like_usage_help(none), "无升级提示被误判成用法文本");
    }

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

