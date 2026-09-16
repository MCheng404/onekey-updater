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
import { applyI18n, getLang, t, watchLang } from './i18n';

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

/** 分组标题。随语言变化，所以每次现算，不能做成静态常量 */
function sourceLabel(kind: SourceKind): string {
  return t(`group.${kind}`);
}
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
/** 上一次扫描更新的错误信息；非空时列表显示错误空状态（而不是谎报"全部最新"） */
let lastCheckError: string | null = null;

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

/* 状态栏内容以 key + 插值保存，语言切换时可原样重建 */
let statusLeftKey = 'status.idle';
let statusLeftVars: Record<string, string | number> | undefined;
let statusRightKey: string | null = null;
let statusRightVars: Record<string, string | number> | undefined;

function setStatusLeft(key: string, vars?: Record<string, string | number>): void {
  statusLeftKey = key;
  statusLeftVars = vars;
  el('statusLeft').textContent = t(key, vars);
}

function setStatusRight(key: string | null, vars?: Record<string, string | number>): void {
  statusRightKey = key;
  statusRightVars = vars;
  el('statusRight').textContent = key ? t(key, vars) : '';
}

/** 按当前语言重建状态栏（语言切换时用） */
function renderStatus(): void {
  el('statusLeft').textContent = t(statusLeftKey, statusLeftVars);
  el('statusRight').textContent = statusRightKey ? t(statusRightKey, statusRightVars) : '';
}

function el<T extends HTMLElement>(id: string): T {
  const node = document.getElementById(id);
  if (!node) throw new Error(`缺少 DOM 节点 #${id}`);
  return node as T;
}

/** 播放一次性反馈动画：先移除类并强制重排，保证连续触发时动画能重新开始 */
/**
 * 一次性反馈动画的计时器：动画放完就把类摘掉。
 *
 * 为什么要摘：动画只在"类被新加上"时触发。如果类常驻，那么在**减弱动画**期间
 * 触发的这些类（动画被 `animation: none` 干掉、却仍然留在元素上）会一直挂着 ——
 * 用户以后一关掉"减弱动画"，这些动画就会毫无缘由地集体重播一遍。
 */
const pulseTimers = new WeakMap<HTMLElement, number>();

function pulse(node: HTMLElement, cls: string): void {
  node.classList.remove(cls);
  void node.offsetWidth;
  node.classList.add(cls);

  const prev = pulseTimers.get(node);
  if (prev) window.clearTimeout(prev);
  // 比最长的反馈动画（0.34s）留足余量
  pulseTimers.set(
    node,
    window.setTimeout(() => {
      node.classList.remove(cls);
      pulseTimers.delete(node);
    }, 600),
  );
}

/** 复选框点选反馈：给 .cb 加一次性动画类（勾/横杠弹入 + 方框微弹） */
function popCheckbox(input: HTMLInputElement): void {
  const label = input.closest<HTMLElement>('.cb');
  if (label) pulse(label, 'box-pop');
}

/* 运行时引用：在 init() 中安全赋值，避免顶层异常阻断整个脚本 */
let logBody: HTMLElement | null = null;
let appWindow: ReturnType<typeof getCurrentWindow> | null = null;

/* ============ 日志卡片 ============ */
/** 待写入的日志（一帧内合并写入，避免每条日志都触发一次重排） */
let logQueue: LogLine[] = [];
let logFlushScheduled = false;

/**
 * 追加日志：先入队，合并到下一帧一次性写 DOM。
 * 更新过程中日志可能密集到达，逐条 append + 读 scrollHeight 会造成频繁重排。
 */
function appendLog(line: LogLine) {
  logQueue.push(line);
  if (logFlushScheduled) return;
  logFlushScheduled = true;
  // 窗口隐藏时 rAF 会被暂停，改为同步落盘到 DOM，避免队列无限增长
  if (document.hidden) flushLogs();
  else requestAnimationFrame(flushLogs);
}

