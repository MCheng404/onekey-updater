//! 自定义字体的后端支持。
//!
//! 三条获取途径：
//!  1. 系统字体 —— 读注册表 Fonts 键，拿到已安装字族名（前端直接用字族名即可）
//!  2. 字体目录 —— 扫描目录里的字体文件，返回文件列表供用户挑选
//!  3. 单个字体文件 —— 用户直接选一个 .ttf/.otf/.ttc
//!
//! 2/3 选中的文件通过**自定义协议** `http://font.localhost/ui` 提供给 WebView，
//! 避免把几十 MB 的字体转 base64 走 IPC 传（CJK 字体 base64 后能到 30MB 量级）。

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

/// 字体文件协议名。Windows 上实际访问地址是 `http://font.localhost/ui`
pub const FONT_SCHEME: &str = "font";
/// 协议内的固定路径
pub const FONT_ROUTE: &str = "ui";

/// 当前生效的字体文件。由 `set_ui_font_file` 写入，协议处理函数读取。
static UI_FONT: Mutex<Option<PathBuf>> = Mutex::new(None);

const FONT_EXTS: &[&str] = &["ttf", "otf", "ttc", "otc", "woff", "woff2"];

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct FontConfig {
    /// 选中的字体文件绝对路径
    path: Option<String>,
}

/// 配置持久化位置：`%LOCALAPPDATA%\onekey-updater\font.json`
fn config_path() -> Option<PathBuf> {
    let local = std::env::var("LOCALAPPDATA").ok()?;
    Some(PathBuf::from(local).join("onekey-updater").join("font.json"))
}

/// 启动时把上次选的字体读回内存
pub fn restore() {
    let Some(path) = config_path() else { return };
    let Ok(text) = std::fs::read_to_string(&path) else {
        return;
    };
    let Ok(cfg) = serde_json::from_str::<FontConfig>(&text) else {
        return;
    };
    if let Some(p) = cfg.path {
        let pb = PathBuf::from(p);
        if pb.is_file() {
            set_current(Some(pb));
        }
    }
}

pub fn set_current(path: Option<PathBuf>) {
    if let Ok(mut guard) = UI_FONT.lock() {
        *guard = path;
    }
}

pub fn current() -> Option<PathBuf> {
    UI_FONT.lock().ok()?.clone()
}

/// 记住/清除字体文件（落盘 + 更新内存）
pub fn persist(path: Option<String>) -> Result<(), String> {
    let pb = match &path {
        Some(p) if !p.trim().is_empty() => {
            let pb = PathBuf::from(p);
            if !pb.is_file() {
                return Err(format!("字体文件不存在：{}", p));
            }
            Some(pb)
        }
        _ => None,
    };

    set_current(pb.clone());

    let Some(cfg_path) = config_path() else {
        return Ok(());
    };
    if let Some(parent) = cfg_path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let cfg = FontConfig {
        path: pb.map(|p| p.to_string_lossy().to_string()),
    };
    let text = serde_json::to_string_pretty(&cfg).map_err(|e| e.to_string())?;
    std::fs::write(&cfg_path, text).map_err(|e| e.to_string())
}

/// 按扩展名给 MIME
fn mime_for(path: &Path) -> &'static str {
    match path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase()
        .as_str()
    {
        "otf" | "otc" => "font/otf",
        "ttc" => "font/collection",
        "woff" => "font/woff",
        "woff2" => "font/woff2",
        _ => "font/ttf",
    }
}

/// 协议处理：把当前字体文件原样吐给 WebView。
///
/// 字体加载受 CORS 约束（跨源字体默认被拒），所以必须带
/// `Access-Control-Allow-Origin`。
pub fn serve(request: &tauri::http::Request<Vec<u8>>) -> tauri::http::Response<Vec<u8>> {
    let not_found = || {
        tauri::http::Response::builder()
            .status(404)
            .body(Vec::new())
            .unwrap_or_else(|_| tauri::http::Response::new(Vec::new()))
    };

    // 只服务 /ui 一个路由（路径不含 query，所以 cache-busting 参数不影响）
    if !request.uri().path().ends_with(FONT_ROUTE) {
        return not_found();
    }

    let Some(path) = current() else {
        return not_found();
    };

    match std::fs::read(&path) {
        Ok(bytes) => tauri::http::Response::builder()
            .status(200)
            .header("Content-Type", mime_for(&path))
            .header("Access-Control-Allow-Origin", "*")
            .header("Cache-Control", "no-cache")
            .body(bytes)
            .unwrap_or_else(|_| tauri::http::Response::new(Vec::new())),
        Err(_) => not_found(),
    }
}

/* ============ 系统字体枚举 ============ */

