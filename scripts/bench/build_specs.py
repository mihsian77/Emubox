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
        out['socs'][name] = entry

    os.makedirs(os.path.dirname(OUT_BENCH), exist_ok=True)
    os.makedirs(os.path.dirname(OUT_ASSETS), exist_ok=True)
    json.dump(out, open(OUT_BENCH, 'w'), ensure_ascii=False, indent=1)
    json.dump(out, open(OUT_ASSETS, 'w'), ensure_ascii=False, indent=1)
    print(f'OK socs={len(out["socs"])} 有频率={len(out["socs"])-len(missing)} 缺={len(missing)}')
    if missing:
        print('缺频率:', missing)


if __name__ == '__main__':
    main()
