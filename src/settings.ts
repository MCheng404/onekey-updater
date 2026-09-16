import './styles.css';
import { emit } from '@tauri-apps/api/event';
import { invoke } from '@tauri-apps/api/core';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { getVersion } from '@tauri-apps/api/app';
import {
  DEFAULT_THEME,
  PRESETS,
  applyMode,
  applyTheme,
  applyWindowEffects,
  loadTheme,
  saveTheme,
  watchSystemTheme,
  type ThemeMode,
  type ThemeState,
} from './theme';
import {
  AppSettings,
  SETTINGS_CHANGED_EVENT,
  listIgnored,
  loadSettings,
  removeFromIgnore,
  saveSettings,
} from './app-settings';
import { applyI18n, t, setLang, getLang, watchLang, type Lang } from './i18n';
import {
  DEFAULT_FONT,
  applyFont,
  commitFont,
  loadFont,
  previewFont,
  type FontSettings,
  type FontSource,
} from './font-settings';

interface NotifyPrefs {
  mode: string;
  position: string;
  durationMs: number;
  opacity: number;
  maxStack: number;
}

const DEFAULT_NOTIFY: NotifyPrefs = {
  mode: 'native',
  position: 'bottom-right',
  durationMs: 4500,
  opacity: 90,
  maxStack: 4,
};

let theme: ThemeState = loadTheme();
let notify: NotifyPrefs = { ...DEFAULT_NOTIFY };
let appSettings: AppSettings = loadSettings();
let fontSettings: FontSettings = loadFont();

/** 通知位置 → i18n key 片段（真实文案在语言包里，随语言变化，所以不能做成静态常量） */
const POS_KEY: Record<string, string> = {
  'top-left': 'topLeft',
  'top-center': 'topCenter',
  'top-right': 'topRight',
  'bottom-left': 'bottomLeft',
  'bottom-center': 'bottomCenter',
  'bottom-right': 'bottomRight',
};

function posHint(pos: string): string {
  return t(`settings.pos.${POS_KEY[pos] ?? 'bottomRight'}Hint`);
}

function el<T extends HTMLElement>(id: string): T {
  const node = document.getElementById(id);
  if (!node) throw new Error(`缺少 DOM 节点 #${id}`);
  return node as T;
}

/* 运行时引用：在 init() 中安全赋值 */
let appWindow: ReturnType<typeof getCurrentWindow> | null = null;

/* ============ 主题 ============ */
function commitTheme(): void {
  applyTheme(theme);
  saveTheme(theme);
  syncThemeControls();
  // 通知主窗口同步刷新
  void emit('theme-changed', theme).catch(() => undefined);
}

function syncThemeControls(): void {
  el<HTMLInputElement>('colorAccent').value = theme.accent;
  el<HTMLInputElement>('colorText').value = theme.text;
  el<HTMLInputElement>('colorDim').value = theme.dim;
  el<HTMLInputElement>('rangeOpacity').value = String(theme.opacity);
  el<HTMLInputElement>('rangeRadius').value = String(theme.radius);
  el<HTMLInputElement>('toggleMotion').checked = theme.reduceMotion;

  el('valAccent').textContent = theme.accent.toUpperCase();
  el('valText').textContent = theme.text.toUpperCase();
  el('valDim').textContent = theme.dim.toUpperCase();
  el('valOpacity').textContent = `${theme.opacity}%`;
  el('valRadius').textContent = `${theme.radius}px`;

  document.querySelectorAll<HTMLElement>('.preset').forEach((node) => {
    node.classList.toggle('active', node.dataset.preset === theme.presetId);
  });
}

function renderPresets(): void {
  const grid = el('presetGrid');
  grid.replaceChildren();

  for (const p of PRESETS) {
    const btn = document.createElement('button');
    btn.className = 'preset';
    btn.type = 'button';
    btn.dataset.preset = p.id;

    const swatch = document.createElement('div');
    swatch.className = 'preset-swatch';
    swatch.style.background = p.accent;

    const name = document.createElement('div');
    name.className = 'preset-name';
    name.textContent = t(`settings.preset.${p.id}`);

    btn.append(swatch, name);
    btn.addEventListener('click', () => {
      theme.presetId = p.id;
      theme.accent = p.accent;
      theme.text = p.text;
      theme.dim = p.dim;
      commitTheme();
    });
    grid.append(btn);
  }
}

