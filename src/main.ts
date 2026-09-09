import './styles.css';
import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { applyTheme, applyWindowEffects, loadTheme, watchSystemTheme, type ThemeState } from './theme';
import { ICON, SOURCE_ICON } from './icons';
import {
  AppSettings,
  SETTINGS_CHANGED_EVENT,
  ignorePermanently,
  ignoreThisVersion,
  isItemIgnored,
  loadSettings,
} from './app-settings';

type SourceKind = 'npm' | 'winget' | 'pip' | 'openclaw';

interface UpdateItem {
  id: string;
  source: SourceKind;
  name: string;
  current: string;
  latest: string;
  tag: string | null;
  pkgId: string | null;
  /** 应用图标 PNG data URL（由 fetch_icons 后置填充） */
  icon?: string | null;
}

interface EnvStatus {
  npm: boolean;
  winget: boolean;
  pip: boolean;
  openclaw: boolean;
  isAdmin: boolean;
}

interface LogLine {
  level: string;
  text: string;
  at: string;
}

interface Progress {
  done: number;
  total: number;
  currentId: string;
  currentName: string;
  status: string;
}

interface ItemResult {
  id: string;
  name: string;
  ok: boolean;
}

const SOURCE_LABEL: Record<SourceKind, string> = {
  npm: 'npm 全局包',
  winget: 'winget 软件',
  pip: 'pip 包',
  openclaw: 'OpenClaw',
};
const SOURCE_ORDER: SourceKind[] = ['npm', 'winget', 'pip', 'openclaw'];
const MAX_LOG_LINES = 800;

const LOG_BADGE: Record<string, string> = {
  ok: 'OK',
  err: 'ERR',
  warn: 'WARN',
  info: 'INFO',
  cmd: 'OUT',
};

let items: UpdateItem[] = [];
let checked = new Set<string>();
let busy = false;
let scanned = false;
let theme: ThemeState = loadTheme();
let appSettings: AppSettings = loadSettings();

function el<T extends HTMLElement>(id: string): T {
  const node = document.getElementById(id);
  if (!node) throw new Error(`缺少 DOM 节点 #${id}`);
  return node as T;
}

/* 运行时引用：在 init() 中安全赋值，避免顶层异常阻断整个脚本 */
let logBody: HTMLElement | null = null;
let appWindow: ReturnType<typeof getCurrentWindow> | null = null;

/* ============ 日志卡片 ============ */
function appendLog(line: LogLine) {
  if (!logBody) return;
  const card = document.createElement('div');
  card.className = `log-card lv-${line.level}`;

  const time = document.createElement('span');
  time.className = 'log-time';
  time.textContent = line.at;

  const badge = document.createElement('span');
  badge.className = 'log-badge';
  badge.textContent = LOG_BADGE[line.level] ?? 'LOG';

  const text = document.createElement('span');
  text.className = 'log-text';
  text.textContent = line.text;

  card.append(time, badge, text);
  logBody.append(card);

  while (logBody.childElementCount > MAX_LOG_LINES) {
    logBody.firstElementChild?.remove();
  }
  logBody.scrollTop = logBody.scrollHeight;
}

/* ============ 环境状态 ============ */
function renderEnv(env: EnvStatus) {
  const box = el('envChips');
  box.replaceChildren();

  const pairs: Array<[string, boolean]> = [
    ['npm', env.npm],
    ['winget', env.winget],
    ['pip', env.pip],
    ['openclaw', env.openclaw],
  ];

  for (const [name, ok] of pairs) {
    const chip = document.createElement('span');
    chip.className = `chip ${ok ? 'on' : 'off'}`;
    chip.textContent = `${name} ${ok ? '已就绪' : '缺失'}`;
    box.append(chip);
  }

  el('adminBadge').hidden = !env.isAdmin;
}

