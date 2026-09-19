mod font;
mod i18n;
mod icon;
mod logfile;
mod model;
mod notify;
mod sources;
mod util;

use std::collections::HashMap;

#[cfg(windows)]
use std::os::windows::process::CommandExt;
#[cfg(windows)]
use util::CREATE_NO_WINDOW;
// 注：`tr!` 由 i18n.rs 的 #[macro_export] 导出，在 crate 根可直接使用，
// 这里**不能**再 `use crate::tr;`（会与根命名空间里的宏重复定义）。

use icon::fetch_icons_blocking;
use model::{EnvStatus, IconEntry, ItemResult, LogLine, NotifyPrefs, Progress, Source, UpdateItem};
use sources::{
    npm::NpmSource, openclaw::OpenclawSource, pip::PipSource, winget::WingetSource, LogFn,
    UpdateSource,
};
use tauri::{AppHandle, Emitter, LogicalSize, Manager, WebviewWindow};

/// 统一的日志出口：**落盘 + 推送前端**。
///
/// 所有日志都必须走这里，不要直接 `app.emit("update-log", ...)`，
/// 否则日志文件会缺行，用户点「打开日志」看到的就不完整。
fn emit_log(app: &AppHandle, level: &str, text: impl Into<String>) {
    let line = LogLine::new(level, text);
    logfile::append(&line);
    let _ = app.emit("update-log", line);
}

/// 探测当前环境：哪些源可用、是否管理员
///
/// 注意：is_admin() 会启动 `net session` 子进程，必须放到 spawn_blocking
/// 中执行，否则同步 command 在某些系统配置下会阻塞事件循环。
#[tauri::command]
async fn detect_env() -> Result<EnvStatus, String> {
    tauri::async_runtime::spawn_blocking(|| {
        // Node 版本决定 OpenClaw 装不装得上（要求 >=24.16 <25 || >=26.1）。
        // 此前 app 对它一无所知，用户只能从 npm 的一堆输出里猜。
        let node_version = util::node_version();
        let node_ok = node_version
            .as_deref()
            .map(util::node_satisfies_openclaw)
            .unwrap_or(false);
        EnvStatus {
            npm: util::cmd_exists("npm"),
            winget: util::cmd_exists("winget"),
            pip: util::cmd_exists("pip"),
            openclaw: util::cmd_exists("openclaw"),
            is_admin: util::is_admin(),
            node_version,
            node_ok,
        }
    })
    .await
    .map_err(|e| e.to_string())
}

/// 前端把当前语言推给后端。
///
/// 后端要自己产出日志文案（"发现 N 个 winget 软件可更新"），这些文案进的是
/// 日志面板与日志文件，前端 i18n 管不到 —— 所以必须让后端知道当前语言，
/// 否则切到英文后界面全英文、唯独日志仍是中文。
///
/// 主窗口前端在启动时、以及收到 lang-changed 事件时各调用一次。
#[tauri::command]
async fn set_language(lang: String) {
    i18n::set_lang(&lang);
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
                Err(_) => emit_log(&app, "err", tr!("log.threadPanic")),
            }
        }
        all
    })
    .await
    .map_err(|e| e.to_string())
}

/// 单个源检查完成时的回传载荷。
/// 前端据此逐源渲染 —— 哪个源先查完就先显示，不必等最慢的那个（通常是 winget）。
#[derive(Clone, serde::Serialize)]
struct SourceResult {
    source: Source,
    items: Vec<UpdateItem>,
}

/// 在独立线程里探测单个源
fn check_one<S: UpdateSource + Send + 'static>(app: AppHandle, source: S) -> Vec<UpdateItem> {
    if !source.available() {
        emit_log(&app, "warn", tr!("log.skipMissing", source.source().label()));
        return Vec::new();
    }

    let logger = |level: &str, text: String| emit_log(&app, level, text);
    let items = source.check(&logger);

    // 逐源推送结果（前端"边查边出"）。失败不影响返回值，收尾时仍会拿到完整列表。
    let _ = app.emit(
        "check-source-result",
        SourceResult {
            source: source.source(),
            items: items.clone(),
        },
    );

    items
}

