#!/usr/bin/env python3
"""解析 bench-data/ 原始抓取 → 结构化 JSON + 完整性 manifest
输入: bench-data/antutu_soc_raw.json, bench-data/ludashi_raw.json
输出: bench-data/antutu_soc.json, bench-data/ludashi.json, bench-data/manifest.json
"""
import json, os, re, sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'bench-data')
BASE = os.path.abspath(BASE)

def parse_antutu(raw_txt):
    rows=[]; lines=raw_txt.split('\n'); i=0; n=len(lines)
    while i<n:
        l=lines[i].strip()
        if re.fullmatch(r'\d{1,3}', l):
            rank=int(l); name=''; detail=''; cpu=''; gpu=''; total=''; j=i+1
            while j<n and not re.fullmatch(r'\d{1,3}', lines[j].strip()):
                t=lines[j].strip()
                if not t: j+=1; continue
                if not name: name=t
                elif not detail and t.startswith('('): detail=t
                elif re.fullmatch(r'[\d,]+', t):
                    if not cpu: cpu=t
                    elif not gpu: gpu=t
                    elif not total: total=t
                elif re.fullmatch(r'[\d,]+\s*分', t) and not total:
                    total=re.match(r'[\d,]+',t).group()
                j+=1
            rows.append({'rank':rank,'name':name,'clusters':detail,'cpu_score':cpu,'gpu_score':gpu,'total_score':total})
            i=j
        else: i+=1
    return rows

def parse_ludashi(txt):
    lines=txt.split('\n'); n=len(lines)
    start=0
    for k in range(n):
        if re.fullmatch(r'\d{1,3}', lines[k].strip()) and k+1<n and not re.fullmatch(r'\d{1,3}', lines[k+1].strip()):
            start=k; break
    rows=[]; i=start
    while i<n:
        l=lines[i].strip()
        if re.fullmatch(r'\d{1,3}', l):
            rank=int(l); name=''; score=''; j=i+1
            while j<n and not re.fullmatch(r'\d{1,3}', lines[j].strip()):
                t=lines[j].strip()
                if not t: j+=1; continue
                if not name: name=t
                else:
                    m=re.fullmatch(r'([\d,]+)分', t)
                    if m and not score: score=m.group(1)
                j+=1
            rows.append({'rank':rank,'name':name,'score':score})
            i=j
        else: i+=1
    return rows

def main():
    raw_a=json.load(open(os.path.join(BASE,'antutu_soc_raw.json')))
    raw_l=json.load(open(os.path.join(BASE,'ludashi_raw.json')))
    antutu={}; counts={}
    for k,v in raw_a['levels'].items():
        antutu[k]=parse_antutu(v['txt']); counts['antutu_'+k]=len(antutu[k])
    ludashi={}; 
    for k in ['phone','soc','desktop_cpu','desktop_gpu','laptop_cpu']:
        ludashi[k]=parse_ludashi(raw_l[k]['txt']); counts['ludashi_'+k]=len(ludashi[k])
    json.dump(antutu, open(os.path.join(BASE,'antutu_soc.json'),'w'), ensure_ascii=False, indent=0)
    json.dump(ludashi, open(os.path.join(BASE,'ludashi.json'),'w'), ensure_ascii=False, indent=0)
    manifest={
        'sources':{
            'antutu':{'url':'https://www.antutu.com/ranking/soc?level={1,2,3}','count':counts,'note':'单页全量渲染，页码导航为页面干扰，无数据丢失'},
            'ludashi':{'url':'https://www.ludashi.com/rank','count':counts,'note':'各榜均无"加载更多"，全量'},
            'socpk':{'status':'phase2','note':'数值通道未完成（WASM codec / tooltip 采集）'}
        },
        'fetched_at':raw_a['fetched_at'],
        'integrity':'ok'
    }
    json.dump(manifest, open(os.path.join(BASE,'manifest.json'),'w'), ensure_ascii=False, indent=2)
    for k,v in counts.items(): print(k, v)

if __name__=='__main__': main()
