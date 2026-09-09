/**
 * 主题状态与 CSS 变量应用，主窗口与设置窗口共用。
 * 支持浅色 / 深色 / 跟随系统三种模式。
 */

export type ThemeMode = 'light' | 'dark' | 'system';

export interface ThemeState {
  presetId: string;
  accent: string;
  text: string;
  dim: string;
  opacity: number;
  radius: number;
  reduceMotion: boolean;
  mode: ThemeMode;
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
  s.setProperty('--bg-alpha', String(theme.opacity / 100));
  s.setProperty('--radius-base', `${theme.radius}px`);

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
