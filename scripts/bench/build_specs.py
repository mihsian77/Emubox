#!/usr/bin/env python3
"""从安兔兔 SoC 天梯生成芯片规格表 soc_specs.json（CPU 架构/频率，全自动）。

数据源：bench-data/antutu_soc.json（level1/2/3 自带 clusters 字段）
输出：bench-data/soc_specs.json + app/src/main/assets/bench/soc_specs.json
后续维基补全（制程/GPU/发布时间）写入同一文件的 process/gpu/year 键（缺失留空）。

用法：python3 scripts/bench/build_specs.py
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from jsonio import save, sniff  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, 'bench-data', 'antutu_soc.json')
OUT_BENCH = os.path.join(ROOT, 'bench-data', 'soc_specs.json')
OUT_ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'bench', 'soc_specs.json')


def parse_clusters(c):
    if not c:
        return None
    groups = re.findall(r'(\d+)x\s*([\d.]+)\s*GHz\s*([^&()]+)', c)
    if not groups:
        return None
    cores = [{'count': int(g[0]), 'freq_ghz': float(g[1]), 'core': g[2].strip()} for g in groups]
    return {
        'groups': cores,
        'total_cores': sum(x['count'] for x in cores),
        'max_freq_ghz': max(x['freq_ghz'] for x in cores),
        'cpu_summary': ' + '.join(f"{g['count']}×{g['freq_ghz']}GHz {g['core']}" for g in cores),
    }


def main():
    a = json.load(open(SRC))
    seen = {}
    for lv in ('level1', 'level2', 'level3'):
        for it in a.get(lv, []):
            name = it.get('name', '').strip()
            # 过滤解析错位的行：安兔兔网页偶发把分数列当名称抓成 "15930 分" 这类
            if not name or not re.search(r'[a-zA-Z]', name):
                continue
            seen.setdefault(name, {**it, 'level': lv})

    # 维基补全（存在 soc_specs_wiki_raw.json 时合并 process/gpu/year）
    wiki = {}
    wiki_fp = os.path.join(ROOT, 'bench-data', 'soc_specs_wiki_raw.json')
    if os.path.exists(wiki_fp):
        wd = json.load(open(wiki_fp))
        for e in wd.get('entries', []):
            key = re.sub(r'[^a-z0-9]', '', (e.get('chip') or '').lower())
            if key:
                wiki.setdefault(key, e)
        print(f'维基补全源: {len(wiki)} 条目')

    # 上一版产物：维基源缺失时用它兜底，避免 process/gpu/year 整片被抹掉
    prev = {}
    if os.path.exists(OUT_BENCH):
        try:
            with open(OUT_BENCH, encoding='utf-8') as f:
                prev = (json.load(f) or {}).get('socs') or {}
        except (OSError, json.JSONDecodeError) as e:
            print(f'[warn] 读不到上一版 soc_specs（{e}），制程/GPU 字段无法兜底')

    out = {'version': '1.0.0',
           'note': 'CPU 架构/频率来自安兔兔 SoC 天梯内置字段；制程/GPU/发布时间由维基列表管线补全（缺失留空）',
           'socs': {}}
    missing = []
    for name in sorted(seen):
        it = seen[name]
        spec = parse_clusters(it.get('clusters', ''))
        entry = {
            'level': it.get('level'),
            'total_score': it.get('total_score'),
            'cpu_score': it.get('cpu_score'),
            'gpu_score': it.get('gpu_score'),
        }
        if spec:
            entry['cores'] = spec['groups']
            entry['total_cores'] = spec['total_cores']
            entry['max_freq_ghz'] = spec['max_freq_ghz']
            entry['cpu_summary'] = spec['cpu_summary']
        else:
            missing.append(name)
        # 维基匹配：型号数字（如 8 Gen 3 / 888）模糊匹配
        if wiki:
            key = re.sub(r'[^a-z0-9]', '', name.lower())
            hit = None
            for wkey, w in wiki.items():
                # 双向包含（名称或型号数字）
                if wkey and (wkey in key or key in wkey):
                    hit = w
                    break
            if hit:
                entry['process'] = hit.get('process', '')
                entry['gpu'] = hit.get('gpu', '')
                entry['year'] = hit.get('year', '')
        # 维基源缺失时沿用上一版：soc_specs_wiki_raw.json 被 .gitignore 排除，
        # 只在 CI 里由 fetch_soc_specs.js 现抓。抓取失败时上面这段不会执行，
        # 若直接覆盖就会把 200+ 条的制程/GPU/发布时间静默抹掉（实测 -288 行），
        # 而 step 又带 continue-on-error，坏了也没人知道。
        if not wiki:
            old = prev.get(name) or {}
            for field in ('process', 'gpu', 'year'):
                # 空串也要原样保留：仓库里的历史条目就是「键在、值为空」，
                # 只补非空值会让整个文件每次重放都丢掉这些键。
                if field not in entry and field in old:
                    entry[field] = old[field]
        out['socs'][name] = entry

    os.makedirs(os.path.dirname(OUT_BENCH), exist_ok=True)
    os.makedirs(os.path.dirname(OUT_ASSETS), exist_ok=True)
    # bench-data 与 assets 副本必须同字节：共用主文件嗅探出的 style
    st = sniff(OUT_BENCH)
    save(OUT_BENCH, out, style=st)
    save(OUT_ASSETS, out, style=st)
    print(f'OK socs={len(out["socs"])} 有频率={len(out["socs"])-len(missing)} 缺={len(missing)}')
    if missing:
        print('缺频率:', missing)


if __name__ == '__main__':
    main()
