#!/usr/bin/env node
/**
 * check-i18n.mjs — i18n 编译检查：防漏译、多译、空译、占位符丢失。
 *
 * 检查项：
 *   1. KEY_MISSING    : zh-CN.json 中缺少 en.json 里的 key（漏译）
 *   2. KEY_EXTRA      : zh-CN.json 中有 en.json 里没有的 key（多译/残留）
 *   3. VALUE_EMPTY    : zh-CN.json 中值为空字符串（未翻译占位）
 *   4. VALUE_SAME     : zh-CN 值与 en 完全相同（可能漏译，纯英文品牌词除外）
 *   5. PLACEHOLDER    : en 中有 {var} 占位符但 zh 中缺失（会导致运行时显示异常）
 *   6. PLACEHOLDER_EXTRA: zh 中有 en 中没有的占位符
 *
 * 用法：
 *   node scripts/check-i18n.mjs
 *
 * 退出码：有任何错误返回 1（CI 阻断合并），全部通过返回 0。
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const EN_PATH = join(ROOT, 'locales', 'en.json');
const ZH_PATH = join(ROOT, 'locales', 'zh-CN.json');

const en = JSON.parse(readFileSync(EN_PATH, 'utf-8'));
const zh = JSON.parse(readFileSync(ZH_PATH, 'utf-8'));

const enKeys = Object.keys(en);
const zhKeys = Object.keys(zh);
const enSet = new Set(enKeys);
const zhSet = new Set(zhKeys);

// Brand / proper noun keys where zh == en is acceptable (product names, tech acronyms)
const BRAND_KEYS = new Set([
  'meta.title',
  'stats.soc_num',
  'nav.winlator',
  'winlator.intro_title',
  'settings.language_en',
  'card.winlator_cmod.name',
  'card.winlator_cmod.badge_cmod',
  'card.gamehub.name',
  'card.gamenative.name',
  'card.vkd3d.name',
  'card.vkd3d.badge',
  'card.dxvk.name',
  'card.box64.name',
  'card.fexcore.name',
]);

const errors = [];
const warnings = [];

// 1. Missing keys in zh
for (const key of enKeys) {
  if (!zhSet.has(key)) {
    errors.push({ type: 'KEY_MISSING', key, detail: `zh-CN.json 缺少 key（漏译）` });
  }
}

// 2. Extra keys in zh
for (const key of zhKeys) {
  if (!enSet.has(key)) {
    errors.push({ type: 'KEY_EXTRA', key, detail: `zh-CN.json 有多余 key（多译/残留）` });
  }
}

// 3-6. Per-key checks for shared keys
const placeholderRe = /\{(\w+)\}/g;

for (const key of enKeys) {
  if (!zhSet.has(key)) continue; // already reported as KEY_MISSING
  const enVal = en[key];
  const zhVal = zh[key];

  // 3. Empty value
  if (zhVal === '' || zhVal === null || zhVal === undefined) {
    errors.push({ type: 'VALUE_EMPTY', key, detail: `翻译为空（未翻译）` });
    continue;
  }

  // 4. Same as English (potential missed translation)
  if (enVal === zhVal && !BRAND_KEYS.has(key)) {
    // Only warn if the English value contains actual letters (not just symbols/numbers)
    if (/[a-zA-Z]{3,}/.test(enVal)) {
      warnings.push({ type: 'VALUE_SAME', key, detail: `译文与英文完全相同，疑似漏译："${enVal.slice(0, 60)}"` });
    }
  }

  // 5. Placeholder consistency
  const enPlaceholders = new Set();
  let pm;
  while ((pm = placeholderRe.exec(enVal)) !== null) enPlaceholders.add(pm[1]);
  placeholderRe.lastIndex = 0;

  const zhPlaceholders = new Set();
  while ((pm = placeholderRe.exec(zhVal)) !== null) zhPlaceholders.add(pm[1]);
  placeholderRe.lastIndex = 0;

  for (const p of enPlaceholders) {
    if (!zhPlaceholders.has(p)) {
      errors.push({ type: 'PLACEHOLDER_MISSING', key, detail: `译文缺少占位符 {${p}}（运行时会显示异常）` });
    }
  }
  for (const p of zhPlaceholders) {
    if (!enPlaceholders.has(p)) {
      errors.push({ type: 'PLACEHOLDER_EXTRA', key, detail: `译文有多余占位符 {${p}}（en 中不存在）` });
    }
  }
}

// ---- Report ----
console.log('=== I18N Compile Check ===');
console.log(`en.json keys    : ${enKeys.length}`);
console.log(`zh-CN.json keys : ${zhKeys.length}`);
console.log('');

if (errors.length > 0) {
  console.log(`❌ ERRORS (${errors.length}):`);
  errors.forEach(e => console.log(`   [${e.type}] ${e.key}\n      ${e.detail}`));
  console.log('');
}

if (warnings.length > 0) {
  console.log(`⚠️  WARNINGS (${warnings.length}):`);
  warnings.forEach(w => console.log(`   [${w.type}] ${w.key}\n      ${w.detail}`));
  console.log('');
}

if (errors.length === 0 && warnings.length === 0) {
  console.log('✅ All checks passed. No missing, extra, empty, or placeholder issues.');
} else if (errors.length === 0) {
  console.log('✅ No blocking errors. Warnings are informational only.');
}

process.exit(errors.length > 0 ? 1 : 0);
