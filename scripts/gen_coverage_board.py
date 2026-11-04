#!/usr/bin/env python3
"""生成「模拟器区生态覆盖看板」。

按平台统计三项指标——条目数、活跃数（近 180 天有提交）、可下载数——
用色块直观暴露空白与薄弱平台。这是分类/排行/评分的前置体检表：
不先知道哪里是空的，排行只会把同一个平台的项目排到一起。
"""
import json
import os
import datetime as dt
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.environ.get("EMUBOX_BOARD", "/workspace/模拟器区生态看板.html")

GROUPS = [
    ("家用机", ["nes", "snes", "n64", "gamecube", "wii", "wiiu", "switch",
                "saturn", "dreamcast", "mega-drive", "master-system", "game-gear",
                "pc-engine", "neo-geo", "3do", "atari-2600", "atari-lynx", "atari-jaguar",
                "xbox", "xbox360", "ps1", "ps2", "ps3", "ps4", "ps5"]),
    ("掌机", ["gb", "gbc", "gba", "nds", "3ds", "psp", "psvita",
              "wonderswan", "virtual-boy", "pico-8", "zeebo"]),
    ("电脑 / 街机", ["windows-pc", "pc-98", "msx", "dos", "c64", "amiga", "zx-spectrum",
                     "arcade", "model3", "brew", "java-me", "symbian", "windows-mobile"]),
    ("运行环境（跨平台）", ["adventure", "visual-novel", "multi", "web", "terminal", "ios", "atari"]),
]

CN = {
    "nes": "红白机", "snes": "超任", "n64": "N64", "gamecube": "NGC", "wii": "Wii",
    "wiiu": "Wii U", "switch": "Switch", "saturn": "土星", "dreamcast": "DC",
    "mega-drive": "MD", "master-system": "SMS", "game-gear": "GG", "pc-engine": "PCE",
    "neo-geo": "NEOGEO", "3do": "3DO", "atari-2600": "Atari 2600", "atari-lynx": "Lynx",
    "atari-jaguar": "Jaguar", "atari": "Atari 其它", "xbox": "Xbox", "xbox360": "Xbox 360",
    "ps1": "PS1", "ps2": "PS2", "ps3": "PS3", "ps4": "PS4", "ps5": "PS5",
    "gb": "GB", "gbc": "GBC", "gba": "GBA", "nds": "NDS", "3ds": "3DS", "psp": "PSP",
    "psvita": "PS Vita", "wonderswan": "WS", "virtual-boy": "VB", "pico-8": "PICO-8",
    "zeebo": "Zeebo", "windows-pc": "Windows", "pc-98": "PC-98", "msx": "MSX", "dos": "DOS",
    "c64": "C64", "amiga": "Amiga", "zx-spectrum": "ZX", "arcade": "街机",
    "model3": "Model3", "brew": "BREW", "java-me": "J2ME", "symbian": "Symbian",
    "windows-mobile": "WM", "adventure": "AVG 引擎", "visual-novel": "视觉小说",
    "multi": "综合", "web": "Web/Flash", "terminal": "终端", "ios": "iOS",
}


