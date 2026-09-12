use crate::model::NotifyPrefs;
use crate::util::now_time;
use serde_json::json;
use std::fs;
use std::path::PathBuf;
use tauri::{AppHandle, Emitter, LogicalPosition, LogicalSize, Manager, WebviewWindow};

/// 通知窗口逻辑宽度（CSS px）。
/// 需与 styles.css 中 `.toast`(340px) + `.toast-root` 左右内边距(各 10px) 之和一致。
const NOTIFY_W: f64 = 360.0;
/// 单条 toast 的基准逻辑高度（`.toast` min-height 84 + 上下内边距 24）。
/// 前端收到事件后会用实测高度覆盖，这里只用于首次显示时的兜底。
const NOTIFY_BASE_H: f64 = 108.0;
/// 窗口最小逻辑高度，避免高度为 0 时窗口不可见
const NOTIFY_MIN_H: f64 = 60.0;
/// 屏幕边距（逻辑 px）
const SCREEN_PAD: f64 = 24.0;

/// 默认通知偏好（独立应用通知、底部居中、显示 4 秒、透明度 88%）
pub fn default_prefs() -> NotifyPrefs {
    NotifyPrefs {
        mode: "native".to_string(),  // 默认走 Windows 原生通知（避免打扰）
        position: "bottom-right".to_string(),
        duration_ms: 4500,
        opacity: 90,
        max_stack: 4,
    }
}

/// 通知偏好持久化文件：`%LOCALAPPDATA%\onekey-updater\notify-prefs.json`
fn prefs_path() -> Option<PathBuf> {
    let local = std::env::var("LOCALAPPDATA").ok()?;
    Some(PathBuf::from(local).join("onekey-updater").join("notify-prefs.json"))
}

pub fn load_prefs(_app: &AppHandle) -> NotifyPrefs {
    let Some(path) = prefs_path() else {
        return default_prefs();
    };
    let Ok(text) = fs::read_to_string(&path) else {
        return default_prefs();
    };
    serde_json::from_str(&text).unwrap_or_else(|_| default_prefs())
}

pub fn save_prefs(_app: &AppHandle, prefs: NotifyPrefs) -> Result<(), String> {
    let Some(path) = prefs_path() else {
        return Err("无法获取 LOCALAPPDATA".into());
    };
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let text = serde_json::to_string_pretty(&prefs).map_err(|e| e.to_string())?;
    fs::write(&path, text).map_err(|e| e.to_string())
}

/// Windows 原生通知：通过 PowerShell 调用 System.Windows.Forms.NotifyIcon 的气泡提示。
///
/// 注意：气泡需要消息循环才能显示，这里用 DoEvents 空转 3 秒；
/// 整个脚本在 spawn_blocking 里执行，不阻塞主线程。
/// 失败时静默返回，不影响核心更新流程。
pub fn show_native(title: &str, body: &str) -> Result<(), String> {
    // 文本会被嵌入 PowerShell 的单引号字符串（'...'）中。
    // 单引号字符串里唯一的转义是 ''（两个单引号），反引号不是转义符——
    // 之前把 " 替换成 `" 会导致通知正文里出现多余的反引号。
    let t = escape_ps_single(title);
    let b = escape_ps_single(body);
    let script = format!(
        r#"
$ErrorActionPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Windows.Forms
$notify = New-Object System.Windows.Forms.NotifyIcon
$notify.Icon = [System.Drawing.SystemIcons]::Information
$notify.Visible = $true
$notify.ShowBalloonTip(3000, '{t}', '{b}', [System.Windows.Forms.ToolTipIcon]::Info)
$end = (Get-Date).AddSeconds(3)
while ((Get-Date) -lt $end) {{
  [System.Windows.Forms.Application]::DoEvents()
  Start-Sleep -Milliseconds 80
}}
$notify.Dispose()
"#,
        t = t,
        b = b,
    );
    // 走 run_powershell（落盘 + -File）：脚本是多行的，经 cmd /C ... -Command
    // 会被解析坏而静默失败（通知一直发不出来就是这个原因）。
    crate::util::run_powershell(&script, 20)?;
    Ok(())
}

