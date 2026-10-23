#!/usr/bin/env python3
"""聚合两个 Winlator 社区配置仓库 → app/src/main/assets/winlator-recipes.json

数据源：
  - https://github.com/The412Banner/bannerlator-game-configs  (configs/*.json)
  - https://github.com/GunaCharanTeja/Winlator-Mali-Community-Configs (configs/*.json)

聚合口径：
  按 GPU 型号归组（Adreno-xxx / Mali-Gxxx），统计组件组合出现频率，
  每组输出 TOP3 组合 + 次数 + 占比，供 App 驱动指南「配置推荐」卡展示。

用法：
  python3 scripts/aggregate-recipes.py [--clone]   # --clone 时先 git clone 两份仓库
"""
import collections
import datetime
import json
import os
import re
import subprocess
import sys
import tempfile

BANNER_REPO = "https://github.com/The412Banner/bannerlator-game-configs.git"
MALI_REPO = "https://github.com/GunaCharanTeja/Winlator-Mali-Community-Configs.git"
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "app", "src", "main", "assets", "winlator-recipes.json")


def norm_gpu(s):
    if not s:
        return None
    s = str(s)
    m = re.search(r'(Adreno\s*TM\s*)?\s*(\d{3,4})|Mali[- ]?(G\d{2,3})', s)
    if m:
        g = m.group(3) or m.group(2)
        return ('Adreno' if m.group(2) else 'Mali') + '-' + g
    m2 = re.search(r'Adreno[^A-Za-z]*(\d+)', s, re.I)
    if m2:
        return 'Adreno-' + m2.group(1)
    if 'adreno' in s.lower():
        return 'Adreno'
    if 'mali' in s.lower():
        return 'Mali'
    return None


def walk_json(root):
    for d, _, fs in os.walk(root):
        for f in fs:
            if f.endswith('.json'):
                yield os.path.join(d, f)


def main():
    banner_dir = mali_dir = None
    if '--clone' in sys.argv:
        tmp = tempfile.mkdtemp(prefix='cfg-')
        banner_dir = os.path.join(tmp, 'banner')
        mali_dir = os.path.join(tmp, 'mali')
        subprocess.run(['git', 'clone', '--depth', '1', '-q', BANNER_REPO, banner_dir], check=True)
        subprocess.run(['git', 'clone', '--depth', '1', '-q', MALI_REPO, mali_dir], check=True)
    else:
        # 无 --clone 时从 /tmp/cfg 读取（CI 外本地调试用）
        banner_dir = os.path.join('/tmp', 'cfg', 'banner', 'configs')
        mali_dir = os.path.join('/tmp', 'cfg', 'mali', 'configs')
        banner_dir = banner_dir if os.path.isdir(banner_dir) else None
        mali_dir = mali_dir if os.path.isdir(mali_dir) else None
        if not banner_dir or not mali_dir:
            print('ERR: 需要 --clone 或 /tmp/cfg 存在', file=sys.stderr)
            sys.exit(1)

    banner_by_gpu = collections.defaultdict(collections.Counter)
    banner_cnt = collections.Counter()
    bcfg = os.path.join(banner_dir, 'configs') if os.path.isdir(os.path.join(banner_dir, 'configs')) else banner_dir
    for fp in walk_json(bcfg):
        try:
            d = json.load(open(fp))
            gpu = norm_gpu(d.get('meta', {}).get('soc', ''))
            comps = [(c.get('type', ''), c.get('name', '')) for c in d.get('components', [])]
            key = json.dumps(sorted(comps), ensure_ascii=False)
            banner_cnt[gpu] += 1
            if gpu:
                banner_by_gpu[gpu][key] += 1
        except Exception:
            pass

    mali_by_gpu = collections.defaultdict(collections.Counter)
    mali_cnt = collections.Counter()
    mcfg = os.path.join(mali_dir, 'configs') if os.path.isdir(os.path.join(mali_dir, 'configs')) else mali_dir
    for fp in walk_json(mcfg):
        try:
            d = json.load(open(fp))
            dev = d.get('meta', {}).get('device', {})
            gpu = norm_gpu(dev.get('gpu', '') if isinstance(dev, dict) else '')
            c = d.get('container', {})
            comps = [('Wine', c.get('wineVersion', '')), ('DxWrapper', c.get('dxwrapper', '')),
                     ('Box64', c.get('box64Version', '')), ('FEX', c.get('fexcoreVersion', '')),
                     ('Driver', c.get('graphicsDriver', ''))]
            comps = [x for x in comps if x[1]]
            key = json.dumps(sorted(comps), ensure_ascii=False)
            mali_cnt[gpu] += 1
            if gpu:
                mali_by_gpu[gpu][key] += 1
        except Exception:
            pass

    def top3(c):
        return [{'combo': json.loads(k), 'count': v,
                 'share': round(v / sum(c.values()) * 100, 1)} for k, v in c.most_common(3)]

    out = {
        "version": "1.2.0",
        "updated_at": datetime.datetime.now().strftime('%Y-%m-%dT00:00:00Z'),
        "description": "Winlator 社区配置推荐（统计层：聚合 bannerlator-game-configs + Winlator-Mali-Community-Configs；官方层：入门配置指引）",
        "generated": True,
        "gpu_combos": {g: {'configs': banner_cnt[g], 'top': top3(c)}
                       for g, c in sorted(banner_by_gpu.items(), key=lambda x: -sum(x[1].values()))},
        "mali_combos": {g: {'configs': mali_cnt[g], 'top': top3(c)}
                        for g, c in sorted(mali_by_gpu.items(), key=lambda x: -sum(x[1].values()))},
    }
    # 官方层：从 emulator-ranking.json 同步入门配置（Adreno/Mali 各一条）
    try:
        er = json.load(open(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                         'emulator-ranking.json')))
        recipes = er.get('recipes', [])
        if recipes:
            out['official_recipes'] = recipes
    except Exception as e:
        print('warn: 未同步 official_recipes', e, file=sys.stderr)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(out, open(OUT, 'w'), ensure_ascii=False, indent=1)
    print(f'OK  gpu={len(out["gpu_combos"])} mali={len(out["mali_combos"])} -> {OUT}')


if __name__ == '__main__':
    main()
