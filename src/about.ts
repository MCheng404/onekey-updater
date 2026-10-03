/**
 * 关于窗口 (label=about) 前端逻辑
 * 显示软件介绍、GitHub 链接、检查更新
 */

import './styles.css';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { getVersion } from '@tauri-apps/api/app';
import { invoke } from '@tauri-apps/api/core';
import { open } from '@tauri-apps/plugin-shell';
import { applyTheme, loadTheme } from './theme';
import { applyFont, loadFont, watchFont } from './font-settings';
import { applyI18n as applyI18nShared, t, getLang, watchLang } from './i18n';

// GitHub 配置
const GITHUB_PROFILE = 'https://github.com/MCheng404';
const GITHUB_REPO = 'https://github.com/MCheng404/onekey-updater';
/** GitHub Releases 列表（需要多条才能筛出桌面版，故用列表接口而非 /latest） */
const REPO_API =
  'https://api.github.com/repos/MCheng404/onekey-updater/releases?per_page=20';

let win: ReturnType<typeof getCurrentWindow> | null = null;

function el<T extends HTMLElement = HTMLElement>(id: string): T {
  const e = document.getElementById(id);
  if (!e) throw new Error(`缺少 #${id}`);
  return e as T;
}

/** 应用当前语言到关于窗口：共享 applyI18n（含 title/aria）+ 窗口标题 */
function applyI18n(): void {
  document.title = t('about.title');
  applyI18nShared();
}

/**
 * 从 releases 列表里挑出**版本号最大的桌面版**。
 *
 * 为什么必须筛：本仓库同时发布 Android 版，tag 形如 `app-v3.1.4`，
 * 且全部标记为 `prerelease=false / draft=false` ——
 * 所以 `GET /releases/latest` 必定返回 **Android 版**。
 * 原实现只做 `.replace(/^v/, '')`，"app-v3.1.4" 剥不掉前缀，
 * 与本地版本一比永远不等，于是"关于"窗口永远提示有新版。
 *
 * 桌面版 tag 规则：`v<semver>`（v2.3.0 / v2.2.2 …）。
 * 这里按该形状过滤，再按 semver 取最大值，不依赖 API 返回顺序。
 */
function pickLatestDesktopRelease(releases: unknown): {
  tag: string;
  version: string;
  url?: string;
} | null {
  if (!Array.isArray(releases)) return null;

  let best: { tag: string; version: string; url?: string } | null = null;
  let bestKey: number[] = [0, 0, 0];

  for (const r of releases) {
    if (!r || typeof r !== 'object') continue;
    const rel = r as {
      tag_name?: unknown;
      html_url?: unknown;
      draft?: unknown;
      prerelease?: unknown;
    };
    if (rel.draft === true || rel.prerelease === true) continue;
    const tag = typeof rel.tag_name === 'string' ? rel.tag_name.trim() : '';
    const m = /^v(\d+)\.(\d+)\.(\d+)(?:[-+][0-9A-Za-z.-]+)?$/.exec(tag);
    if (!m) continue; // app-v3.1.4 等非桌面版在此被排除

    const key = [Number(m[1]), Number(m[2]), Number(m[3])];
    const isNewer =
      key[0] > bestKey[0] ||
      (key[0] === bestKey[0] && key[1] > bestKey[1]) ||
      (key[0] === bestKey[0] && key[1] === bestKey[1] && key[2] > bestKey[2]);

    if (!best || isNewer) {
      best = {
        tag,
        version: `${m[1]}.${m[2]}.${m[3]}`,
        url: typeof rel.html_url === 'string' ? rel.html_url : undefined,
      };
      bestKey = key;
    }
  }
  return best;
}

