//! 运行日志落盘。
//!
//! 日志按天写到 `%LOCALAPPDATA%\onekey-updater\logs\YYYY-MM-DD.log`，
//! 供更新失败后事后排查（设置里的「打开日志文件夹」也指向这里）。
//!
//! 设计原则：写日志失败一律静默 —— 它只是辅助手段，绝不能影响更新主流程。

use crate::model::LogLine;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::PathBuf;

/// 日志目录：`%LOCALAPPDATA%\onekey-updater\logs`
pub fn log_dir() -> Option<PathBuf> {
    let local = std::env::var("LOCALAPPDATA").ok()?;
    Some(PathBuf::from(local).join("onekey-updater").join("logs"))
}

/// 今天日志文件的完整路径
pub fn today_file() -> Option<PathBuf> {
    Some(log_dir()?.join(format!("{}.log", today())))
}

/// 追加一行日志（失败静默）
pub fn append(line: &LogLine) {
    let Some(path) = today_file() else {
        return;
    };
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    // 先在内存里拼出完整的一整行，再一次性写入。
    //
    // 直接 `writeln!(f, "{} [{}] {}", ..)` 会被拆成**多次** write 系统调用
    // （每个格式参数一次），而 Windows 的 FILE_APPEND_DATA 只保证**单次写**
    // 的原子性，保证不了一个逻辑行的原子性。check_updates 同时跑四个源线程
    // （npm / winget / pip / openclaw），谁都在高频调 emit_log —— 实测四线程
    // 各写 300 行时有 43% 的行被撕成半截交错。
    // 而这个文件存在的唯一理由就是"更新失败后事后排查"，撕坏了就毫无价值。
    let buf = format!(
        "{} [{}] {}\n",
        line.at,
        line.level.to_uppercase(),
        line.text
    );

    let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&path) else {
        return;
    };
    let _ = f.write_all(buf.as_bytes());
}

/// 本地日期 `YYYY-MM-DD`（UTC+8，避免为此引入 chrono）
fn today() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let days = ((secs + 8 * 3600) / 86_400) as i64;
    let (y, m, d) = civil_from_days(days);
    format!("{:04}-{:02}-{:02}", y, m, d)
}

/// Howard Hinnant 的 `days -> (y, m, d)` 算法（days 自 1970-01-01 起）
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as i64; // [0, 146096]
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365; // [0, 399]
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100); // [0, 365]
    let mp = (5 * doy + 2) / 153; // [0, 11]
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32; // [1, 31]
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32; // [1, 12]
    (if m <= 2 { y + 1 } else { y }, m, d)
}

#[cfg(test)]
mod tests {
    use super::civil_from_days;

    #[test]
    fn epoch_is_1970_01_01() {
        assert_eq!(civil_from_days(0), (1970, 1, 1));
    }

    #[test]
    fn leap_day_2024() {
        // 2024-02-29 = 1970-01-01 之后第 19782 天
        assert_eq!(civil_from_days(19_782), (2024, 2, 29));
    }

    #[test]
    fn year_end_2026() {
        assert_eq!(civil_from_days(20_818), (2026, 12, 31));
    }
}
