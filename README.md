# 一键更新 (OneKey Updater)

统一管理 npm / winget / pip / OpenClaw 四源软件更新的桌面工具，基于 Tauri 2 + Rust + TypeScript 构建。轻量、快速、无感。

## 功能特性

- 🔄 **四源统一更新检查** - 同时检查 npm 全局包、winget 软件、pip Python 包、OpenClaw
- 📢 **双模式通知** - 应用内毛玻璃通知 + Windows 系统通知，可自选位置和时长
- 🎨 **主题定制** - 浅色 / 深色 / 跟随系统，6 种配色预设，自定义强调色/文字色/背景浓度/圆角
- 🌍 **多语言支持** - 简体中文 / English，设置中一键切换
- 🚫 **忽略管理** - 忽略此版本（版本更新后自动恢复）/ 永久忽略（黑名单可移除）
- ⚡ **开机自启动** - 可选开机自启动 + 启动时自动检查更新
- 🪟 **关于窗口** - 软件介绍、GitHub 链接、检查更新

## 技术栈

- **前端**: TypeScript + Vite + 原生 CSS（无框架依赖）
- **后端**: Rust + Tauri 2
- **窗口效果**: Acrylic / Mica 毛玻璃
- **打包**: NSIS 安装包

## 开发

```bash
# 安装依赖
npm install

# 开发模式
npm run tauri:dev

# 构建发布版
npm run tauri:build
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
│   ├── icons.ts           # 图标
│   └── styles.css         # 全局样式
├── src-tauri/             # Rust 后端
│   ├── src/
│   │   ├── main.rs        # 入口
│   │   ├── lib.rs         # 命令注册
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

## 下载

从 [Releases](https://github.com/MCheng404/onekey-updater/releases) 页面下载最新版本的安装包。

## 许可证

MIT
