#!/usr/bin/env node
/**
 * 维基百科 SoC 列表抓取 → 补全芯片规格（制程/GPU/发布时间）。
 * 数据源：en.wikipedia.org List of ... systems on chips（统一表格结构）
 * 输出：bench-data/soc_specs_wiki_raw.json（[{chip, process, gpu, year, vendor}]）
 *
 * 仅用原生 fetch（维基 API JSON），无浏览器依赖。
 * 注意：本地沙箱网络无法访问维基，本脚本设计为在 CI（GitHub Actions）运行验证。
 */
const fs = require('fs');
const path = require('path');

const OUT = process.env.OUT_DIR || path.resolve(__dirname, '../../bench-data');
const PAGES = [
  { title: 'List of Qualcomm Snapdragon systems on chips', vendor: 'Qualcomm' },
  { title: 'List of MediaTek systems on chips', vendor: 'MediaTek' },
  { title: 'Apple silicon', vendor: 'Apple' },
  { title: 'Kirin (chip)', vendor: 'Huawei' },
  { title: 'Exynos', vendor: 'Samsung' },
];

const UA = 'EmuBox-Bench/1.0 (emubox project; https://github.com/mihsian77/EmuHub-CN)';

async function fetchSections(title) {
  const url = `https://en.wikipedia.org/w/api.php?action=parse&page=${encodeURIComponent(title)}&prop=sections&format=json&origin=*`;
  const r = await fetch(url, { headers: { 'User-Agent': UA } });
  if (!r.ok) throw new Error(`${title} sections HTTP ${r.status}`);
  return r.json().then(d => d.parse?.sections || []);
}

/** 抓指定 section 的 HTML，抽取表格行单元格文本 */
async function fetchSectionHtml(title, index) {
  const url = `https://en.wikipedia.org/w/api.php?action=parse&page=${encodeURIComponent(title)}&prop=text&section=${index}&format=json&origin=*`;
  const r = await fetch(url, { headers: { 'User-Agent': UA } });
  if (!r.ok) throw new Error(`${title}#${index} HTTP ${r.status}`);
  const d = await r.json();
  const html = d.parse?.text?.['*'] || '';
  const rows = [];
  const trRe = /<tr[^>]*>([\s\S]*?)<\/tr>/g;
  let m;
  while ((m = trRe.exec(html))) {
    const cells = [];
    const tdRe = /<t[dh][^>]*>([\s\S]*?)<\/t[dh]>/g;
    let c;
    while ((c = tdRe.exec(m[1]))) {
      cells.push(stripHtml(c[1]));
    }
    if (cells.length >= 3) rows.push(cells);
  }
  return rows;
}

function stripHtml(s) {
  return s
    .replace(/<[^>]+>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function normChip(s) {
  return s.replace(/\s+/g, '').toLowerCase();
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true });
  const out = { fetched_at: new Date().toISOString(), sources: PAGES.map(p => p.title), entries: [] };
  for (const page of PAGES) {
    try {
      const sections = await fetchSections(page.title);
      // 找含列表的 section（跳过参考资料/图库等）
      const listSecs = sections.filter(s =>
        /list|series|model/i.test(s.line) && !/reference|gallery|see also|external/i.test(s.line)
      );
      if (!listSecs.length) {
        console.warn(`[warn] ${page.title}: 无列表 section，取全部前 5 个`);
        listSecs.push(...sections.filter(s => s.toclevel === 1).slice(0, 5));
      }
      let count = 0;
      for (const sec of listSecs.slice(0, 6)) {
        try {
          const rows = await fetchSectionHtml(page.title, sec.index);
          for (const row of rows) {
            // 尝试按常见列位提取：chip 名 + 制程/CPU/GPU/年份（列位因页而异，宽松匹配）
            const joined = row.join(' | ');
            const year = (joined.match(/20\d{2}/) || [null])[0];
            const process = (joined.match(/\d+\s?n\s?m/) || [null])[0];
            const gpu = (joined.match(/Adreno\s*\d+|Mali\s*-?\s*G?\d+|Apple\s*\d+|Immortalis\s*-?\s*G?\d+/) || [null])[0];
            const chip = row[0].replace(/\([^)]*\)/g, '').trim();
            if (!chip || chip.length > 40 || /^\d+$/.test(chip)) continue;
            out.entries.push({ vendor: page.vendor, chip, process: process || '', gpu: gpu || '', year: year || '' });
            count++;
            if (count >= 400) break;
          }
        } catch (e) {
          console.warn(`[warn] ${page.title}#${sec.index}: ${e.message}`);
        }
        if (count >= 400) break;
      }
      console.log(`[ok] ${page.title}: ${count} 行`);
    } catch (e) {
      console.warn(`[warn] ${page.title}: ${e.message}`);
    }
  }
  // 去重（同 chip 名保留首个非空字段最全的）
  const dedup = new Map();
  for (const e of out.entries) {
    const k = normChip(e.chip);
    if (!dedup.has(k)) dedup.set(k, e);
    else {
      const old = dedup.get(k);
      dedup.set(k, { ...old,
        process: old.process || e.process,
        gpu: old.gpu || e.gpu,
        year: old.year || e.year });
    }
  }
  out.entries = [...dedup.values()];
  const fp = path.join(OUT, 'soc_specs_wiki_raw.json');
  fs.writeFileSync(fp, JSON.stringify(out, null, 1));
  console.log(`saved ${fp} entries=${out.entries.length}`);
}

main().catch(e => { console.error('ERR', e.message); process.exit(1); });
