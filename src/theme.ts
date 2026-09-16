/**
 * 主题状态与 CSS 变量应用，主窗口与设置窗口共用。
 * 支持浅色 / 深色 / 跟随系统三种模式。
 */

import { Effect, getCurrentWindow } from '@tauri-apps/api/window';

export type ThemeMode = 'light' | 'dark' | 'system';

/**
 * 文字渲染方式。
 * - auto：跟随主题（浅色用描边、深色用投影）—— 保持原有观感
 * - none / outline / shadow / both：手动指定，浅色深色都按这个来
 */
export type TextRender = 'auto' | 'none' | 'outline' | 'shadow' | 'both';

/**
 * 窗口透明材质（DWM 合成，作用于 main / settings / about 三个窗口）。
 * - none：不加材质，只用自绘底色（默认，最快）
 * - acrylic：亚克力，实时模糊 + 噪点
 * - mica / tabbed：Win11 云母（取壁纸色调，**不**实时模糊窗口后的内容）
 * - blur：经典实时高斯模糊、无色调 —— 最接近"类苹果玻璃"的实时模糊
 */
export type WindowMaterial = 'none' | 'acrylic' | 'mica' | 'tabbed' | 'blur';

export interface ThemeState {
  presetId: string;
  accent: string;
  text: string;
  dim: string;
  opacity: number;
  radius: number;
  reduceMotion: boolean;
  mode: ThemeMode;
  /** 文字渲染方式，默认跟随主题 */
  textRender: TextRender;
  /** 描边/投影强度 0–100，默认 50（对应原来的观感） */
  textStrength: number;
  /** 窗口材质，默认 none */
  material: WindowMaterial;
}

export const THEME_KEY = 'onekey-updater.theme';

/* ============ 深色 / 浅色默认文字 ============ */
const DARK_TEXT = '#f4f7fa';
const DARK_DIM = '#c7d2da';
const LIGHT_TEXT = '#1a1d23';
const LIGHT_DIM = '#5a6270';

function isDefaultText(t: string): boolean {
  return t === DARK_TEXT || t === LIGHT_TEXT;
}
function isDefaultDim(d: string): boolean {
  return d === DARK_DIM || d === LIGHT_DIM;
}

export const PRESETS: Array<{
  id: string;
  name: string;
  accent: string;
  text: string;
  dim: string;
}> = [
  { id: 'teal', name: '青碧', accent: '#2dd4bf', text: DARK_TEXT, dim: DARK_DIM },
  { id: 'azure', name: '天青', accent: '#60a5fa', text: DARK_TEXT, dim: DARK_DIM },
  { id: 'violet', name: '紫藤', accent: '#a78bfa', text: DARK_TEXT, dim: DARK_DIM },
  { id: 'amber', name: '琥珀', accent: '#fbbf24', text: DARK_TEXT, dim: DARK_DIM },
  { id: 'rose', name: '玫瑰', accent: '#fb7185', text: DARK_TEXT, dim: DARK_DIM },
  { id: 'jade', name: '竹青', accent: '#4ade80', text: DARK_TEXT, dim: DARK_DIM },
];

export const DEFAULT_THEME: ThemeState = {
  presetId: 'teal',
  accent: '#2dd4bf',
  text: DARK_TEXT,
  dim: DARK_DIM,
  opacity: 38,
  radius: 18,
  reduceMotion: false,
  mode: 'system',
  textRender: 'auto',
  textStrength: 50,
  material: 'none',
};

/* ============ 系统主题检测 ============ */

