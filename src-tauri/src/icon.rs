use crate::model::{IconEntry, UpdateItem};
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

/// 给一组 items 填充 icon 字段。仅 winget 类型能匹配到系统已装程序图标。
#[allow(dead_code)]
pub fn fill_icons(items: &mut [UpdateItem]) {
    let needs: Vec<&UpdateItem> = items
        .iter()
        .filter(|i| matches!(i.source, crate::model::Source::Winget))
        .collect();

    if needs.is_empty() {
        return;
    }

    let cache = load_or_build_cache();
    if cache.is_empty() {
        return;
    }

    for item in items.iter_mut() {
        if !matches!(item.source, crate::model::Source::Winget) {
            continue;
        }
        let mut best: Option<&String> = None;
        for (name, icon) in &cache {
            if name_matches(&item.name, name) {
                best = Some(icon);
                break;
            }
        }
        if let Some(b64) = best {
            item.icon = Some(format!("data:image/png;base64,{}", b64));
        }
    }
}

/// 异步命令版本，调用方传入 items，返回 id → data_url 映射。
/// 同时返回耗时（毫秒），方便前端调试。
pub fn fetch_icons_blocking(items: &[UpdateItem]) -> (HashMap<String, String>, u128) {
    let started = SystemTime::now();
    let cache = load_or_build_cache();
    let mut out = HashMap::new();
    for item in items {
        if !matches!(item.source, crate::model::Source::Winget) {
            continue;
        }
        for (name, icon) in &cache {
            if name_matches(&item.name, name) {
                out.insert(item.id.clone(), format!("data:image/png;base64,{}", icon));
                break;
            }
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