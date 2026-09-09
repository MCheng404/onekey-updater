/**
 * 应用设置：启动自动检查 + 忽略列表（忽略此版本 / 永久忽略）
 * 存储在 localStorage，主窗口与设置窗口通过 Tauri 事件同步。
 */

export const SETTINGS_KEY = 'onekey-updater.settings';
export const SETTINGS_CHANGED_EVENT = 'app-settings-changed';

export interface AppSettings {
  /** 启动时自动检查更新 */
  autoCheck: boolean;
  /** 忽略指定版本：itemId -> 被忽略的版本号。版本号变化后自动恢复显示。 */
  ignoreVersions: Record<string, string>;
  /** 永久忽略黑名单：itemId 列表。 */
  blacklist: string[];
}

export const DEFAULT_SETTINGS: AppSettings = {
  autoCheck: false,
  ignoreVersions: {},
  blacklist: [],
};

export function loadSettings(): AppSettings {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY);
    if (!raw) return { ...DEFAULT_SETTINGS };
    const parsed = JSON.parse(raw) as Partial<AppSettings>;
    return {
      autoCheck: parsed.autoCheck ?? false,
      ignoreVersions: parsed.ignoreVersions ?? {},
      blacklist: parsed.blacklist ?? [],
    };
  } catch {
    return { ...DEFAULT_SETTINGS };
  }
}

export function saveSettings(s: AppSettings): void {
  try {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(s));
  } catch {
    /* 存储不可用时忽略 */
  }
}

/** 判断一个更新项是否应被过滤（忽略此版本 或 永久忽略） */
export function isItemIgnored(
  itemId: string,
  latestVersion: string,
  s: AppSettings,
): boolean {
  if (s.blacklist.includes(itemId)) return true;
  const ignoredVer = s.ignoreVersions[itemId];
  if (ignoredVer && ignoredVer === latestVersion) return true;
  return false;
}

/** 忽略此版本：仅当最新版本号与当前一致时隐藏，版本变化后自动恢复 */
export function ignoreThisVersion(
  itemId: string,
  latestVersion: string,
  s: AppSettings,
): AppSettings {
  const next = { ...s, ignoreVersions: { ...s.ignoreVersions, [itemId]: latestVersion } };
  saveSettings(next);
  return next;
}

/** 永久忽略：加入黑名单 */
export function ignorePermanently(itemId: string, s: AppSettings): AppSettings {
  if (s.blacklist.includes(itemId)) return s;
  const next = { ...s, blacklist: [...s.blacklist, itemId] };
  saveSettings(next);
  return next;
}

/** 从忽略列表移除（同时清除版本忽略和永久忽略） */
export function removeFromIgnore(itemId: string, s: AppSettings): AppSettings {
  const nextIgnoreVersions = { ...s.ignoreVersions };
  delete nextIgnoreVersions[itemId];
  const nextBlacklist = s.blacklist.filter((id) => id !== itemId);
  const next = { ...s, ignoreVersions: nextIgnoreVersions, blacklist: nextBlacklist };
  saveSettings(next);
  return next;
}

/** 获取所有被忽略的条目（用于设置面板展示），返回 itemId -> 类型映射 */
export function listIgnored(s: AppSettings): Array<{ id: string; kind: 'version' | 'permanent'; version?: string }> {
  const out: Array<{ id: string; kind: 'version' | 'permanent'; version?: string }> = [];
  for (const [id, ver] of Object.entries(s.ignoreVersions)) {
    out.push({ id, kind: 'version', version: ver });
  }
  for (const id of s.blacklist) {
    if (!s.ignoreVersions[id]) {
      out.push({ id, kind: 'permanent' });
    }
  }
  return out;
}
