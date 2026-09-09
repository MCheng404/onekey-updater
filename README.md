# OneKey Updater

[![GitHub release](https://img.shields.io/github/v/release/MCheng404/onekey-updater)](https://github.com/MCheng404/onekey-updater/releases)
[![GitHub license](https://img.shields.io/github/license/MCheng404/onekey-updater)](https://github.com/MCheng404/onekey-updater/blob/main/LICENSE)
[![GitHub downloads](https://img.shields.io/github/downloads/MCheng404/onekey-updater/total)](https://github.com/MCheng404/onekey-updater/releases)

**[简体中文](README.zh-CN.md) | English**

A desktop tool for unified software updates across npm, winget, pip, and OpenClaw. Built with Tauri 2 + Rust + TypeScript. Lightweight, fast, and seamless.

## Features

- 🔄 **Four-source unified update check** — Simultaneously check npm global packages, winget software, pip Python packages, and OpenClaw
- 📢 **Dual-mode notifications** — In-app acrylic notifications + Windows system notifications, with customizable position and duration
- 🎨 **Theme customization** — Light / Dark / System follow, 6 color presets, custom accent color / text color / background opacity / corner radius
- 🌍 **Multi-language support** — Simplified Chinese / English, switch in settings
- 🚫 **Ignore management** — Ignore this version (auto-recovers after version change) / Ignore forever (blacklist, removable)
- ⚡ **Auto-start on boot** — Optional auto-start + auto-check on startup, with customizable delay for network authentication (e.g. campus Wi-Fi)
- 🪟 **About window** — Software intro, GitHub links, check for updates
- 🖥️ **Dual architecture support** — x64 and ARM64 independent builds

## Tech Stack

- **Frontend**: TypeScript + Vite + native CSS (no framework dependencies)
- **Backend**: Rust + Tauri 2
- **Window effects**: Acrylic / Mica blur
- **Packaging**: NSIS installer

## Installation

### Download

Go to the [Releases](https://github.com/MCheng404/onekey-updater/releases) page and download the latest version:

| Architecture | Installer | Portable |
|--------------|-----------|----------|
| x64 (Intel/AMD) | `onekey-updater-<version>-x64-setup.exe` | `onekey-updater-x64.exe` |
| ARM64 (Snapdragon) | `onekey-updater-<version>-arm64-setup.exe` | `onekey-updater-arm64.exe` |

> Not sure which architecture? Open **Settings → System → About** and check **System type**.

### Build from source

```bash
# Install dependencies
npm install

# Development mode
npm run tauri:dev

# Build x64 release
npm run tauri:build

# Build ARM64 release
npm run tauri:build -- --target aarch64-pc-windows-msvc
```

## Project Structure

```
├── src/                    # Frontend source
│   ├── main.ts            # Main window logic
│   ├── settings.ts        # Settings window logic
│   ├── notify.ts          # Notification window logic
│   ├── about.ts           # About window logic
│   ├── theme.ts           # Theme system
│   ├── i18n.ts            # Multi-language system
│   ├── app-settings.ts    # App settings
│   ├── icons.ts           # Icons
│   └── styles.css         # Global styles
├── src-tauri/             # Rust backend
│   ├── src/
│   │   ├── main.rs        # Entry point
│   │   ├── lib.rs         # Command registration
│   │   ├── sources/       # Update sources
│   │   │   ├── npm.rs
│   │   │   ├── winget.rs
│   │   │   ├── pip.rs
│   │   │   └── openclaw.rs
│   │   └── ...
│   └── tauri.conf.json    # Tauri configuration
├── index.html             # Main window
├── settings.html          # Settings window
├── notify.html            # Notification window
└── about.html             # About window
```

## License

MIT