function flushLogs() {
  logFlushScheduled = false;
  const pane = logBody;
  if (!pane || logQueue.length === 0) {
    logQueue.length = 0;
    return;
  }

  const batch = logQueue;
  logQueue = [];

  const frag = document.createDocumentFragment();
  for (const line of batch) {
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
    frag.append(card);
  }
  pane.append(frag);

  // 超出上限的旧日志一次性裁掉（只在批量写入后检查一次）
  let excess = pane.childElementCount - MAX_LOG_LINES;
  while (excess-- > 0) pane.firstElementChild?.remove();

  pane.scrollTop = pane.scrollHeight;
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
/** 最近一次环境探测结果，语言切换时需要原样重放 */
let lastEnv: EnvStatus | null = null;

function renderEnv(env: EnvStatus) {
  lastEnv = env;
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
    chip.textContent = t(ok ? 'env.ready' : 'env.missing', { name });
    box.append(chip);
  }

  el('adminBadge').hidden = !env.isAdmin;
}

/* ============ 列表渲染 ============ */

/**
 * 显示列表空状态：主题色图标 + 一句说明。
 * 传 i18n key 而非成品文本 —— 语言切换时 renderList 会按当前状态重建。
 * @param spin true 时图标自转，用于"扫描中"这类进行态
 */
function showEmpty(
  key: string,
  icon: string,
  vars?: Record<string, string | number>,
  spin = false,
): void {
  el('listEmpty').hidden = false;
  const iconEl = el('emptyIcon');
  iconEl.innerHTML = icon;
  iconEl.classList.toggle('spin', spin);
  el('emptyText').textContent = t(key, vars);
}

/**
 * 渲染列表。
 * @param animate 是否播放入场动画。仅"新一次扫描完成"时为 true；
 *   设置变更 / 忽略项 / 更新后移除 / 图标回填一律 false ——
 *   否则每次交互都会让整表重播一遍入场动画，既闪烁又白白重排。
 */
function renderList(animate = false) {
  const scroll = el('listScroll');
  scroll.classList.toggle('no-anim', !animate);
  scroll.replaceChildren();
  verCache = [];

  if (!scanned) {
    // 扫描中必须与"全部最新"区分开：之前 items 被清空但 scanned 仍为 true，
    // 结果扫描过程中会误导性地显示"所有软件都是最新版本"。
    if (busy) {
      showEmpty('empty.scanning', ICON.refresh, undefined, true);
    } else {
      showEmpty('empty.notScanned', ICON.search);
    }
    return;
  }

  // 上一次扫描失败：给明确错误态，别让空列表谎报"全部最新"。
  // 放在这里（而不是只在 catch 里直接渲染），语言切换重渲染也能保持正确。
  if (lastCheckError) {
    showEmpty('status.checkFailedDetail', ICON.alert, {
      error: lastCheckError.slice(0, 90),
    });
    return;
  }

  // 过滤被忽略的项（忽略此版本 / 永久忽略）
  const visible = items.filter((i) => !isItemIgnored(i.id, i.latest, appSettings));
  ignoredCount = items.length - visible.length;

  if (visible.length === 0) {
    if (items.length === 0) {
      showEmpty('empty.allLatest', ICON.checkCircle);
    } else {
      showEmpty('empty.allIgnored', ICON.eyeOffBig, { count: items.length });
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
    frag.append(buildGroup(source, group, globalIndex, animate));
    globalIndex += group.length;
  }
  scroll.append(frag);

  // 注意：这里不再写 statusRight。状态栏由 doCheck / doUpdate 显式管理，
  // 否则列表重绘会覆盖掉「成功 N · 失败 M」等更新结果。
  requestAnimationFrame(cacheVerNodes);
}

/** 入场动画的交错步长与上限：超过上限的项不再继续延后，避免长列表末尾项迟到数秒 */
const STAGGER_STEP_MS = 30;
const STAGGER_CAP = 20;

function buildGroup(
  source: SourceKind,
  group: UpdateItem[],
  startIndex: number,
  animate: boolean,
): HTMLElement {
  const wrap = document.createElement('div');
  wrap.className = 'group';

  const head = document.createElement('div');
  head.className = 'group-head';

  const cb = document.createElement('input');
  cb.type = 'checkbox';
  cb.dataset.role = 'group';
  cb.dataset.source = source;
  // 分组复选框要反映当前选中状态。此前重建列表时没设置它，
  // 于是无论选了多少项，分组头永远显示"未选中"，与下面条目的勾选状态自相矛盾。
  const selInGroup = group.filter((i) => checked.has(i.id)).length;
  cb.checked = group.length > 0 && selInGroup === group.length;
  cb.indeterminate = selInGroup > 0 && selInGroup < group.length;
  // 整组选中时给分组头一道主题色描边+光晕 —— CSS 里 .group.checked-glow 早就写好了，
  // 但一直没人挂这个类，等于白写
  wrap.classList.toggle('checked-glow', cb.checked);
  // 复选框的 label 里只有视觉方块、没有文字，无障碍名称会是空的 → 显式补一个
  cb.setAttribute('aria-label', t('action.selectAllIn', { group: sourceLabel(source) }));
  head.append(makeCheckbox(cb));

  const icon = document.createElement('span');
  icon.className = 'group-icon';
  icon.innerHTML = SOURCE_ICON[source] ?? ICON.package;

  const title = document.createElement('span');
  title.className = 'group-title';
  title.textContent = sourceLabel(source);

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
    // 交错进入动画：每项延迟 30ms，累计到 STAGGER_CAP 项后封顶
    if (animate) {
      const slot = Math.min(startIndex + i, STAGGER_CAP);
      el.style.animationDelay = `${slot * STAGGER_STEP_MS}ms`;
    }
    itemFrag.append(el);
  });
  body.append(itemFrag);
  wrap.append(body);

  return wrap;
}

