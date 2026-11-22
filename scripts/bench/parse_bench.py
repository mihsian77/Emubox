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

def load_raw(name):
    """读原始抓取文件；不存在或损坏返回 None（交给调用方降级，不在这里崩）"""
    path=os.path.join(BASE,name)
    if not os.path.exists(path):
        print('[skip] 缺少 %s，本次保留上一版产物' % name)
        return None
    try:
        return json.load(open(path))
    except Exception as e:
        print('[warn] %s 读取失败：%s，本次保留上一版产物' % (name,e))
        return None

def count_existing(name):
    """统计已有产物里的榜单条数，用于源缺失时 manifest 仍能给出真实规模"""
    path=os.path.join(BASE,name)
    if not os.path.exists(path): return {}
    try:
        d=json.load(open(path))
    except Exception:
        return {}
    return {k:len(v) for k,v in d.items() if isinstance(v,list)}

def guard_shrink(name, fresh):
    """新数据比上一版少太多 → 大概率是页面改版/被抓了半截，拒绝覆盖。
    宁可这一天不更新，也不要让跑分榜被半截数据洗掉——坏数据看起来和正常数据一样，
    等发现时已经不知道原始值是什么了。返回不通过的原因，通过返回 None。"""
    old = count_existing(name)
    for k, rows in fresh.items():
        prev = old.get(k, 0)
        if prev >= 20 and len(rows) < prev * 0.6:
            return '%s 条数从上一版 %d 降到 %d' % (k, prev, len(rows))
    return None

def main():
    # 两个源各自独立降级：单站点超时不该让另一边的数据也停更。
    # 2026-11-21 实测：鲁大师站点超时 → ludashi_raw.json 缺失 → 本脚本抛
    # FileNotFoundError → 连安兔兔（已抓成功）也不落盘，跑分数据整体停更。
    raw_a=load_raw('antutu_soc_raw.json')
    raw_l=load_raw('ludashi_raw.json')
    stale=[]; counts={}

    if raw_a:
        try:
            antutu={}
            for k,v in (raw_a.get('levels') or {}).items():
                antutu[k]=parse_antutu((v or {}).get('txt') or '')
            if not antutu:
                raise ValueError('levels 为空')
            shrink=guard_shrink('antutu_soc.json', antutu)
        except Exception as e:
            print('[warn] 安兔兔原始数据不可用：%s，本次保留上一版' % e)
            antutu=None; shrink=None
        if antutu is None:
            stale.append('antutu'); counts.update(count_existing('antutu_soc.json'))
        elif shrink:
            print('[guard] 安兔兔数据疑似不完整（%s），本次保留上一版' % shrink)
            stale.append('antutu'); counts.update(count_existing('antutu_soc.json'))
        else:
            for k,v in antutu.items(): counts['antutu_'+k]=len(v)
            # 榜单本身自描述：文件里带上抓取时间与条数，
            # 避免「同一颗 SoC 两次抓取差 3554 分，却看不出这份是哪天的」
            antutu_out={'_meta':{
                'source':'https://www.antutu.com/ranking/soc?level={1,2,3}',
                'fetched_at':raw_a.get('fetched_at'),
                'counts':{k:len(v) for k,v in antutu.items()},
                'note':'与同目录 manifest.json 同批次抓取；UI 显示采集月份时以此为准'
            }}
            antutu_out.update(antutu)
            json.dump(antutu_out, open(os.path.join(BASE,'antutu_soc.json'),'w'), ensure_ascii=False, indent=0)
    else:
        stale.append('antutu'); counts.update(count_existing('antutu_soc.json'))

    if raw_l:
        try:
            ludashi={}
            for k in ['phone','soc','desktop_cpu','desktop_gpu','laptop_cpu']:
                ludashi[k]=parse_ludashi((raw_l.get(k) or {}).get('txt') or '')
            if not any(ludashi.values()):
                raise ValueError('五张榜全空')
            shrink=guard_shrink('ludashi.json', ludashi)
        except Exception as e:
            print('[warn] 鲁大师原始数据不可用：%s，本次保留上一版' % e)
            ludashi=None; shrink=None
        if ludashi is None:
            stale.append('ludashi'); counts.update(count_existing('ludashi.json'))
        elif shrink:
            print('[guard] 鲁大师数据疑似不完整（%s），本次保留上一版' % shrink)
            stale.append('ludashi'); counts.update(count_existing('ludashi.json'))
        else:
            for k,v in ludashi.items(): counts['ludashi_'+k]=len(v)
            json.dump(ludashi, open(os.path.join(BASE,'ludashi.json'),'w'), ensure_ascii=False, indent=0)
    else:
        stale.append('ludashi'); counts.update(count_existing('ludashi.json'))

    fetched_at=(raw_a or {}).get('fetched_at') or (raw_l or {}).get('fetched_at')

    def prev_manifest():
        path=os.path.join(BASE,'manifest.json')
        if not os.path.exists(path): return {}
        try:
            return json.load(open(path))
        except Exception:
            return {}

    prev=prev_manifest()
    if not fetched_at:
        fetched_at=prev.get('fetched_at')

    def prev_time(src):
        """上一次这个源真正采到数据的时间；用于源停更时仍能说清"这份数据是哪天的"""
        return ((prev.get('sources') or {}).get(src) or {}).get('fetched_at') or prev.get('fetched_at')

    def src(name,url,note,status,own_fetched_at):
        return {'url':url,'count':counts,'status':status,
                'fetched_at':own_fetched_at or prev_time(name),
                'note':note}

    manifest={
        'sources':{
            'antutu':src('antutu','https://www.antutu.com/ranking/soc?level={1,2,3}',
                         '单页全量渲染，页码导航为页面干扰，无数据丢失',
                         'stale' if 'antutu' in stale else 'ok',
                         (raw_a or {}).get('fetched_at') if 'antutu' not in stale else None),
            'ludashi':src('ludashi','https://www.ludashi.com/rank',
                          '各榜均无"加载更多"，全量',
                          'stale' if 'ludashi' in stale else 'ok',
                          (raw_l or {}).get('fetched_at') if 'ludashi' not in stale else None),
            'socpk':{'status':'phase2','note':'数值通道未完成（WASM codec / tooltip 采集）'}
        },
        'fetched_at':fetched_at,
        # partial 不是错误：某个源本轮没抓到，对应产物沿用上一版，其余照常更新。
        # 消费方（build_meta / App）可照常读，需要严格性时再看 sources[].status。
        'integrity':'partial' if stale else 'ok'
    }
    json.dump(manifest, open(os.path.join(BASE,'manifest.json'),'w'), ensure_ascii=False, indent=2)
    for k,v in counts.items(): print(k, v)
    if stale:
        print('[partial] 本轮未更新的源：%s' % ', '.join(stale))

if __name__=='__main__': main()
