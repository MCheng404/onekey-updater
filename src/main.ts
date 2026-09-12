import './styles.css';
import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { applyTheme, applyWindowEffects, loadTheme, watchSystemTheme, type ThemeState } from './theme';
import { applyFont, loadFont, watchFont } from './font-settings';
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
/** 最近一次渲染中被忽略（隐藏）的项数，由 renderList 更新 */
let ignoredCount = 0;
/** 上一次更新失败的项 id，用于在列表里给出「查看日志」入口 */
let failedIds = new Set<string>();

/**
 * 当前"未被忽略"的项 —— 也就是列表里真正显示出来的那些。
 *
 * 注意 `items` 是**全量**（含黑名单项），只有 renderList 显示时才过滤。
 * 所以凡是"按界面上的东西操作"的地方（更新选中 / 更新全部 / 计数）
 * 都必须走这里，绝不能直接用 items，否则会把黑名单项也一起更新掉。
 */
function visibleItems(): UpdateItem[] {
  return items.filter((i) => !isItemIgnored(i.id, i.latest, appSettings));
}

/**
 * 把选中集合收敛到可见项。
 * 黑名单是在设置窗口里改的，改完 appSettings 变了但 checked 不会自动更新，
 * 于是"更新选中 · N"会把不在列表里的项也算进去。
 */
function pruneChecked(): void {
  const visible = new Set(visibleItems().map((i) => i.id));
  for (const id of Array.from(checked)) {
    if (!visible.has(id)) checked.delete(id);
  }
}
let theme: ThemeState = loadTheme();
let appSettings: AppSettings = loadSettings();
/** 是否为开机自启动模式（--autostart 参数） */
let isAutostart = false;

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

/**
 * 「跳转到日志」：日志面板常驻在主界面，所以跳转 = 滚到最新一行 + 闪一下高亮，
 * 给用户明确的落点反馈。
 */
function jumpToLog(): void {
  // 存成局部常量：模块级 let 在闭包里不会被收窄
  const pane = logBody;
  if (!pane) return;
  pane.scrollTop = pane.scrollHeight;
  pane.classList.remove('log-flash');
  void pane.offsetWidth; // 强制重排，让动画能重新触发
  pane.classList.add('log-flash');
  window.setTimeout(() => pane.classList.remove('log-flash'), 1400);
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

/**
 * 显示列表空状态：主题色图标 + 一句说明。
 * spin=true 时图标改为自转，用于"扫描中"这类进行态。
 */
function showEmpty(text: string, icon: string, spin = false): void {
  el('listEmpty').hidden = false;
  const iconEl = el('emptyIcon');
  iconEl.innerHTML = icon;
  iconEl.classList.toggle('spin', spin);
  el('emptyText').textContent = text;
}

function renderList() {
  const scroll = el('listScroll');
  scroll.replaceChildren();
  verCache = [];

  if (!scanned) {
    // 扫描中必须与"全部最新"区分开：之前 items 被清空但 scanned 仍为 true，
    // 结果扫描过程中会误导性地显示"所有软件都是最新版本"。
    if (busy) {
      showEmpty('正在扫描可更新项…', ICON.refresh, true);
    } else {
      showEmpty('点击「检查更新」开始扫描', ICON.search);
    }
    return;
  }

  // 过滤被忽略的项（忽略此版本 / 永久忽略）
  const visible = items.filter((i) => !isItemIgnored(i.id, i.latest, appSettings));
  ignoredCount = items.length - visible.length;

  if (visible.length === 0) {
    if (items.length === 0) {
      showEmpty('所有软件都是最新版本', ICON.checkCircle);
    } else {
      showEmpty(
        `全部 ${items.length} 项已被忽略，可在设置中管理`,
        ICON.eyeOffBig
      );
    }
    return;
  }

  el('listEmpty').hidden = true;

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

  // 注意：这里不再写 statusRight。状态栏由 doCheck / doUpdate 显式管理，
  // 否则列表重绘会覆盖掉「成功 N · 失败 M」等更新结果。
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

  row.append(makeCheckbox(cb), icon, main, ver);

  // 上次更新失败的项：常驻显示「查看日志」入口（不能放进 hover 才出现的操作区）
  if (failedIds.has(item.id)) {
    const failBtn = document.createElement('button');
    failBtn.className = 'item-fail';
    failBtn.type = 'button';
    failBtn.title = '更新失败，点击查看运行日志';
    failBtn.innerHTML = `${ICON.alert}<span>查看日志</span>`;
    failBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      jumpToLog();
    });
    row.append(failBtn);
  }

  row.append(actions);
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