function buildItem(item: UpdateItem): HTMLElement {
  const row = document.createElement('div');
  row.className = `item${checked.has(item.id) ? ' checked' : ''}`;
  row.dataset.id = item.id; // 供图标就地回填（patchIcons）定位

  const cb = document.createElement('input');
  cb.type = 'checkbox';
  cb.dataset.role = 'item';
  cb.dataset.id = item.id;
  cb.checked = checked.has(item.id);
  // 同上：label 只有方块没文字，补无障碍名称，否则读屏只能念出"复选框"
  cb.setAttribute('aria-label', item.name);

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
  meta.textContent = item.pkgId ?? sourceLabel(item.source);

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
  btnIgnoreVer.title = t('action.ignoreVersion');
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
  btnIgnoreForever.title = t('action.ignoreForever');
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
    failBtn.title = t('action.failedViewLog');
    failBtn.innerHTML = `${ICON.alert}<span>${t('btn.viewLog')}</span>`;
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

/**
 * 图标回填：就地把矢量占位替换为真实 PNG，**不整表重建**。
 *
 * fetch_icons 是后置的异步调用（首次要扫注册表，5~10s），返回时列表往往已可见。
 * 旧实现直接 renderList() 重建整表 —— 会闪烁、重排，并让入场动画重播一遍。
 */
function patchIcons(icons: Record<string, string>): void {
  const nameById = new Map(items.map((i) => [i.id, i.name]));
  const rows = document.querySelectorAll<HTMLElement>('.list-scroll .item[data-id]');
  for (const row of rows) {
    const id = row.dataset.id;
    if (!id) continue;
    const src = icons[id];
    if (!src) continue;
    const holder = row.querySelector<HTMLElement>('.item-icon');
    if (!holder || holder.querySelector('img')) continue; // 已有 PNG 就跳过

    const img = document.createElement('img');
    img.src = src;
    img.alt = nameById.get(id) ?? '';
    img.draggable = false;
    holder.classList.remove('item-icon-fallback');
    holder.replaceChildren(img);
  }
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
  // 与 buildGroup 保持一致：整组选中才点亮分组头
  cb.closest('.group')?.classList.toggle('checked-glow', cb.checked);
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
      popCheckbox(target);
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
      popCheckbox(target);
      syncItemsOfGroup(source);
      syncGroupCheckbox(source);
      refreshActions();
    }
  });
}

/* ============ 按钮状态 ============ */
/** 上一次的选中计数，用于"计数变化时弹一下"（-1 = 尚未初始化，首帧不弹） */
let lastSelCount = -1;

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
  btnSel.textContent = t('btn.updateSelectedCount', { count: selCount });
  // 选中数变化时弹一下，给"选中了几个"一个即时反馈
  if (selCount !== lastSelCount) {
    if (lastSelCount >= 0) pulse(btnSel, 'pulse-pop');
    lastSelCount = selCount;
  }

  el('checkSpinner').hidden = !busy;
  el('btnCheckLabel').textContent = busy ? t('btn.checking') : t('btn.check');
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
    // 走完时停掉呼吸、改播一次高亮收尾
    fill.classList.remove('done');
    void fill.offsetWidth;
    fill.classList.add('done');
    return;
  }

  fill.classList.remove('done');
  wrap.hidden = false;
  const pct = p.total > 0 ? Math.round((p.done / p.total) * 100) : 0;
  fill.style.width = `${pct}%`;
  text.textContent = `${p.done} / ${p.total} · ${p.currentName}`;
}