function bindThemeControls(): void {
  el<HTMLInputElement>('colorAccent').addEventListener('input', (e) => {
    theme.accent = (e.target as HTMLInputElement).value;
    theme.presetId = 'custom';
    commitTheme();
  });
  el<HTMLInputElement>('colorText').addEventListener('input', (e) => {
    theme.text = (e.target as HTMLInputElement).value;
    theme.presetId = 'custom';
    commitTheme();
  });
  el<HTMLInputElement>('colorDim').addEventListener('input', (e) => {
    theme.dim = (e.target as HTMLInputElement).value;
    theme.presetId = 'custom';
    commitTheme();
  });

  el<HTMLInputElement>('rangeOpacity').addEventListener('input', (e) => {
    theme.opacity = Number((e.target as HTMLInputElement).value);
    commitTheme();
  });
  el<HTMLInputElement>('rangeRadius').addEventListener('input', (e) => {
    theme.radius = Number((e.target as HTMLInputElement).value);
    commitTheme();
  });
  el<HTMLInputElement>('toggleMotion').addEventListener('change', (e) => {
    theme.reduceMotion = (e.target as HTMLInputElement).checked;
    commitTheme();
  });

  document.querySelectorAll<HTMLElement>('[data-reset]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const key = btn.dataset.reset;
      const preset = PRESETS.find((p) => p.id === theme.presetId) ?? PRESETS[0];
      if (key === 'accent') theme.accent = preset.accent;
      if (key === 'text') theme.text = preset.text;
      if (key === 'dim') theme.dim = preset.dim;
      commitTheme();
    });
  });

  el('btnResetAll').addEventListener('click', () => {
    theme = { ...DEFAULT_THEME };
    commitTheme();
  });
}

/* ============ 主题模式（浅色/深色/跟随系统） ============ */
function syncModeButtons(): void {
  document.querySelectorAll<HTMLElement>('.mode-btn').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.mode === theme.mode);
  });
}

function commitMode(mode: ThemeMode): void {
  theme = applyMode(theme, mode);
  applyTheme(theme);
  saveTheme(theme);
  syncModeButtons();
  syncThemeControls();
  void emit('theme-changed', theme).catch(() => undefined);
}

function bindModeSelector(): void {
  try {
    document.querySelectorAll<HTMLElement>('.mode-btn').forEach((btn) => {
      btn.addEventListener('click', () => {
        const mode = btn.dataset.mode as ThemeMode;
        if (mode) commitMode(mode);
      });
    });
    // 监听系统主题变化，跟随系统模式时实时切换
    watchSystemTheme(() => {
      if (theme.mode === 'system') {
        applyTheme(theme);
        void emit('theme-changed', theme).catch(() => undefined);
      }
    });
  } catch (e) {
    console.warn('[settings] 主题模式绑定失败', e);
  }
}

/* ============ 开机自启动 ============ */

/** 自启动延迟滑块仅在开启自启动时才有意义，关闭时隐藏 */
function syncAutostartDelayRow(enabled: boolean): void {
  const row = document.getElementById('rowAutostartDelay');
  if (row) row.hidden = !enabled;
}

function bindAutostart(): void {
  try {
    const toggle = el<HTMLInputElement>('toggleAutostart');
    // 初始查询当前状态
    void invoke<boolean>('get_autostart')
      .then((enabled) => {
        toggle.checked = enabled;
        syncAutostartDelayRow(enabled);
      })
      .catch(() => undefined);

    toggle.addEventListener('change', (e) => {
      const enabled = (e.target as HTMLInputElement).checked;
      syncAutostartDelayRow(enabled);
      void invoke('set_autostart', { enable: enabled }).catch((err) => {
        console.warn('[settings] 开机自启动设置失败', err);
        // 写入失败时回滚 UI
        toggle.checked = !enabled;
        syncAutostartDelayRow(!enabled);
      });
    });
  } catch (e) {
    console.warn('[settings] 开机自启动绑定失败', e);
  }
}