/**
 * 列表勾选事件代理。
 * 改为在 init() 内调用，避免模块顶层直接取 DOM 在异常时序下中断整个脚本。
 */
function bindListEvents(): void {
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
}

/* ============ 按钮状态 ============ */
function refreshActions() {
  const btnCheck = el<HTMLButtonElement>('btnCheck');
  const btnSel = el<HTMLButtonElement>('btnUpdateSelected');
  const btnAll = el<HTMLButtonElement>('btnUpdateAll');

  btnCheck.disabled = busy;
  // 计数只统计"界面上看得见"的项，黑名单里的不能算进去
  const visible = visibleItems();
  const selCount = visible.filter((i) => checked.has(i.id)).length;
  btnSel.disabled = busy || selCount === 0;
  btnAll.disabled = busy || visible.length === 0;
  btnSel.textContent = `更新选中 · ${selCount}`;

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
  failedIds = new Set<string>();
  scanned = false; // 进入"扫描中"空状态
  renderList();

  try {
    const found = await invoke<UpdateItem[]>('check_updates');
    items = found;
    // 只默认勾选「未被忽略」的项。被忽略的项不会出现在列表里，
    // 若一并勾选会让「更新选中 · N」计数虚高，并误把被忽略项也更新掉。
    checked = new Set(
      found
        .filter((i) => !isItemIgnored(i.id, i.latest, appSettings))
        .map((i) => i.id),
    );
    scanned = true;
    renderList();
    el('statusLeft').textContent = `检查完成 · 共 ${items.length} 项可更新`;
    el('statusRight').textContent = ignoredCount > 0 ? `已忽略 ${ignoredCount} 项` : '';

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
    // 检查失败时给明确的错误态，而不是让空列表谎报"全部最新"
    scanned = true;
    items = [];
    checked = new Set<string>();
    el('listScroll').replaceChildren();
    showEmpty(`检查失败：${String(e).slice(0, 90)}`, ICON.alert);
    el('statusLeft').textContent = '检查失败';
    refreshActions();
  } finally {
    setBusy(false);
  }
}