/** 检查 GitHub 最新版本 */
async function checkUpdate(): Promise<void> {
  const btn = el<HTMLButtonElement>('btnCheckUpdate');
  const status = el<HTMLElement>('updateStatus');

  btn.disabled = true;
  status.hidden = false;
  status.textContent = t('about.checking');
  status.className = 'about-update-status checking';

  // GitHub API 在国内网络下可能长时间无响应，必须加超时，
  // 否则按钮会一直处于 disabled 状态，用户以为卡死。
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), 8000);

  try {
    let releases: unknown;
    try {
      const res = await fetch(REPO_API, {
        headers: { Accept: 'application/vnd.github.v3+json' },
        signal: controller.signal,
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      releases = await res.json();
    } finally {
      window.clearTimeout(timer);
    }

    const latest = pickLatestDesktopRelease(releases);
    const current = await getVersion();

    // 用 DOM 构建而非 innerHTML，避免把远端返回的字符串当 HTML 解析
    status.replaceChildren();
    if (!latest) {
      // 列表里一个桌面版都没有：不能报"已是最新"，那是错误结论
      status.textContent = t('common.error');
      status.className = 'about-update-status';
    } else if (latest.version !== current) {
      const line = document.createElement('span');
      line.append(`${t('about.newVersion')}: `);
      const ver = document.createElement('span');
      ver.className = 'mono';
      ver.textContent = `v${latest.version}`;
      line.append(ver);
      status.append(line);
      status.className = 'about-update-status new';

      // 添加下载按钮
      const downloadBtn = document.createElement('button');
      downloadBtn.className = 'btn btn-primary';
      downloadBtn.style.marginLeft = '8px';
      downloadBtn.textContent = t('about.download');
      downloadBtn.addEventListener('click', () => {
        void open(latest.url ?? GITHUB_REPO);
      });
      status.append(downloadBtn);
    } else {
      status.textContent = t('about.upToDate');
      status.className = 'about-update-status ok';
    }
  } catch (e) {
    console.warn('[about] 检查更新失败', e);
    // 网络失败时明确提示错误，而不是谎报"已是最新版本"
    status.replaceChildren();
    status.textContent = t('common.error');
    status.className = 'about-update-status';
  } finally {
    btn.disabled = false;
  }
}

async function init(): Promise<void> {
  // 1. 窗口对象
  try {
    win = getCurrentWindow();
  } catch (e) {
    console.warn('[about] 非 Tauri 环境', e);
  }

  // 2. 主题
  try {
    applyTheme(loadTheme());
  } catch (e) {
    console.warn('[about] 主题应用失败', e);
  }

  // 2.5 字体
  try {
    applyFont(loadFont());
    watchFont((f) => applyFont(f));
  } catch (e) {
    console.warn('[about] 字体应用失败', e);
  }

  // 3. 语言
  try {
    document.documentElement.setAttribute('lang', getLang());
    applyI18n();
    watchLang(() => {
      document.documentElement.setAttribute('lang', getLang());
      applyI18n();
    });
  } catch (e) {
    console.warn('[about] i18n 初始化失败', e);
  }

  // 4. 版本号 + 架构 + 平台
  try {
    const version = await getVersion();
    el('appVersion').textContent = version;
  } catch {
    el('appVersion').textContent = '2.1.0';
  }

  try {
    const info = await invoke<{ arch: string; platform: string }>('get_system_info');
    // 架构名称美化：x86_64 → x64，aarch64 → ARM64
    const archLabel = info.arch === 'x86_64' ? 'x64' : info.arch === 'aarch64' ? 'ARM64' : info.arch;
    el('appArch').textContent = archLabel;
    // 平台名称美化
    const platformLabel = info.platform === 'windows' ? 'Windows' : info.platform;
    el('appPlatform').textContent = platformLabel;
  } catch (e) {
    console.warn('[about] 获取架构信息失败', e);
  }

  // 5. 关闭按钮
  try {
    el('btnClose').addEventListener('click', () => {
      void win?.hide();
    });
  } catch (e) {
    console.warn('[about] 关闭按钮绑定失败', e);
  }

  // 6. GitHub 链接
  try {
    el('linkGithub').addEventListener('click', (e) => {
      e.preventDefault();
      void open(GITHUB_PROFILE);
    });
    el('linkRepo').addEventListener('click', (e) => {
      e.preventDefault();
      void open(GITHUB_REPO);
    });
  } catch (e) {
    console.warn('[about] 链接绑定失败', e);
  }

  // 7. 检查更新
  try {
    el('btnCheckUpdate').addEventListener('click', () => {
      void checkUpdate();
    });
  } catch (e) {
    console.warn('[about] 检查更新按钮绑定失败', e);
  }

  // 8. 窗口关闭时隐藏而非销毁
  try {
    win?.onCloseRequested((event) => {
      event.preventDefault();
      void win?.hide();
    });
  } catch {
    /* ignore */
  }

  console.log('[about] 初始化完成');
}

void init();