/* ============ 列表渲染 ============ */
function renderList() {
  const scroll = el('listScroll');
  const empty = el('listEmpty');
  scroll.replaceChildren();
  verCache = [];

  if (!scanned) {
    empty.hidden = false;
    empty.textContent = '点击「检查更新」开始扫描';
    return;
  }

  // 过滤被忽略的项（忽略此版本 / 永久忽略）
  const visible = items.filter((i) => !isItemIgnored(i.id, i.latest, appSettings));
  const ignoredCount = items.length - visible.length;

  if (visible.length === 0) {
    empty.hidden = false;
    if (items.length === 0) {
      empty.textContent = '所有软件都是最新版本';
    } else {
      empty.textContent = `全部 ${items.length} 项已被忽略（可在设置中管理）`;
    }
    return;
  }

  empty.hidden = true;

  // 使用 DocumentFragment 批量插入，减少重排
  const frag = document.createDocumentFragment();
  let globalIndex = 0;
  for (const source of SOURCE_ORDER) {
    const group = visible.filter((i) => i.source === source);
    if (group.length === 0) continue;
    frag.append(buildGroup(source, group, globalIndex));
    globalIndex += group.length;
  }
  scroll.append(frag);

  if (ignoredCount > 0) {
    el('statusRight').textContent = `已忽略 ${ignoredCount} 项`;
  }

  requestAnimationFrame(cacheVerNodes);
}

function buildGroup(source: SourceKind, group: UpdateItem[], startIndex: number): HTMLElement {
  const wrap = document.createElement('div');
  wrap.className = 'group';

  const head = document.createElement('div');
  head.className = 'group-head';

  const cb = document.createElement('input');
  cb.type = 'checkbox';
  cb.dataset.role = 'group';
  cb.dataset.source = source;
  head.append(makeCheckbox(cb));

  const icon = document.createElement('span');
  icon.className = 'group-icon';
  icon.innerHTML = SOURCE_ICON[source] ?? ICON.package;

  const title = document.createElement('span');
  title.className = 'group-title';
  title.textContent = SOURCE_LABEL[source];

  const count = document.createElement('span');
  count.className = 'group-count';
  count.textContent = String(group.length);

  head.append(icon, title, count);
  wrap.append(head);

  const body = document.createElement('div');
  // 使用 DocumentFragment 批量插入子项
  const itemFrag = document.createDocumentFragment();
  group.forEach((item, i) => {
    const el = buildItem(item);
    // 交错进入动画：每项延迟 30ms
    el.style.animationDelay = `${(startIndex + i) * 30}ms`;
    itemFrag.append(el);
  });
  body.append(itemFrag);
  wrap.append(body);

  return wrap;
}

function buildItem(item: UpdateItem): HTMLElement {
  const row = document.createElement('div');
  row.className = `item${checked.has(item.id) ? ' checked' : ''}`;

  const cb = document.createElement('input');
  cb.type = 'checkbox';
  cb.dataset.role = 'item';
  cb.dataset.id = item.id;
  cb.checked = checked.has(item.id);

  // 应用图标：有 PNG 用 PNG，否则退化到矢量
  const icon = document.createElement('div');
  icon.className = 'item-icon';
  if (item.icon) {
    const img = document.createElement('img');
    img.src = item.icon;
    img.alt = item.name;
    img.draggable = false;
    icon.append(img);
  } else {
    icon.classList.add('item-icon-fallback');
    const fallbackSvg = SOURCE_ICON[item.source] ?? ICON.package;
    icon.innerHTML = fallbackSvg;
    const svg = icon.querySelector('svg');
    if (svg) {
      svg.classList.add('ico');
      svg.setAttribute('width', '17');
      svg.setAttribute('height', '17');
    }
  }

  const main = document.createElement('div');
  main.className = 'item-main';

  const name = document.createElement('div');
  name.className = 'item-name';
  name.textContent = item.name;

  const meta = document.createElement('div');
  meta.className = 'item-meta';
  meta.textContent = item.pkgId ?? SOURCE_LABEL[item.source];

  main.append(name, meta);

  const ver = document.createElement('div');
  ver.className = 'item-ver';

  const cur = document.createElement('span');
  cur.className = 'v-cur';
  cur.textContent = item.current;

  const arrow = document.createElement('span');
  arrow.className = 'v-arrow';
  arrow.textContent = '→';

  const next = document.createElement('span');
  next.className = 'v-new';
  next.textContent = item.latest;

  ver.append(cur, arrow, next);

  if (item.tag && item.tag !== 'latest') {
    const tag = document.createElement('span');
    tag.className = 'tag';
    tag.textContent = item.tag;
    ver.append(tag);
  }

  // 忽略操作：忽略此版本 / 永久忽略
  const actions = document.createElement('div');
  actions.className = 'item-actions';

  const btnIgnoreVer = document.createElement('button');
  btnIgnoreVer.className = 'item-action-btn';
  btnIgnoreVer.title = '忽略此版本（版本更新后自动恢复）';
  btnIgnoreVer.innerHTML = ICON.eyeOff;
  btnIgnoreVer.addEventListener('click', (e) => {
    e.stopPropagation();
    appSettings = ignoreThisVersion(item.id, item.latest, appSettings);
    checked.delete(item.id);
    renderList();
    refreshActions();
  });

  const btnIgnoreForever = document.createElement('button');
  btnIgnoreForever.className = 'item-action-btn danger';
  btnIgnoreForever.title = '永久忽略（可在设置中移除）';
  btnIgnoreForever.innerHTML = ICON.ban;
  btnIgnoreForever.addEventListener('click', (e) => {
    e.stopPropagation();
    appSettings = ignorePermanently(item.id, appSettings);
    checked.delete(item.id);
    renderList();
    refreshActions();
  });

  actions.append(btnIgnoreVer, btnIgnoreForever);

  row.append(makeCheckbox(cb), icon, main, ver, actions);
  return row;
}

