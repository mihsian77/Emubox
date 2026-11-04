#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 ranking.json 渲染成一页可点的榜单（自包含 HTML，无外部依赖）。"""
import json
import os
from datetime import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RANK = os.path.join(ROOT, "ranking.json")
OUT = "/workspace/模拟器区排行榜-方案预览.html"

METHOD_ZH = {
    "emulation": "模拟", "translation": "转译", "recomp": "重编译",
    "engine": "引擎", "virtualization": "虚拟机", None: "未定",
}
PLAT_ZH = {
    "windows-pc": "Windows 游戏", "switch": "Switch", "n64": "N64", "3ds": "3DS",
    "ps3": "PS3", "ps5": "PS5", "gba": "GBA", "xbox360": "Xbox 360", "ps2": "PS2",
    "gamecube": "NGC", "ps1": "PS1", "nds": "NDS", "dreamcast": "DC", "snes": "SFC",
    "ps4": "PS4", "nes": "NES", "visual-novel": "视觉小说", "dos": "DOS",
    "java-me": "J2ME", "arcade": "街机", "terminal": "终端", "wii": "Wii",
    "wiiu": "Wii U", "psp": "PSP", "xbox": "Xbox", "symbian": "Symbian",
    "multi": "多平台", "windows-mobile": "WP", "ios": "iOS", "gb": "GB",
}


def fmt_num(n):
    if n is None:
        return "—"
    if n >= 10_000_000:
        return f"{n/10_000_000:.1f}千万".replace("千万", "0千万")
    if n >= 10_000:
        return f"{n/10_000:.1f}万"
    return str(n)


def human(n):
    if n is None:
        return "—"
    if n >= 1_000_000:
        return f"{n/1_000_000:.1f}M"
    if n >= 1000:
        return f"{n/1000:.1f}k"
    return str(n)


def badge(r):
    out = [f'<span class="tag m-{r.get("method") or "na"}">{METHOD_ZH.get(r.get("method"), "未定")}</span>']
    for p in (r.get("platforms") or [])[:2]:
        out.append(f'<span class="tag plat">{PLAT_ZH.get(p, p)}</span>')
    if r["members"] > 1:
        out.append(f'<span class="tag fam">{r["members"]} 个分支</span>')
    if r["official"]:
        out.append('<span class="tag off">官方</span>')
    if r["status"] == "quiet":
        out.append('<span class="tag quiet">沉寂</span>')
    if r["status"] == "archived":
        out.append('<span class="tag quiet">已归档</span>')
    return "".join(out)


def metric_text(board, r):
    k = board["key"]
    if k == "downloads":
        return human(r["downloads_sum"])
    if k == "stars":
        return human(r["stars_max"])
    if k == "active":
        d = r["days_since_push"]
        return "今天" if d == 0 else f"{d} 天前"
    if k == "fresh":
        d = r["days_since_release"]
        return "今天" if d == 0 else f"{d} 天前"
    if k == "rising":
        d = r.get("downloads_delta") or 0
        pct = r.get("growth_pct")
        return f"+{human(d)}" + (f" ({pct:+.0f}%)" if pct is not None else "")
    return "—"


def metric_ratio(board, r, top):
    k = board["key"]
    if k in ("downloads",):
        v, t = r["downloads_sum"], max(x["downloads_sum"] for x in top)
    elif k == "stars":
        v, t = r["stars_max"], max(x["stars_max"] for x in top)
    elif k in ("active", "fresh"):
        f = "days_since_push" if k == "active" else "days_since_release"
        t = max((x[f] or 0) for x in top) or 1
        v = t - (r[f] or 0)
        return max(0.02, v / t)
    elif k == "rising":
        v, t = (r.get("downloads_delta") or 0), max((x.get("downloads_delta") or 0) for x in top) or 1
    else:
        return 0.02
    return max(0.02, v / t) if t else 0.02


