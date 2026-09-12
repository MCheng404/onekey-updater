/**
 * 应用内通知窗口 (label=notify) 前端逻辑
 *
 * 功能：
 * - 监听 Rust 端 `notify-toast` 事件，追加消息到 toast 栈
 * - 倒计时自动消失，点击立即关闭，鼠标悬停暂停
 * - 按 position 渲染堆叠方向（top-* 自上而下 / bottom-* 自下而上）
 * - 主题同步：监听 `theme-changed` 实时更新
 * - 偏好同步：监听 `notify-prefs-changed` 实时更新
 * - 超过最大堆叠数时自动移除最旧通知
 */

import './styles.css';
import { invoke } from '@tauri-apps/api/core';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import { ICON } from './icons';
import { applyTheme, loadTheme, type ThemeState } from './theme';
import { applyFont, loadFont, watchFont } from './font-settings';

// ============ 类型 ============

interface NotifyPrefs {
  mode: string;
  position: string;
  durationMs: number;
  opacity: number;
  maxStack: number;
}

interface ToastPayload {
  title: string;
  body: string;
  level: string;
  durationMs: number;
  ts: string;
}

interface LiveToast {
  id: number;
  el: HTMLElement;
  remain: number;
  lastTick: number;
  timer: number;
  removed: boolean;
}

// ============ 常量 ============

const LEVEL_COLOR: Record<string, string> = {
  info: 'var(--info)',
  ok: 'var(--ok)',
  warn: 'var(--warn)',
  err: 'var(--err)',
};

/** 级别色调叠加层（覆盖在卡片底色之上） */
const LEVEL_TINT: Record<string, string> = {
  info: 'rgba(96, 165, 250, 0.16)',
  ok: 'rgba(74, 222, 128, 0.18)',
  warn: 'rgba(251, 191, 36, 0.18)',
  err: 'rgba(248, 113, 113, 0.20)',
};

// 与 Rust 侧 notify::default_prefs() 及设置面板默认值保持一致。
// 之前这里写的是 'app'，与后端的 "native" 不一致 —— 一旦 load_notify_prefs
// 调用失败，界面就会莫名其妙地退成"应用内弹窗"。
const DEFAULT_PREFS: NotifyPrefs = {
  mode: 'native',
  position: 'bottom-right',
  durationMs: 4500,
  opacity: 90,
  maxStack: 4,
};

const EXIT_ANIM_MS = 280;
const HIDE_DELAY_MS = 360;
/** 与 styles.css 中 `.toast-root` 的 gap / 上下 padding 保持一致 */
const TOAST_GAP = 8;
const ROOT_PAD = 12;

// ============ 状态 ============

let root: HTMLElement | null = null;
let win: ReturnType<typeof getCurrentWindow> | null = null;
let prefs: NotifyPrefs = { ...DEFAULT_PREFS };
let seq = 0;
const stack: LiveToast[] = [];
const unlisteners: UnlistenFn[] = [];

// ============ 工具函数 ============

function directionFor(pos: string): 'top-down' | 'bottom-up' {
  return pos.startsWith('top') ? 'top-down' : 'bottom-up';
}

function alignFor(pos: string): 'align-left' | 'align-right' | 'align-center' {
  if (pos.endsWith('left')) return 'align-left';
  if (pos.endsWith('right')) return 'align-right';
  return 'align-center';
}

function applyPrefs(): void {
  document.documentElement.style.setProperty('--notif-position', prefs.position);
  document.documentElement.style.setProperty('--notif-opacity', String(prefs.opacity / 100));
  if (root) {
    root.className = `toast-root dir-${directionFor(prefs.position)} ${alignFor(prefs.position)}`;
  }
}

/**
 * 把通知窗口的尺寸/位置同步给 Rust 端。
 *
 * 窗口透明留白同样会拦截鼠标点击，因此窗口必须与内容等高：
 * 这里实测所有 toast 的高度总和 + 间距 + 内边距，交给 Rust 换算物理像素。
 */
function syncWindowSize(): void {
  if (!root) return;
  const toasts = Array.from(root.querySelectorAll<HTMLElement>('.toast'));
  if (toasts.length === 0) return;
  const sum = toasts.reduce((s, el) => s + el.offsetHeight, 0);
  const height = sum + TOAST_GAP * (toasts.length - 1) + ROOT_PAD * 2;
  void invoke('layout_notify', { height }).catch(() => undefined);
}

// ============ Toast DOM 构建 ============

function buildToastEl(p: ToastPayload): HTMLElement {
  const card = document.createElement('div');
  card.className = `toast lv-${p.level}`;

  // 左侧色条
  const stripe = document.createElement('div');
  stripe.className = 'toast-stripe';
  stripe.style.background = LEVEL_COLOR[p.level] ?? LEVEL_COLOR.info;

  // 主体区域
  const main = document.createElement('div');
  main.className = 'toast-main';

  // 头部：标题 + 时间 + 关闭
  const head = document.createElement('div');
  head.className = 'toast-head';

  const title = document.createElement('span');
  title.className = 'toast-title';
  title.textContent = p.title;

  const time = document.createElement('span');
  time.className = 'toast-time';
  time.textContent = p.ts;

  const closeBtn = document.createElement('button');
  closeBtn.className = 'toast-close';
  closeBtn.innerHTML = ICON.x;
  closeBtn.title = '关闭';
  closeBtn.setAttribute('aria-label', '关闭通知');

  head.append(title, time, closeBtn);

  // 正文
  const body = document.createElement('div');
  body.className = 'toast-body';
  body.textContent = p.body;

  main.append(head, body);
  card.append(stripe, main);

  // 多层背景：级别色调叠加 + 不透明底。
  // 第二层必须是 --toast-base 而不是 --bg-card：窗口去掉 acrylic 后没有材质
  // 兜底，用近乎透明的 --bg-card 会让 toast 在浅色桌面上几乎看不见。
  const tint = LEVEL_TINT[p.level] ?? LEVEL_TINT.info;
  // 给 var() 带上兜底值：万一变量没定义，整条 background 声明会被判为无效而
  // 整个失效，卡片就只剩一圈 1px 描边、没有底色 —— 正是"只见描边"的成因。
  const base = 'var(--toast-base, rgba(20, 23, 29, 0.88))';
  card.style.background = `linear-gradient(${tint}, ${tint}), linear-gradient(${base}, ${base})`;

  return card;
}