function makeCheckbox(input: HTMLInputElement): HTMLElement {
  const label = document.createElement('label');
  label.className = 'cb';
  const box = document.createElement('span');
  box.className = 'box';
  label.append(input, box);
  return label;
}

/* ============ 勾选同步 ============ */
function syncGroupCheckbox(source: SourceKind) {
  const group = items.filter((i) => i.source === source);
  const cb = document.querySelector<HTMLInputElement>(
    `input[data-role="group"][data-source="${source}"]`
  );
  if (!cb) return;
  const sel = group.filter((i) => checked.has(i.id)).length;
  cb.checked = group.length > 0 && sel === group.length;
  cb.indeterminate = sel > 0 && sel < group.length;
}

function syncItemsOfGroup(source: SourceKind) {
  document.querySelectorAll<HTMLInputElement>('input[data-role="item"]').forEach((input) => {
    const id = input.dataset.id;
    if (!id) return;
    const item = items.find((i) => i.id === id);
    if (!item || item.source !== source) return;
    const on = checked.has(id);
    input.checked = on;
    input.closest('.item')?.classList.toggle('checked', on);
  });
}

el('listScroll').addEventListener('change', (e) => {
  const target = e.target as HTMLInputElement;

  if (target.dataset.role === 'item') {
    const id = target.dataset.id;
    if (!id) return;
    if (target.checked) checked.add(id);
    else checked.delete(id);
    target.closest('.item')?.classList.toggle('checked', target.checked);
    const source = items.find((i) => i.id === id)?.source;
    if (source) syncGroupCheckbox(source);
    refreshActions();
    return;
  }

  if (target.dataset.role === 'group') {
    const source = target.dataset.source as SourceKind | undefined;
    if (!source) return;
    const group = items.filter((i) => i.source === source);
    if (target.checked) group.forEach((i) => checked.add(i.id));
    else group.forEach((i) => checked.delete(i.id));
    syncItemsOfGroup(source);
    syncGroupCheckbox(source);
    refreshActions();
  }
});

/* ============ 按钮状态 ============ */
function refreshActions() {
  const btnCheck = el<HTMLButtonElement>('btnCheck');
  const btnSel = el<HTMLButtonElement>('btnUpdateSelected');
  const btnAll = el<HTMLButtonElement>('btnUpdateAll');

  btnCheck.disabled = busy;
  btnSel.disabled = busy || checked.size === 0;
  btnAll.disabled = busy || items.length === 0;
  btnSel.textContent = `更新选中 · ${checked.size}`;

  el('checkSpinner').hidden = !busy;
  el('btnCheckLabel').textContent = busy ? '检查中' : '检查更新';
}

function setBusy(value: boolean) {
  busy = value;
  refreshActions();
  if (!value) el('progressWrap').hidden = true;
}

/* ============ 进度 ============ */
function onProgress(p: Progress) {
  const wrap = el('progressWrap');
  const fill = el('progressFill');
  const text = el('progressText');

  if (p.status === 'done') {
    fill.style.width = '100%';
    text.textContent = `${p.total} / ${p.total}`;
    return;
  }

  wrap.hidden = false;
  const pct = p.total > 0 ? Math.round((p.done / p.total) * 100) : 0;
  fill.style.width = `${pct}%`;
  text.textContent = `${p.done} / ${p.total} · ${p.currentName}`;
}

