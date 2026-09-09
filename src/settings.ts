import './styles.css';
import { emit } from '@tauri-apps/api/event';
import { invoke } from '@tauri-apps/api/core';
import { getCurrentWindow } from '@tauri-apps/api/window';
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
import { t, setLang, getLang, watchLang, type Lang } from './i18n';

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

const POS_LABELS: Record<string, { label: string; hint: string }> = {
  'top-left': {
    label: '左上',
    hint: '左上：适合作为信息提示流，新通知从顶部向下依次堆叠，眼睛先看到最新一条。',
  },
  'top-center': {
    label: '顶部居中',
    hint: '顶部居中：适合中等优先级的提示，视线从屏幕中央向下扫读，左右对称。',
  },
  'top-right': {
    label: '右上',
    hint: '右上：与 macOS 通知中心一致，新通知顶部对齐，先看最新消息。',
  },
  'bottom-left': {
    label: '左下',
    hint: '左下：左侧靠下适合"非打扰型"提示，新通知向上叠，旧的下沉远离视线。',
  },
  'bottom-center': {
    label: '底部居中',
    hint: '底部居中：更新进度类通知常用，从下往上依次累加，旧消息保持可见。',
  },
  'bottom-right': {
    label: '右下',
    hint: '右下：人眼最常见的视线落点，新通知从底部滑入，与系统通知中心一致。',
  },
};

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
    name.textContent = p.name;

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
function bindAutostart(): void {
  try {
    const toggle = el<HTMLInputElement>('toggleAutostart');
    // 初始查询当前状态
    void invoke<boolean>('get_autostart')
      .then((enabled) => {
        toggle.checked = enabled;
      })
      .catch(() => undefined);

    toggle.addEventListener('change', (e) => {
      const enabled = (e.target as HTMLInputElement).checked;
      void invoke('set_autostart', { enable: enabled }).catch((err) => {
        console.warn('[settings] 开机自启动设置失败', err);
        toggle.checked = !enabled;
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

/** 应用 i18n 文本到设置面板（关键元素） */
function applyI18nTexts(): void {
  try {
    document.documentElement.setAttribute('lang', getLang());
    document.title = t('settings.title');
    const tbTitle = document.querySelector('.tb-title');
    if (tbTitle) tbTitle.textContent = t('settings.title');
  } catch {
    /* ignore */
  }
}

/* ============ 关于按钮 ============ */
function bindAbout(): void {
  try {
    const btn = el<HTMLButtonElement>('btnOpenAbout');
    btn.addEventListener('click', () => {
      void invoke('open_about');
    });
  } catch (e) {
    console.warn('[settings] 关于按钮绑定失败', e);
  }
}

/* ============ 通知偏好 ============ */
function syncNotifyControls(): void {
  document.querySelectorAll<HTMLInputElement>('input[name="notifMode"]').forEach((r) => {
    r.checked = r.value === notify.mode;
  });
  const mini = el<HTMLDivElement>('posMini');
  mini.dataset.pos = notify.position;
  const info = POS_LABELS[notify.position];
  el('posHint').textContent = info?.hint ?? '';

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
      title: notify.mode === 'native' ? '系统通知测试' : '应用内通知测试',
      body: '这是一条测试消息，用于确认通知位置与样式。',
      level: 'info',
    }).catch(() => undefined);
  });
}

/* ============ 图标缓存刷新 ============ */
function bindIconControls(): void {
  el('btnRefreshIcons').addEventListener('click', async () => {
    const stat = el('iconStat');
    stat.textContent = '刷新中…';
    try {
      const entries = await invoke<Array<{ name: string; icon: string }>>('rebuild_icon_cache');
      stat.textContent = `已缓存 ${entries.length} 个图标`;
    } catch (e) {
      stat.textContent = `刷新失败：${String(e).slice(0, 40)}`;
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
      container.innerHTML = '<div class="ignore-empty">暂无被忽略的更新项</div>';
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
      kind.textContent = item.kind === 'version' ? `忽略版本 ${item.version ?? ''}` : '永久忽略';
      info.append(name, kind);

      const btn = document.createElement('button');
      btn.className = 'mini-btn';
      btn.textContent = '移除';
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
}

void init();