def main():
    data = json.load(open(os.path.join(ROOT, "emulator-registry.json"), encoding="utf-8"))
    ems = data["emulators"]
    now = dt.datetime.now(dt.timezone.utc)

    def age(s):
        try:
            return (now - dt.datetime.fromisoformat((s or "").replace("Z", "+00:00"))).days
        except Exception:
            return 9999

    stat = defaultdict(lambda: {"n": 0, "active": 0, "dl": 0, "items": []})
    for e in ems:
        act = age(e.get("pushed_at")) <= 180
        hasdl = bool(e.get("builds") or e.get("latest_apk_url") or e.get("download_url"))
        for p in (e.get("platforms") or ["multi"]):
            s = stat[p]
            s["n"] += 1
            if act:
                s["active"] += 1
            if hasdl:
                s["dl"] += 1
            s["items"].append(e)

    total_active = sum(1 for e in ems if age(e.get("pushed_at")) <= 180)
    all_plats = [p for g in GROUPS for p in g[1]]
    covered = [p for p in all_plats if stat[p]["n"] > 0]
    zero = [p for p in all_plats if stat[p]["n"] == 0]

    def cell(p):
        s = stat[p]
        n, a, d = s["n"], s["active"], s["dl"]
        if n == 0:
            cls, tag = "empty", "空白"
        elif a == 0:
            cls, tag = "dead", "全死"
        elif a < 2:
            cls, tag = "thin", "薄弱"
        elif a < 4:
            cls, tag = "ok", "一般"
        else:
            cls, tag = "good", "充足"
        # 最佳三项：活跃优先，其次 star
        top = sorted(s["items"], key=lambda e: (age(e.get("pushed_at")) > 180,
                                                -(e.get("stars") or 0)))[:3]
        names = "、".join(f'<a href="{e.get("repo_url") or e.get("homepage") or "#"}" target="_blank">{e["name"]}</a>'
                         for e in top)
        return f'''<div class="cell {cls}">
  <div class="pname">{CN.get(p, p)}<span class="tag">{tag}</span></div>
  <div class="nums"><b>{n}</b> 条 · 活跃 {a} · 有包 {d}</div>
  <div class="top">{names or '—'}</div>
</div>'''

    groups_html = ""
    for gname, plats in GROUPS:
        cells = "".join(cell(p) for p in plats)
        groups_html += f'<section><h2>{gname}</h2><div class="grid">{cells}</div></section>'

    # 排行榜：每个平台的最佳选择
    rank_rows = []
    for p in all_plats:
        s = stat[p]
        if not s["n"]:
            continue
        top = sorted(s["items"], key=lambda e: (age(e.get("pushed_at")) > 180,
                                                -(e.get("stars") or 0)))[0]
        rank_rows.append((p, top, s["active"], s["n"]))
    rank_rows.sort(key=lambda r: (-r[2], r[0]))
    rank_html = "".join(
        f'<tr><td class="pl">{CN.get(p,p)}</td><td><a href="{e.get("repo_url") or "#"}" target="_blank">{e["name"]}</a></td>'
        f'<td class="num">{e.get("stars") or 0}</td><td class="num">{a}/{n}</td>'
        f'<td>{"活跃" if age(e.get("pushed_at"))<=180 else "停更"}</td></tr>'
        for p, e, a, n in rank_rows)

    html = f'''<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>模拟器区生态看板</title>
<style>
:root{{--bg:#0f1013;--card:#181a20;--line:#262932;--txt:#e6e8ee;--dim:#8a90a0;
--good:#2ea06a;--ok:#7a8b3a;--thin:#b8862b;--dead:#a03a3a;--empty:#2a2d36;}}
*{{box-sizing:border-box}}
body{{margin:0;background:var(--bg);color:var(--txt);
font:14px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;padding:28px 32px 64px}}
h1{{font-size:22px;margin:0 0 6px}}
.sub{{color:var(--dim);font-size:13px;margin-bottom:22px}}
.kpis{{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:26px}}
.kpi{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 16px;min-width:120px}}
.kpi b{{display:block;font-size:22px;line-height:1.2}}
.kpi span{{color:var(--dim);font-size:12px}}
h2{{font-size:14px;color:var(--dim);font-weight:500;margin:26px 0 10px;
letter-spacing:.06em;text-transform:uppercase}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(212px,1fr));gap:8px}}
.cell{{background:var(--card);border:1px solid var(--line);border-left:3px solid var(--empty);
border-radius:8px;padding:9px 11px}}
.cell.good{{border-left-color:var(--good)}}
.cell.ok{{border-left-color:var(--ok)}}
.cell.thin{{border-left-color:var(--thin)}}
.cell.dead{{border-left-color:var(--dead)}}
.cell.empty{{border-left-color:#3a3d47;opacity:.55}}
.pname{{font-weight:600;font-size:14px;display:flex;justify-content:space-between;align-items:center}}
.tag{{font-size:11px;font-weight:400;color:var(--dim)}}
.cell.good .tag{{color:var(--good)}} .cell.thin .tag{{color:var(--thin)}}
.cell.dead .tag{{color:var(--dead)}}
.nums{{color:var(--dim);font-size:12px;margin-top:2px}}
.nums b{{color:var(--txt)}}
.top{{font-size:12px;margin-top:4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}}
a{{color:#7fb3ff;text-decoration:none}} a:hover{{text-decoration:underline}}
table{{width:100%;border-collapse:collapse;margin-top:8px;font-size:13px}}
th,td{{text-align:left;padding:6px 10px;border-bottom:1px solid var(--line)}}
th{{color:var(--dim);font-weight:500}}
td.num,th.num{{text-align:right;font-variant-numeric:tabular-nums}}
td.pl{{color:var(--dim)}}
.note{{margin-top:28px;color:var(--dim);font-size:12px;line-height:1.8;
border-top:1px solid var(--line);padding-top:14px}}
</style></head><body>
<h1>模拟器区生态看板</h1>
<div class="sub">按平台体检：条目数 · 近 180 天活跃数 · 可直接下载数。色条越绿越健康，灰色是空白。</div>

<div class="kpis">
<div class="kpi"><b>{len(ems)}</b><span>条目总数</span></div>
<div class="kpi"><b>{total_active}</b><span>活跃条目</span></div>
<div class="kpi"><b>{len(covered)}/{len(all_plats)}</b><span>平台覆盖</span></div>
<div class="kpi"><b>{len(zero)}</b><span>空白平台</span></div>
<div class="kpi"><b>{len([p for p in all_plats if 0<stat[p]["active"]<2])}</b><span>薄弱平台</span></div>
</div>

{groups_html}

<h2>各平台首选</h2>
<table><thead><tr><th>平台</th><th>首选项目</th><th class="num">★</th>
<th class="num">活跃/总数</th><th>状态</th></tr></thead><tbody>{rank_html}</tbody></table>

<div class="note">
口径：活跃 = 仓库近 180 天有推送；有包 = release 资产或直链下载；空白 = 库内该平台零条目。
「薄弱」不等于不合格——复古平台有一条活跃条目即可用，但不足两条时排行的可比性会下降。
生成时间 {dt.datetime.now().strftime('%Y-%m-%d %H:%M')}。
</div>
</body></html>'''

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    open(OUT, "w", encoding="utf-8").write(html)
    print(f"写入 {OUT}")
    print(f"平台覆盖 {len(covered)}/{len(all_plats)}，空白 {zero}")


if __name__ == "__main__":
    main()