/* ============ 语言选择 ============ */
function bindLanguage(): void {
  try {
    const select = el<HTMLSelectElement>('selectLang');
    select.value = getLang();

    select.addEventListener('change', (e) => {
      const lang = (e.target as HTMLSelectElement).value as Lang;
      setLang(lang);
      applyI18nTexts();
    });

    // 监听其他窗口的语言变更
    watchLang(() => {
      select.value = getLang();
      applyI18nTexts();
    });
  } catch (e) {
    console.warn('[settings] 语言选择绑定失败', e);
  }
}

/**
 * 把当前语言应用到整个设置面板。
 * 静态文案交给 applyI18n；预设名 / 通知位置提示 / 忽略列表是 JS 生成的，
 * 必须一并重建，否则切语言后它们还停留在旧语言。
 */
function applyI18nTexts(): void {
  try {
    document.documentElement.setAttribute('lang', getLang());
    document.title = t('settings.title');
    applyI18n();
    renderPresets();
    syncThemeControls();
    syncNotifyControls();
    renderIgnoreList();
  } catch {
    /* ignore */
  }
}

/* ============ 字体 ============ */

function syncFontControls(): void {
  document.querySelectorAll<HTMLElement>('[data-font-src]').forEach((b) => {
    b.classList.toggle('active', b.dataset.fontSrc === fontSettings.source);
  });
  el('fontPaneSystem').hidden = fontSettings.source !== 'system';
  el('fontPaneFile').hidden = fontSettings.source !== 'file';

  el<HTMLInputElement>('rangeFontScale').value = String(Math.round(fontSettings.scale * 100));
  el('valFontScale').textContent = `${Math.round(fontSettings.scale * 100)}%`;

  el('fontFilePath').textContent = fontSettings.customPath || t('settings.noFontFile');
  const dirSel = el<HTMLSelectElement>('selectDirFont');
  el('rowFontDirList').hidden = dirSel.options.length === 0;

  if (fontSettings.family) el<HTMLSelectElement>('selectSystemFont').value = fontSettings.family;
}

function setFontSource(src: FontSource): void {
  fontSettings.source = src;
  // 首次切到系统字体时，先把下拉框当前值带上，避免出现"选了来源但没字族"
  if (src === 'system' && !fontSettings.family) {
    const v = el<HTMLSelectElement>('selectSystemFont').value;
    if (v) fontSettings.family = v;
  }
  commitFont(fontSettings);
  syncFontControls();
}

async function loadSystemFonts(): Promise<void> {
  const sel = el<HTMLSelectElement>('selectSystemFont');
  sel.innerHTML = `<option value="">${t('common.loading')}</option>`;

  let fonts: string[] = [];
  try {
    fonts = await invoke<string[]>('list_system_fonts');
  } catch (e) {
    console.warn('[settings] 读取系统字体失败', e);
  }

  if (fonts.length === 0) {
    sel.innerHTML = `<option value="">${t('settings.noSystemFont')}</option>`;
    return;
  }

  sel.replaceChildren();
  for (const name of fonts) {
    const opt = document.createElement('option');
    opt.value = name;
    opt.textContent = name;
    sel.append(opt);
  }
  if (fontSettings.family && fonts.includes(fontSettings.family)) {
    sel.value = fontSettings.family;
  }
}

/** 把某个字体文件设为当前字体 */
async function useFontFile(path: string): Promise<void> {
  try {
    await invoke('set_ui_font_file', { path });
  } catch (e) {
    console.warn('[settings] 设置字体文件失败', e);
    return;
  }
  fontSettings.source = 'file';
  fontSettings.customPath = path;
  fontSettings.customName = path.split(/[\\/]/).pop() ?? path;
  commitFont(fontSettings);
  syncFontControls();
}