/* ============ 主流程 ============ */
async function doCheck() {
  if (busy) return;
  setBusy(true);
  el('statusLeft').textContent = '正在检查更新…';
  el('statusRight').textContent = '';

  items = [];
  checked = new Set<string>();
  renderList();

  try {
    const found = await invoke<UpdateItem[]>('check_updates');
    items = found;
    found.forEach((i) => checked.add(i.id));
    scanned = true;
    renderList();
    el('statusLeft').textContent = `检查完成 · 共 ${items.length} 项可更新`;

    // 异步拉取应用图标（首次会触发 PowerShell 扫描注册表，5~10s）
    try {
      const icons = await invoke<Record<string, string>>('fetch_icons', { items: found });
      if (icons && Object.keys(icons).length > 0) {
        for (const item of items) {
          if (icons[item.id]) item.icon = icons[item.id];
        }
        renderList();
      }
    } catch (e) {
      el('statusRight').textContent = `图标加载失败：${String(e).slice(0, 40)}`;
    }
  } catch (e) {
    scanned = true;
    renderList();
    el('statusLeft').textContent = `检查失败：${String(e)}`;
  } finally {
    setBusy(false);
  }
}

async function doUpdate(target: UpdateItem[]) {
  if (busy || target.length === 0) return;
  setBusy(true);
  el('statusLeft').textContent = `正在更新 ${target.length} 项…`;

  try {
    const results = await invoke<ItemResult[]>('start_update', { items: target });
    const ok = results.filter((r) => r.ok).length;
    const bad = results.length - ok;
    el('statusLeft').textContent = '更新完成';
    el('statusRight').textContent = `成功 ${ok} · 失败 ${bad}`;

    // 通知：根据成功/失败送不同 level 的 toast
    const level = bad === 0 ? 'ok' : ok === 0 ? 'err' : 'warn';
    void invoke('send_notification', {
      title: '更新完成',
      body: `${ok} 项成功 · ${bad} 项失败`,
      level,
    }).catch(() => undefined);
  } catch (e) {
    el('statusLeft').textContent = `更新失败：${String(e)}`;
    void invoke('send_notification', {
      title: '更新失败',
      body: String(e).slice(0, 80),
      level: 'err',
    }).catch(() => undefined);
  } finally {
    setBusy(false);
    void doCheck();
  }
}

/* ============ 图标注入 ============ */
function injectButtonIcons(): void {
  el('btnCheck').insertAdjacentHTML('afterbegin', ICON.refresh);
  el('btnUpdateAll').insertAdjacentHTML('afterbegin', ICON.download);
}

/* ============ 版本号距离感应放大 ============ */
const VER_BASE_SIZE = 11.5;
const VER_MAX_SIZE = 15.5;
const VER_RADIUS = 150;

// 缓存的版本号元素及其屏幕中心坐标（避免每次 mousemove 强制重排）
type VerNode = { el: HTMLElement; cx: number; cy: number };
let verCache: VerNode[] = [];

function cacheVerNodes(): void {
  verCache = Array.from(document.querySelectorAll<HTMLElement>('.item-ver')).map((el) => {
    const r = el.getBoundingClientRect();
    return { el, cx: r.left + r.width / 2, cy: r.top + r.height / 2 };
  });
}

function setupProximityZoom(): void {
  const container = el('listScroll');
  let frame = 0;
  let px = -9999;
  let py = -9999;

  const paint = () => {
    frame = 0;
    for (const { el, cx, cy } of verCache) {
      const dx = px - cx;
      const dy = py - cy;
      const dist = Math.sqrt(dx * dx + dy * dy);

      // smoothstep：靠近时变化柔和，远离时快速回落
      const raw = Math.max(0, Math.min(1, 1 - dist / VER_RADIUS));
      const t = raw * raw * (3 - 2 * raw);

      el.style.fontSize = `${(VER_BASE_SIZE + (VER_MAX_SIZE - VER_BASE_SIZE) * t).toFixed(2)}px`;
      el.style.opacity = (0.7 + 0.3 * t).toFixed(3);
    }
  };

  container.addEventListener('mousemove', (e) => {
    px = e.clientX;
    py = e.clientY;
    if (!frame) frame = requestAnimationFrame(paint);
  });
  container.addEventListener('mouseleave', () => {
    px = -9999;
    py = -9999;
    if (!frame) frame = requestAnimationFrame(paint);
  });

  // 滚动 / 窗口尺寸变化 / 列表重建时重新缓存坐标
  container.addEventListener('scroll', cacheVerNodes, { passive: true });
  window.addEventListener('resize', cacheVerNodes);
}

