/**
 * 多语言支持（i18n）
 * 支持简体中文和英文，在设置中切换，localStorage 持久化
 */

import { emit, listen, type UnlistenFn } from '@tauri-apps/api/event';

export type Lang = 'zh-CN' | 'en-US';

export const LANG_KEY = 'onekey-updater.lang';

export const LANGS: Array<{ code: Lang; label: string; flag: string }> = [
  { code: 'zh-CN', label: '简体中文', flag: '🇨🇳' },
  { code: 'en-US', label: 'English', flag: '🇺🇸' },
];

// ============ 语言包 ============

const zhCN: Record<string, string> = {
  // 主窗口
  'app.title': '系统一键更新',
  'app.subtitle': '统一管理 npm / winget / pip / OpenClaw 软件更新',
  'btn.check': '检查更新',
  'btn.checking': '检查中',
  'btn.updateSelected': '更新选中',
  'btn.updateAll': '全部更新',
  'btn.settings': '设置',
  'btn.clearLog': '清空日志',
  'status.idle': '就绪',
  'status.checking': '正在检查更新…',
  'status.updating': '正在更新…',
  'status.done': '检查完成',
  'status.ignored': '已忽略',
  'empty.notScanned': '点击「检查更新」开始扫描',
  'empty.allLatest': '所有软件都是最新版本',
  'empty.allIgnored': '全部 {count} 项已被忽略（可在设置中管理）',
  'group.npm': 'npm 全局包',
  'group.winget': 'winget 软件',
  'group.pip': 'pip Python 包',
  'group.openclaw': 'OpenClaw',
  'log.title': '更新日志',
  'log.empty': '暂无日志',
  'version.current': '当前',
  'version.latest': '最新',
  'tag.beta': 'beta',
  'action.ignoreVersion': '忽略此版本（版本更新后自动恢复）',
  'action.ignoreForever': '永久忽略（可在设置中移除）',

  // 设置窗口
  'settings.title': '设置',
  'settings.appearance': '界面',
  'settings.themeMode': '主题模式',
  'settings.themeLight': '浅色',
  'settings.themeDark': '深色',
  'settings.themeSystem': '跟随系统',
  'settings.language': '语言',
  'settings.opacity': '背景浓度',
  'settings.radius': '圆角',
  'settings.reduceMotion': '减弱动画',
  'settings.autoCheck': '启动时自动检查更新',
  'settings.autoCheckHint': '开启后应用启动时自动扫描可更新项，无需手动点击。',
  'settings.autostart': '开机自启动',
  'settings.autostartDelay': '自启动检查延迟',
  'settings.autostartDelayHint': '开机自启动时等待网络就绪后再检查更新，适用于需要认证的网络环境（如校园网）。',
  'settings.notification': '通知',
  'settings.notifMode': '通知方式',
  'settings.notifApp': '应用内通知',
  'settings.notifNative': '系统通知',
  'settings.notifPosition': '通知位置',
  'settings.notifDuration': '显示时长',
  'settings.notifOpacity': '不透明度',
  'settings.notifMaxStack': '最大堆叠数',
  'settings.icons': '应用图标',
  'settings.rebuildIcons': '重建图标缓存',
  'settings.ignoreList': '忽略列表',
  'settings.ignoreEmpty': '暂无忽略项',
  'settings.ignoreVersion': '忽略版本',
  'settings.ignoreForever': '永久忽略',
  'settings.remove': '移除',
  'settings.about': '关于',
  'settings.resetAll': '恢复默认',
  'settings.presets': '配色预设',

  // 关于窗口
  'about.title': '关于',
  'about.appName': '一键更新',
  'about.version': '版本',
  'about.description': '统一管理 npm、winget、pip、OpenClaw 四源软件更新的桌面工具。轻量、快速、无感。',
  'about.features': '主要功能',
  'about.feature1': '四源统一更新检查',
  'about.feature2': '应用内通知与系统通知',
  'about.feature3': '浅色/深色/跟随系统主题',
  'about.feature4': '忽略版本与永久忽略黑名单',
  'about.feature5': '开机自启动与自动检查',
  'about.github': 'GitHub',
  'about.repository': '源代码仓库',
  'about.checkUpdate': '检查更新',
  'about.checking': '检查中…',
  'about.upToDate': '已是最新版本',
  'about.newVersion': '发现新版本',
  'about.download': '前往下载',
  'about.madeWith': '使用 Tauri 2 + Rust + TypeScript 构建',

  // 通知
  'notify.updateAvailable': '发现 {count} 项可更新',
  'notify.updateComplete': '更新完成',
  'notify.updateFailed': '更新失败',
  'notify.noUpdate': '所有软件已是最新版本',

  // 通用
  'common.ok': '确定',
  'common.cancel': '取消',
  'common.close': '关闭',
  'common.save': '保存',
  'common.loading': '加载中…',
  'common.error': '错误',
  'common.success': '成功',
  'common.warning': '警告',
  'common.info': '信息',
};

