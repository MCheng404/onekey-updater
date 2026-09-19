/**
 * CSS 自定义属性（--x）的交叉检查器。
 *
 * 为什么需要它：`check-css-classes.mjs` 只查类选择器，查不到自定义属性。
 * 而自定义属性有个更隐蔽的使用方式 —— **TS 里用字符串拼接消费**：
 *
 *     const base = 'var(--toast-base, rgba(...))';
 *     card.style.background = `linear-gradient(${base}, ${base})`;
 *
 * 于是「只 grep 样式表」会把仍在使用的变量误判成死代码（我犯过这个错，
 * 删掉 --toast-base 导致浅色模式下通知卡片变深色底）。
 *
 * 两个方向都报：
 *   1. 【死变量】定义了，但全项目既没人 var() 读、也没人 setProperty() 写
 *   2. 【未定义】有人 var() 读，但全项目找不到定义 —— 会静默走 var() 的兜底值，
 *      而这个方向更危险：不报错、界面直接错色/失效（--toast-base 就是被这么搞坏的）
 *
 * 用法：node scripts/check-css-vars.mjs
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = process.cwd();
const CSS = join(ROOT, 'src', 'styles.css');

/** 需要扫描的源码：CSS + TS + HTML（含内联 style 与 var() 字符串） */
function collectFiles() {
  const out = [CSS];
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      if (name === 'node_modules' || name.startsWith('.')) continue;
      const p = join(dir, name);
      if (statSync(p).isDirectory()) {
        walk(p);
      } else if (/\.(ts|html|css)$/.test(name)) {
        out.push(p);
      }
    }
  };
  walk(join(ROOT, 'src'));
  for (const f of ['index.html', 'settings.html', 'notify.html', 'about.html']) {
    out.push(join(ROOT, f));
  }
  return out;
}

const DEFINITION = /(^|[\s{;])(--[\w-]+)\s*:/g;
const USE = /var\(\s*(--[\w-]+)/g;
const WRITE = /setProperty\(\s*['"`](--[\w-]+)/g;

function scan() {
  const defined = new Map(); // name -> 定义所在文件
  const used = new Map(); // name -> 使用所在文件
  const written = new Map(); // name -> 写入所在文件

  for (const file of collectFiles()) {
    const text = readFileSync(file, 'utf8');
    const rel = relative(ROOT, file).replace(/\\/g, '/');
    const isCss = file.endsWith('.css');

    // 定义只在 CSS 里找（TS 里的 setProperty 算"写入"，另算）
    if (isCss) {
      for (const m of text.matchAll(DEFINITION)) {
        if (!defined.has(m[2])) defined.set(m[2], rel);
      }
    }
    for (const m of text.matchAll(USE)) {
      if (!used.has(m[1])) used.set(m[1], rel);
    }
    for (const m of text.matchAll(WRITE)) {
      if (!written.has(m[1])) written.set(m[1], rel);
    }
  }
  return { defined, used, written };
}

function main() {
  const { defined, used, written } = scan();

  // 1) 定义了但无人使用（读 + 写都没有）
  const dead = [...defined.keys()]
    .filter((k) => !used.has(k) && !written.has(k))
    .sort();

  // 2) 使用了但没定义 —— 会静默走 var() 兜底值
  const undef = [...used.keys()]
    .filter((k) => !defined.has(k) && !written.has(k))
    .sort();

  // 3) 只写不读 / 只读不写，供人工判断
  const writeOnly = [...written.keys()]
    .filter((k) => !used.has(k))
    .sort();

  console.log(
    `CSS 自定义属性：定义 ${defined.size}  读取 ${used.size}  写入(T S) ${written.size}`,
  );

  if (dead.length) {
    console.log(`\n❌ 死变量（定义了但全项目没人用）：${dead.length}`);
    for (const k of dead) console.log(`   ${k}  ← 定义于 ${defined.get(k)}`);
  } else {
    console.log('\n✅ 没有死变量');
  }

  if (undef.length) {
    console.log(`\n⚠️  使用了但没有定义（会静默走兜底值）：${undef.length}`);
    for (const k of undef) console.log(`   ${k}  ← 使用于 ${used.get(k)}`);
  } else {
    console.log('✅ 没有"用了但没定义"的变量');
  }

  if (writeOnly.length) {
    console.log(`\nℹ️  只被 TS 写、CSS 从不读取：${writeOnly.length}（多为有意，供确认）`);
    for (const k of writeOnly) console.log(`   ${k}  ← 写于 ${written.get(k)}`);
  }

  // 有真正的错误才非零退出（只写不读属于提示，不算失败）
  process.exit(dead.length || undef.length ? 1 : 0);
}

main();
