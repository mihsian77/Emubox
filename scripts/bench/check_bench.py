#!/usr/bin/env python3
"""跑分数据完整性校验：条数阈值防抓取失败/页面改版导致数据错乱"""
import json, os, sys
BASE = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'bench-data'))
a = json.load(open(os.path.join(BASE,'antutu_soc.json')))
l = json.load(open(os.path.join(BASE,'ludashi.json')))
a_total = sum(len(v) for v in a.values())
l_total = sum(len(v) for v in l.values())
ok = True
if a_total < 500: print('FAIL antutu total', a_total); ok=False
if l_total < 1500: print('FAIL ludashi total', l_total); ok=False
for k,v in a.items():
    if k=='level3' and len(v) < 20: print('FAIL',k,len(v)); ok=False
    elif k!='level3' and len(v) < 200: print('FAIL',k,len(v)); ok=False
print('antutu_total', a_total, '| ludashi_total', l_total, '|', 'OK' if ok else 'FAIL')
sys.exit(0 if ok else 1)