const enUS: Record<string, string> = {
  // Main window
  'app.title': 'OneKey Updater',
  'app.subtitle': 'Unified npm / winget / pip / OpenClaw software updater',
  'btn.check': 'Check Updates',
  'btn.checking': 'Checking',
  'btn.updateSelected': 'Update Selected',
  'btn.updateAll': 'Update All',
  'btn.settings': 'Settings',
  'btn.clearLog': 'Clear Log',
  'status.idle': 'Ready',
  'status.checking': 'Checking for updates…',
  'status.updating': 'Updating…',
  'status.done': 'Check complete',
  'status.ignored': 'ignored',
  'empty.notScanned': 'Click "Check Updates" to start scanning',
  'empty.allLatest': 'All software is up to date',
  'empty.allIgnored': 'All {count} items ignored (manage in Settings)',
  'group.npm': 'npm Global Packages',
  'group.winget': 'winget Software',
  'group.pip': 'pip Python Packages',
  'group.openclaw': 'OpenClaw',
  'log.title': 'Update Log',
  'log.empty': 'No logs yet',
  'version.current': 'Current',
  'version.latest': 'Latest',
  'tag.beta': 'beta',
  'action.ignoreVersion': 'Ignore this version (auto-restore on new version)',
  'action.ignoreForever': 'Ignore forever (removable in Settings)',

  // Settings window
  'settings.title': 'Settings',
  'settings.appearance': 'Appearance',
  'settings.themeMode': 'Theme Mode',
  'settings.themeLight': 'Light',
  'settings.themeDark': 'Dark',
  'settings.themeSystem': 'System',
  'settings.language': 'Language',
  'settings.opacity': 'Background Opacity',
  'settings.radius': 'Corner Radius',
  'settings.reduceMotion': 'Reduce Motion',
  'settings.autoCheck': 'Auto-check on startup',
  'settings.autoCheckHint': 'Automatically scan for updates when app starts.',
  'settings.autostart': 'Launch on startup',
  'settings.autostartDelay': 'Autostart check delay',
  'settings.autostartDelayHint': 'Wait for network to be ready before checking updates on autostart. Useful for networks requiring authentication (e.g. campus Wi-Fi).',
  'settings.notification': 'Notifications',
  'settings.notifMode': 'Notification Mode',
  'settings.notifApp': 'In-app notification',
  'settings.notifNative': 'System notification',
  'settings.notifPosition': 'Position',
  'settings.notifDuration': 'Duration',
  'settings.notifOpacity': 'Opacity',
  'settings.notifMaxStack': 'Max stack',
  'settings.icons': 'App Icons',
  'settings.rebuildIcons': 'Rebuild icon cache',
  'settings.ignoreList': 'Ignore List',
  'settings.ignoreEmpty': 'No ignored items',
  'settings.ignoreVersion': 'Version ignored',
  'settings.ignoreForever': 'Forever ignored',
  'settings.remove': 'Remove',
  'settings.about': 'About',
  'settings.resetAll': 'Reset to defaults',
  'settings.presets': 'Color Presets',

  // About window
  'about.title': 'About',
  'about.appName': 'OneKey Updater',
  'about.version': 'Version',
  'about.description': 'A desktop tool for unified software updates across npm, winget, pip, and OpenClaw. Lightweight, fast, and seamless.',
  'about.features': 'Key Features',
  'about.feature1': 'Unified update check for 4 sources',
  'about.feature2': 'In-app and system notifications',
  'about.feature3': 'Light / Dark / System theme',
  'about.feature4': 'Version ignore and permanent blacklist',
  'about.feature5': 'Auto-launch and auto-check',
  'about.github': 'GitHub',
  'about.repository': 'Source Repository',
  'about.checkUpdate': 'Check for Updates',
  'about.checking': 'Checking…',
  'about.upToDate': 'You are up to date',
  'about.newVersion': 'New version available',
  'about.download': 'Download',
  'about.madeWith': 'Built with Tauri 2 + Rust + TypeScript',

  // Notifications
  'notify.updateAvailable': '{count} updates available',
  'notify.updateComplete': 'Update complete',
  'notify.updateFailed': 'Update failed',
  'notify.noUpdate': 'All software is up to date',

  // Common
  'common.ok': 'OK',
  'common.cancel': 'Cancel',
  'common.close': 'Close',
  'common.save': 'Save',
  'common.loading': 'Loading…',
  'common.error': 'Error',
  'common.success': 'Success',
  'common.warning': 'Warning',
  'common.info': 'Info',
};

const PACKS: Record<Lang, Record<string, string>> = {
  'zh-CN': zhCN,
  'en-US': enUS,
};

// ============ 核心 API ============

let currentLang: Lang = detectLang();

function detectLang(): Lang {
  try {
    const saved = localStorage.getItem(LANG_KEY) as Lang | null;
    if (saved && PACKS[saved]) return saved;
  } catch {
    /* ignore */
  }
  // 检测系统语言
  if (typeof navigator !== 'undefined') {
    const sys = navigator.language;
    if (sys.startsWith('zh')) return 'zh-CN';
  }
  return 'en-US';
}

export function getLang(): Lang {
  return currentLang;
}

export function setLang(lang: Lang): void {
  if (!PACKS[lang]) return;
  currentLang = lang;
  try {
    localStorage.setItem(LANG_KEY, lang);
  } catch {
    /* ignore */
  }
  document.documentElement.setAttribute('lang', lang);
  // 通知所有窗口语言变更
  void emit('lang-changed', lang).catch(() => undefined);
}

/**
 * 翻译函数，支持 {placeholder} 插值
 */
export function t(key: string, vars?: Record<string, string | number>): string {
  const pack = PACKS[currentLang] ?? PACKS['zh-CN'];
  let text = pack[key] ?? PACKS['zh-CN'][key] ?? key;
  if (vars) {
    for (const [k, v] of Object.entries(vars)) {
      text = text.replace(new RegExp(`\\{${k}\\}`, 'g'), String(v));
    }
  }
  return text;
}

/**
 * 监听语言变更，返回取消监听函数
 */
export function watchLang(callback: (lang: Lang) => void): () => void {
  let unlisten: UnlistenFn | null = null;
  void listen<Lang>('lang-changed', (e) => {
    currentLang = e.payload;
    callback(e.payload);
  }).then((fn) => {
    unlisten = fn;
  });
  return () => unlisten?.();
}