function bindFontControls(): void {
  document.querySelectorAll<HTMLElement>('[data-font-src]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const src = btn.dataset.fontSrc as FontSource | undefined;
      if (src) setFontSource(src);
    });
  });

  el<HTMLSelectElement>('selectSystemFont').addEventListener('change', (e) => {
    fontSettings.family = (e.target as HTMLSelectElement).value;
    fontSettings.source = 'system';
    commitFont(fontSettings);
    syncFontControls();
  });

  el('btnPickFontFile').addEventListener('click', () => {
    void (async () => {
      const path = await invoke<string | null>('pick_font_file').catch(() => null);
      if (path) await useFontFile(path);
    })();
  });

  el('btnPickFontDir').addEventListener('click', () => {
    void (async () => {
      const dir = await invoke<string | null>('pick_font_dir').catch(() => null);
      if (!dir) return;

      const files = await invoke<string[]>('list_font_files', { dir }).catch(() => []);
      const sel = el<HTMLSelectElement>('selectDirFont');
      sel.replaceChildren();

      if (files.length === 0) {
        el('fontFilePath').textContent = t('settings.noFontInDir', { dir });
        syncFontControls();
        return;
      }
      for (const f of files) {
        const opt = document.createElement('option');
        opt.value = f;
        opt.textContent = f.split(/[\\/]/).pop() ?? f;
        sel.append(opt);
      }
      await useFontFile(files[0]);
    })();
  });

  el<HTMLSelectElement>('selectDirFont').addEventListener('change', (e) => {
    const path = (e.target as HTMLSelectElement).value;
    if (path) void useFontFile(path);
  });

  const range = el<HTMLInputElement>('rangeFontScale');
  range.addEventListener('input', (e) => {
    const pct = Number((e.target as HTMLInputElement).value);
    fontSettings.scale = pct / 100;
    el('valFontScale').textContent = `${pct}%`;
    // 拖动时只做实时预览；松手（change）才落盘 + 广播，避免刷爆 localStorage
    previewFont(fontSettings);
  });
  range.addEventListener('change', () => commitFont(fontSettings));

  el('btnFontReset').addEventListener('click', () => {
    void (async () => {
      await invoke('set_ui_font_file', { path: null }).catch(() => undefined);
      fontSettings = { ...DEFAULT_FONT };
      commitFont(fontSettings);
      syncFontControls();
    })();
  });
}

/* ============ 日志 ============ */
function bindLogControls(): void {
  try {
    el('btnOpenLog').addEventListener('click', () => {
      void invoke('open_log_dir').catch((err) => {
        console.warn('[settings] 打开日志文件夹失败', err);
      });
    });
  } catch (e) {
    console.warn('[settings] 日志按钮绑定失败', e);
  }

  // 展示日志目录，方便用户手动去翻
  void invoke<string>('get_log_dir')
    .then((dir) => {
      el('logPath').textContent = dir;
    })
    .catch(() => {
      el('logPath').textContent = '—';
    });
}

/* ============ 关于区块 ============ */
function bindAbout(): void {
  try {
    const btn = el<HTMLButtonElement>('btnOpenAbout');
    btn.addEventListener('click', () => {
      void invoke('open_about');
    });
  } catch (e) {
    console.warn('[settings] 关于按钮绑定失败', e);
  }

  // 版本号从运行时读取，避免 HTML 里硬编码的版本号与包版本不一致
  void getVersion()
    .then((v) => {
      el('settingsVersion').textContent = v;
    })
    .catch(() => {
      el('settingsVersion').textContent = '—';
    });
}

/* ============ 通知偏好 ============ */
function syncNotifyControls(): void {
  document.querySelectorAll<HTMLInputElement>('input[name="notifMode"]').forEach((r) => {
    r.checked = r.value === notify.mode;
  });
  const mini = el<HTMLDivElement>('posMini');
  const posKey = POS_KEY[notify.position] ?? 'bottomRight';
  const posName = t(`settings.pos.${posKey}`);
  mini.dataset.pos = notify.position;
  mini.title = posName; // 悬停提示 = 当前位置名
  // 唯一的"通知位置"入口，必须给出无障碍名称，否则读屏只念"分组"
  mini.setAttribute('aria-label', `${t('settings.notifPosition')}: ${posName}`);
  el('posHint').textContent = posHint(notify.position);

  el<HTMLInputElement>('rangeNotifDuration').value = String(notify.durationMs);
  el<HTMLInputElement>('rangeNotifOpacity').value = String(notify.opacity);
  el('valNotifDuration').textContent = `${(notify.durationMs / 1000).toFixed(1)}s`;
  el('valNotifOpacity').textContent = `${notify.opacity}%`;

  // 仅"应用内"模式显示位置 / 时长 / 透明度
  document.querySelectorAll<HTMLElement>('.not-app-only').forEach((node) => {
    node.classList.toggle('active', notify.mode === 'app');
  });
}

async function commitNotify(): Promise<void> {
  try {
    await invoke('save_notify_prefs', { prefs: notify });
  } catch {
    /* 静默 */
  }
}

