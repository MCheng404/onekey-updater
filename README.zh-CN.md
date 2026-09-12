# 一键更新 (OneKey Updater)

[![GitHub release](https://img.shields.io/github/v/release/MCheng404/onekey-updater)](https://github.com/MCheng404/onekey-updater/releases)
[![GitHub license](https://img.shields.io/github/license/MCheng404/onekey-updater)](https://github.com/MCheng404/onekey-updater/blob/main/LICENSE)
[![GitHub downloads](https://img.shields.io/github/downloads/MCheng404/onekey-updater/total)](https://github.com/MCheng404/onekey-updater/releases)

**简体中文 | [English](README.md)**

统一管理 npm / winget / pip / OpenClaw 四源软件更新的桌面工具，基于 Tauri 2 + Rust + TypeScript 构建。轻量、快速、无感。

## 功能特性

- 🔄 **四源统一更新检查** — 同时检查 npm 全局包、winget 软件、pip Python 包、OpenClaw
- 📢 **双模式通知** — 应用内毛玻璃通知 + Windows 系统通知，可自选位置和时长
- 🎨 **主题定制** — 浅色 / 深色 / 跟随系统，6 种配色预设，自定义强调色/文字色/背景浓度/圆角
- 🌍 **多语言支持** — 简体中文 / English，设置中一键切换
- 🚫 **忽略管理** — 忽略此版本（版本更新后自动恢复）/ 永久忽略（黑名单可移除）
- ⚡ **开机自启动** — 可选开机自启动 + 启动时自动检查更新，支持自定义延迟（适应校园网认证等慢网络环境）
- 🖋️ **字体自定义** — 内置霞鹜新晰黑 / Inter / JetBrains Mono，可从系统字体列表、指定目录或单个字体文件更换界面字体，并按档缩放字号
- 📄 **运行日志** — 每日日志自动落盘，设置中一键打开；更新失败项点击即可跳转到对应日志
- 🔍 **高分屏适配** — 窗口自动适配系统 DPI 缩放，避免超出可用工作区
- 🪟 **关于窗口** — 软件介绍、GitHub 链接、检查更新
- 🖥️ **双架构支持** — x64 和 ARM64 独立构建版本

## 技术栈

- **前端**: TypeScript + Vite + 原生 CSS（无框架依赖）
- **后端**: Rust + Tauri 2
- **窗口效果**: Acrylic / Mica 毛玻璃
- **打包**: NSIS 安装包

## 安装

### 下载

前往 [Releases](https://github.com/MCheng404/onekey-updater/releases) 页面下载最新版本：

| 架构 | 安装包 | 绿色版 |
|------|--------|--------|
| x64（Intel/AMD） | `onekey-updater-<版本号>-x64-setup.exe` | `onekey-updater-x64.exe` |
| ARM64（高通骁龙） | `onekey-updater-<版本号>-arm64-setup.exe` | `onekey-updater-arm64.exe` |

> 不确定自己的架构？打开「设置 → 系统 → 关于」，查看「系统类型」。

### 从源码构建

```bash
# 安装依赖
npm install

# 开发模式
npm run tauri:dev

# 构建 x64 发布版
npm run tauri:build

# 构建 ARM64 发布版
npm run tauri:build -- --target aarch64-pc-windows-msvc
```

## 项目结构

```
├── src/                    # 前端源码
│   ├── main.ts            # 主窗口逻辑
│   ├── settings.ts        # 设置窗口逻辑
│   ├── notify.ts          # 通知窗口逻辑
│   ├── about.ts           # 关于窗口逻辑
│   ├── theme.ts           # 主题系统
│   ├── i18n.ts            # 多语言系统
│   ├── app-settings.ts    # 应用设置
│   ├── font-settings.ts   # 字体设置
│   ├── icons.ts           # 图标
│   └── styles.css         # 全局样式
├── src-tauri/             # Rust 后端
│   ├── src/
│   │   ├── main.rs        # 入口
│   │   ├── lib.rs         # 命令注册
│   │   ├── font.rs        # 系统字体枚举 / 自定义字体
│   │   ├── logfile.rs     # 每日日志落盘
│   │   ├── sources/       # 更新源
│   │   │   ├── npm.rs
│   │   │   ├── winget.rs
│   │   │   ├── pip.rs
│   │   │   └── openclaw.rs
│   │   └── ...
│   └── tauri.conf.json    # Tauri 配置
├── index.html             # 主窗口
├── settings.html          # 设置窗口
├── notify.html            # 通知窗口
└── about.html             # 关于窗口
```

## 许可证

MIT