/// 转义要嵌入 PowerShell 单引号字符串的文本：仅需把 ' 变成 ''，
/// 并去除换行（避免破坏单行命令结构）。
fn escape_ps_single(s: &str) -> String {
    s.replace(['\r', '\n'], " ").replace('\'', "''")
}

/// 显示应用内 Toast（独立通知窗口）。
///
/// 通知窗口是一个常驻 Webview（label=notify，url=notify.html），里面跑一个
/// Toast 栈渲染器。Rust 端负责：
///  - 计算窗口在屏幕上的物理位置（按偏好位置）
///  - 调整窗口尺寸
///  - 通过 `notify-toast` 事件把消息推送给前端
pub fn show_toast(app: &AppHandle, title: &str, body: &str, level: &str) -> Result<(), String> {
    let prefs = load_prefs(app);

    if prefs.mode == "native" {
        // 原生模式：仅走系统通知，不显示应用内窗口
        let _ = show_native(title, body);
        return Ok(());
    }

    let Some(window) = app.get_webview_window("notify") else {
        return Err("通知窗口未配置".into());
    };

    // 先按单条通知做一次兜底布局，**再**显示窗口。
    // 顺序很重要：反过来（先 show 后 layout）窗口会以"上一次的旧位置/旧尺寸"
    // 先渲染一帧才被挪走，肉眼就是"莫名其妙闪一下的窗口"。
    // layout 在隐藏状态下拿不到显示器时会自行跳过，前端随后用 layout_notify 校正。
    let _ = layout(&window, &prefs.position, NOTIFY_BASE_H);

    let _ = window.unminimize();
    let _ = window.show();

    // 显示后再校正一次（此时必定能拿到有效显示器），确保位置准确
    let _ = layout(&window, &prefs.position, NOTIFY_BASE_H);

    // 推送消息
    let payload = json!({
        "title": title,
        "body": body,
        "level": level,
        "durationMs": prefs.duration_ms,
        "ts": now_time(),
    });
    let _ = app.emit("notify-toast", payload);

    Ok(())
}

/// 按当前 toast 数量，重新设置通知窗口的尺寸与位置。
///
/// 之所以要动态改尺寸：窗口透明区域仍会拦截鼠标事件，
/// 若窗口固定按最大条数留白，会挡住下方其它窗口的点击。
/// 因此窗口高度必须与内容等高。
///
/// - `top-*`    窗口顶边贴工作区顶边（新通知向下生长）
/// - `bottom-*` 窗口底边贴工作区底边（新通知向上生长）
///
/// `height_css` 由前端实测（CSS px），Rust 侧按显示器缩放系数换算。
pub fn layout(window: &WebviewWindow, position: &str, height_css: f64) -> Result<(), String> {
    let monitor = window
        .current_monitor()
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "无法获取主显示器".to_string())?;

    let scale = monitor.scale_factor();
    // 工作区（排除任务栏）换算到逻辑坐标
    let work = monitor.work_area();
    let work_w = work.size.width as f64 / scale;
    let work_h = work.size.height as f64 / scale;
    let ox = work.position.x as f64 / scale;
    let oy = work.position.y as f64 / scale;

    let h = height_css.max(NOTIFY_MIN_H);
    let w = NOTIFY_W;

    window
        .set_size(LogicalSize::new(w, h))
        .map_err(|e| e.to_string())?;

    let is_top = position.starts_with("top");
    let y = if is_top {
        oy + SCREEN_PAD
    } else {
        oy + work_h - h - SCREEN_PAD
    };

    let x = if position.ends_with("left") {
        ox + SCREEN_PAD
    } else if position.ends_with("center") {
        ox + (work_w - w) / 2.0
    } else {
        ox + work_w - w - SCREEN_PAD
    };

    window
        .set_position(LogicalPosition::new(x, y))
        .map_err(|e| e.to_string())?;
    Ok(())
}