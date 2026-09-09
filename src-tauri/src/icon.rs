use crate::model::{IconEntry, Source, UpdateItem};
use crate::util::run_capture;
use std::collections::HashMap;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

/// PowerShell 脚本 — 扫描注册表 Uninstall 表，提取 DisplayIcon，
/// 转 48×48 PNG，再 base64 输出 JSON 数组。
///
/// 注意：
///  - 同时扫 HKLM / HKLM\WOW6432Node / HKCU 三处，覆盖 32/64 位与用户安装。
///  - 单实例 System.Drawing，避免每个图标重复加载。
///  - 兼容 .exe / .ico 两种 DisplayIcon 形式（.exe 后可能跟 ",0" 资源索引）。
const PS_SCRIPT: &str = r#"
$ErrorActionPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Drawing

$paths = @(
  'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
  'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
  'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*'
)

$seen = @{}
$out = New-Object System.Collections.Generic.List[object]

foreach ($reg in $paths) {
  Get-ItemProperty $reg -ErrorAction SilentlyContinue | ForEach-Object {
    if (-not $_.DisplayName -or -not $_.DisplayIcon) { return }
    $raw = ($_.DisplayIcon -replace '"','').Trim()
    if (-not $raw) { return }
    $parts = $raw -split ',', 2
    $iconPath = $parts[0].Trim()
    $iconIdx = 0
    if ($parts.Count -gt 1) { [int]::TryParse($parts[1].Trim(), [ref]$iconIdx) | Out-Null }
    if (-not (Test-Path -LiteralPath $iconPath)) { return }

    $nameKey = $_.DisplayName.ToLowerInvariant()
    if ($seen.ContainsKey($nameKey)) { return }
    $seen[$nameKey] = $true

    try {
      $bmp = New-Object System.Drawing.Bitmap 48, 48
      $g = [System.Drawing.Graphics]::FromImage($bmp)
      $g.SmoothingMode = 'AntiAlias'
      $g.InterpolationMode = 'HighQualityBicubic'

      $ext = [IO.Path]::GetExtension($iconPath).ToLower()
      if ($ext -eq '.ico') {
        $icon = [System.Drawing.Icon]::ExtractAssociatedIcon($iconPath)
        if ($icon) { $g.DrawIcon($icon, 0, 0); $icon.Dispose() }
      } else {
        $img = [System.Drawing.Image]::FromFile($iconPath)
        $g.DrawImage($img, 0, 0, 48, 48)
        $img.Dispose()
      }

      $g.Dispose()
      $ms = New-Object System.IO.MemoryStream
      $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
      $bytes = $ms.ToArray()
      $ms.Dispose(); $bmp.Dispose()
      $b64 = [Convert]::ToBase64String($bytes)
      $out.Add([PSCustomObject]@{ name = $_.DisplayName; icon = $b64 })
    } catch {}
  }
}

ConvertTo-Json -InputObject $out -Depth 3 -Compress
"#;

/// 返回当前时间戳（秒）
#[allow(dead_code)]
fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// 缓存文件路径：`%LOCALAPPDATA%\onekey-updater\icon-cache.json`
fn cache_path() -> Option<PathBuf> {
    let local = std::env::var("LOCALAPPDATA").ok()?;
    Some(PathBuf::from(local).join("onekey-updater").join("icon-cache.json"))
}

/// 默认品牌图标（SVG data URL），用于无法从注册表匹配到图标的情况
mod default_icons {
    /// npm 品牌图标：红色圆角方块 + 白色 npm 文字
    pub const NPM: &str = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 48 48'%3E%3Crect width='48' height='48' rx='8' fill='%23CB3837'/%3E%3Ctext x='24' y='30' font-family='Arial,sans-serif' font-size='14' font-weight='bold' fill='white' text-anchor='middle'%3Enpm%3C/text%3E%3C/svg%3E";

