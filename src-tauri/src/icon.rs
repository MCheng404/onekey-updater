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

/// PowerShell 脚本 — 从 winget manifest 获取官方图标 URL。
///
/// 流程：
///  1. winget export 导出已安装包列表（含版本号）
///  2. 构造 GitHub raw manifest URL 并下载 YAML
///  3. 解析 Icons[].IconUrl 字段
///  4. 下载图标并转 base64 data URL
///
/// 输出：JSON 数组 [{id, icon}]，仅包含成功获取到图标的包。
const PS_WINGET_MANIFEST: &str = r#"
$ErrorActionPreference = 'SilentlyContinue'

# 1. 导出已安装的 winget 包列表
$tempFile = "$env:TEMP\winget-export-$(Get-Random).json"
winget export -o $tempFile --include-versions --accept-source-agreements 2>$null

if (-not (Test-Path -LiteralPath $tempFile)) { Write-Output '[]'; exit }
$json = Get-Content -LiteralPath $tempFile -Raw | ConvertFrom-Json
Remove-Item -LiteralPath $tempFile -Force

if (-not $json.Sources -or $json.Sources.Count -eq 0) { Write-Output '[]'; exit }

$out = New-Object System.Collections.Generic.List[object]
$packages = $json.Sources[0].Packages

foreach ($pkg in $packages) {
    $id = $pkg.PackageIdentifier
    $version = $pkg.Version
    if (-not $id -or -not $version) { continue }

    # 构造 manifest URL: manifests/<首字母>/<Publisher>/<Package>/<version>/<id>.yaml
    $firstLetter = $id.Substring(0,1).ToLower()
    $dotIdx = $id.IndexOf('.')
    if ($dotIdx -lt 0) { continue }
    $publisher = $id.Substring(0, $dotIdx)
    $packageName = $id.Substring($dotIdx + 1)

    $manifestUrl = "https://raw.githubusercontent.com/microsoft/winget-pkgs/master/manifests/$firstLetter/$publisher/$packageName/$version/$id.yaml"

    try {
        # 2. 下载 manifest YAML
        $resp = Invoke-WebRequest -Uri $manifestUrl -UseBasicParsing -TimeoutSec 6
        $content = $resp.Content

        # 3. 解析 IconUrl（支持新格式 Icons[].IconUrl 和旧格式 IconUrl）
        $iconUrl = $null
        if ($content -match '(?m)^\s*-\s*IconUrl:\s*(.+?)\s*$') {
            $iconUrl = $matches[1].Trim().Trim('"').Trim("'")
        } elseif ($content -match '(?m)^\s*IconUrl:\s*(.+?)\s*$') {
            $iconUrl = $matches[1].Trim().Trim('"').Trim("'")
        }

        if (-not $iconUrl) { continue }

        # 4. 下载图标并转 base64
        $iconResp = Invoke-WebRequest -Uri $iconUrl -UseBasicParsing -TimeoutSec 6
        $bytes = $iconResp.Content
        if ($bytes.Length -eq 0) { continue }

        $b64 = [Convert]::ToBase64String($bytes)
        $ext = [IO.Path]::GetExtension($iconUrl).ToLower().TrimStart('.')
        $mime = switch ($ext) {
            'png' { 'image/png' }
            'jpg' { 'image/jpeg' }
            'jpeg' { 'image/jpeg' }
            'svg' { 'image/svg+xml' }
            'ico' { 'image/x-icon' }
            default { 'image/png' }
        }

        $out.Add([PSCustomObject]@{ id = $id; icon = "data:$mime;base64,$b64" })
    } catch { continue }
}

ConvertTo-Json -InputObject $out -Depth 3 -Compress
"#;

/// PowerShell 脚本 — 扫描开始菜单快捷方式，解析 .lnk 的 TargetPath / IconLocation，
/// 从可执行文件或图标文件提取 48×48 PNG，base64 输出 JSON 数组。
///
/// 覆盖：公共开始菜单 + 用户开始菜单，递归子目录。
const PS_SHORTCUTS: &str = r#"
$ErrorActionPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Drawing

