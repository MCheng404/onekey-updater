mod icon;
mod model;
mod notify;
mod sources;
mod util;

use std::collections::HashMap;

use icon::fetch_icons_blocking;
use model::{EnvStatus, IconEntry, ItemResult, LogLine, NotifyPrefs, Progress, Source, UpdateItem};
use sources::{
    npm::NpmSource, openclaw::OpenclawSource, pip::PipSource, winget::WingetSource, LogFn,
    UpdateSource,
};
use tauri::{AppHandle, Emitter, Manager};

/// 探测当前环境：哪些源可用、是否管理员
///
/// 注意：is_admin() 会启动 `net session` 子进程，必须放到 spawn_blocking
/// 中执行，否则同步 command 在某些系统配置下会阻塞事件循环。
#[tauri::command]
async fn detect_env() -> Result<EnvStatus, String> {
    tauri::async_runtime::spawn_blocking(|| EnvStatus {
        npm: util::cmd_exists("npm"),
        winget: util::cmd_exists("winget"),
        pip: util::cmd_exists("pip"),
        openclaw: util::cmd_exists("openclaw"),
        is_admin: util::is_admin(),
    })
    .await
    .map_err(|e| e.to_string())
}

/// 检查全部可更新项，日志通过 update-log 事件流式推送
#[tauri::command]
async fn check_updates(app: AppHandle) -> Result<Vec<UpdateItem>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        // 四个源并行探测：各自拉起独立子进程，串行会互相等待
        let handles = vec![
            {
                let a = app.clone();
                std::thread::spawn(move || check_one(a, NpmSource))
            },
            {
                let a = app.clone();
                std::thread::spawn(move || check_one(a, WingetSource))
            },
            {
                let a = app.clone();
                std::thread::spawn(move || check_one(a, PipSource))
            },
            {
                let a = app.clone();
                std::thread::spawn(move || check_one(a, OpenclawSource))
            },
        ];

        let mut all = Vec::new();
        for h in handles {
            match h.join() {
                Ok(mut part) => all.append(&mut part),
                Err(_) => {
                    let _ = app.emit(
                        "update-log",
                        LogLine::new("err", "某个更新源线程异常终止，已跳过"),
                    );
                }
            }
        }
        all
    })
    .await
    .map_err(|e| e.to_string())
}

/// 在独立线程里探测单个源
fn check_one<S: UpdateSource + Send + 'static>(app: AppHandle, source: S) -> Vec<UpdateItem> {
    if !source.available() {
        let _ = app.emit(
            "update-log",
            LogLine::new("warn", format!("跳过 {}（未安装）", source.source().label())),
        );
        return Vec::new();
    }

    let logger = |level: &str, text: String| {
        let _ = app.emit("update-log", LogLine::new(level, text));
    };
    source.check(&logger)
}

/// 执行勾选的更新项，日志与进度通过事件推送
#[tauri::command]
async fn start_update(app: AppHandle, items: Vec<UpdateItem>) -> Result<Vec<ItemResult>, String> {
    let handle = app.clone();

    tauri::async_runtime::spawn_blocking(move || {
        let logger = |level: &str, text: String| {
            let _ = handle.emit("update-log", LogLine::new(level, text));
        };
        let log: LogFn = &logger;

        let total = items.len();
        let mut results: Vec<ItemResult> = Vec::new();

        for (i, item) in items.iter().enumerate() {
            let _ = handle.emit(
                "update-progress",
                Progress {
                    done: i,
                    total,
                    current_id: item.id.clone(),
                    current_name: item.name.clone(),
                    status: "running".to_string(),
                },
            );

            let ok = match item.source {
                Source::Npm => NpmSource.update(item, log),
                Source::Winget => WingetSource.update(item, log),
                Source::Pip => PipSource.update(item, log),
                Source::Openclaw => OpenclawSource.update(item, log),
            };

            results.push(ItemResult {
                id: item.id.clone(),
                name: item.name.clone(),
                ok,
            });
        }

        let _ = handle.emit(
            "update-progress",
            Progress {
                done: total,
                total,
                current_id: String::new(),
                current_name: String::new(),
                status: "done".to_string(),
            },
        );

        results
    })
    .await
    .map_err(|e| e.to_string())
}

/// 打开设置子窗口。
/// 窗口在配置中已预创建（visible: false），这里只负责显示。
/// 改为 async command，避免窗口操作与事件循环互等造成死锁。
#[tauri::command]
async fn open_settings(app: AppHandle) {
    if let Some(window) = app.get_webview_window("settings") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

/// 打开关于子窗口。
#[tauri::command]
async fn open_about(app: AppHandle) {
    if let Some(window) = app.get_webview_window("about") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

/// 给一组 items 填充图标（仅 winget 能命中）。
/// 返回 id → data_url 映射，前端合并到 items。
#[tauri::command]
async fn fetch_icons(items: Vec<UpdateItem>) -> Result<HashMap<String, String>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let (map, _ms) = fetch_icons_blocking(&items);
        Ok(map)
    })
    .await
    .map_err(|e| e.to_string())?
}