def main():
    with open(RANK, encoding="utf-8") as f:
        data = json.load(f)

    boards = [b for b in data["boards"] if b["status"] == "ok"]
    pending = [b for b in data["boards"] if b["status"] == "accumulating"]

    tabs, panes = [], []
    for i, b in enumerate(boards):
        tabs.append(
            f'<button class="tab{" on" if i == 0 else ""}" data-board="{b["key"]}">{b["label"]}</button>'
        )
        top = b["rows"]
        rows_html = []
        for n, r in enumerate(top, 1):
            ratio = metric_ratio(b, r, top)
            members = ""
            if r["members"] > 1:
                chips = "".join(f'<span class="mchip">{m}</span>' for m in r["member_ids"])
                members = f'<div class="members">{chips}</div>'
            rows_html.append(f"""
      <div class="row" data-sec="{r['section']}" data-name="{r['label']}{r['rep_name']}">
        <div class="rk">{n}</div>
        <div class="who">
          <div class="nm">{r['label']}<a class="gh" href="{r['repo_url']}" target="_blank" rel="noopener">↗</a></div>
          <div class="tags">{badge(r)}</div>
          {members}
        </div>
        <div class="num">{metric_text(b, r)}</div>
        <div class="bar"><i style="width:{ratio*100:.1f}%"></i></div>
      </div>""")
        panes.append(f"""
  <section class="pane{' on' if i == 0 else ''}" id="p-{b['key']}">
    <div class="bnote">{b['note']}</div>
    {''.join(rows_html)}
  </section>""")

    sec_chips = ['<button class="chip on" data-sec="all">全部</button>']
    for s in data["sections"]:
        if s["count"]:
            sec_chips.append(f'<button class="chip" data-sec="{s["key"]}">{s["label"]} {s["count"]}</button>')

    pending_html = ""
    if pending:
        p = pending[0]
        pending_html = f'<div class="pending"><b>{p["label"]}</b>：{p["status_text"]}</div>'

    cov = data["coverage"]
    html = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>模拟器区排行榜 · 方案预览</title>