/* ============ 主流程 ============ */
async function doCheck() {
  if (busy) return;
  setBusy(true);
  setStatusLeft('status.checking');
  setStatusRight(null);

  items = [];
  checked = new Set<string>();
  failedIds = new Set<string>();
  lastCheckError = null;
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
    renderList(true); // 新一次扫描完成 —— 播放入场动画
    setStatusLeft('status.checkDone', { count: items.length });
    setStatusRight(
      ignoredCount > 0 ? 'status.ignoredCount' : null,
      ignoredCount > 0 ? { count: ignoredCount } : undefined,
    );

    // 异步拉取应用图标（首次会触发 PowerShell 扫描注册表，5~10s）
    try {
      const icons = await invoke<Record<string, string>>('fetch_icons', { items: found });
      if (icons && Object.keys(icons).length > 0) {
        for (const item of items) {
          if (icons[item.id]) item.icon = icons[item.id];
        }
        patchIcons(icons); // 就地替换图标，不整表重建
      }
    } catch (e) {
      setStatusRight('status.iconFailed', { error: String(e).slice(0, 40) });
    }
  } catch (e) {
    // 检查失败时给明确的错误态，而不是让空列表谎报"全部最新"
    scanned = true;
    items = [];
    checked = new Set<string>();
    lastCheckError = String(e);
    renderList(); // 由 renderList 统一渲染错误空状态（语言切换时也能重建）
    setStatusLeft('status.checkFailed');
    refreshActions();
  } finally {
    setBusy(false);
  }
}