    /// pip 品牌图标：蓝色圆角方块 + 白色 pip 文字
    pub const PIP: &str = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 48 48'%3E%3Crect width='48' height='48' rx='8' fill='%233776AB'/%3E%3Ctext x='24' y='30' font-family='Arial,sans-serif' font-size='14' font-weight='bold' fill='white' text-anchor='middle'%3Epip%3C/text%3E%3C/svg%3E";

    /// OpenClaw 品牌图标：紫色渐变圆角方块 + 白色 OC 文字
    pub const OPENCLAW: &str = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 48 48'%3E%3Cdefs%3E%3ClinearGradient id='g' x1='0' y1='0' x2='1' y2='1'%3E%3Cstop offset='0' stop-color='%238B5CF6'/%3E%3Cstop offset='1' stop-color='%236366F1'/%3E%3C/linearGradient%3E%3C/defs%3E%3Crect width='48' height='48' rx='8' fill='url(%23g)'/%3E%3Ctext x='24' y='30' font-family='Arial,sans-serif' font-size='13' font-weight='bold' fill='white' text-anchor='middle'%3EOC%3C/text%3E%3C/svg%3E";

    /// winget 品牌图标：蓝色圆角方块 + 白色 winget 文字
    pub const WINGET: &str = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 48 48'%3E%3Crect width='48' height='48' rx='8' fill='%230078D4'/%3E%3Ctext x='24' y='30' font-family='Arial,sans-serif' font-size='11' font-weight='bold' fill='white' text-anchor='middle'%3Ewinget%3C/text%3E%3C/svg%3E";
}

/// 根据源类型获取默认品牌图标
fn default_icon_for_source(source: Source) -> &'static str {
    match source {
        Source::Npm => default_icons::NPM,
        Source::Pip => default_icons::PIP,
        Source::Openclaw => default_icons::OPENCLAW,
        Source::Winget => default_icons::WINGET,
    }
}

/// 读取缓存；若文件超过 24 小时则视为过期。
fn load_cache() -> Option<HashMap<String, String>> {
    let path = cache_path()?;
    let meta = std::fs::metadata(&path).ok()?;
    let mtime = meta.modified().ok()?;
    let age = SystemTime::now().duration_since(mtime).ok()?.as_secs();
    if age > 86_400 {
        return None;
    }
    let text = std::fs::read_to_string(&path).ok()?;
    let arr: Vec<IconEntry> = serde_json::from_str(&text).ok()?;
    Some(arr.into_iter().map(|e| (e.name, e.icon)).collect())
}

/// 缓存到本地 JSON
fn save_cache(map: &HashMap<String, String>) {
    let Some(path) = cache_path() else { return };
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let entries: Vec<IconEntry> = map
        .iter()
        .map(|(k, v)| IconEntry {
            name: k.clone(),
            icon: v.clone(),
        })
        .collect();
    if let Ok(text) = serde_json::to_string(&entries) {
        let _ = std::fs::write(&path, text);
    }
}

/// 同步执行 PS 脚本，返回 (DisplayName → base64 PNG)
fn build_via_powershell() -> HashMap<String, String> {
    let mut map = HashMap::new();
    let out = match run_capture(
        "powershell",
        &["-NoProfile", "-NonInteractive", "-Command", PS_SCRIPT],
    ) {
        Ok(o) => o,
        Err(_) => return map,
    };

    let trimmed = out.trim();
    if trimmed.is_empty() || trimmed == "null" {
        return map;
    }

    // PowerShell ConvertTo-Json 输出的键名保持 PascalCase（Name / Icon）
    let value: serde_json::Value = match serde_json::from_str(trimmed) {
        Ok(v) => v,
        Err(_) => return map,
    };

    let arr = match value.as_array() {
        Some(a) => a,
        None => return map,
    };

    for entry in arr {
        let name = entry
            .get("Name")
            .or_else(|| entry.get("name"))
            .and_then(|v| v.as_str());
        let icon = entry
            .get("Icon")
            .or_else(|| entry.get("icon"))
            .and_then(|v| v.as_str());
        if let (Some(n), Some(i)) = (name, icon) {
            map.insert(n.to_string(), i.to_string());
        }
    }
    map
}