export function getSystemMode(): 'light' | 'dark' {
  if (typeof window !== 'undefined' && window.matchMedia) {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  return 'dark';
}

export function resolveMode(mode: ThemeMode): 'light' | 'dark' {
  return mode === 'system' ? getSystemMode() : mode;
}

/**
 * 切换主题模式。如果文字颜色是默认值，自动切换到对应模式的默认文字色。
 */
export function applyMode(theme: ThemeState, mode: ThemeMode): ThemeState {
  const next: ThemeState = { ...theme, mode };
  const resolved = resolveMode(mode);
  if (isDefaultText(theme.text)) {
    next.text = resolved === 'dark' ? DARK_TEXT : LIGHT_TEXT;
  }
  if (isDefaultDim(theme.dim)) {
    next.dim = resolved === 'dark' ? DARK_DIM : LIGHT_DIM;
  }
  return next;
}

/* ============ 工具函数 ============ */

export function hexToRgba(hex: string, alpha: number): string {
  const h = hex.replace('#', '');
  const full =
    h.length === 3
      ? h
          .split('')
          .map((c) => c + c)
          .join('')
      : h;
  const r = parseInt(full.slice(0, 2), 16);
  const g = parseInt(full.slice(2, 4), 16);
  const b = parseInt(full.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

/* ============ 应用主题 ============ */

/**
 * 由"强度"算出描边/投影的 CSS 值，并把最终渲染方式写到 `<html data-text-render>` 上。
 *
 * CSS 只认 `--text-outline` / `--text-shadow` 两个变量和 `data-text-render` 属性，
 * 具体用哪个、多强全在这里决定 —— 这样"文字渲染"才是一个可调项，而不是写死在样式里。
 * 强度 50 = 原来的观感（描边 alpha 0.35 / 投影 alpha 0.5）。
 */
function applyTextRender(theme: ThemeState, isDark: boolean): void {
  const root = document.documentElement;
  const strength = Math.max(0, Math.min(100, theme.textStrength));
  const outA = (0.007 * strength).toFixed(3);
  const shA = (0.01 * strength).toFixed(3);

  const dirs = [
    '-1px -1px', '0 -1px', '1px -1px', '-1px 0', '1px 0', '-1px 1px', '0 1px', '1px 1px',
  ];
  root.style.setProperty(
    '--text-outline',
    dirs.map((d) => `${d} 0 rgba(0, 0, 0, ${outA})`).join(', '),
  );
  root.style.setProperty('--text-shadow', `0 1px 2px rgba(0, 0, 0, ${shA})`);

  // auto = 跟随主题：浅色用描边、深色用投影（即原来的设计）
  const render =
    theme.textRender === 'auto' ? (isDark ? 'shadow' : 'outline') : theme.textRender;
  root.setAttribute('data-text-render', render);
}

/** 材质名 → Tauri 的 WindowEffect；空数组 = 不加材质 */
const MATERIAL_EFFECTS: Record<WindowMaterial, Effect[]> = {
  none: [],
  acrylic: [Effect.Acrylic],
  mica: [Effect.Mica],
  tabbed: [Effect.Tabbed],
  // blur = DWM 经典实时高斯模糊（无色调），最接近"类苹果玻璃"的实时模糊
  blur: [Effect.Blur],
};

/**
 * 施加窗口材质（DWM 合成层的事，走 Tauri 的窗口 API，每个窗口对自己设一次）。
 *
 * 材质属于锦上添花：老系统不支持（Mica/Tabbed 要 Win11）、非 Tauri 环境等一律静默。
 * 通知窗口必须保持纯透明，永远不加材质。
 */
async function applyMaterial(material: WindowMaterial): Promise<void> {
  try {
    const win = getCurrentWindow();
    if (win.label === 'notify') return;
    await win.setEffects({ effects: MATERIAL_EFFECTS[material] ?? [] });
  } catch {
    /* ignore */
  }
}

export function applyTheme(theme: ThemeState): void {
  const s = document.documentElement.style;
  const resolved = resolveMode(theme.mode);
  const isDark = resolved === 'dark';

  s.setProperty('--accent', theme.accent);
  s.setProperty('--accent-soft', hexToRgba(theme.accent, 0.14));
  s.setProperty('--accent-glow', hexToRgba(theme.accent, 0.5));
  s.setProperty('--text', theme.text);
  s.setProperty('--text-dim', hexToRgba(theme.dim, 0.72));
  s.setProperty('--text-faint', hexToRgba(theme.dim, 0.44));
  // 浅色模式的玻璃底本来就亮，桌面壁纸再透上来 60% 的话，深色文字几乎没有落脚点
  // ——"浅色下文字看不清"就是这么来的。给浅色一个下限（最低 62%）。
  const alpha = isDark ? theme.opacity / 100 : Math.max(theme.opacity / 100, 0.62);
  s.setProperty('--bg-alpha', String(alpha));
  s.setProperty('--radius-base', `${theme.radius}px`);

  applyTextRender(theme, isDark);
  void applyMaterial(theme.material);

  // 根据模式设置背景与线条变量
  if (isDark) {
    s.setProperty('--bg-root', `rgba(13, 15, 19, var(--bg-alpha))`);
    s.setProperty('--bg-card', 'rgba(255, 255, 255, 0.038)');
    s.setProperty('--bg-card-hover', 'rgba(255, 255, 255, 0.07)');
    s.setProperty('--bg-sunken', 'rgba(0, 0, 0, 0.2)');
    s.setProperty('--line', 'rgba(255, 255, 255, 0.07)');
    s.setProperty('--line-strong', 'rgba(255, 255, 255, 0.13)');
  } else {
    s.setProperty('--bg-root', `rgba(245, 247, 250, var(--bg-alpha))`);
    s.setProperty('--bg-card', 'rgba(0, 0, 0, 0.04)');
    s.setProperty('--bg-card-hover', 'rgba(0, 0, 0, 0.07)');
    s.setProperty('--bg-sunken', 'rgba(0, 0, 0, 0.06)');
    s.setProperty('--line', 'rgba(0, 0, 0, 0.08)');
    s.setProperty('--line-strong', 'rgba(0, 0, 0, 0.12)');
  }

  document.body.classList.toggle('reduce-motion', theme.reduceMotion);
  document.documentElement.setAttribute('data-theme', resolved);
}

/* ============ 持久化 ============ */

export function saveTheme(theme: ThemeState): void {
  try {
    localStorage.setItem(THEME_KEY, JSON.stringify(theme));
  } catch {
    /* 存储不可用时忽略 */
  }
}

export function loadTheme(): ThemeState {
  try {
    const raw = localStorage.getItem(THEME_KEY);
    if (!raw) return { ...DEFAULT_THEME };
    const parsed = JSON.parse(raw) as Partial<ThemeState>;
    return { ...DEFAULT_THEME, ...parsed };
  } catch {
    return { ...DEFAULT_THEME };
  }
}

/**
 * 监听系统主题变化，当 mode === 'system' 时自动重新应用主题。
 * 返回取消监听的函数。
 */
export function watchSystemTheme(onChange: () => void): () => void {
  if (typeof window === 'undefined' || !window.matchMedia) return () => {};
  const mql = window.matchMedia('(prefers-color-scheme: dark)');
  const handler = () => onChange();
  mql.addEventListener('change', handler);
  return () => mql.removeEventListener('change', handler);
}

/**
 * 窗口效果已在 tauri.conf.json 中静态声明（windowEffects），
 * 运行时再调用 setEffects 会在部分显卡/驱动上触发合成层重建，
 * 导致渲染进程卡死。这里改为空实现，仅保留接口兼容。
 */
export async function applyWindowEffects(): Promise<void> {
  // 窗口毛玻璃效果由 tauri.conf.json 的 windowEffects 静态配置提供，
  // 不需要（也不应该）在运行时重复调用 setEffects。
}