async function doUpdate(target: UpdateItem[]) {
  if (busy || target.length === 0) return;
  setBusy(true);
  setStatusLeft('status.updatingCount', { count: target.length });
  setStatusRight(null);

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

    setStatusLeft('status.updateDone');
    setStatusRight('status.updateResult', { ok, failed: bad });
    pulse(el('statusRight'), 'pulse-pop'); // 结果数字弹一下，强化"完成了"的反馈

    // 通知：根据成功/失败送不同 level 的 toast
    const level = bad === 0 ? 'ok' : ok === 0 ? 'err' : 'warn';
    void invoke('send_notification', {
      title: t('notify.updateComplete'),
      body: t('notify.updateResult', { ok, failed: bad }),
      level,
    }).catch(() => undefined);
  } catch (e) {
    setStatusLeft('status.updateFailed', { error: String(e) });
    void invoke('send_notification', {
      title: t('notify.updateFailed'),
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
// t 记录上一次写入的插值，用于跳过"半径外 / 无变化"的无谓写样式
type VerNode = { el: HTMLElement; cx: number; cy: number; t: number };
let verCache: VerNode[] = [];

function cacheVerNodes(): void {
  verCache = Array.from(document.querySelectorAll<HTMLElement>('.item-ver')).map((el) => {
    const r = el.getBoundingClientRect();
    // t 置 -1：强制下一帧写一次初始值
    return { el, cx: r.left + r.width / 2, cy: r.top + r.height / 2, t: -1 };
  });
}

function setupProximityZoom(): void {
  const container = el('listScroll');
  let frame = 0;
  let px = -9999;
  let py = -9999;

  const paint = () => {
    frame = 0;
    for (const node of verCache) {
      const dx = px - node.cx;
      const dy = py - node.cy;
      const dist = Math.sqrt(dx * dx + dy * dy);

      // smoothstep：靠近时变化柔和，远离时快速回落
      const raw = Math.max(0, Math.min(1, 1 - dist / VER_RADIUS));
      const t = raw * raw * (3 - 2 * raw);

      // 无实质变化就跳过。否则每帧都会给"半径外"的全部元素重写一次基准值，
      // 触发全表样式重算 + 文本重排 —— 列表一长就掉帧（旧版"卡成 PPT"的根源之一）。
      if (Math.abs(t - node.t) < 0.008) continue;
      node.t = t;

      node.el.style.fontSize = `${(VER_BASE_SIZE + (VER_MAX_SIZE - VER_BASE_SIZE) * t).toFixed(2)}px`;
      // 基线透明度不能太低，否则远离光标时版本号几乎不可读
      node.el.style.opacity = (0.86 + 0.14 * t).toFixed(3);
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

  // 滚动 / 缩放时重算坐标。scroll 事件可能一帧内触发多次，
  // 用 rAF 合并成每帧最多一次，避免反复 getBoundingClientRect 强制重排。
  let cacheQueued = false;
  const queueCache = () => {
    if (cacheQueued) return;
    cacheQueued = true;
    requestAnimationFrame(() => {
      cacheQueued = false;
      cacheVerNodes();
    });
  };
  container.addEventListener('scroll', queueCache, { passive: true });
  window.addEventListener('resize', queueCache);
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
        setStatusLeft('status.openSettingsFailed', { error: String(e) });
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

  /* 6.6 多语言：先套用静态文案，之后语言一变就重建所有动态内容
   * （只重跑 applyI18n 不够：列表、状态栏、chips 都是 JS 生成的一次性文本） */
  try {
    document.title = t('app.title');
    applyI18n();
    // 后端也要知道当前语言。日志面板的文案是 Rust 产出的
    // （"发现 N 个 winget 软件可更新"这类），前端 i18n 管不到 ——
    // 不推语言的话，切到英文后界面全英文、唯独日志仍是中文。
    void invoke('set_language', { lang: getLang() }).catch(() => undefined);
    watchLang(() => {
      document.title = t('app.title');
      applyI18n();
      void invoke('set_language', { lang: getLang() }).catch(() => undefined);
      if (lastEnv) renderEnv(lastEnv);
      renderList();
      refreshActions();
      renderStatus();
    });
  } catch (e) {
    console.warn('[init] 多语言初始化失败', e);
  }

  /* 7. 图标注入与距离感应放大 */
  try { injectButtonIcons(); } catch (e) { console.warn('[init] 图标注入失败', e); }
  try { setupProximityZoom(); } catch (e) { console.warn('[init] 距离感应初始化失败', e); }

  /* 8. 事件监听（单个失败不影响其他） */
  try { await listen<LogLine>('update-log', (e) => appendLog(e.payload)); } catch (e) { console.warn('[init] update-log 监听失败', e); }
  try { await listen<Progress>('update-progress', (e) => onProgress(e.payload)); } catch (e) { console.warn('[init] update-progress 监听失败', e); }

  // 逐源结果：检查过程中哪个源先完成就先显示，不必等最慢的（通常是 winget）
  try {
    await listen<{ source: SourceKind; items: UpdateItem[] }>('check-source-result', (e) => {
      if (!busy) return; // 只在"检查中"合并；收尾时 doCheck 会用完整结果再渲一次
      const incoming = e.payload.items;
      if (incoming.length === 0) return;

      const ids = new Set(incoming.map((i) => i.id));
      items = [...items.filter((i) => !ids.has(i.id)), ...incoming];
      for (const i of incoming) {
        if (!isItemIgnored(i.id, i.latest, appSettings)) checked.add(i.id);
      }
      scanned = true;
      renderList(); // 不带入场动画：每来一个源就闪一下不好看
      refreshActions();
      setStatusLeft('status.checkPartial', { count: visibleItems().length });
    });
  } catch (e) { console.warn('[init] check-source-result 监听失败', e); }
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
    el('btnClearLog').addEventListener('click', () => {
      logQueue.length = 0; // 同时丢掉尚未刷入的最新一批，避免清空后又被补回来
      logBody?.replaceChildren();
    });
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
        title: t('notify.foundTitle'),
        body: t('notify.foundBody', { count: items.length }),
        level: 'info',
      }).catch(() => undefined);
    } else {
      // 无更新：不再后台常驻。WebView2 的隐藏窗口依然占几十 MB 内存，
      // 开机自启动这种"检查完就没事了"的场景没必要一直挂着。
      // 先发一条"已是最新"通知，留一点时间让通知可见（应用内 toast 需要
      // 存活到动画播完），然后退出进程。
      void invoke('send_notification', {
        title: t('notify.noUpdateTitle'),
        body: t('notify.noUpdate'),
        level: 'ok',
      }).catch(() => undefined);
      window.setTimeout(() => {
        if (appWindow) void appWindow.close(); // 关闭主窗口 = 退出进程（Rust 侧已绑定）
      }, 5000);
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