/// 取或构建缓存；构建完成后写盘。
fn load_or_build_cache() -> HashMap<String, String> {
    if let Some(c) = load_cache() {
        if !c.is_empty() {
            return c;
        }
    }
    let map = build_via_powershell();
    if !map.is_empty() {
        save_cache(&map);
    }
    map
}

/// 名称匹配：忽略大小写、子串包含、版本号尾巴差异
fn name_matches(a: &str, b: &str) -> bool {
    let al = a.to_lowercase();
    let bl = b.to_lowercase();
    if al == bl {
        return true;
    }
    if al.contains(&bl) || bl.contains(&al) {
        return true;
    }
    // 处理 winget 包常带的多余尾巴（如 "(x64)" / 版本号 / "Desktop" 等）
    let stripped_a: String = al.chars().filter(|c| c.is_alphanumeric()).collect();
    let stripped_b: String = bl.chars().filter(|c| c.is_alphanumeric()).collect();
    stripped_a == stripped_b && stripped_a.len() >= 4
}

/// 给一组 items 填充 icon 字段。
///
/// 优化：对所有源类型进行图标匹配，无法匹配时使用默认品牌图标。
#[allow(dead_code)]
pub fn fill_icons(items: &mut [UpdateItem]) {
    let cache = load_or_build_cache();

    for item in items.iter_mut() {
        let mut matched = false;
        if !cache.is_empty() {
            for (name, icon) in &cache {
                if name_matches(&item.name, name) {
                    item.icon = Some(format!("data:image/png;base64,{}", icon));
                    matched = true;
                    break;
                }
            }
        }
        if !matched {
            item.icon = Some(default_icon_for_source(item.source).to_string());
        }
    }
}

/// 异步命令版本，调用方传入 items，返回 id → data_url 映射。
/// 同时返回耗时（毫秒），方便前端调试。
///
/// 优化：对所有源类型进行图标匹配（不只是 winget），
/// 无法从注册表匹配到时使用源类型的默认品牌图标，确保列表中每个项都有图标。
pub fn fetch_icons_blocking(items: &[UpdateItem]) -> (HashMap<String, String>, u128) {
    let started = SystemTime::now();
    let cache = load_or_build_cache();
    let mut out = HashMap::new();

    for item in items {
        // 优先从注册表缓存中匹配（所有源类型都尝试匹配）
        let mut matched = false;
        if !cache.is_empty() {
            for (name, icon) in &cache {
                if name_matches(&item.name, name) {
                    out.insert(
                        item.id.clone(),
                        format!("data:image/png;base64,{}", icon),
                    );
                    matched = true;
                    break;
                }
            }
        }

        // 无法匹配时使用源类型的默认品牌图标
        if !matched {
            out.insert(item.id.clone(), default_icon_for_source(item.source).to_string());
        }
    }

    let elapsed = SystemTime::now()
        .duration_since(started)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    (out, elapsed)
}

/// 主动刷新缓存（设置里手动触发）
pub fn rebuild_cache() -> HashMap<String, String> {
    let map = build_via_powershell();
    if !map.is_empty() {
        save_cache(&map);
    }
    map
}

/// 当前缓存大小（用于设置面板显示）
#[allow(dead_code)]
pub fn cache_size() -> Option<usize> {
    let path = cache_path()?;
    let meta = std::fs::metadata(&path).ok()?;
    Some(meta.len() as usize)
}

/// 用于调试：上次刷新缓存的时间
#[allow(dead_code)]
pub fn cache_age_secs() -> Option<u64> {
    let path = cache_path()?;
    let meta = std::fs::metadata(&path).ok()?;
    let mtime = meta.modified().ok()?;
    Some(now_secs().saturating_sub(
        SystemTime::now()
            .duration_since(mtime)
            .ok()?
            .as_secs(),
    ))
}