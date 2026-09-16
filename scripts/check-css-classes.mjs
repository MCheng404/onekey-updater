// 找出 CSS 里"没人用"的类选择器。
//
// 为什么需要：改 HTML/JS 的类名时很容易漏改 CSS（或反过来）。本项目就踩过 ——
// 浅色模式的两条 .pos-mini .screen / .dot 规则，实际类名是 .pos-mini-frame / .pos-dot，
// 于是整个通知位置示意图在浅色下样式全丢，肉眼还看不出是"规则没生效"。
//
// 用法: node scripts/check-css-classes.mjs
// 退出码 1 表示发现可疑选择器。
import { readFileSync } from 'node:fs';

const css = readFileSync('src/styles.css', 'utf8');

// ---- 1. 收集 CSS 里出现的类选择器 ----
// 只看每条规则的选择器部分（`{` 之前），跳过声明块与 @ 规则名。
const cssClasses = new Set();
const withoutComments = css.replace(/\/\*[\s\S]*?\*\//g, '');
for (const m of withoutComments.matchAll(/(^|})\s*([^{}@]+)\{/g)) {
  const selector = m[2];
  for (const c of selector.matchAll(/\.(-?[_a-zA-Z][\w-]*)/g)) {
    cssClasses.add(c[1]);
  }
}

// ---- 2. 收集 HTML / TS 里真正用到的类名 ----
const used = new Set();

for (const f of ['index.html', 'settings.html', 'notify.html', 'about.html']) {
  const html = readFileSync(f, 'utf8');
  for (const m of html.matchAll(/class="([^"]*)"/g)) {
    for (const t of m[1].split(/\s+/)) if (t) used.add(t);
  }
}

for (const f of ['main.ts', 'settings.ts', 'notify.ts', 'about.ts', 'font-settings.ts', 'theme.ts', 'icons.ts', 'app-settings.ts', 'i18n.ts']) {
  const ts = readFileSync('src/' + f, 'utf8');
  // className = '...' / classList.add|remove|toggle|contains('...')
  for (const m of ts.matchAll(/className\s*=\s*[`'"]([^`'"]*)/g)) {
    for (const t of m[1].split(/\s+/)) if (/^[a-z][\w-]*$/i.test(t)) used.add(t);
  }
  for (const m of ts.matchAll(/classList\.(?:add|remove|toggle|contains)\(\s*'([^']+)'/g)) {
    used.add(m[1]);
  }
  // 模板串里拼出来的类名（如 `chip ${ok ? 'on' : 'off'}`）：把静态片段里的词也收进来
  for (const m of ts.matchAll(/`([^`$]*)`/g)) {
    for (const t of m[1].split(/[^A-Za-z0-9_-]+/)) if (t) used.add(t);
  }
}

// ---- 3. 报告 ----
// ⚠️ 忽略名单要尽量小。之前把 'screen' / 'dot' 也放过，
// 结果正好漏掉了 `.pos-mini .screen` 这类"选择器写错类名"的 bug。
// 现在只留确实抓不到的：三元表达式里拼的状态类（`chip ${ok ? 'on' : 'off'}`）。
const IGNORE = new Set(['on', 'off']);

// 「前缀 + 变量」拼出来的类名家族（如 `toast lv-${level}`、`toast-root dir-${x}`），
// 静态分析拿不到全名，按家族放行。**新的这类家族要显式加进来**，别去放宽 IGNORE。
const COMPOSED_PREFIXES = ['lv-', 'dir-', 'align-'];

const suspects = [...cssClasses]
  .filter(
    (c) =>
      !used.has(c) &&
      !IGNORE.has(c) &&
      !COMPOSED_PREFIXES.some((p) => c.startsWith(p)) &&
      c.length >= 4,
  )
  .sort();

console.log(`CSS 类选择器: ${cssClasses.size}   HTML/TS 中出现的类名: ${used.size}`);
if (suspects.length === 0) {
  console.log('✅ 没有发现"没人用"的类选择器');
  process.exit(0);
}
console.log(`⚠️  以下 ${suspects.length} 个类在 HTML/TS 里找不到（可能是类名改漏了）：`);
for (const s of suspects) console.log('   .' + s);
process.exit(1);