function bindNotifyControls(): void {
  document.querySelectorAll<HTMLInputElement>('input[name="notifMode"]').forEach((r) => {
    r.addEventListener('change', (e) => {
      if (!(e.target as HTMLInputElement).checked) return;
      notify.mode = (e.target as HTMLInputElement).value;
      syncNotifyControls();
      void commitNotify();
    });
  });

  // 迷你绘制窗口：点击区域 → 选位置
  const mini = el<HTMLDivElement>('posMini');

  // 键盘可达：这是设置"通知位置"的唯一入口，不能只支持鼠标。
  // 方向键在 3×2 的落点里移动：左右切 left/center/right，上下切 top/bottom。
  mini.tabIndex = 0;
  mini.setAttribute('role', 'button');
  mini.addEventListener('keydown', (e) => {
    if (notify.mode !== 'app') return;
    const [v, h] = notify.position.split('-');
    const cols = ['left', 'center', 'right'];
    let ci = cols.indexOf(h);
    if (ci < 0) ci = 2;

    let next: string | null = null;
    switch (e.key) {
      case 'ArrowLeft':
        next = `${v}-${cols[Math.max(0, ci - 1)]}`;
        break;
      case 'ArrowRight':
        next = `${v}-${cols[Math.min(2, ci + 1)]}`;
        break;
      case 'ArrowUp':
        next = `top-${cols[ci]}`;
        break;
      case 'ArrowDown':
        next = `bottom-${cols[ci]}`;
        break;
      default:
        break;
    }
    if (!next) return;

    e.preventDefault();
    notify.position = next;
    syncNotifyControls();
    void commitNotify();
  });

  mini.addEventListener('click', (e) => {
    if (notify.mode !== 'app') return;
    const rect = mini.getBoundingClientRect();
    const x = (e.clientX - rect.left) / rect.width;
    const y = (e.clientY - rect.top) / rect.height;

    let v: 'top' | 'bottom';
    if (y < 0.5) v = 'top';
    else v = 'bottom';

    let h: 'left' | 'center' | 'right';
    if (x < 0.34) h = 'left';
    else if (x > 0.66) h = 'right';
    else h = 'center';

    notify.position = `${v}-${h}`;
    syncNotifyControls();
    void commitNotify();
  });

  el<HTMLInputElement>('rangeNotifDuration').addEventListener('input', (e) => {
    notify.durationMs = Number((e.target as HTMLInputElement).value);
    syncNotifyControls();
    void commitNotify();
  });
  el<HTMLInputElement>('rangeNotifOpacity').addEventListener('input', (e) => {
    notify.opacity = Number((e.target as HTMLInputElement).value);
    syncNotifyControls();
    void commitNotify();
  });

  el('btnTestNotif').addEventListener('click', () => {
    void invoke('send_notification', {
      title: notify.mode === 'native' ? t('settings.testNotifNative') : t('settings.testNotifApp'),
      body: t('settings.testNotifBody'),
      level: 'info',
    }).catch(() => undefined);
  });
}

/* ============ 图标缓存刷新 ============ */
function bindIconControls(): void {
  el('btnRefreshIcons').addEventListener('click', async () => {
    const stat = el('iconStat');
    stat.textContent = t('settings.refreshing');
    try {
      const entries = await invoke<Array<{ name: string; icon: string }>>('rebuild_icon_cache');
      stat.textContent = t('settings.iconCached', { count: entries.length });
    } catch (e) {
      stat.textContent = t('settings.iconFailed', { error: String(e).slice(0, 40) });
    }
  });
}

/* ============ 应用设置（自动检查 + 忽略列表） ============ */
function broadcastSettings(): void {
  void emit(SETTINGS_CHANGED_EVENT, appSettings).catch(() => undefined);
}

function bindAutoCheck(): void {
  try {
    const toggle = el<HTMLInputElement>('toggleAutoCheck');
    toggle.checked = appSettings.autoCheck;
    toggle.addEventListener('change', (e) => {
      appSettings.autoCheck = (e.target as HTMLInputElement).checked;
      saveSettings(appSettings);
      broadcastSettings();
    });
  } catch (e) {
    console.warn('[settings] 自动检查开关绑定失败', e);
  }
}