const PS_SYSTEM_FONTS: &str = r#"
$ErrorActionPreference = 'SilentlyContinue'
$keys = @(
  'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Fonts',
  'HKCU:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Fonts'
)
$seen = @{}
$out = New-Object System.Collections.Generic.List[string]
foreach ($k in $keys) {
  $item = Get-ItemProperty -Path $k -ErrorAction SilentlyContinue
  if (-not $item) { continue }
  $item.PSObject.Properties | Where-Object { $_.Name -notlike 'PS*' } | ForEach-Object {
    # 值名形如 "微软雅黑 (TrueType)"、"Microsoft YaHei & Microsoft YaHei UI (TrueType)"
    $n = $_.Name -replace '\s*\((TrueType|OpenType|Type 1|TrueType Collection)\)\s*$',''
    foreach ($part in ($n -split '&')) {
      $p = $part.Trim()
      if ($p -and -not $seen.ContainsKey($p)) { $seen[$p] = $true; $out.Add($p) }
    }
  }
}
ConvertTo-Json -InputObject $out -Compress
"#;

/// 系统字体列表缓存：注册表扫描约 1 秒，扫一次就够了
static SYSTEM_FONTS: OnceLock<Vec<String>> = OnceLock::new();

/// 枚举已安装的系统字族名（去重、排序，带缓存）
pub fn list_system_fonts() -> Vec<String> {
    SYSTEM_FONTS.get_or_init(build_system_fonts).clone()
}

fn build_system_fonts() -> Vec<String> {
    // 必须走 run_powershell（落盘 + -File）：
    // 多行脚本经 cmd /C ... -Command 会被解析坏，脚本静默失败 → 读不到字体。
    let out = match crate::util::run_powershell(PS_SYSTEM_FONTS, 30) {
        Ok(o) => o,
        Err(_) => return Vec::new(),
    };

    let trimmed = out.trim();
    if trimmed.is_empty() || trimmed == "null" {
        return Vec::new();
    }

    let Ok(value) = serde_json::from_str::<serde_json::Value>(trimmed) else {
        return Vec::new();
    };

    let mut names: Vec<String> = match value {
        // 单个结果时 ConvertTo-Json 会退化成字符串
        serde_json::Value::String(s) => vec![s],
        serde_json::Value::Array(a) => a
            .into_iter()
            .filter_map(|v| v.as_str().map(|s| s.to_string()))
            .collect(),
        _ => Vec::new(),
    };

    names.retain(|n| !n.trim().is_empty());
    names.sort();
    names.dedup();
    names
}

/// 列出目录下的字体文件（按文件名排序，绝对路径）
pub fn list_font_files(dir: &str) -> Vec<String> {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return Vec::new();
    };

    let mut files: Vec<String> = entries
        .filter_map(|e| e.ok())
        .filter(|e| e.file_type().map(|t| t.is_file()).unwrap_or(false))
        .filter(|e| {
            e.path()
                .extension()
                .and_then(|x| x.to_str())
                .map(|x| FONT_EXTS.contains(&x.to_ascii_lowercase().as_str()))
                .unwrap_or(false)
        })
        .map(|e| e.path().to_string_lossy().to_string())
        .collect();

    files.sort();
    files
}

/* ============ 原生选择对话框（走 PowerShell + WinForms，省一个插件依赖） ============ */

const PS_PICK_DIR: &str = r#"
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms | Out-Null
$d = New-Object System.Windows.Forms.FolderBrowserDialog
$d.Description = '选择存放字体的文件夹'
$d.ShowNewFolderButton = $false
if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $d.SelectedPath }
"#;

const PS_PICK_FILE: &str = r#"
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms | Out-Null
$f = New-Object System.Windows.Forms.OpenFileDialog
$f.Title = '选择字体文件'
$f.Filter = '字体文件|*.ttf;*.otf;*.ttc;*.otc;*.woff;*.woff2|所有文件|*.*'
$f.CheckFileExists = $true
if ($f.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $f.FileName }
"#;

/// 弹出"选择文件夹"对话框。用户取消返回 None。
///
/// 超时给到 5 分钟 —— 原生对话框要等用户操作，默认 30s 根本不够。
pub fn pick_dir() -> Option<String> {
    pick(PS_PICK_DIR)
}

/// 弹出"选择字体文件"对话框。用户取消返回 None。
pub fn pick_file() -> Option<String> {
    pick(PS_PICK_FILE)
}

fn pick(script: &str) -> Option<String> {
    let out = crate::util::run_powershell(script, 300).ok()?;
    let path = out.trim();
    if path.is_empty() {
        None
    } else {
        Some(path.to_string())
    }
}
