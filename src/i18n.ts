/**
 * 多语言支持（i18n）
 * 支持简体中文和英文，在设置中切换，localStorage 持久化。
 *
 * 约定：
 * - 静态文案写在 HTML 上用 `data-i18n` / `data-i18n-title` / `data-i18n-aria`
 *   / `data-i18n-placeholder`，由 `applyI18n()` 统一套用；
 * - 动态文案（含插值）在 TS 里用 `t('key', { ... })`；
 * - 所有语言变更都要重跑一遍渲染函数（只重跑 applyI18n 不够，
 *   因为列表/状态栏等是 JS 生成的一次性文本）。
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
  // ---- 通用 ----
  'common.close': '关闭',
  'common.reset': '重置',
  'common.remove': '移除',
  'common.loading': '读取中…',
  'common.error': '错误',

  // ---- 主窗口 ----
  'app.title': '系统一键更新',
  'app.admin': '管理员',
  'app.minimize': '最小化',
  'app.maximize': '最大化',
  'btn.check': '检查更新',
  'btn.checking': '检查中',
  'btn.updateSelected': '更新选中',
  'btn.updateSelectedCount': '更新选中 · {count}',
  'btn.updateAll': '一键全部更新',
  'btn.settings': '设置',
  'btn.clearLog': '清空',
  'btn.viewLog': '查看日志',
  'env.ready': '{name} 已就绪',
  'env.missing': '{name} 缺失',
  'status.idle': '就绪',
  'status.checking': '正在检查更新…',
  'status.checkDone': '检查完成 · 共 {count} 项可更新',
  'status.checkPartial': '已发现 {count} 项，仍在检查…',
  'status.ignoredCount': '已忽略 {count} 项',
  'status.iconFailed': '图标加载失败：{error}',
  'status.checkFailed': '检查失败',
  'status.checkFailedDetail': '检查失败：{error}',
  'status.updatingCount': '正在更新 {count} 项…',
  'status.updateDone': '更新完成',
  'status.updateResult': '成功 {ok} · 失败 {failed}',
  'status.updateFailed': '更新失败：{error}',
  'status.openSettingsFailed': '无法打开设置：{error}',
  'empty.scanning': '正在扫描可更新项…',
  'empty.notScanned': '点击「检查更新」开始扫描',
  'empty.allLatest': '所有软件都是最新版本',
  'empty.allIgnored': '全部 {count} 项已被忽略，可在设置中管理',
  'group.npm': 'npm 全局包',
  'group.winget': 'winget 软件',
  'group.pip': 'pip 包',
  'group.openclaw': 'OpenClaw',
  'log.title': '运行日志',
  'action.ignoreVersion': '忽略此版本（版本更新后自动恢复）',
  'action.ignoreForever': '永久忽略（可在设置中移除）',
  'action.failedViewLog': '更新失败，点击查看运行日志',
  'action.selectAllIn': '全选 {group}',

  // ---- 通知窗口 ----
  'notify.closeAria': '关闭通知',
  'notify.foundTitle': '发现可更新项',
  'notify.foundBody': '共 {count} 项可更新，点击查看详情',
  'notify.updateComplete': '更新完成',
  'notify.updateFailed': '更新失败',
  'notify.updateResult': '{ok} 项成功 · {failed} 项失败',
  'notify.noUpdateTitle': '检查完成',
  'notify.noUpdate': '所有软件已是最新版本',

  // ---- 设置窗口 ----
  'settings.title': '设置',
  'settings.appearancePresets': '外观预设',
  'settings.preset.teal': '青碧',
  'settings.preset.azure': '天青',
  'settings.preset.violet': '紫藤',
  'settings.preset.amber': '琥珀',
  'settings.preset.rose': '玫瑰',
  'settings.preset.jade': '竹青',
  'settings.customColors': '自定义配色',
  'settings.accent': '强调色',
  'settings.textColor': '主文字',
  'settings.dimColor': '次要文字',
  'settings.dimHint': '次要文字用于名称、说明与时间戳，建议保留一定透明度。',
  'settings.sectionUi': '界面',
  'settings.themeMode': '主题模式',
  'settings.themeLight': '浅色',
  'settings.themeDark': '深色',
  'settings.themeSystem': '跟随系统',
  'settings.language': '语言',
  'settings.opacity': '背景浓度',
  'settings.radius': '圆角',
  'settings.reduceMotion': '减弱动画',
  'settings.autoCheck': '启动时自动检查更新',
  'settings.autostart': '开机自启动',
  'settings.autostartDelay': '自启动检查延迟',
  'settings.startupHint': '开启后应用启动时自动扫描可更新项，无需手动点击。自启动延迟用于等待网络（如校园网认证）就绪后再检查。',
  'settings.sectionFont': '字体',
  'settings.fontSource': '字体来源',
  'settings.fontBuiltin': '内置',
  'settings.fontSystem': '系统字体',
  'settings.fontFile': '字体文件',
  'settings.systemFontFamily': '系统字族',
  'settings.pickMethod': '选择方式',
  'settings.pickFontFile': '选字体文件…',
  'settings.pickFontDir': '选字体文件夹…',
  'settings.dirFont': '文件夹内字体',
  'settings.noFontFile': '未选择字体文件',
  'settings.noSystemFont': '（未读取到系统字体）',
  'settings.noFontInDir': '该文件夹里没有字体文件：{dir}',
  'settings.fontScale': '字号',
  'settings.fontPreview': '字体预览 FONT PREVIEW',
  'settings.resetFont': '恢复默认字体',
  'settings.sectionNotification': '通知',
  'settings.notifNative': 'Windows 系统通知',
  'settings.notifNativeHint': '走 Windows 操作中心，不打扰、不依赖本应用窗口',
  'settings.notifApp': '应用内通知',
  'settings.notifAppHint': '在屏幕一角显示独立毛玻璃窗口，可自选位置',
  'settings.notifPosition': '通知位置',
  'settings.notifDuration': '显示时长',
  'settings.notifOpacity': '不透明度',
  'settings.testNotif': '测试通知',
  'settings.testNotifNative': '系统通知测试',
  'settings.testNotifApp': '应用内通知测试',
  'settings.testNotifBody': '这是一条测试消息，用于确认通知位置与样式。',
  'settings.pos.topLeft': '左上',
  'settings.pos.topLeftHint': '左上：适合作为信息提示流，新通知从顶部向下依次堆叠，眼睛先看到最新一条。',
  'settings.pos.topCenter': '顶部居中',
  'settings.pos.topCenterHint': '顶部居中：适合中等优先级的提示，视线从屏幕中央向下扫读，左右对称。',
  'settings.pos.topRight': '右上',
  'settings.pos.topRightHint': '右上：与 macOS 通知中心一致，新通知顶部对齐，先看最新消息。',
  'settings.pos.bottomLeft': '左下',
  'settings.pos.bottomLeftHint': '左下：左侧靠下适合"非打扰型"提示，新通知向上叠，旧的下沉远离视线。',
  'settings.pos.bottomCenter': '底部居中',
  'settings.pos.bottomCenterHint': '底部居中：更新进度类通知常用，从下往上依次累加，旧消息保持可见。',
  'settings.pos.bottomRight': '右下',
  'settings.pos.bottomRightHint': '右下：人眼最常见的视线落点，新通知从底部滑入，与系统通知中心一致。',
  'settings.sectionIcons': '应用图标',
  'settings.iconsHint': '从注册表 Uninstall 表读取已安装程序的 DisplayIcon，缓存 24 小时。',
  'settings.rebuildIcons': '刷新图标缓存',
  'settings.refreshing': '刷新中…',
  'settings.iconCached': '已缓存 {count} 个图标',
  'settings.iconFailed': '刷新失败：{error}',
  'settings.sectionIgnore': '忽略列表',
  'settings.ignoreHint': '管理被忽略的更新项。「忽略此版本」在版本号变化后自动恢复；「永久忽略」需手动移除。',
  'settings.ignoreEmpty': '暂无被忽略的更新项',
  'settings.ignoreVersionLabel': '忽略版本 {version}',
  'settings.ignoreForeverLabel': '永久忽略',
  'settings.sectionLog': '日志',
  'settings.logHint': '运行日志按天写入本地文件。更新失败时，主界面列表会出现「更新失败 · 查看日志」入口。',
  'settings.openLogFolder': '打开日志文件夹',
  'settings.sectionAbout': '关于',
  'settings.aboutDesc': '统一管理 npm / winget / pip / OpenClaw 四源软件更新',
  'settings.openAbout': '关于软件',
  'settings.resetAppearance': '恢复默认外观',

  // ---- 关于窗口 ----
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
};

const enUS: Record<string, string> = {
  // ---- Common ----
  'common.close': 'Close',
  'common.reset': 'Reset',
  'common.remove': 'Remove',
  'common.loading': 'Reading…',
  'common.error': 'Error',

  // ---- Main window ----
  'app.title': 'OneKey Updater',
  'app.admin': 'Administrator',
  'app.minimize': 'Minimize',
  'app.maximize': 'Maximize',
  'btn.check': 'Check for Updates',
  'btn.checking': 'Checking',
  'btn.updateSelected': 'Update Selected',
  'btn.updateSelectedCount': 'Update Selected · {count}',
  'btn.updateAll': 'Update All',
  'btn.settings': 'Settings',
  'btn.clearLog': 'Clear',
  'btn.viewLog': 'View Log',
  'env.ready': '{name} ready',
  'env.missing': '{name} missing',
  'status.idle': 'Ready',
  'status.checking': 'Checking for updates…',
  'status.checkDone': 'Check complete · {count} update(s) available',
  'status.checkPartial': '{count} found so far, still checking…',
  'status.ignoredCount': '{count} ignored',
  'status.iconFailed': 'Failed to load icons: {error}',
  'status.checkFailed': 'Check failed',
  'status.checkFailedDetail': 'Check failed: {error}',
  'status.updatingCount': 'Updating {count} item(s)…',
  'status.updateDone': 'Update complete',
  'status.updateResult': '{ok} succeeded · {failed} failed',
  'status.updateFailed': 'Update failed: {error}',
  'status.openSettingsFailed': 'Cannot open settings: {error}',
  'empty.scanning': 'Scanning for updates…',
  'empty.notScanned': 'Click "Check for Updates" to start scanning',
  'empty.allLatest': 'All software is up to date',
  'empty.allIgnored': 'All {count} item(s) ignored — manage them in Settings',
  'group.npm': 'npm Global Packages',
  'group.winget': 'winget Software',
  'group.pip': 'pip Packages',
  'group.openclaw': 'OpenClaw',
  'log.title': 'Runtime Log',
  'action.ignoreVersion': 'Ignore this version (auto-restored when a newer version appears)',
  'action.ignoreForever': 'Ignore forever (removable in Settings)',
  'action.failedViewLog': 'Update failed — click to view the log',
  'action.selectAllIn': 'Select all {group}',

  // ---- Notification window ----
  'notify.closeAria': 'Dismiss notification',
  'notify.foundTitle': 'Updates available',
  'notify.foundBody': '{count} update(s) available — click for details',
  'notify.updateComplete': 'Update complete',
  'notify.updateFailed': 'Update failed',
  'notify.updateResult': '{ok} succeeded · {failed} failed',
  'notify.noUpdateTitle': 'Check complete',
  'notify.noUpdate': 'All software is up to date',

  // ---- Settings window ----
  'settings.title': 'Settings',
  'settings.appearancePresets': 'Appearance Presets',
  'settings.preset.teal': 'Teal',
  'settings.preset.azure': 'Azure',
  'settings.preset.violet': 'Violet',
  'settings.preset.amber': 'Amber',
  'settings.preset.rose': 'Rose',
  'settings.preset.jade': 'Jade',
  'settings.customColors': 'Custom Colors',
  'settings.accent': 'Accent',
  'settings.textColor': 'Primary Text',
  'settings.dimColor': 'Secondary Text',
  'settings.dimHint': 'Secondary text is used for names, hints and timestamps; keeping some transparency is recommended.',
  'settings.sectionUi': 'Interface',
  'settings.themeMode': 'Theme Mode',
  'settings.themeLight': 'Light',
  'settings.themeDark': 'Dark',
  'settings.themeSystem': 'System',
  'settings.language': 'Language',
  'settings.opacity': 'Background Opacity',
  'settings.radius': 'Corner Radius',
  'settings.reduceMotion': 'Reduce Motion',
  'settings.autoCheck': 'Auto-check on startup',
  'settings.autostart': 'Launch on startup',
  'settings.autostartDelay': 'Autostart check delay',
  'settings.startupHint': 'When enabled, the app scans for updates on startup without a manual click. The autostart delay waits for the network (e.g. campus Wi-Fi auth) to be ready before checking.',
  'settings.sectionFont': 'Font',
  'settings.fontSource': 'Font Source',
  'settings.fontBuiltin': 'Built-in',
  'settings.fontSystem': 'System Fonts',
  'settings.fontFile': 'Font File',
  'settings.systemFontFamily': 'System Family',
  'settings.pickMethod': 'Pick Method',
  'settings.pickFontFile': 'Pick font file…',
  'settings.pickFontDir': 'Pick font folder…',
  'settings.dirFont': 'Fonts in folder',
  'settings.noFontFile': 'No font file selected',
  'settings.noSystemFont': '(no system fonts found)',
  'settings.noFontInDir': 'No font files in this folder: {dir}',
  'settings.fontScale': 'Font Size',
  'settings.fontPreview': '字体预览 FONT PREVIEW',
  'settings.resetFont': 'Reset font to default',
  'settings.sectionNotification': 'Notifications',
  'settings.notifNative': 'Windows Notifications',
  'settings.notifNativeHint': 'Goes through the Windows Action Center — unobtrusive, independent of this window',
  'settings.notifApp': 'In-app Notifications',
  'settings.notifAppHint': 'Shows a standalone glass window in a screen corner; position is configurable',
  'settings.notifPosition': 'Position',
  'settings.notifDuration': 'Duration',
  'settings.notifOpacity': 'Opacity',
  'settings.testNotif': 'Test Notification',
  'settings.testNotifNative': 'System notification test',
  'settings.testNotifApp': 'In-app notification test',
  'settings.testNotifBody': 'This is a test message to confirm the notification position and style.',
  'settings.pos.topLeft': 'Top Left',
  'settings.pos.topLeftHint': 'Top left: good for an information feed — new notifications stack downward so the newest is seen first.',
  'settings.pos.topCenter': 'Top Center',
  'settings.pos.topCenterHint': 'Top center: suits medium-priority prompts; the eye scans downward from the screen centre, symmetric left and right.',
  'settings.pos.topRight': 'Top Right',
  'settings.pos.topRightHint': 'Top right: matches the macOS Notification Center — new notifications align to the top, newest first.',
  'settings.pos.bottomLeft': 'Bottom Left',
  'settings.pos.bottomLeftHint': 'Bottom left: a low-left spot suits non-intrusive prompts; new notifications stack upward and old ones sink out of view.',
  'settings.pos.bottomCenter': 'Bottom Center',
  'settings.pos.bottomCenterHint': 'Bottom center: common for update-progress notices — they accumulate upward while older messages stay visible.',
  'settings.pos.bottomRight': 'Bottom Right',
  'settings.pos.bottomRightHint': 'Bottom right: the most common gaze target — new notifications slide in from the bottom, like the system notification centre.',
  'settings.sectionIcons': 'App Icons',
  'settings.iconsHint': 'Reads DisplayIcon for installed programs from the registry Uninstall keys; cached for 24 hours.',
  'settings.rebuildIcons': 'Rebuild icon cache',
  'settings.refreshing': 'Refreshing…',
  'settings.iconCached': '{count} icon(s) cached',
  'settings.iconFailed': 'Refresh failed: {error}',
  'settings.sectionIgnore': 'Ignore List',
  'settings.ignoreHint': 'Manage ignored updates. "Ignore this version" is restored automatically once the version changes; "Ignore forever" must be removed manually.',
  'settings.ignoreEmpty': 'No ignored items',
  'settings.ignoreVersionLabel': 'Version ignored {version}',
  'settings.ignoreForeverLabel': 'Ignored forever',
  'settings.sectionLog': 'Log',
  'settings.logHint': 'Runtime logs are written to a local file per day. When an update fails, the main list shows an "update failed · view log" entry.',
  'settings.openLogFolder': 'Open log folder',
  'settings.sectionAbout': 'About',
  'settings.aboutDesc': 'Unified npm / winget / pip / OpenClaw software updater',
  'settings.openAbout': 'About this app',
  'settings.resetAppearance': 'Reset appearance',

  // ---- About window ----
  'about.title': 'About',
  'about.appName': 'OneKey Updater',
  'about.version': 'Version',
  'about.description': 'A desktop tool for unified software updates across npm, winget, pip and OpenClaw. Lightweight, fast and seamless.',
  'about.features': 'Key Features',
  'about.feature1': 'Unified update check across 4 sources',
  'about.feature2': 'In-app and system notifications',
  'about.feature3': 'Light / Dark / System theme',
  'about.feature4': 'Version-scoped and permanent ignore list',
  'about.feature5': 'Launch on startup and auto-check',
  'about.github': 'GitHub',
  'about.repository': 'Source Repository',
  'about.checkUpdate': 'Check for Updates',
  'about.checking': 'Checking…',
  'about.upToDate': 'You are up to date',
  'about.newVersion': 'New version available',
  'about.download': 'Download',
  'about.madeWith': 'Built with Tauri 2 + Rust + TypeScript',
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
 * 把静态标记里的文案套用成当前语言。
 *
 * 支持的属性（可同时出现在一个元素上）：
 *   data-i18n              -> textContent
 *   data-i18n-title        -> title 属性（tooltip）
 *   data-i18n-aria         -> aria-label 属性
 *   data-i18n-placeholder  -> placeholder 属性
 *
 * @param root 作用范围，默认整个 document
 */
export function applyI18n(root: ParentNode = document): void {
  root.querySelectorAll<HTMLElement>('[data-i18n]').forEach((node) => {
    const key = node.dataset.i18n;
    if (key) node.textContent = t(key);
  });
  root.querySelectorAll<HTMLElement>('[data-i18n-title]').forEach((node) => {
    const key = node.dataset.i18nTitle;
    if (key) node.title = t(key);
  });
  root.querySelectorAll<HTMLElement>('[data-i18n-aria]').forEach((node) => {
    const key = node.dataset.i18nAria;
    if (key) node.setAttribute('aria-label', t(key));
  });
  root.querySelectorAll<HTMLElement>('[data-i18n-placeholder]').forEach((node) => {
    const key = node.dataset.i18nPlaceholder;
    if (key) node.setAttribute('placeholder', t(key));
  });
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