/** 开机自启动检查延迟（秒） */
function bindAutostartDelay(): void {
  try {
    const range = el<HTMLInputElement>('rangeAutostartDelay');
    const val = el('valAutostartDelay');
    range.value = String(appSettings.autostartDelay);
    val.textContent = `${appSettings.autostartDelay}s`;

    range.addEventListener('input', (e) => {
      const seconds = Number((e.target as HTMLInputElement).value);
      appSettings.autostartDelay = seconds;
      val.textContent = `${seconds}s`;
      saveSettings(appSettings);
      broadcastSettings();
    });
  } catch (e) {
    console.warn('[settings] 自启动延迟绑定失败', e);
  }
}

function renderIgnoreList(): void {
  try {
    const container = el('ignoreList');
    const ignored = listIgnored(appSettings);
    if (ignored.length === 0) {
      container.innerHTML = `<div class="ignore-empty">${t('settings.ignoreEmpty')}</div>`;
      return;
    }
    container.replaceChildren();
    for (const item of ignored) {
      const row = document.createElement('div');
      row.className = 'ignore-row';

      const info = document.createElement('div');
      info.className = 'ignore-info';
      const name = document.createElement('span');
      name.className = 'ignore-name';
      name.textContent = item.id;
      const kind = document.createElement('span');
      kind.className = 'ignore-kind';
      kind.textContent =
        item.kind === 'version'
          ? t('settings.ignoreVersionLabel', { version: item.version ?? '' })
          : t('settings.ignoreForeverLabel');
      info.append(name, kind);

      const btn = document.createElement('button');
      btn.className = 'mini-btn';
      btn.textContent = t('common.remove');
      btn.addEventListener('click', () => {
        appSettings = removeFromIgnore(item.id, appSettings);
        renderIgnoreList();
        broadcastSettings();
      });

      row.append(info, btn);
      container.append(row);
    }
  } catch (e) {
    console.warn('[settings] 忽略列表渲染失败', e);
  }
}

/* ============ 初始化 ============ */
async function init(): Promise<void> {
  /* 1. Tauri 窗口对象 */
  try {
    appWindow = getCurrentWindow();
  } catch (e) {
    console.warn('[init] 非 Tauri 环境，窗口控制不可用', e);
  }

  /* 2. 窗口控制按钮 + 标题栏拖拽 */
  if (appWindow) {
    try {
      el('btnClose').addEventListener('click', () => void appWindow!.close());
      const titlebar = el('titlebar');
      titlebar.addEventListener('mousedown', (e) => {
        const t = e.target as HTMLElement;
        if (t.closest('.tb-btn')) return;
        if (e.buttons === 1) void appWindow!.startDragging();
      });
    } catch (e) {
      console.warn('[init] 窗口控制绑定失败', e);
    }
  }

  /* 3. 主题 */
  try {
    applyTheme(theme);
    await applyWindowEffects();
  } catch (e) {
    console.warn('[init] 主题应用失败', e);
  }

  /* 4. 主题控件 */
  try {
    renderPresets();
    bindThemeControls();
    syncThemeControls();
    bindModeSelector();
    syncModeButtons();
  } catch (e) {
    console.error('[init] 主题控件初始化失败', e);
  }

  /* 5. 通知偏好 */
  try {
    notify = await invoke<NotifyPrefs>('load_notify_prefs');
  } catch {
    notify = { ...DEFAULT_NOTIFY };
  }
  try {
    bindNotifyControls();
    syncNotifyControls();
  } catch (e) {
    console.warn('[init] 通知控件初始化失败', e);
  }

  /* 6. 图标缓存 */
  try {
    bindIconControls();
  } catch (e) {
    console.warn('[init] 图标控件初始化失败', e);
  }

  /* 7. 自动检查 + 忽略列表 + 自启动延迟 */
  bindAutoCheck();
  bindAutostartDelay();
  renderIgnoreList();

  /* 8. 开机自启动 */
  bindAutostart();

  /* 9. 语言选择 */
  bindLanguage();
  applyI18nTexts();

  /* 10. 关于按钮 */
  bindAbout();

  /* 11. 日志入口 */
  bindLogControls();

  /* 12. 字体 */
  try {
    applyFont(fontSettings);
    bindFontControls();
    syncFontControls();
    void loadSystemFonts();
  } catch (e) {
    console.warn('[init] 字体设置初始化失败', e);
  }
}

void init();