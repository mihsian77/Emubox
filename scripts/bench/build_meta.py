#!/usr/bin/env python3
"""生成 bench_meta.json：curated 别名映射 + 发布年表 + 模拟器分级阈值 + 同芯机型表
从 bench-data/antutu_soc.json + ludashi.json 实时解析真实分数，命中失败标 null（App 端显示数据缺失）
输出: app/src/main/assets/bench/bench_meta.json
"""
import json, os, re

BASE = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
a = json.load(open(os.path.join(BASE, 'bench-data', 'antutu_soc.json')))
l = json.load(open(os.path.join(BASE, 'bench-data', 'ludashi.json')))

antutu_names = [r['name'] for lv in ['level1','level2','level3'] for r in a[lv]]
antutu_by_name = {r['name']: r for lv in ['level1','level2','level3'] for r in a[lv]}

def find_antutu(kw, exclude=()):
    # 精确名优先（去掉厂商前缀）
    for n in antutu_names:
        base = n.split(' ', 1)[1] if ' ' in n and n.split(' ',1)[0] in ('高通','联发科技','三星','小米') else n
        if base == kw: return antutu_by_name[n]
    # 包含匹配，排除衍生词（+、e、Gen N、超级、领先版等）
    for n in antutu_names:
        if kw in n and not any(x in n for x in exclude): return antutu_by_name[n]
    return None

def find_ludashi(kw):
    for r in l['soc']:
        if kw in r['name']: return r
    return None

def find_phone(kw):
    for r in l['phone']:
        if kw in r['name']: return r
    return None

