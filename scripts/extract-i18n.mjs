#!/usr/bin/env node
/**
 * extract-i18n.mjs — 从 index.html 提取所有可翻译字符，与 locales/en.json 对比。
 *
 * 功能：
 *   1. 扫描 data-i18n="key" 属性，收集 key 及对应英文文本
 *   2. 扫描 JS 中的 I18N.t('key') / this.t('key') 字面量调用
 *   3. 解析 EMU_CONFIG 中的 i18nKey，推导 card.<key>.name / card.<key>.description
 *   4. 与 locales/en.json 对比，报告：
 *      - MISSING_IN_EN: HTML/JS 中使用但 en.json 没有的 key
 *      - OBSOLETE_IN_EN: en.json 中有但 HTML/JS 中未使用的 key（仅供参考，不阻断）
 *      - VALUE_MISMATCH: data-i18n 文本与 en.json 值不一致
 *   5. 带 --fix 参数时，自动将 MISSING_IN_EN 的 key 补入 en.json
 *
 * 用法：
 *   node scripts/extract-i18n.mjs           # 仅报告
 *   node scripts/extract-i18n.mjs --fix     # 报告并自动补全 en.json
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const HTML_PATH = join(ROOT, 'index.html');
const EN_PATH = join(ROOT, 'locales', 'en.json');

const html = readFileSync(HTML_PATH, 'utf-8');
const enDict = JSON.parse(readFileSync(EN_PATH, 'utf-8'));

const found = new Map(); // key -> sourceEnglishText (may be undefined)

// ---- 1. data-i18n attributes ----
const dataI18nRe = /data-i18n="([^"]+)"[^>]*>([^<]*)</g;
let m;
while ((m = dataI18nRe.exec(html)) !== null) {
  const key = m[1];
  const text = m[2].trim();
  if (!found.has(key)) found.set(key, text);
}

// ---- 2. I18N.t('key') and this.t('key') literal calls ----
const tCallRe = /(?:I18N|this)\.t\(\s*['"`]([^'"`]+)['"`]/g;
while ((m = tCallRe.exec(html)) !== null) {
  const key = m[1];
  // Skip keys that are clearly partial (end with a dot — from string concatenation)
  if (key.endsWith('.')) continue;
  if (!found.has(key)) found.set(key, undefined);
}

// ---- 3. EMU_CONFIG: parse item i18nKeys to derive card.<key>.name/.description ----
// Extract the EMU_CONFIG block
const configMatch = html.match(/const EMU_CONFIG = \{([\s\S]*?)\n\};/);
if (configMatch) {
  const configBlock = configMatch[1];
  // Find all i18nKey values
  const i18nKeyRe = /i18nKey:\s*["']([^"']+)["']/g;
  const allKeys = [];
  while ((m = i18nKeyRe.exec(configBlock)) !== null) {
    allKeys.push(m[1]);
  }
  // Item keys: not "badge", not starting with "badge_"
  // Badge keys: "badge" or "badge_*"
  const itemKeys = allKeys.filter(k => k !== 'badge' && !k.startsWith('badge_'));
  for (const ck of itemKeys) {
    found.set(`card.${ck}.name`, undefined);
    found.set(`card.${ck}.description`, undefined);
  }
  // Badge keys: for each item key, we expect card.<itemKey>.<badgeKey>
  // Since we can't easily associate badges to items via regex, we derive all combinations
  // that exist in en.json (this is just for obsolete detection, not missing detection)
  const badgeKeys = allKeys.filter(k => k === 'badge' || k.startsWith('badge_'));
  for (const ik of itemKeys) {
    for (const bk of badgeKeys) {
      const candidate = `card.${ik}.${bk}`;
      // Only mark as "found" if it actually exists in en.json (avoid false missing)
      if (candidate in enDict) found.set(candidate, undefined);
    }
  }
}

// ---- 4. Compare with en.json ----
const missingInEn = [];
const obsoleteInEn = [];
const valueMismatch = [];

for (const [key, srcText] of found) {
  if (!(key in enDict)) {
    missingInEn.push({ key, srcText });
  } else if (srcText !== undefined && srcText !== '' && enDict[key] !== srcText) {
    valueMismatch.push({ key, html: srcText, en: enDict[key] });
  }
}

for (const key of Object.keys(enDict)) {
  if (!found.has(key)) {
    obsoleteInEn.push(key);
  }
}

// ---- 5. Report ----
console.log('=== I18N Extraction Report ===');
console.log(`Keys found in HTML/JS : ${found.size}`);
console.log(`Keys in en.json        : ${Object.keys(enDict).length}`);
console.log('');

if (missingInEn.length > 0) {
  console.log(`❌ MISSING_IN_EN (${missingInEn.length}): used in HTML/JS but not in en.json`);
  missingInEn.forEach(({ key, srcText }) => {
    console.log(`   - ${key}${srcText ? `  →  "${srcText.slice(0, 60)}"` : ''}`);
  });
  console.log('');
}

if (obsoleteInEn.length > 0) {
  console.log(`ℹ️  OBSOLETE_IN_EN (${obsoleteInEn.length}): in en.json but not auto-detected (may be used via string concatenation)`);
  obsoleteInEn.forEach(k => console.log(`   - ${k}`));
  console.log('');
}

if (valueMismatch.length > 0) {
  console.log(`ℹ️  VALUE_MISMATCH (${valueMismatch.length}): HTML text differs from en.json value`);
  valueMismatch.forEach(({ key, html: h, en }) => {
    console.log(`   - ${key}`);
    console.log(`     HTML: "${h.slice(0, 70)}"`);
    console.log(`     EN:   "${en.slice(0, 70)}"`);
  });
  console.log('');
}

if (missingInEn.length === 0) {
  console.log('✅ No missing keys. en.json is in sync with HTML/JS.');
}

// ---- 6. --fix ----
if (process.argv.includes('--fix') && missingInEn.length > 0) {
  for (const { key, srcText } of missingInEn) {
    enDict[key] = srcText || '';
  }
  const sorted = {};
  Object.keys(enDict).sort().forEach(k => { sorted[k] = enDict[k]; });
  writeFileSync(EN_PATH, JSON.stringify(sorted, null, 2) + '\n', 'utf-8');
  console.log(`✅ Added ${missingInEn.length} missing keys to en.json`);
}

process.exit(missingInEn.length > 0 ? 1 : 0);