/// 主动重建图标缓存（设置面板"刷新图标"按钮触发）
#[tauri::command]
async fn rebuild_icon_cache() -> Result<Vec<IconEntry>, String> {
    tauri::async_runtime::spawn_blocking(|| {
        let map = icon::rebuild_cache();
        Ok(map
            .into_iter()
            .map(|(name, icon)| IconEntry { name, icon })
            .collect())
    })
    .await
    .map_err(|e| e.to_string())?
}

/// 显示一条应用内通知（独立 toast 窗口）。
/// level: info / ok / warn / err
///
/// 注意：native 模式下会启动 PowerShell 并空转 3 秒等待气泡消失，
/// 这里采用 fire-and-forget：spawn 后立即返回，不阻塞调用方。
#[tauri::command]
async fn send_notification(
    app: AppHandle,
    title: String,
    body: String,
    level: Option<String>,
) -> Result<(), String> {
    let level = level.unwrap_or_else(|| "info".to_string());
    tauri::async_runtime::spawn(async move {
        let _ = tauri::async_runtime::spawn_blocking(move || {
            notify::show_toast(&app, &title, &body, &level)
        })
        .await;
    });
    Ok(())
}

/// 持久化通知偏好
#[tauri::command]
async fn save_notify_prefs(app: AppHandle, prefs: NotifyPrefs) -> Result<(), String> {
    notify::save_prefs(&app, prefs.clone()).map_err(|e| e.to_string())?;
    // 实时通知 notify 窗口更新 CSS 变量与堆叠方向
    let _ = app.emit("notify-prefs-changed", prefs);
    Ok(())
}

/// 读取通知偏好
#[tauri::command]
async fn load_notify_prefs(app: AppHandle) -> Result<NotifyPrefs, String> {
    Ok(notify::load_prefs(&app))
}

/// 开机自启动：注册表路径 HKCU\Software\Microsoft\Windows\CurrentVersion\Run
const AUTOSTART_REG_KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
const AUTOSTART_REG_VALUE: &str = "OneKeyUpdater";

/// 查询是否已启用开机自启动
#[tauri::command]
async fn get_autostart() -> Result<bool, String> {
    let output = std::process::Command::new("reg")
        .args(["query", AUTOSTART_REG_KEY, "/v", AUTOSTART_REG_VALUE])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .map_err(|e| e.to_string())?;
    Ok(output.success())
}

/// 设置开机自启动（启用/禁用）
#[tauri::command]
async fn set_autostart(enable: bool) -> Result<(), String> {
    if enable {
        let exe = std::env::current_exe().map_err(|e| format!("获取可执行文件路径失败: {}", e))?;
        let exe_path = exe.to_string_lossy().to_string();
        // 用引号包裹路径，避免路径含空格时解析错误；
        // main.rs 已设置 windows_subsystem="windows"，不会弹出控制台窗口
        let quoted = format!("\"{}\"", exe_path);
        let status = std::process::Command::new("reg")
            .args([
                "add",
                AUTOSTART_REG_KEY,
                "/v",
                AUTOSTART_REG_VALUE,
                "/t",
                "REG_SZ",
                "/d",
                &quoted,
                "/f",
            ])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .map_err(|e| e.to_string())?;
        if !status.success() {
            return Err("写入注册表失败".into());
        }
    } else {
        let _ = std::process::Command::new("reg")
            .args(["delete", AUTOSTART_REG_KEY, "/v", AUTOSTART_REG_VALUE, "/f"])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status();
    }
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .setup(|app| {
            // 设置窗口关闭时改为隐藏而非销毁，下次打开直接 show
            if let Some(window) = app.get_webview_window("settings") {
                let handle = window.clone();
                window.on_window_event(move |event| {
                    if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                        api.prevent_close();
                        let _ = handle.hide();
                    }
                });
            }

            // 通知窗口默认隐藏；每次 show_toast 时按偏好位置弹出
            if let Some(window) = app.get_webview_window("notify") {
                let handle = window.clone();
                window.on_window_event(move |event| {
                    if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                        api.prevent_close();
                        let _ = handle.hide();
                    }
                });
            }

            // 关于窗口关闭时隐藏而非销毁
            if let Some(window) = app.get_webview_window("about") {
                let handle = window.clone();
                window.on_window_event(move |event| {
                    if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                        api.prevent_close();
                        let _ = handle.hide();
                    }
                });
            }

            // 主窗口真正关闭时，退出整个应用，避免 settings/notify
            // 因 prevent_close 残留导致僵尸进程（用户报告的"通知残留进程"）。
            if let Some(window) = app.get_webview_window("main") {
                let app_handle = app.handle().clone();
                window.on_window_event(move |event| {
                    if let tauri::WindowEvent::CloseRequested { .. } = event {
                        // 主窗口关闭 = 退出应用，彻底清理所有子窗口与子进程
                        app_handle.exit(0);
                    }
                });
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            detect_env,
            check_updates,
            start_update,
            open_settings,
            open_about,
            fetch_icons,
            rebuild_icon_cache,
            send_notification,
            save_notify_prefs,
            load_notify_prefs,
            get_autostart,
            set_autostart
        ])
        .run(tauri::generate_context!())
        .expect("启动 Tauri 应用失败");
}
