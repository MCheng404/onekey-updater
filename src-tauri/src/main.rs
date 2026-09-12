// 隐藏发布版的控制台窗口
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

// 编译期保护：release 构建却处于 dev 模式，说明漏了 `custom-protocol` feature
// （tauri 的 build.rs 里 `dev = !custom-protocol`）。此时 exe 会去连
// http://localhost:1420 而不是加载内嵌资源，表现为白屏 /「无法访问此页面」。
// 直接报错，避免静默产出坏包。
#[cfg(all(dev, not(debug_assertions)))]
compile_error!(
    "release 构建缺少 `custom-protocol` feature，产出的 exe 会以 dev 模式启动并尝试连接 \
     http://localhost:1420。请改用 `npm run tauri build`（推荐），\
     或 `cargo build --release --features custom-protocol`。"
);

/// 在创建 WebView 之前设置 WebView2 的 Chromium 启动参数。
///
/// 重要：之前启用的 `--ignore-gpu-blocklist --enable-zero-copy
/// --enable-native-gpu-memory-buffers` 以及 `--enable-gpu-rasterization`
/// 会在部分显卡/驱动（Intel 集显、老旧驱动、远程桌面、虚拟机）上导致
/// WebView2 渲染进程崩溃或死锁，表现为窗口卡死无法拖动、子窗口无法操作。
///
/// 这里只保留 `--disable-features=CalculateNativeWinOcclusion`：
/// 避免透明窗口被 Windows 误判为遮挡后停止合成。GPU 决策完全交给
/// WebView2 默认行为（自动回退软件渲染），保证在所有硬件上稳定运行。
fn enable_gpu_acceleration() {
    std::env::set_var(
        "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS",
        "--disable-features=CalculateNativeWinOcclusion",
    );
}

/// 解析命令行参数，判断是否为开机自启动模式。
///
/// 开机自启动时注册表会传入 `--autostart` 参数，此时应用应：
/// - 主窗口默认隐藏
/// - 后台静默检查更新
/// - 有更新时才显示主窗口，无更新时保持后台运行
fn is_autostart_mode() -> bool {
    std::env::args().any(|arg| arg == "--autostart" || arg == "-a")
}

fn main() {
    enable_gpu_acceleration();
    let autostart = is_autostart_mode();
    onekey_updater_lib::run(autostart)
}
