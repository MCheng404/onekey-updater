// 语言包完整性校验：
//  1) 中英 key 必须一一对应
//  2) 所有 t('...') / data-i18n* 引用到的 key 都必须存在于语言包
//  3) 模板串 key（group.${kind} / settings.preset.${id} / settings.pos.${name}Hint）逐项展开核对
import { readFileSync } from 'node:fs';

const src = readFileSync('src/i18n.ts', 'utf8');

function block(name) {
  const re = new RegExp(`const ${name}[^=]*= \\{([\\s\\S]*?)\\n\\};`);
  const m = src.match(re);
  if (!m) throw new Error('未找到语言块: ' + name);
  return m[1];
}
function keysOf(text) {
  const out = new Set();
  for (const m of text.matchAll(/'([A-Za-z0-9_.]+)':/g)) out.add(m[1]);
  return out;
}

const zh = keysOf(block('zhCN'));
const en = keysOf(block('enUS'));

let fail = 0;
console.log(`zh keys: ${zh.size}   en keys: ${en.size}`);
const onlyZh = [...zh].filter((k) => !en.has(k));
const onlyEn = [...en].filter((k) => !zh.has(k));
if (onlyZh.length) { console.log('❌ 英文缺失:', onlyZh); fail++; }
if (onlyEn.length) { console.log('❌ 仅英文有:', onlyEn); fail++; }
if (!fail) console.log('✅ 中英 key 完全对齐');

// ---- 收集引用 ----
const used = new Set();

// HTML: data-i18n / data-i18n-title / data-i18n-aria / data-i18n-placeholder
for (const h of ['index.html', 'settings.html', 'notify.html', 'about.html']) {
  const t = readFileSync(h, 'utf8');
  for (const m of t.matchAll(/data-i18n(?:-title|-aria|-placeholder)?="([^"]+)"/g)) used.add(m[1]);
}
// TS: 静态 t('...')，以及动态传入的 key
//     （setStatusLeft('status.x') / showEmpty('empty.x') / 三元里的 'status.ignoredCount' …）
//     统一用「形如 命名空间.路径 的字符串字面量」兜住。
const NS = 'common|app|btn|env|status|empty|group|log|action|notify|settings|about';
const keyLike = new RegExp(`'((?:${NS})\\.[A-Za-z0-9_.]+)'`, 'g');
for (const f of ['main.ts', 'settings.ts', 'about.ts', 'notify.ts', 'font-settings.ts']) {
  const t = readFileSync('src/' + f, 'utf8');
  for (const m of t.matchAll(/\bt\('([A-Za-z0-9_.]+)'/g)) used.add(m[1]);
  for (const m of t.matchAll(keyLike)) used.add(m[1]);
}
// TS: 模板串 key —— 逐项展开
const KINDS = ['npm', 'winget', 'pip', 'openclaw'];
const PRESETS = ['teal', 'azure', 'violet', 'amber', 'rose', 'jade'];
const POS = ['topLeft', 'topCenter', 'topRight', 'bottomLeft', 'bottomCenter', 'bottomRight'];
for (const k of KINDS) used.add(`group.${k}`);
for (const p of PRESETS) used.add(`settings.preset.${p}`);
for (const p of POS) { /* 位置名 + 位置提示 */ used.add(`settings.pos.${p}`); used.add(`settings.pos.${p}Hint`); }

const missing = [...used].filter((k) => !zh.has(k));
console.log(`引用到的 key: ${used.size}`);
if (missing.length) { console.log('❌ 语言包中不存在:', missing); fail++; }
else console.log('✅ 所有引用的 key 都存在');

// 反向：语言包里几乎没被引用的 key（提示清理，不算失败）
const unused = [...zh].filter((k) => !used.has(k));
if (unused.length) console.log('ℹ️  未被引用（可考虑清理）:', unused);

process.exit(fail ? 1 : 0);
