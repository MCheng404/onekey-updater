use std::cmp::Ordering;
use std::io::{BufRead, BufReader};
use std::process::{Command, Stdio};
use std::sync::{mpsc, RwLock};
use std::time::Duration;

#[cfg(windows)]
use std::os::windows::process::CommandExt;

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// is_admin 结果缓存：应用运行期间管理员状态不会变化，
/// 避免每次 OpenClaw 更新都启动 `net session` 子进程。
static ADMIN_CACHE: RwLock<Option<bool>> = RwLock::new(None);

/// 子进程输出解码：优先 UTF-8，失败回退 GBK（Windows 中文环境常见）
pub fn decode(bytes: &[u8]) -> String {
    match std::str::from_utf8(bytes) {
        Ok(s) => s.to_string(),
        Err(_) => {
            let (cow, _, _) = encoding_rs::GBK.decode(bytes);
            cow.into_owned()
        }
    }
}

/// 构造子进程。Windows 下统一走 cmd /C，兼容 npm.cmd / pip.exe / winget.exe 等。
fn base_command(program: &str, args: &[&str]) -> Command {
    let mut cmd = Command::new("cmd");
    cmd.arg("/D").arg("/C").arg(program);
    for a in args {
        cmd.arg(a);
    }
    // 让 Python 系工具输出 UTF-8
    cmd.env("PYTHONIOENCODING", "utf-8");
    cmd.env("PYTHONUTF8", "1");

    #[cfg(windows)]
    cmd.creation_flags(CREATE_NO_WINDOW);

    cmd
}

/// 同步执行并捕获 stdout，默认 30 秒超时
pub fn run_capture(program: &str, args: &[&str]) -> Result<String, String> {
    run_capture_with_timeout(program, args, 30)
}

/// 带超时的同步执行并捕获 stdout
pub fn run_capture_with_timeout(
    program: &str,
    args: &[&str],
    timeout_secs: u64,
) -> Result<String, String> {
    let program_owned = program.to_string();
    let args_owned: Vec<String> = args.iter().map(|s| s.to_string()).collect();

    let (tx, rx) = mpsc::channel::<Result<String, String>>();

    std::thread::spawn(move || {
        let args_ref: Vec<&str> = args_owned.iter().map(|s| s.as_str()).collect();
        let result = (|| -> Result<String, String> {
            let out = base_command(&program_owned, &args_ref)
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .output()
                .map_err(|e| format!("执行 {} 失败: {}", program_owned, e))?;

            let mut text = decode(&out.stdout);
            if text.trim().is_empty() {
                text = decode(&out.stderr);
            }
            Ok(text)
        })();
        let _ = tx.send(result);
    });

    rx.recv_timeout(Duration::from_secs(timeout_secs))
        .map_err(|_| format!("{} 执行超时（{}秒）", program, timeout_secs))?
}

/// 流式执行，逐行回调；返回进程退出码。默认 120 秒超时。
pub fn run_stream<F: FnMut(&str)>(program: &str, args: &[&str], on_line: F) -> Result<i32, String> {
    run_stream_with_timeout(program, args, 120, on_line)
}

