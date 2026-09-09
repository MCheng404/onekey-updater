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
import { t, getLang, watchLang } from './i18n';

// GitHub 配置
const GITHUB_PROFILE = 'https://github.com/MCheng404';
const GITHUB_REPO = 'https://github.com/MCheng404/onekey-updater';
const REPO_API = 'https://api.github.com/repos/MCheng404/onekey-updater/releases/latest';

let win: ReturnType<typeof getCurrentWindow> | null = null;

function el<T extends HTMLElement = HTMLElement>(id: string): T {
  const e = document.getElementById(id);
  if (!e) throw new Error(`缺少 #${id}`);
  return e as T;
}

/** 应用 i18n 文本到所有 data-i18n 元素 */
function applyI18n(): void {
  document.querySelectorAll<HTMLElement>('[data-i18n]').forEach((node) => {
    const key = node.dataset.i18n;
    if (key) node.textContent = t(key);
  });
  document.title = t('about.title');
}

/** 检查 GitHub 最新版本 */
async function checkUpdate(): Promise<void> {
  const btn = el<HTMLButtonElement>('btnCheckUpdate');
  const status = el<HTMLElement>('updateStatus');

  btn.disabled = true;
  status.hidden = false;
  status.textContent = t('about.checking');
  status.className = 'about-update-status checking';

  try {
    if (!REPO_API) {
      // 仓库未配置时，显示提示
      status.textContent = t('about.upToDate');
      status.className = 'about-update-status ok';
      return;
    }

    const res = await fetch(REPO_API, {
      headers: { Accept: 'application/vnd.github.v3+json' },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);

    const data = await res.json();
    const latest = data.tag_name?.replace(/^v/, '') ?? '';
    const current = await getVersion();

    if (latest && latest !== current) {
      status.innerHTML = `${t('about.newVersion')}: <span class="mono">v${latest}</span>`;
      status.className = 'about-update-status new';
      // 添加下载按钮
      const downloadBtn = document.createElement('button');
      downloadBtn.className = 'btn btn-primary';
      downloadBtn.style.marginLeft = '8px';
      downloadBtn.textContent = t('about.download');
      downloadBtn.addEventListener('click', () => {
        void open(data.html_url ?? GITHUB_REPO);
      });
      status.append(downloadBtn);
    } else {
      status.textContent = t('about.upToDate');
      status.className = 'about-update-status ok';
    }
  } catch (e) {
    console.warn('[about] 检查更新失败', e);
    status.textContent = t('about.upToDate');
    status.className = 'about-update-status ok';
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
    el('appVersion').textContent = '0.2.0';
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