// ============ Toast 生命周期 ============

function spawnToast(p: ToastPayload): void {
  if (!root) return;

  // 超出最大堆叠数时移除最旧的
  while (stack.length >= prefs.maxStack && stack.length > 0) {
    dismissToast(stack[0].id, /* immediate */ true);
  }

  const id = ++seq;
  const el = buildToastEl(p);
  el.dataset.id = String(id);

  const toast: LiveToast = {
    id,
    el,
    remain: p.durationMs,
    lastTick: Date.now(),
    timer: 0,
    removed: false,
  };

  // 关闭按钮
  el.querySelector('.toast-close')?.addEventListener('click', (e) => {
    e.stopPropagation();
    dismissToast(id);
  });

  // 点击卡片关闭
  el.addEventListener('click', () => dismissToast(id));

  // 悬停暂停 / 离开恢复
  el.addEventListener('mouseenter', () => {
    window.clearTimeout(toast.timer);
    const now = Date.now();
    toast.remain = Math.max(0, toast.remain - (now - toast.lastTick));
    toast.lastTick = now;
  });

  el.addEventListener('mouseleave', () => {
    toast.lastTick = Date.now();
    toast.timer = window.setTimeout(() => dismissToast(id), toast.remain);
  });

  // 启动倒计时
  toast.timer = window.setTimeout(() => dismissToast(id), toast.remain);

  stack.push(toast);
  root.append(el);

  // 确保窗口可见
  void win?.show().catch(() => undefined);

  // 等新节点完成布局后再测量高度，避免读到 0
  requestAnimationFrame(() => syncWindowSize());
}

function dismissToast(id: number, immediate = false): void {
  const idx = stack.findIndex((t) => t.id === id);
  if (idx < 0) return;

  const toast = stack[idx];
  if (toast.removed) return;
  toast.removed = true;

  window.clearTimeout(toast.timer);
  stack.splice(idx, 1);

  if (immediate) {
    toast.el.remove();
    syncWindowSize();
  } else {
    toast.el.classList.add('toast-out');
    window.setTimeout(() => {
      toast.el.remove();
      // 元素真正移除后再收缩窗口，避免动画期间窗口跳变
      syncWindowSize();
    }, EXIT_ANIM_MS);
  }

  // 栈空后延迟隐藏窗口（等待退出动画完成）
  if (stack.length === 0) {
    window.setTimeout(() => {
      if (stack.length === 0) {
        void win?.hide().catch(() => undefined);
      }
    }, HIDE_DELAY_MS);
  }
}

// ============ 事件监听 ============

async function setupListeners(): Promise<void> {
  try {
    const unlisten = await listen<NotifyPrefs>('notify-prefs-changed', (e) => {
      prefs = e.payload;
      applyPrefs();
      // 位置/堆叠方向变化后重新校正窗口
      requestAnimationFrame(() => syncWindowSize());
    });
    unlisteners.push(unlisten);
  } catch (e) {
    console.warn('[notify] notify-prefs-changed 监听失败', e);
  }

  try {
    const unlisten = await listen<ThemeState>('theme-changed', (e) => {
      applyTheme(e.payload);
    });
    unlisteners.push(unlisten);
  } catch (e) {
    console.warn('[notify] theme-changed 监听失败', e);
  }

  try {
    const unlisten = await listen<ToastPayload>('notify-toast', (e) => {
      spawnToast(e.payload);
    });
    unlisteners.push(unlisten);
  } catch (e) {
    console.warn('[notify] notify-toast 监听失败', e);
  }
}

// ============ 初始化 ============

async function init(): Promise<void> {
  // 1. DOM
  try {
    root = document.getElementById('toastRoot');
    if (!root) throw new Error('缺少 #toastRoot');
  } catch (e) {
    console.error('[notify] 初始化失败：DOM 元素缺失', e);
    return;
  }

  // 2. 窗口对象
  try {
    win = getCurrentWindow();
  } catch (e) {
    console.warn('[notify] 非 Tauri 环境', e);
  }

  // 3. 主题
  try {
    applyTheme(loadTheme());
  } catch (e) {
    console.warn('[notify] 主题应用失败', e);
  }

  // 3.5 字体
  try {
    applyFont(loadFont());
    watchFont((f) => applyFont(f));
  } catch (e) {
    console.warn('[notify] 字体应用失败', e);
  }

  // 4. 偏好
  try {
    prefs = await invoke<NotifyPrefs>('load_notify_prefs');
  } catch {
    prefs = { ...DEFAULT_PREFS };
  }
  applyPrefs();

  // 5. 事件
  await setupListeners();

  console.log('[notify] 初始化完成');
}

void init();
