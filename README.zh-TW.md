# 一鍵更新 (OneKey Updater)

[![GitHub release](https://img.shields.io/github/v/release/MCheng404/onekey-updater)](https://github.com/MCheng404/onekey-updater/releases)
[![GitHub license](https://img.shields.io/github/license/MCheng404/onekey-updater)](https://github.com/MCheng404/onekey-updater/blob/main/LICENSE)
[![GitHub downloads](https://img.shields.io/github/downloads/MCheng404/onekey-updater/total)](https://github.com/MCheng404/onekey-updater/releases)

**[简体中文](README.zh-CN.md) | 繁體中文 | [English](README.md)**

統一管理 npm / winget / pip / OpenClaw 四種來源軟體更新的桌面工具，以 Tauri 2 + Rust + TypeScript 打造。輕量、快速、無感。

## 功能特色

- 🔄 **四源統一更新檢查** — 同時檢查 npm 全域套件、winget 軟體、pip Python 套件、OpenClaw
- 📢 **雙模式通知** — 應用程式內毛玻璃通知 + Windows 系統通知，可自選位置與時長
- 🎨 **主題自訂** — 淺色 / 深色 / 跟隨系統，6 種配色預設，自訂強調色 / 文字色 / 背景濃度 / 圓角
- 🌍 **多語言支援** — 簡體中文 / English，於設定中一鍵切換
- 🚫 **忽略管理** — 忽略此版本（版本更新後自動恢復）/ 永久忽略（黑名單可移除）
- ⚡ **開機自動啟動** — 可選開機自動啟動 + 啟動時自動檢查更新，支援自訂延遲（適應校園網路認證等較慢的網路環境）
- 🖋️ **字型自訂** — 內建霞鶩新晰黑 / Inter / JetBrains Mono，可從系統字型清單、指定資料夾或單一字型檔更換介面字型，並分級縮放字級
- 📄 **執行日誌** — 每日日誌自動寫入磁碟，設定中一鍵開啟；更新失敗的項目點一下即可跳至對應日誌
- 🔍 **高解析度螢幕適配** — 視窗自動配合系統 DPI 縮放，避免超出可用工作區
- 🪟 **關於視窗** — 軟體介紹、GitHub 連結、檢查更新
- 🖥️ **雙架構支援** — x64 與 ARM64 獨立建置版本

## 技術棧

- **前端**: TypeScript + Vite + 原生 CSS（無框架依賴）
- **後端**: Rust + Tauri 2
- **視窗效果**: Acrylic / Mica 毛玻璃
- **封裝**: NSIS 安裝程式

## 安裝

### 下載

前往 [Releases](https://github.com/MCheng404/onekey-updater/releases) 頁面下載最新版本：

| 架構 | 安裝程式 | 免安裝版 |
|------|----------|----------|
| x64（Intel / AMD） | `onekey-updater-<版本號>-x64-setup.exe` | `onekey-updater-x64.exe` |
| ARM64（高通驍龍） | `onekey-updater-<版本號>-arm64-setup.exe` | `onekey-updater-arm64.exe` |

> 不確定自己的架構？開啟「設定 → 系統 → 關於」，查看「系統類型」。

### 從原始碼建置

```bash
# 安裝依賴
npm install

# 開發模式
npm run tauri:dev

# 建置 x64 發行版
npm run tauri:build

# 建置 ARM64 發行版
npm run tauri:build -- --target aarch64-pc-windows-msvc
```

## 專案結構

```
├── src/                    # 前端原始碼
│   ├── main.ts            # 主視窗邏輯
│   ├── settings.ts        # 設定視窗邏輯
│   ├── notify.ts          # 通知視窗邏輯
│   ├── about.ts           # 關於視窗邏輯
│   ├── theme.ts           # 主題系統
│   ├── i18n.ts            # 多語言系統
│   ├── app-settings.ts    # 應用程式設定
│   ├── font-settings.ts   # 字型設定
│   ├── icons.ts           # 圖示
│   └── styles.css         # 全域樣式
├── src-tauri/             # Rust 後端
│   ├── src/
│   │   ├── main.rs        # 進入點
│   │   ├── lib.rs         # 指令註冊
│   │   ├── font.rs        # 系統字型列舉 / 自訂字型
│   │   ├── logfile.rs     # 每日日誌寫入
│   │   ├── sources/       # 更新來源
│   │   │   ├── npm.rs
│   │   │   ├── winget.rs
│   │   │   ├── pip.rs
│   │   │   └── openclaw.rs
│   │   └── ...
│   └── tauri.conf.json    # Tauri 設定
├── index.html             # 主視窗
├── settings.html          # 設定視窗
├── notify.html            # 通知視窗
└── about.html             # 關於視窗
```

## 授權條款

MIT