async function doUpdate(target: UpdateItem[]) {
  if (busy || target.length === 0) return;
  setBusy(true);
  el('statusLeft').textContent = `正在更新 ${target.length} 项…`;
  el('statusRight').textContent = '';

  try {
    const results = await invoke<ItemResult[]>('start_update', { items: target });
    const ok = results.filter((r) => r.ok).length;
    const bad = results.length - ok;

    // 记录失败项：它们会留在列表里并带上「查看日志」入口
    failedIds = new Set(results.filter((r) => !r.ok).map((r) => r.id));

    // 已成功的项从列表中移除，让「成功 N · 失败 M」保持可见
    // （旧实现会在 finally 里立即 doCheck，把结果统计瞬间覆盖掉）
    if (ok > 0) {
      const doneIds = new Set(results.filter((r) => r.ok).map((r) => r.id));
      items = items.filter((i) => !doneIds.has(i.id));
      doneIds.forEach((id) => checked.delete(id));
      renderList();
      refreshActions();
    }

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
      // 基线透明度不能太低，否则远离光标时版本号几乎不可读
      el.style.opacity = (0.86 + 0.14 * t).toFixed(3);
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

  /* 6.5 字体（自定义字体 / 字号缩放） */
  try {
    applyFont(loadFont());
    watchFont((f) => applyFont(f));
  } catch (e) {
    console.warn('[init] 字体应用失败', e);
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
      // 黑名单可能刚被改过：必须先收敛选中集，否则"更新选中 · N"
      // 会继续统计那些已经不在列表里的项
      pruneChecked();
      renderList();
      refreshActions();
    });
  } catch (e) { console.warn('[init] settings-changed 监听失败', e); }

  /* 9. 主操作按钮 */
  try {
    bindListEvents();
    el('btnCheck').addEventListener('click', () => void doCheck());
    el('btnUpdateSelected').addEventListener('click', () => {
      void doUpdate(visibleItems().filter((i) => checked.has(i.id)));
    });
    // 「更新全部」更必须是可见项：items 里含黑名单项，直接用会连被忽略的一起更新
    el('btnUpdateAll').addEventListener('click', () => void doUpdate(visibleItems()));
    el('btnClearLog').addEventListener('click', () => logBody?.replaceChildren());
  } catch (e) {
    console.error('[init] 主按钮绑定失败', e);
  }

  /* 10. 启动模式检测（开机自启动 vs 正常启动） */
  try {
    isAutostart = await invoke<boolean>('get_startup_mode');
  } catch {
    isAutostart = false;
  }

  /* 11. 环境探测（不阻塞主界面） */
  try {
    const env = await invoke<EnvStatus>('detect_env');
    renderEnv(env);
  } catch {
    /* 环境探测失败不阻塞主界面 */
  }

  /* 12. 初始渲染 */
  try {
    renderList();
    refreshActions();
  } catch (e) {
    console.error('[init] 初始渲染失败', e);
  }

  /* 13. 自动检查更新
   * - 开机自启动模式：无论 autoCheck 设置如何，都静默检查更新
   *   有更新 → 显示主窗口；无更新 → 保持后台静默运行
   * - 正常启动模式：仅当用户开启 autoCheck 时自动检查
   */
  if (isAutostart) {
    // 开机自启动：延迟自定义秒数后静默检查，默认 10 秒
    // 用于等待网络（如校园网认证）就绪后再检查更新
    const delayMs = Math.max(0, (appSettings.autostartDelay ?? 10)) * 1000;
    setTimeout(() => void autostartCheck(), delayMs);
  } else if (appSettings.autoCheck) {
    setTimeout(() => void doCheck(), 500);
  }
}

/**
 * 开机自启动模式下的静默检查：
 * 有更新 → 显示主窗口并发送通知；无更新 → 保持后台静默运行
 */
async function autostartCheck() {
  try {
    const result = await invoke<UpdateItem[]>('check_updates');
    // 与手动「检查更新」保持一致：items 保留全量，过滤交给 renderList，
    // 这样状态栏才能算出"已忽略 N 项"
    items = result || [];
    // 默认勾选全部可见项（黑名单里的不勾）
    checked = new Set(visibleItems().map((i) => i.id));
    scanned = true;
    renderList();
    refreshActions();

    // 全被忽略时不该弹窗打扰 —— 所以这里必须用可见项判断
    if (visibleItems().length > 0) {
      // 有更新：显示主窗口 + 发送通知
      if (appWindow) {
        await appWindow.show();
        await appWindow.setFocus();
      }
      void invoke('send_notification', {
        title: '发现可更新项',
        body: `共 ${items.length} 项可更新，点击查看详情`,
        level: 'info',
      }).catch(() => undefined);
    } else {
      // 无更新：保持后台静默运行，不显示窗口
      // 可选：发送一条"无更新"通知，但为了不打扰用户，这里静默处理
      console.log('[autostart] 无更新，保持后台静默运行');
    }
  } catch (e) {
    console.warn('[autostart] 静默检查失败', e);
    // 检查失败时也显示窗口，让用户知道出了问题
    if (appWindow) {
      await appWindow.show();
    }
  }
}

void init();