$dirs = @(
  "$env:ProgramData\Microsoft\Windows\Start Menu\Programs",
  "$env:APPDATA\Microsoft\Windows\Start Menu\Programs"
)

$shell = New-Object -ComObject WScript.Shell
$seen = @{}
$out = New-Object System.Collections.Generic.List[object]

foreach ($dir in $dirs) {
  if (-not (Test-Path -LiteralPath $dir)) { continue }
  Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.lnk' -ErrorAction SilentlyContinue | ForEach-Object {
    try {
      $link = $shell.CreateShortcut($_.FullName)
      $name = [IO.Path]::GetFileNameWithoutExtension($_.Name)
      if (-not $name) { return }

      $nameKey = $name.ToLowerInvariant()
      if ($seen.ContainsKey($nameKey)) { return }

      # 优先用 IconLocation，其次用 TargetPath
      $iconPath = $null
      $iconIdx = 0
      if ($link.IconLocation -and $link.IconLocation -ne ',0') {
        $parts = $link.IconLocation -split ',', 2
        $iconPath = $parts[0].Trim()
        if ($parts.Count -gt 1) { [int]::TryParse($parts[1].Trim(), [ref]$iconIdx) | Out-Null }
      }
      if (-not $iconPath -or -not (Test-Path -LiteralPath $iconPath)) {
        if ($link.TargetPath -and (Test-Path -LiteralPath $link.TargetPath)) {
          $iconPath = $link.TargetPath
        }
      }
      if (-not $iconPath -or -not (Test-Path -LiteralPath $iconPath)) { return }

      $seen[$nameKey] = $true

      # 提取图标
      $bmp = New-Object System.Drawing.Bitmap 48, 48
      $g = [System.Drawing.Graphics]::FromImage($bmp)
      $g.SmoothingMode = 'AntiAlias'
      $g.InterpolationMode = 'HighQualityBicubic'

      $ext = [IO.Path]::GetExtension($iconPath).ToLower()
      if ($ext -eq '.ico') {
        $icon = [System.Drawing.Icon]::ExtractAssociatedIcon($iconPath)
        if ($icon) { $g.DrawIcon($icon, 0, 0); $icon.Dispose() }
      } elseif ($ext -eq '.exe') {
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
      $out.Add([PSCustomObject]@{ name = $name; icon = $b64 })
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

/// 从 winget manifest 获取官方图标（第一级，网络，最高质量）。
/// 返回 (PackageId → data_url)。
///
/// 注意：此函数需要网络连接，且可能较慢。设置 15 秒整体超时。
fn build_via_winget_manifest() -> HashMap<String, String> {
    let mut map = HashMap::new();
    let out = match crate::util::run_capture_with_timeout(
        "powershell",
        &["-NoProfile", "-NonInteractive", "-Command", PS_WINGET_MANIFEST],
        15,
    ) {
        Ok(o) => o,
        Err(_) => return map,
    };

    let trimmed = out.trim();
    if trimmed.is_empty() || trimmed == "null" || trimmed == "[]" {
        return map;
    }

    let value: serde_json::Value = match serde_json::from_str(trimmed) {
        Ok(v) => v,
        Err(_) => return map,
    };

    let arr = match value.as_array() {
        Some(a) => a,
        None => return map,
    };

    for entry in arr {
        let id = entry
            .get("id")
            .or_else(|| entry.get("Id"))
            .and_then(|v| v.as_str());
        let icon = entry
            .get("icon")
            .or_else(|| entry.get("Icon"))
            .and_then(|v| v.as_str());
        if let (Some(i), Some(ic)) = (id, icon) {
            map.insert(i.to_string(), ic.to_string());
        }
    }
    map
}

/// 从开始菜单快捷方式获取图标（第三级，离线）。
/// 返回 (快捷方式名称 → base64 PNG)。
fn build_via_shortcuts() -> HashMap<String, String> {
    let mut map = HashMap::new();
    let out = match run_capture(
        "powershell",
        &["-NoProfile", "-NonInteractive", "-Command", PS_SHORTCUTS],
    ) {
        Ok(o) => o,
        Err(_) => return map,
    };

    let trimmed = out.trim();
    if trimmed.is_empty() || trimmed == "null" || trimmed == "[]" {
        return map;
    }

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
            .get("name")
            .or_else(|| entry.get("Name"))
            .and_then(|v| v.as_str());
        let icon = entry
            .get("icon")
            .or_else(|| entry.get("Icon"))
            .and_then(|v| v.as_str());
        if let (Some(n), Some(i)) = (name, icon) {
            map.insert(n.to_string(), i.to_string());
        }
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
/// 多级降级策略：
///  1. winget manifest 官方图标（仅 winget 类型）
///  2. 注册表 DisplayIcon（离线）
///  3. 开始菜单快捷方式（离线）
///  4. 品牌默认图标（最终兜底）
#[allow(dead_code)]
pub fn fill_icons(items: &mut [UpdateItem]) {
    // 第一级：winget manifest
    let winget_manifest = if items.iter().any(|i| matches!(i.source, Source::Winget)) {
        build_via_winget_manifest()
    } else {
        HashMap::new()
    };

    // 第二级：注册表
    let registry = load_or_build_cache();

    // 第三级：快捷方式
    let shortcuts = if registry.is_empty() {
        build_via_shortcuts()
    } else {
        HashMap::new()
    };

    for item in items.iter_mut() {
        let mut matched = false;

        // 第一级：winget manifest
        if matches!(item.source, Source::Winget) {
            if let Some(icon) = winget_manifest.get(&item.id) {
                item.icon = Some(icon.clone());
                matched = true;
            }
        }

        // 第二级：注册表
        if !matched && !registry.is_empty() {
            for (name, icon) in &registry {
                if name_matches(&item.name, name) {
                    item.icon = Some(format!("data:image/png;base64,{}", icon));
                    matched = true;
                    break;
                }
            }
        }

        // 第三级：快捷方式
        if !matched && !shortcuts.is_empty() {
            for (name, icon) in &shortcuts {
                if name_matches(&item.name, name) {
                    item.icon = Some(format!("data:image/png;base64,{}", icon));
                    matched = true;
                    break;
                }
            }
        }

        // 第四级：品牌默认图标
        if !matched {
            item.icon = Some(default_icon_for_source(item.source).to_string());
        }
    }
}

/// 异步命令版本，调用方传入 items，返回 id → data_url 映射。
/// 同时返回耗时（毫秒），方便前端调试。
///
/// 多级降级策略：
///  1. winget manifest 官方图标（仅 winget 类型，网络，最高质量）
///  2. 注册表 DisplayIcon（离线，所有源类型）
///  3. 开始菜单快捷方式（离线，所有源类型）
///  4. 品牌默认图标（最终兜底）
pub fn fetch_icons_blocking(items: &[UpdateItem]) -> (HashMap<String, String>, u128) {
    let started = SystemTime::now();

    // 第一级：winget manifest 官方图标（仅 winget 类型）
    let winget_manifest = if items.iter().any(|i| matches!(i.source, Source::Winget)) {
        build_via_winget_manifest()
    } else {
        HashMap::new()
    };

    // 第二级：注册表 DisplayIcon（离线缓存）
    let registry = load_or_build_cache();

    // 第三级：开始菜单快捷方式（仅在注册表没匹配到时才构建，节省时间）
    let shortcuts = if registry.is_empty() {
        build_via_shortcuts()
    } else {
        HashMap::new()
    };

    let mut out = HashMap::new();

    for item in items {
        let mut matched = false;

        // 第一级：winget manifest（通过包 ID 精确匹配）
        if matches!(item.source, Source::Winget) {
            if let Some(icon) = winget_manifest.get(&item.id) {
                out.insert(item.id.clone(), icon.clone());
                matched = true;
            }
        }

        // 第二级：注册表 DisplayIcon（通过名称模糊匹配）
        if !matched && !registry.is_empty() {
            for (name, icon) in &registry {
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

        // 第三级：开始菜单快捷方式（通过名称模糊匹配）
        if !matched && !shortcuts.is_empty() {
            for (name, icon) in &shortcuts {
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

        // 第四级：品牌默认图标（最终兜底）
        if !matched {
            out.insert(
                item.id.clone(),
                default_icon_for_source(item.source).to_string(),
            );
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