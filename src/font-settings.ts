/**
 * 字体设置：内置字体 / 系统字体 / 自定义字体文件，外加字号缩放。
 *
 * - 设置存 localStorage（同源，各窗口共享）
 * - 字体**文件**本身由 Rust 侧持久化，并通过自定义协议
 *   `http://font.localhost/ui` 提供给 WebView，不走 IPC 传字节
 */

import { emit, listen, type UnlistenFn } from '@tauri-apps/api/event';

export const FONT_KEY = 'onekey-updater.font';
export const FONT_CHANGED_EVENT = 'font-changed';

export type FontSource = 'builtin' | 'system' | 'file';

export interface FontSettings {
  source: FontSource;
  /** source = system 时选中的系统字族名 */
  family: string;
  /** source = file 时的字体文件名（展示用） */
  customName: string;
  /** source = file 时的绝对路径（仅用于 cache-busting 与回显） */
  customPath: string;
  /** 字号缩放倍率，1 = 标准 */
  scale: number;
}

export const DEFAULT_FONT: FontSettings = {
  source: 'builtin',
  family: '',
  customName: '',
  customPath: '',
  scale: 1,
};

/** 字号缩放范围（设置面板滑块与校验共用） */
export const FONT_SCALE_MIN = 0.85;
export const FONT_SCALE_MAX = 1.4;

/** 内置字体栈（与 styles.css 的 :root 默认值保持一致） */
const BUILTIN_CN = "'LXGW Neo XiHei Plus', 'Microsoft YaHei UI', 'PingFang SC', sans-serif";
const BUILTIN_EN = "'Inter', system-ui, -apple-system, 'Segoe UI', sans-serif";

/** 自定义字体文件在 @font-face 里使用的家族名 */
const USER_FAMILY = 'OneKeyUserFont';
const USER_FACE_ID = 'onekey-user-font-face';

export function loadFont(): FontSettings {
  try {
    const raw = localStorage.getItem(FONT_KEY);
    if (!raw) return { ...DEFAULT_FONT };
    const parsed = JSON.parse(raw) as Partial<FontSettings>;
    const scale = Number(parsed.scale);
    return {
      source: (parsed.source as FontSource) ?? 'builtin',
      family: parsed.family ?? '',
      customName: parsed.customName ?? '',
      customPath: parsed.customPath ?? '',
      scale:
        Number.isFinite(scale) && scale >= FONT_SCALE_MIN && scale <= FONT_SCALE_MAX ? scale : 1,
    };
  } catch {
    return { ...DEFAULT_FONT };
  }
}

export function saveFont(f: FontSettings): void {
  try {
    localStorage.setItem(FONT_KEY, JSON.stringify(f));
  } catch {
    /* 存储不可用时忽略 */
  }
}

/** 系统字体名可能含空格/中文，统一加引号 */
function quoteFamily(name: string): string {
  return `"${name.replace(/["']/g, '')}"`;
}

/**
 * 注入（或刷新）自定义字体文件的 @font-face。
 *
 * URL 带上字体路径作为查询参数：换了字体文件后必须让浏览器重新取，
 * 否则会沿用上一次缓存的 @font-face。
 */
function ensureUserFontFace(cacheKey: string): void {
  let style = document.getElementById(USER_FACE_ID) as HTMLStyleElement | null;
  if (!style) {
    style = document.createElement('style');
    style.id = USER_FACE_ID;
    document.head.append(style);
  }
  const url = `http://font.localhost/ui?v=${encodeURIComponent(cacheKey)}`;
  style.textContent = `@font-face{font-family:'${USER_FAMILY}';src:url('${url}');font-display:swap;}`;
}

/** 把字体设置写进 CSS 变量 */
export function applyFont(f: FontSettings): void {
  const s = document.documentElement.style;

  s.setProperty('--fs', String(f.scale));

  let primary = '';
  if (f.source === 'file' && f.customPath) {
    ensureUserFontFace(f.customPath);
    primary = `'${USER_FAMILY}'`;
  } else if (f.source === 'system' && f.family.trim()) {
    primary = quoteFamily(f.family.trim());
  }

  s.setProperty('--font-cn', primary ? `${primary}, ${BUILTIN_CN}` : BUILTIN_CN);
  s.setProperty('--font-en', primary ? `${primary}, ${BUILTIN_EN}` : BUILTIN_EN);
}

/** 供设置面板做实时预览：临时套用某套字体（不落盘） */
export function previewFont(f: FontSettings): void {
  applyFont(f);
}

/**
 * 监听其他窗口的字体变更，返回取消监听函数。
 * 注意 listen 是异步的，取消函数会在注册完成后才生效。
 */
export function watchFont(onChange: (f: FontSettings) => void): () => void {
  let unlisten: UnlistenFn | null = null;
  void listen<FontSettings>(FONT_CHANGED_EVENT, (e) => onChange(e.payload)).then((fn) => {
    unlisten = fn;
  });
  return () => unlisten?.();
}

/** 保存并广播给所有窗口 */
export function commitFont(f: FontSettings): void {
  saveFont(f);
  applyFont(f);
  void emit(FONT_CHANGED_EVENT, f).catch(() => undefined);
}
