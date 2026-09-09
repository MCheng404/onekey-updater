use crate::model::NotifyPrefs;
use crate::util::now_time;
use serde_json::json;
use std::fs;
use std::path::PathBuf;
use tauri::{AppHandle, Emitter, Manager, PhysicalPosition, PhysicalSize};

/// 通知窗口尺寸
const NOTIFY_W: u32 = 360;
const NOTIFY_H: u32 = 92;
/// 屏幕边距
const SCREEN_PAD: i32 = 24;
/// 通知之间堆叠间距
#[allow(dead_code)]
const NOTIFY_GAP: i32 = 10;

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
    let t = title.replace('\'', "''").replace('"', "`\"");
    let b = body.replace('\'', "''").replace('"', "`\"");
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
    crate::util::run_capture(
        "powershell",
        &["-NoProfile", "-NonInteractive", "-Command", &script],
    )?;
    Ok(())
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

    // 重新定位 + 重新设定尺寸
    position_window(&window, &prefs.position)?;
    let _ = window.set_size(PhysicalSize::new(NOTIFY_W, NOTIFY_H));

    // 把窗口显出来（不抢焦点，前端接管动画 + 倒计时关闭）
    let _ = window.unminimize();
    let _ = window.show();

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

/// 按用户偏好，把通知窗口定位到屏幕某个角落。
///
/// 使用 work_area（工作区）而非全屏尺寸，避免被任务栏遮挡。
pub fn position_window(window: &tauri::WebviewWindow, position: &str) -> Result<(), String> {
    let monitor = window
        .current_monitor()
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "无法获取主显示器".to_string())?;

    // 使用工作区尺寸（排除任务栏等系统栏），而非全屏尺寸
    let work_area = monitor.work_area();
    let sx = work_area.size.width as i32;
    let sy = work_area.size.height as i32;
    let ox = work_area.position.x;
    let oy = work_area.position.y;

    // 注意：NOTIFY_H 是单条通知高度；实际窗口内部前端再 stack
    let (x, y) = match position {
        "top-left" => (ox + SCREEN_PAD, oy + SCREEN_PAD),
        "top-center" => (ox + (sx - NOTIFY_W as i32) / 2, oy + SCREEN_PAD),
        "top-right" => (ox + sx - NOTIFY_W as i32 - SCREEN_PAD, oy + SCREEN_PAD),
        "bottom-left" => (ox + SCREEN_PAD, oy + sy - NOTIFY_H as i32 - SCREEN_PAD),
        "bottom-center" => (
            ox + (sx - NOTIFY_W as i32) / 2,
            oy + sy - NOTIFY_H as i32 - SCREEN_PAD,
        ),
        "bottom-right" => (
            ox + sx - NOTIFY_W as i32 - SCREEN_PAD,
            oy + sy - NOTIFY_H as i32 - SCREEN_PAD,
        ),
        _ => (
            ox + sx - NOTIFY_W as i32 - SCREEN_PAD,
            oy + sy - NOTIFY_H as i32 - SCREEN_PAD,
        ),
    };

    window
        .set_position(PhysicalPosition::new(x, y))
        .map_err(|e| e.to_string())?;
    Ok(())
}

/// 计算"如果再新增一条 toast 时，窗口需要向上/向下平移多少"。
/// 当前实现单窗口单 toast 模式，所以此函数仅用于扩展预留。
#[allow(dead_code)]
pub fn stack_offset(position: &str, index: u32) -> i32 {
    let dir = if position.starts_with("top") { 1 } else { -1 };
    dir * (index as i32) * (NOTIFY_H as i32 + NOTIFY_GAP)
}