/// 执行勾选的更新项，日志与进度通过事件推送
#[tauri::command]
async fn start_update(app: AppHandle, items: Vec<UpdateItem>) -> Result<Vec<ItemResult>, String> {
    let handle = app.clone();

    tauri::async_runtime::spawn_blocking(move || {
        let logger = |level: &str, text: String| emit_log(&handle, level, text);
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

/* ============ 自定义字体 ============ */

/// 枚举系统已安装字族（Rust 侧有缓存，重复调用不会重复扫注册表）
#[tauri::command]
async fn list_system_fonts() -> Result<Vec<String>, String> {
    tauri::async_runtime::spawn_blocking(font::list_system_fonts)
        .await
        .map_err(|e| e.to_string())
}

/// 列出目录下的字体文件（绝对路径）
#[tauri::command]
async fn list_font_files(dir: String) -> Result<Vec<String>, String> {
    tauri::async_runtime::spawn_blocking(move || font::list_font_files(&dir))
        .await
        .map_err(|e| e.to_string())
}

/// 弹出原生「选择文件夹」对话框
#[tauri::command]
async fn pick_font_dir() -> Result<Option<String>, String> {
    tauri::async_runtime::spawn_blocking(font::pick_dir)
        .await
        .map_err(|e| e.to_string())
}

/// 弹出原生「选择字体文件」对话框
#[tauri::command]
async fn pick_font_file() -> Result<Option<String>, String> {
    tauri::async_runtime::spawn_blocking(font::pick_file)
        .await
        .map_err(|e| e.to_string())
}

/// 设定 / 清除自定义字体文件（传 None 表示恢复内置字体）
#[tauri::command]
async fn set_ui_font_file(path: Option<String>) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || font::persist(path))
        .await
        .map_err(|e| e.to_string())?
}

/// 查询当前自定义字体文件路径
#[tauri::command]
async fn get_ui_font_file() -> Option<String> {
    font::current().map(|p| p.to_string_lossy().to_string())
}

/// 查询日志目录路径（设置面板展示用）
#[tauri::command]
async fn get_log_dir() -> Result<String, String> {
    logfile::log_dir()
        .map(|p| p.to_string_lossy().to_string())
        .ok_or_else(|| tr!("lib.logDirFailed"))
}

/// 在文件资源管理器中打开日志文件夹
#[tauri::command]
async fn open_log_dir() -> Result<(), String> {
    let dir = logfile::log_dir().ok_or_else(|| tr!("lib.logDirFailed"))?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;

    #[cfg(windows)]
    {
        // explorer.exe 打开目录时退出码通常非 0，所以只 spawn 不等结果
        std::process::Command::new("explorer")
            .arg(dir.as_os_str())
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// 显示一条应用内通知（独立 toast 窗口）。
/// level: info / ok / warn / err
///
/// 注意：native 模式下会启动 PowerShell 并空转 3 秒等待气泡消失，
/// 这里采用 fire-and-forget：spawn_blocking 后立即返回，不阻塞调用方。
#[tauri::command]
async fn send_notification(
    app: AppHandle,
    title: String,
    body: String,
    level: Option<String>,
) -> Result<(), String> {
    let level = level.unwrap_or_else(|| "info".to_string());
    // fire-and-forget：spawn_blocking 后立即返回
    tauri::async_runtime::spawn_blocking(move || {
        let _ = notify::show_toast(&app, &title, &body, &level);
    });
    Ok(())
}

/// 按当前 toast 数量，校正通知窗口的尺寸与位置。
///
/// 由通知窗口前端在每次 toast 增删后调用：`height` 是前端实测的内容高度
/// （CSS px）。窗口必须与内容等高，否则透明留白会拦截鼠标事件。
#[tauri::command]
async fn layout_notify(app: AppHandle, height: f64) -> Result<(), String> {
    let Some(window) = app.get_webview_window("notify") else {
        return Ok(());
    };
    let prefs = notify::load_prefs(&app);
    notify::layout(&window, &prefs.position, height)
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
    // ⚠️ 必须带 CREATE_NO_WINDOW：设置窗口在应用启动时就会调这个命令（读取开关状态），
    // 裸调 reg 会让黑窗一闪 —— 这是"开机自启动时闪过一个 cmd 窗口"的来源。
    let mut cmd = std::process::Command::new("reg");
    cmd.args(["query", AUTOSTART_REG_KEY, "/v", AUTOSTART_REG_VALUE])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null());
    #[cfg(windows)]
    cmd.creation_flags(CREATE_NO_WINDOW);
    let output = cmd.status().map_err(|e| e.to_string())?;
    Ok(output.success())
}

/// 设置开机自启动（启用/禁用）
///
/// 优化：注册表值添加 `--autostart` 参数，应用启动时可识别开机自启动模式，
/// 实现"有更新才显示窗口，无更新后台静默运行"。
#[tauri::command]
async fn set_autostart(enable: bool) -> Result<(), String> {
    if enable {
        let exe = std::env::current_exe().map_err(|e| tr!("lib.exePathFailed", e))?;
        let exe_path = exe.to_string_lossy().to_string();
        // 用引号包裹路径，避免路径含空格时解析错误；
        // 追加 --autostart 参数，标识开机自启动模式
        // main.rs 已设置 windows_subsystem="windows"，不会弹出控制台窗口
        let quoted = format!("\"{}\" --autostart", exe_path);
        // 同上：不加 CREATE_NO_WINDOW 的话，点开关的一瞬间会闪一个黑窗
        let mut cmd = std::process::Command::new("reg");
        cmd.args([
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
        .stderr(std::process::Stdio::null());
        #[cfg(windows)]
        cmd.creation_flags(CREATE_NO_WINDOW);
        let status = cmd.status().map_err(|e| e.to_string())?;
        if !status.success() {
            return Err(tr!("lib.regWriteFailed"));
        }
    } else {
        let mut cmd = std::process::Command::new("reg");
        cmd.args(["delete", AUTOSTART_REG_KEY, "/v", AUTOSTART_REG_VALUE, "/f"])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null());
        #[cfg(windows)]
        cmd.creation_flags(CREATE_NO_WINDOW);
        let _ = cmd.status();
    }
    Ok(())
}

/// 查询当前启动模式：是否为开机自启动（--autostart 参数）
///
/// 前端根据此值决定：自启动模式下静默检查更新，有更新才显示窗口。
#[tauri::command]
async fn get_startup_mode() -> bool {
    std::env::args().any(|arg| arg == "--autostart" || arg == "-a")
}

/// 获取系统信息：CPU 架构 + 操作系统平台
///
/// 用于关于窗口显示当前运行架构（x64 / ARM64）和平台（Windows）。
#[tauri::command]
async fn get_system_info() -> SystemInfo {
    SystemInfo {
        arch: std::env::consts::ARCH.to_string(),
        platform: std::env::consts::OS.to_string(),
    }
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct SystemInfo {
    arch: String,
    platform: String,
}

/// 窗口关闭时改为隐藏而非销毁（settings/notify/about 共用）
///
/// 优化：提取为辅助函数，避免三个窗口重复相同的 on_window_event 代码。
fn hide_on_close(window: &WebviewWindow) {
    let handle = window.clone();
    window.on_window_event(move |event| {
        if let tauri::WindowEvent::CloseRequested { api, .. } = event {
            api.prevent_close();
            let _ = handle.hide();
        }
    });
}

/// 把窗口收进当前显示器的工作区，避免高 DPI 下窗口超出屏幕。
///
/// `tauri.conf.json` 里的 width/height 是**逻辑像素**。在 150% 缩放的
/// 1920×1080 屏上，工作区只有约 1280×693 逻辑像素，而配置写的是 1120×760——
/// 高度就超了，`center()` 之后标题栏会被顶到屏幕外，用户拖都拖不动。
fn fit_to_work_area(window: &WebviewWindow) {
    // 隐藏窗口拿不到 current_monitor()，退回主显示器 —— 自启动模式下主窗口是隐藏的，
    // 但它之后会显示在同一块屏幕上，适配必须照做，否则小屏上窗口会超出工作区。
    let monitor = window
        .current_monitor()
        .ok()
        .flatten()
        .or_else(|| window.primary_monitor().ok().flatten());
    let Some(monitor) = monitor else {
        return;
    };
    let scale = monitor.scale_factor();
    let work = monitor.work_area();

    // 工作区与当前尺寸都换算到逻辑像素再比较
    let max_w = work.size.width as f64 / scale;
    let max_h = work.size.height as f64 / scale;

    let Ok(size) = window.outer_size() else {
        return;
    };
    let cur_w = size.width as f64 / scale;
    let cur_h = size.height as f64 / scale;

    // 留 28px 边距，既避免贴边，也避开任务栏自动隐藏的触发区
    let pad = 28.0;
    let w = cur_w.min(max_w - pad);
    let h = cur_h.min(max_h - pad);

    if w < cur_w - 0.5 || h < cur_h - 0.5 {
        // ⚠️ set_size / center 在部分后端会把窗口一并显示出来。settings / about
        // 在配置里是 visible:false 的，如果这里是"隐藏窗口被顺手显示"，用户看到的
        // 就是"启动时关于窗口自己冒出来"。所以先记下原可见状态，做完再还原。
        let was_visible = window.is_visible().unwrap_or(true);

        // 若算出来低于窗口最小尺寸，Tauri 会自动夹回最小值（尽力而为）
        let _ = window.set_size(LogicalSize::new(w, h));
        let _ = window.center();

        if !was_visible {
            let _ = window.hide();
        }
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run(autostart: bool) {
    // 必须在建窗口之前把上次选的字体读回内存，否则字体协议会先返回 404
    font::restore();
    // 同理：setup 阶段就会产出日志文案（"已加载 N 个系统字体"），
    // 此时前端还没跑、推不了语言，所以先恢复上次的语言。
    i18n::restore();

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        // 字体文件通过自定义协议提供给 WebView：
        // Windows 上地址是 http://font.localhost/ui
        .register_uri_scheme_protocol(font::FONT_SCHEME, |_ctx, req| font::serve(&req))
        .setup(move |app| {
            // 设置/通知/关于窗口关闭时改为隐藏而非销毁，下次打开直接 show
            if let Some(window) = app.get_webview_window("settings") {
                hide_on_close(&window);
            }
            if let Some(window) = app.get_webview_window("notify") {
                hide_on_close(&window);
            }
            if let Some(window) = app.get_webview_window("about") {
                hide_on_close(&window);
            }

            // 后台预热系统字体列表（注册表扫描约 1s）：等用户打开设置面板时
            // 列表已经就绪，不会看到"读取中…"。顺带在日志里留一条结果记录，
            // 万一 PowerShell 调用失败能立刻从日志看出来。
            {
                let handle = app.handle().clone();
                std::thread::spawn(move || {
                    let n = font::list_system_fonts().len();
                    if n == 0 {
                        emit_log(&handle, "warn", tr!("log.fontsFailed"));
                    } else {
                        emit_log(&handle, "info", tr!("log.fontsLoaded", n));
                    }
                });
            }

            // 主窗口在 tauri.conf.json 里是 visible:false。
            // 配置若默认可见，窗口会在 setup 执行前就显示出来 —— 自启动时就成了
            // "开机闪一下主窗口"。所以这里显式区分：非自启动才显示，
            // 且必须在 DPI 适配之前显示（可见窗口才拿得到准确的显示器信息）。
            if !autostart {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.show();
                }
            }

            // 高分屏适配：把各窗口收进当前显示器工作区，避免超出屏幕
            for label in ["main", "settings", "about"] {
                if let Some(window) = app.get_webview_window(label) {
                    fit_to_work_area(&window);
                }
            }

            // 开机自启动模式：所有窗口先全部藏起来，后台静默检查更新，
            // 有更新时前端再 show() 主窗口。
            //
            // ⚠️ 必须把 settings / notify / about 一并藏。上面 fit_to_work_area 会对
            // 隐藏窗口调 set_size() / center()；在小屏或高 DPI 下窗口尺寸一旦算出需要
            // 收缩，这些调用有可能把它带出来 —— 用户看到的就是"开机自启动时
            // 关于窗口自己冒出来"。只藏 main 挡不住。
            if autostart {
                for label in ["main", "settings", "notify", "about"] {
                    if let Some(window) = app.get_webview_window(label) {
                        let _ = window.hide();
                    }
                }
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
            get_log_dir,
            open_log_dir,
            list_system_fonts,
            list_font_files,
            pick_font_dir,
            pick_font_file,
            set_ui_font_file,
            get_ui_font_file,
            send_notification,
            layout_notify,
            save_notify_prefs,
            load_notify_prefs,
            get_autostart,
            set_autostart,
            get_startup_mode,
            set_language,
            get_system_info
        ])
        .run(tauri::generate_context!())
        .expect("启动 Tauri 应用失败");
}
