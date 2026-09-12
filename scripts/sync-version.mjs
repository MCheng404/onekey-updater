// 把 5 处版本号统一改成传入的版本（取自 git tag，去掉前缀 v）。
// 用 Node 而非 PowerShell：避免中文系统下 UTF-8/GBK 误判、BOM 注入、行尾翻转、
// 以及 PowerShell 5.1 与 7 在 -replace scriptblock 上的行为差异。
// 仅做正则替换，不重新序列化 JSON，保留原有格式与中文。
import { readFileSync, writeFileSync } from 'node:fs';

const version = process.argv[2];
if (!version) {
  console.error('usage: node scripts/sync-version.mjs <version>');
  process.exit(1);
}
console.log(`Syncing version -> ${version}`);

const edit = (path, pattern, repl) => {
  const s = readFileSync(path, 'utf8').replace(pattern, repl);
  writeFileSync(path, s); // 默认 UTF-8 无 BOM，且保留原行尾
};

// package.json / tauri.conf.json: "version": "X"
edit('package.json', /("version"\s*:\s*")[^"]+(")/g, (_, g1, g2) => g1 + version + g2);
edit('src-tauri/tauri.conf.json', /("version"\s*:\s*")[^"]+(")/g, (_, g1, g2) => g1 + version + g2);
// Cargo.toml: version = "X"（[package] 段）
edit('src-tauri/Cargo.toml', /^version = "[^"]+"/m, `version = "${version}"`);
// about.html（仓库根目录）: <span ... id="appVersion">X</span>
edit('about.html', /(<span class="mono" id="appVersion">)[^<]+(<\/span>)/, (_, g1, g2) => g1 + version + g2);
// about.ts: 仅更新 catch 分支的兜底字面量（主版本来自 getVersion()）
edit('src/about.ts', /el\('appVersion'\)\.textContent = '[^']+'/, `el('appVersion').textContent = '${version}'`);

console.log('Done.');