<style>
:root{{--bg:#0e1116;--card:#161b22;--line:#232a35;--txt:#e6edf3;--dim:#8b949e;
--hot:#ff6b5e;--ok:#3fb950;--blue:#58a6ff;--purple:#bc8cff;--amber:#d29922;}}
*{{box-sizing:border-box}}
body{{margin:0;background:var(--bg);color:var(--txt);
font:14px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif}}
.wrap{{max-width:1080px;margin:0 auto;padding:28px 20px 60px}}
h1{{font-size:22px;margin:0 0 4px;letter-spacing:.5px}}
.sub{{color:var(--dim);font-size:13px}}
.stats{{display:flex;gap:10px;flex-wrap:wrap;margin:18px 0 6px}}
.st{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:10px 14px;min-width:120px}}
.st b{{display:block;font-size:20px;line-height:1.2}}
.st span{{color:var(--dim);font-size:12px}}
.warn{{margin:14px 0 0;background:rgba(210,153,34,.1);border:1px solid rgba(210,153,34,.35);
border-radius:10px;padding:10px 14px;font-size:13px;color:#e3b341}}
.tabs{{display:flex;gap:6px;flex-wrap:wrap;margin:20px 0 8px}}
.tab{{background:var(--card);border:1px solid var(--line);color:var(--dim);border-radius:8px;
padding:7px 14px;cursor:pointer;font-size:13px}}
.tab.on{{background:#1f6feb22;border-color:#1f6feb88;color:#79c0ff}}
.chips{{display:flex;gap:6px;flex-wrap:wrap;margin:0 0 14px}}
.chip{{background:transparent;border:1px solid var(--line);color:var(--dim);border-radius:999px;
padding:4px 11px;font-size:12px;cursor:pointer}}
.chip.on{{border-color:var(--blue);color:var(--blue)}}
.pane{{display:none}} .pane.on{{display:block}}
.bnote{{color:var(--dim);font-size:12px;margin:0 0 12px;padding-left:2px}}
.row{{display:grid;grid-template-columns:34px 1fr 96px 108px;gap:12px;align-items:center;
background:var(--card);border:1px solid var(--line);border-radius:10px;padding:10px 14px;margin-bottom:7px}}
.row:hover{{border-color:#30363d}}
.rk{{color:var(--dim);font-variant-numeric:tabular-nums;font-size:13px;text-align:right}}
.nm{{font-weight:600;font-size:14px}}
.gh{{color:var(--dim);text-decoration:none;margin-left:6px;font-size:12px}}
.gh:hover{{color:var(--blue)}}
.tags{{display:flex;gap:5px;flex-wrap:wrap;margin-top:3px}}
.tag{{font-size:11px;padding:1px 7px;border-radius:5px;background:#21262d;color:var(--dim)}}
.tag.m-emulation{{background:#1f6feb22;color:#79c0ff}}
.tag.m-translation{{background:#db6d2822;color:#f0883e}}
.tag.m-recomp{{background:#bc8cff22;color:#bc8cff}}
.tag.m-engine{{background:#3fb95022;color:#56d364}}
.tag.off{{background:#d2992222;color:#e3b341}}
.tag.quiet{{background:#484f58;color:#adbac7}}
.tag.fam{{background:#3fb95018;color:#56d364}}
.num{{text-align:right;font-variant-numeric:tabular-nums;font-weight:600;font-size:13px}}
.bar{{height:5px;background:#21262d;border-radius:3px;overflow:hidden}}
.bar i{{display:block;height:100%;background:linear-gradient(90deg,#1f6feb,#58a6ff);border-radius:3px}}
.members{{display:none;margin-top:7px;padding-top:7px;border-top:1px dashed var(--line);
gap:4px;flex-wrap:wrap}}
.row.open .members{{display:flex}}
.mchip{{font-size:11px;color:var(--dim);background:#0d1117;border:1px solid var(--line);
border-radius:4px;padding:1px 6px;font-family:ui-monospace,monospace}}
.pending{{margin:10px 0 0;color:var(--dim);font-size:12px;background:var(--card);
border:1px dashed var(--line);border-radius:10px;padding:9px 14px}}
.foot{{margin-top:26px;color:#6e7681;font-size:12px;line-height:1.8;border-top:1px solid var(--line);padding-top:14px}}
</style></head><body><div class="wrap">
<h1>模拟器区排行榜 · 方案预览</h1>
<div class="sub">家族聚合 · 多轴排序 · 不造综合分 ｜ 数据 {data['updated_at'][:16].replace('T',' ')} UTC</div>

<div class="stats">
  <div class="st"><b>{cov['entries']}</b><span>条目</span></div>
  <div class="st"><b>{cov['families']}</b><span>家族（聚合后行数）</span></div>
  <div class="st"><b>{cov['with_downloads']}</b><span>有下载数据</span></div>
  <div class="st"><b>{cov['downloads_visible_pct']}%</b><span>下载量可见率</span></div>
</div>
<div class="warn">下载榜只覆盖 {cov['with_downloads']} 条 —— RPCS3 / Dolphin / PPSSPP / RetroArch 这些大牌，
以及全部国内项目（Tyranor / YukiHub / Aether）都走官网或网盘分发，GitHub 下载量读不到。
所以「总下载」不能当默认榜，只能当一个可选的轴。</div>

<div class="tabs">{''.join(tabs)}</div>
<div class="chips">{''.join(sec_chips)}</div>
{''.join(panes)}
{pending_html}

<div class="foot">
口径说明：家族 = 同一上游代码谱系，fork 与官方版合并成一行（点「N 个分支」展开可见全部 id）。<br>
「总下载」= 家族内各 APK 下载量之和；「星标」取家族内最高值，避免 fork 抬高。<br>
本页不含任何加权总分：同一批数据配多个排序轴，避免「换个系数整个榜重排」。
</div>
</div>
<script>
const tabs=[...document.querySelectorAll('.tab')],panes=[...document.querySelectorAll('.pane')];
tabs.forEach(t=>t.onclick=()=>{{
  tabs.forEach(x=>x.classList.toggle('on',x===t));
  panes.forEach(p=>p.classList.toggle('on',p.id==='p-'+t.dataset.board));
  filter();
}});
let sec='all';
document.querySelectorAll('.chip').forEach(c=>c.onclick=()=>{{
  document.querySelectorAll('.chip').forEach(x=>x.classList.toggle('on',x===c));
  sec=c.dataset.sec;filter();
}});
function filter(){{
  const pane=document.querySelector('.pane.on');if(!pane)return;
  let i=0;
  pane.querySelectorAll('.row').forEach(r=>{{
    const show=(sec==='all'||r.dataset.sec===sec);
    r.style.display=show?'':'none';
    if(show)r.querySelector('.rk').textContent=++i;
  }});
}}
document.querySelectorAll('.row').forEach(r=>{{
  r.onclick=e=>{{if(e.target.tagName==='A')return;r.classList.toggle('open');}};
}});
filter();
</script></body></html>"""
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(html)
    print(f"写出 {OUT}  ({len(html)//1024} KB)")


if __name__ == "__main__":
    main()