/* ============ 初始化 ============ */
async function init() {
  /* 1. DOM 引用 */
  try {
    logBody = el('logBody');
  } catch (e) {
    console.error('[init] 获取日志区域失败', e);
  }

  /* 2. Tauri 窗口对象（非 Tauri 环境下跳过窗口控制） */
  try {
    appWindow = getCurrentWindow();
  } catch (e) {
    console.warn('[init] 非 Tauri 环境，窗口控制不可用', e);
  }

  /* 3. 窗口控制按钮 */
  if (appWindow) {
    try {
      el('btnMin').addEventListener('click', () => void appWindow!.minimize());
      el('btnMax').addEventListener('click', () => void appWindow!.toggleMaximize());
      el('btnClose').addEventListener('click', () => void appWindow!.close());
    } catch (e) {
      console.warn('[init] 窗口控制按钮绑定失败', e);
    }
  }

  /* 4. 设置按钮 */
  try {
    el('btnSettings').addEventListener('click', () => {
      void invoke('open_settings').catch((e) => {
        el('statusLeft').textContent = `无法打开设置：${String(e)}`;
      });
    });
  } catch (e) {
    console.warn('[init] 设置按钮绑定失败', e);
  }

  /* 5. 标题栏拖拽 */
  if (appWindow) {
    try {
      const titlebar = el('titlebar');
      titlebar.addEventListener('mousedown', (e) => {
        const t = e.target as HTMLElement;
        if (t.closest('.tb-btn')) return;
        if (e.buttons === 1) void appWindow!.startDragging();
      });
      titlebar.addEventListener('dblclick', (e) => {
        const t = e.target as HTMLElement;
        if (t.closest('.tb-btn')) return;
        void appWindow!.toggleMaximize();
      });
    } catch (e) {
      console.warn('[init] 标题栏拖拽绑定失败', e);
    }
  }

  /* 6. 主题与视觉效果 */
  try {
    applyTheme(theme);
    await applyWindowEffects();
    // 跟随系统主题变化时实时切换
    watchSystemTheme(() => {
      if (theme.mode === 'system') {
        applyTheme(theme);
      }
    });
  } catch (e) {
    console.warn('[init] 主题应用失败', e);
  }

  /* 7. 图标注入与距离感应放大 */
  try { injectButtonIcons(); } catch (e) { console.warn('[init] 图标注入失败', e); }
  try { setupProximityZoom(); } catch (e) { console.warn('[init] 距离感应初始化失败', e); }

  /* 8. 事件监听（单个失败不影响其他） */
  try { await listen<LogLine>('update-log', (e) => appendLog(e.payload)); } catch (e) { console.warn('[init] update-log 监听失败', e); }
  try { await listen<Progress>('update-progress', (e) => onProgress(e.payload)); } catch (e) { console.warn('[init] update-progress 监听失败', e); }
  try {
    await listen<ThemeState>('theme-changed', (e) => {
      theme = e.payload;
      applyTheme(theme);
    });
  } catch (e) { console.warn('[init] theme-changed 监听失败', e); }

  // 设置窗口修改忽略列表/自动检查后同步到主窗口
  try {
    await listen<AppSettings>(SETTINGS_CHANGED_EVENT, (e) => {
      appSettings = e.payload;
      renderList();
      refreshActions();
    });
  } catch (e) { console.warn('[init] settings-changed 监听失败', e); }

  /* 9. 主操作按钮 */
  try {
    el('btnCheck').addEventListener('click', () => void doCheck());
    el('btnUpdateSelected').addEventListener('click', () => {
      void doUpdate(items.filter((i) => checked.has(i.id)));
    });
    el('btnUpdateAll').addEventListener('click', () => void doUpdate(items));
    el('btnClearLog').addEventListener('click', () => logBody?.replaceChildren());
  } catch (e) {
    console.error('[init] 主按钮绑定失败', e);
  }

  /* 10. 环境探测（不阻塞主界面） */
  try {
    const env = await invoke<EnvStatus>('detect_env');
    renderEnv(env);
  } catch {
    /* 环境探测失败不阻塞主界面 */
  }

  /* 11. 初始渲染 */
  try {
    renderList();
    refreshActions();
  } catch (e) {
    console.error('[init] 初始渲染失败', e);
  }

  /* 12. 启动时自动检查更新（用户可在设置中开启） */
  if (appSettings.autoCheck) {
    setTimeout(() => void doCheck(), 500);
  }
}

void init();