# ---------------- curated 别名表 ----------------
# keys: 设备侧常见名（营销名/型号）；antutu: 安兔兔匹配关键词；ludashi: 鲁大师 soc 榜匹配关键词；year: 发布年月
ALIASES = [
    # 骁龙 8 系列（旗舰）
    {"keys":["骁龙888","SM8350"], "antutu":"骁龙888", "antutu_exclude":["Plus"], "ludashi":"骁龙 888", "year":"2020-12", "family":"骁龙8"},
    {"keys":["骁龙8 Gen1","骁龙8 Gen 1","SM8450"], "antutu":"骁龙8 Gen 1", "ludashi":"骁龙 8 Gen1", "year":"2021-12", "family":"骁龙8"},
    {"keys":["骁龙8 Gen2","骁龙8 Gen 2","SM8550"], "antutu":"骁龙8 Gen 2", "ludashi":"骁龙 8 Gen2", "year":"2022-11", "family":"骁龙8"},
    {"keys":["骁龙8 Gen3","骁龙8 Gen 3","SM8650"], "antutu":"骁龙8 Gen 3", "ludashi":"骁龙 8 Gen3", "year":"2023-10", "family":"骁龙8"},
    {"keys":["骁龙8 Elite","骁龙8 至尊版","SM8750"], "antutu":"骁龙8 至尊版", "antutu_exclude":["Gen","超级"], "ludashi":"骁龙 8 至尊版", "year":"2024-10", "family":"骁龙8 Elite"},
    {"keys":["骁龙8 Elite Gen5","骁龙8 至尊版 Gen5"], "antutu":"骁龙8 至尊版 Gen5", "ludashi":"骁龙 8 Elite Gen5", "year":"2025-10", "family":"骁龙8 Elite"},
    {"keys":["骁龙8 Elite Gen6","骁龙8 至尊版 Gen6"], "antutu":"骁龙8 至尊版 Gen 6", "ludashi":"骁龙 8 Elite Gen6", "year":"2026-10", "family":"骁龙8 Elite"},
    {"keys":["骁龙8 Elite Extreme","骁龙8 超级至尊版"], "antutu":"骁龙8 超级至尊版 Gen 6", "ludashi":"骁龙 8 Elite Extreme", "year":"2026-12", "family":"骁龙8 Elite"},
    # 骁龙 8s / 8+ 衍生
    {"keys":["骁龙8s Gen3"], "antutu":"骁龙8s Gen 3", "ludashi":"骁龙 8s Gen3", "year":"2024-03", "family":"骁龙8s"},
    {"keys":["骁龙8s Gen4"], "antutu":"骁龙8s Gen 4", "ludashi":"骁龙 8s Gen4", "year":"2025-04", "family":"骁龙8s"},
    # 骁龙 7 中端
    {"keys":["骁龙7 Gen3"], "antutu":"骁龙7 Gen 3", "ludashi":None, "year":"2023-11", "family":"骁龙7"},
    {"keys":["骁龙7s Gen3"], "antutu":"骁龙7s Gen 3", "ludashi":None, "year":"2024-08", "family":"骁龙7s"},
    {"keys":["骁龙7 Gen4"], "antutu":"骁龙7 Gen 4", "ludashi":None, "year":"2025-02", "family":"骁龙7"},
    # 天玑旗舰
    {"keys":["天玑9000","MT6983"], "antutu":"天玑9000", "antutu_exclude":["+"], "ludashi":"天玑9000", "year":"2021-12", "family":"天玑旗舰"},
    {"keys":["天玑9200","MT6985"], "antutu":"天玑9200", "antutu_exclude":["+"], "ludashi":"天玑9200", "year":"2022-11", "family":"天玑旗舰"},
    {"keys":["天玑9300","MT6989"], "antutu":"天玑9300", "antutu_exclude":["+"], "ludashi":"天玑9300", "year":"2023-11", "family":"天玑旗舰"},
    {"keys":["天玑9400","MT6995"], "antutu":"天玑9400", "antutu_exclude":["+","e"], "ludashi":"天玑9400", "year":"2024-10", "family":"天玑旗舰"},
    {"keys":["天玑9500"], "antutu":"天玑9500", "antutu_exclude":["M","s"], "ludashi":"天玑9500", "year":"2025-10", "family":"天玑旗舰"},
    {"keys":["天玑9600"], "antutu":"天玑9600", "antutu_exclude":["M","s","Pro"], "ludashi":"天玑9600", "year":"2026-08", "family":"天玑旗舰"},
    {"keys":["天玑9400+"],"antutu":"天玑9400+", "ludashi":"天玑9400+", "year":"2025-06", "family":"天玑旗舰"},
    # 苹果 A 系列
    {"keys":["A15"], "antutu":"A15", "antutu_exclude":["Plus"], "ludashi":None, "year":"2021-09", "family":"Apple A"},
    {"keys":["A16"], "antutu":"A16", "antutu_exclude":["Pro"], "ludashi":None, "year":"2022-09", "family":"Apple A"},
    {"keys":["A17 Pro","A17Pro"], "antutu":"A17", "ludashi":None, "year":"2023-09", "family":"Apple A"},
    {"keys":["A18 Pro","A18Pro"], "antutu":"A18", "ludashi":None, "year":"2024-09", "family":"Apple A"},
    {"keys":["A19 Pro","A19Pro"], "antutu":"A19", "ludashi":None, "year":"2025-09", "family":"Apple A"},
    # 三星 Exynos
    {"keys":["Exynos 2200"], "antutu":"Exynos 2200", "ludashi":None, "year":"2022-01", "family":"Exynos"},
    {"keys":["Exynos 2400"], "antutu":"Exynos 2400", "ludashi":None, "year":"2024-01", "family":"Exynos"},
    {"keys":["Exynos 2500"], "antutu":"Exynos 2500", "ludashi":None, "year":"2025-01", "family":"Exynos"},
]

# ---------------- 同芯机型 curated 表（鲁大师手机性能榜内取分） ----------------
SAME_CHIP = [
    {"soc": "骁龙8 Elite", "devices": ["小米15 Pro", "iQOO 13", "一加13", "红魔10 Pro", "荣耀Magic7 Pro"]},
    {"soc": "骁龙8 Gen 3", "devices": ["小米14", "iQOO 12", "一加12", "红魔9 Pro", "魅族21"]},
    {"soc": "骁龙8 Gen 2", "devices": ["小米13", "iQOO 11", "一加11", "红魔8 Pro", "三星Galaxy S23"]},
    {"soc": "天玑9400", "devices": ["vivo X200 Pro", "OPPO Find X8 Pro", "iQOO Neo10 Pro"]},
    {"soc": "天玑9300", "devices": ["vivo X100 Pro", "OPPO Find X7 Ultra"]},
]

