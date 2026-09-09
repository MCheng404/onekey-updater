/**
 * 内嵌矢量图标（Lucide 风格，stroke-based，MIT License）
 * 以常量字符串形式内联，避免引入运行时依赖与网络请求。
 */

const wrap = (body: string, size = 14): string =>
  `<svg class="ico" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" ` +
  `stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${body}</svg>`;

export const ICON = {
  /** 刷新：检查更新 */
  refresh: wrap(
    '<path d="M21 12a9 9 0 1 1-2.64-6.36"/><path d="M21 3v6h-6"/>'
  ),
  /** 下载：执行更新 */
  download: wrap(
    '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>' +
      '<path d="M7 10l5 5 5-5"/><path d="M12 15V3"/>'
  ),
  /** 包：npm */
  package: wrap(
    '<path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>' +
      '<path d="M3.3 7L12 12l8.7-5"/><path d="M12 22V12"/>'
  ),
  /** 窗口：winget */
  window: wrap(
    '<rect x="3" y="3" width="18" height="18" rx="2"/>' +
      '<path d="M3 9h18"/><circle cx="7" cy="6" r="0.6" fill="currentColor"/>'
  ),
  /** 代码块：pip */
  code: wrap('<path d="M16 18l6-6-6-6"/><path d="M8 6l-6 6 6 6"/>'),
  /** 终端：OpenClaw */
  terminal: wrap(
    '<path d="M4 17l6-6-6-6"/><path d="M12 19h8"/>'
  ),
  /** 对勾：成功 */
  check: wrap('<path d="M20 6L9 17l-5-5"/>'),
  /** 警告 */
  alert: wrap(
    '<path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>' +
      '<path d="M12 9v4"/><path d="M12 17h.01"/>'
  ),
  /** 铃铛：通知 */
  bell: wrap(
    '<path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/>' +
      '<path d="M13.73 21a2 2 0 0 1-3.46 0"/>'
  ),
  /** 显示器：屏幕位置 */
  monitor: wrap(
    '<rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8"/><path d="M12 17v4"/>'
  ),
  /** 齿轮：设置 */
  settings: wrap(
    '<circle cx="12" cy="12" r="3"/>' +
      '<path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>'
  ),
  /** × 关闭 */
  x: wrap('<path d="M18 6L6 18"/><path d="M6 6l12 12"/>'),
  /** 眼睛关闭：忽略此版本 */
  eyeOff: wrap(
    '<path d="M9.88 9.88a3 3 0 1 0 4.24 4.24"/>' +
      '<path d="M10.73 5.08A10.43 10.43 0 0 1 12 5c7 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68"/>' +
      '<path d="M6.61 6.61A13.526 13.526 0 0 0 2 12s3 7 10 7a9.74 9.74 0 0 0 5.39-1.61"/>' +
      '<line x1="2" y1="2" x2="22" y2="22"/>'
  ),
  /** 禁止：永久忽略 */
  ban: wrap('<circle cx="12" cy="12" r="10"/><path d="m4.9 4.9 14.2 14.2"/>'),
  /** 箭头：回到顶部 / 列表指示 */
  arrowUp: wrap('<path d="M12 19V5"/><path d="M5 12l7-7 7 7"/>'),
  arrowDown: wrap('<path d="M12 5v14"/><path d="M5 12l7 7 7-7"/>'),
  arrowLeft: wrap('<path d="M19 12H5"/><path d="M12 19l-7-7 7-7"/>'),
  arrowRight: wrap('<path d="M5 12h14"/><path d="M12 5l7 7-7 7"/>'),
} as const;

/** 来源对应的图标键 */
export const SOURCE_ICON: Record<string, string> = {
  npm: ICON.package,
  winget: ICON.window,
  pip: ICON.code,
  openclaw: ICON.terminal,
};