/// 带超时的流式执行，逐行回调；返回进程退出码。
///
/// 优化：用 250ms 轮询替代 100ms，减少 CPU 唤醒次数；
/// 有输出时立即处理（recv_timeout 返回 Ok），无输出时才轮询检查子进程。
pub fn run_stream_with_timeout<F: FnMut(&str)>(
    program: &str,
    args: &[&str],
    timeout_secs: u64,
    mut on_line: F,
) -> Result<i32, String> {
    let mut child = base_command(program, args)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("启动 {} 失败: {}", program, e))?;

    let stdout = child.stdout.take();
    let stderr = child.stderr.take();

    let (tx, rx) = mpsc::channel::<Vec<u8>>();

    // stdout 读取线程
    if let Some(out) = stdout {
        let tx = tx.clone();
        std::thread::spawn(move || {
            let mut reader = BufReader::new(out);
            loop {
                let mut buf = Vec::new();
                match reader.read_until(b'\n', &mut buf) {
                    Ok(0) => break,
                    Ok(_) => {
                        if tx.send(buf).is_err() {
                            break;
                        }
                    }
                    Err(_) => break,
                }
            }
        });
    }

    // stderr 读取线程
    if let Some(err) = stderr {
        let tx = tx.clone();
        std::thread::spawn(move || {
            let mut reader = BufReader::new(err);
            loop {
                let mut buf = Vec::new();
                match reader.read_until(b'\n', &mut buf) {
                    Ok(0) => break,
                    Ok(_) => {
                        if tx.send(buf).is_err() {
                            break;
                        }
                    }
                    Err(_) => break,
                }
            }
        });
    }

    drop(tx);

    let deadline = std::time::Instant::now() + Duration::from_secs(timeout_secs);
    let poll_interval = Duration::from_millis(250);

    // 接收输出 + 等待子进程，带总超时
    loop {
        match rx.recv_timeout(poll_interval) {
            Ok(raw) => {
                let line = decode(&raw);
                let line = line.trim_end_matches(['\r', '\n']);
                on_line(line);
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {
                // 250ms 内无新输出，检查子进程是否已退出
                match child.try_wait() {
                    Ok(Some(status)) => {
                        // 子进程已退出，排空通道中剩余的输出
                        for raw in rx.try_iter() {
                            let line = decode(&raw);
                            let line = line.trim_end_matches(['\r', '\n']);
                            on_line(line);
                        }
                        return Ok(status.code().unwrap_or(-1));
                    }
                    Ok(None) => {
                        // 子进程仍在运行，检查总超时
                        if std::time::Instant::now() > deadline {
                            let _ = child.kill();
                            let _ = child.wait();
                            return Err(format!("{} 执行超时（{}秒）", program, timeout_secs));
                        }
                        // 继续循环
                    }
                    Err(e) => return Err(format!("等待 {} 失败: {}", program, e)),
                }
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => {
                // 所有读取线程已退出，等待子进程结束
                let status = child
                    .wait()
                    .map_err(|e| format!("等待 {} 结束失败: {}", program, e))?;
                return Ok(status.code().unwrap_or(-1));
            }
        }
    }
}

/// 命令是否可用
pub fn cmd_exists(program: &str) -> bool {
    which::which(program).is_ok()
}

/// 是否管理员权限（带缓存，5 秒超时，超时视为非管理员）
///
/// 优化：结果缓存到 ADMIN_CACHE，应用运行期间只检测一次，
/// 避免每次 OpenClaw 更新都启动 `net session` 子进程。
pub fn is_admin() -> bool {
    // 快速路径：读缓存
    if let Ok(guard) = ADMIN_CACHE.read() {
        if let Some(cached) = *guard {
            return cached;
        }
    }

    // 慢速路径：检测并缓存
    let result = detect_admin();
    if let Ok(mut guard) = ADMIN_CACHE.write() {
        *guard = Some(result);
    }
    result
}

fn detect_admin() -> bool {
    let (tx, rx) = mpsc::channel::<bool>();
    std::thread::spawn(move || {
        let mut cmd = Command::new("net");
        cmd.arg("session").stdout(Stdio::null()).stderr(Stdio::null());
        #[cfg(windows)]
        cmd.creation_flags(CREATE_NO_WINDOW);
        let result = cmd.status().map(|s| s.success()).unwrap_or(false);
        let _ = tx.send(result);
    });
    rx.recv_timeout(Duration::from_secs(5))
        .unwrap_or(false)
}

/// 本地时间 HH:MM:SS（避免引入 chrono 依赖）
///
/// 统一从 util 导出，model.rs 和 notify.rs 共用，避免重复实现。
pub fn now_time() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    // UTC+8（中国标准时间）
    let secs_of_day = (secs + 8 * 3600) % 86400;
    let h = secs_of_day / 3600;
    let m = (secs_of_day % 3600) / 60;
    let s = secs_of_day % 60;
    format!("{:02}:{:02}:{:02}", h, m, s)
}

