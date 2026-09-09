// 隐藏发布版的控制台窗口
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

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

fn main() {
    enable_gpu_acceleration();
    onekey_updater_lib::run()
}