# ---------------- 模拟器分级阈值（安兔兔总分体系；经验值，待实测校准） ----------------
EMU_TIERS = {
    "winlator": {"smooth": 2800000, "playable": 2000000, "heavy": 1200000, "note": "Win 游戏容器（含 DXVK/VKD3D 需求）"},
    "ns":       {"smooth": 2600000, "playable": 1800000, "heavy": 1000000, "note": "Switch（GPU 负载重，优先看 GPU 分）"},
    "ps2":      {"smooth": 1500000, "playable": 900000,  "heavy": 500000,  "note": "PS2"},
    "gc_wii":   {"smooth": 1800000, "playable": 1000000, "heavy": 600000,  "note": "NGC/Wii"},
    "ds3ds":    {"smooth": 1200000, "playable": 600000,  "heavy": 300000,  "note": "3DS"},
    "psp":      {"smooth": 800000,  "playable": 400000,  "heavy": 200000,  "note": "PSP（低要求）"},
}

# ---------------- PC 显卡规格（显存带宽/FP32算力；来源：厂商公开规格 + TechPowerUp 数据库） ----------------
PC_GPU_SPECS = [
    # name, 显存带宽 GB/s, FP32 TFLOPS
    ["NVIDIA GeForce RTX 4090", 1008, 82.6],
    ["NVIDIA GeForce RTX 4080", 717, 48.7],
    ["NVIDIA GeForce RTX 4070 Ti", 504, 40.1],
    ["NVIDIA GeForce RTX 4070", 504, 29.1],
    ["NVIDIA GeForce RTX 4060 Ti", 288, 22.1],
    ["NVIDIA GeForce RTX 4060", 272, 15.1],
    ["NVIDIA GeForce RTX 3080", 760, 29.8],
    ["NVIDIA GeForce RTX 3070", 448, 20.3],
    ["NVIDIA GeForce RTX 3060", 360, 12.7],
    ["NVIDIA GeForce GTX 1080", 320, 8.9],
    ["NVIDIA GeForce GTX 1060", 192, 4.4],
    ["AMD Radeon RX 7900 XTX", 960, 61.4],
    ["AMD Radeon RX 7900 XT", 800, 51.5],
    ["AMD Radeon RX 7800 XT", 624, 37.3],
    ["AMD Radeon RX 7700 XT", 432, 28.3],
    ["AMD Radeon RX 6750 XT", 384, 13.4],
]

# GPU 等效显卡：自占比法（鲁大师同分制分 × 安兔兔GPU占比）→ 桌面GPU榜最近邻
def gpu_equiv(antutu_gpu, antutu_total, ludashi_score):
    if not (antutu_gpu and antutu_total and ludashi_score):
        return None
    ratio = int(antutu_gpu) / int(antutu_total)
    est = int(ludashi_score) * ratio
    best = min(l['desktop_gpu'], key=lambda r: abs(int(r['score']) - est)) if l['desktop_gpu'] else None
    if best is None: return None
    return {"name": best['name'], "score": int(best['score']), "est": int(est), "ratio": round(ratio, 3)}

meta = {
    "version": "2",
    "aliases": [],
    "emulator_tiers": EMU_TIERS,
    "same_chip_devices": [],
    "pc_gpu_specs": PC_GPU_SPECS,
}

for al in ALIASES:
    aa = find_antutu(al["antutu"], tuple(al.get("antutu_exclude") or ())) if al["antutu"] else None
    ls = find_ludashi(al["ludashi"]) if al["ludashi"] else None
    meta["aliases"].append({
        "keys": al["keys"],
        "family": al["family"],
        "year": al["year"],
        "antutu_name": aa["name"] if aa else None,
        "antutu_total": aa["total_score"] if aa else None,
        "antutu_cpu": aa["cpu_score"] if aa else None,
        "antutu_gpu": aa["gpu_score"] if aa else None,
        "antutu_clusters": aa["clusters"] if aa else None,
        "ludashi_score": ls["score"] if ls else None,
        "gpu_equiv": gpu_equiv(aa["gpu_score"] if aa else None, aa["total_score"] if aa else None, ls["score"] if ls else None),
    })

for sc in SAME_CHIP:
    devs = []
    for d in sc["devices"]:
        p = find_phone(d)
        devs.append({"name": d, "score": p["score"] if p else None})
    meta["same_chip_devices"].append({"soc": sc["soc"], "devices": devs})

out_path = os.path.join(BASE, 'app', 'src', 'main', 'assets', 'bench', 'bench_meta.json')
os.makedirs(os.path.dirname(out_path), exist_ok=True)
json.dump(meta, open(out_path, 'w'), ensure_ascii=False, indent=1)
print('written', out_path)
hit = sum(1 for a_ in meta['aliases'] if a_['antutu_total'])
print('aliases', len(meta['aliases']), '| antutu命中', hit, '| 缺失', len(meta['aliases'])-hit)