/// 宽松版本号比较：1 = a>b, -1 = a<b, 0 = 相等
pub fn compare_version(a: &str, b: &str) -> i8 {
    match cmp_ver(a, b) {
        Ordering::Greater => 1,
        Ordering::Less => -1,
        Ordering::Equal => 0,
    }
}

fn split_ver(v: &str) -> Vec<String> {
    let trimmed = v.trim();
    let trimmed = trimmed.trim_start_matches(|c: char| !c.is_ascii_digit());
    trimmed
        .split(|c: char| c == '.' || c == '-' || c == '+' || c == '_')
        .filter(|s| !s.is_empty())
        .map(|s| s.to_string())
        .collect()
}

fn cmp_ver(a: &str, b: &str) -> Ordering {
    let va = split_ver(a);
    let vb = split_ver(b);
    let len = va.len().max(vb.len());

    for i in 0..len {
        let x = va.get(i).map(|s| s.as_str()).unwrap_or("0");
        let y = vb.get(i).map(|s| s.as_str()).unwrap_or("0");
        let nx = x.parse::<u64>().ok();
        let ny = y.parse::<u64>().ok();

        match (nx, ny) {
            (Some(p), Some(q)) => {
                if p != q {
                    return p.cmp(&q);
                }
            }
            // 数字段优先于非数字段（release > prerelease）
            (Some(_), None) => return Ordering::Greater,
            (None, Some(_)) => return Ordering::Less,
            (None, None) => {
                if x != y {
                    return x.cmp(y);
                }
            }
        }
    }
    Ordering::Equal
}

/// 从可能带前导警告的输出里抠出 JSON 对象（第一个 { 到最后一个 }）
pub fn extract_json_object(s: &str) -> Option<&str> {
    let start = s.find('{')?;
    let end = s.rfind('}')?;
    if end > start {
        Some(&s[start..=end])
    } else {
        None
    }
}

/// 从可能带前导警告的输出里抠出 JSON 数组（第一个 [ 到最后一个 ]）
pub fn extract_json_array(s: &str) -> Option<&str> {
    let start = s.find('[')?;
    let end = s.rfind(']')?;
    if end > start {
        Some(&s[start..=end])
    } else {
        None
    }
}

/// 受限并发的并行 map。把输入切成 `limit` 份交给工作线程，
/// 用于批量跑外部命令（npm view / pip 查询等网络 IO 密集操作）。
///
/// 优化：工作线程 panic 时输出错误日志，避免静默丢失结果。
pub fn par_map<T, R, F>(items: Vec<T>, limit: usize, f: F) -> Vec<R>
where
    T: Send + Sync + 'static,
    R: Send + 'static,
    F: Fn(&T) -> R + Send + Sync + 'static,
{
    let n = items.len();
    if n == 0 {
        return Vec::new();
    }

    let workers = limit.max(1).min(n);
    let chunk = (n + workers - 1) / workers;

    let shared = std::sync::Arc::new(items);
    let func = std::sync::Arc::new(f);
    let mut handles = Vec::with_capacity(workers);

    for idx in 0..workers {
        let start = idx * chunk;
        if start >= n {
            break;
        }
        let end = (start + chunk).min(n);
        let shared = shared.clone();
        let func = func.clone();

        handles.push(std::thread::spawn(move || {
            let mut out = Vec::with_capacity(end - start);
            for i in start..end {
                out.push(func(&shared[i]));
            }
            out
        }));
    }

    let mut result = Vec::with_capacity(n);
    for (i, h) in handles.into_iter().enumerate() {
        match h.join() {
            Ok(mut part) => result.append(&mut part),
            Err(_) => eprintln!("[par_map] 工作线程 {} panic，对应结果已丢失", i),
        }
    }
    result
}
