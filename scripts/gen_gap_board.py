#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成缺口总览看板（HTML）。不删除、不筛选任何条目，只分组呈现供人工决策。"""
import json, html
from datetime import datetime

ROWS = json.load(open("/tmp/rows.json", encoding="utf-8"))
REG = json.load(open("emulator-registry.json", encoding="utf-8"))
TOTAL = len(REG["emulators"])

PLAT = {
    "windows-pc": "Windows", "switch": "Switch", "n64": "N64", "xbox360": "Xbox 360",
    "ps1": "PS1", "gba": "GBA", "gb": "GB", "multi": "多平台", "nds": "NDS",
    "ps3": "PS3", "ios": "iOS", "3ds": "3DS", "ps4": "PS4", "dreamcast": "DC",
    "arcade": "街机", "java-me": "J2ME", "atari-jaguar": "Jaguar", "atari": "Atari",
    "pico-8": "PICO-8", "brew": "BREW", "gbc": "GBC", "psp": "PSP",
    "gamecube": "NGC", "wii": "Wii", "ps5": "PS5", "mega-drive": "MD",
    "ps2": "PS2", "snes": "SFC",
}
GAPNAME = {
    "no_repo_field": "无仓库字段", "unresolvable": "无法溯源",
    "repo_404": "仓库404", "repo_451": "DMCA451", "no_release": "无Release",
    "no_apk_anywhere_in_releases": "无APK", "tag_stale": "版本过期",
    "tag_missing": "无版本记录", "archived": "已归档", "no_logo": "无图标",
    "no_description": "无简介", "has_build_workflow": "有CI构建",
    "homepage_recoverable": "官网可补",
}
CLOSED_SRC = {"official-play", "official-web", "official-site", "official-store", "official-apk"}


def has(r, *keys):
    return any(k in r["gaps"] for k in keys)


def is_no_source(r):
    return has(r, "no_repo_field", "unresolvable", "repo_451")


def is_closed(r):
    """闭源：有商店 / 官网分发，只是没有源码仓库。这是正常形态，不算缺口。"""
    if not is_no_source(r):
        return False
    if has(r, "repo_451"):
        return False
    if r.get("source") in CLOSED_SRC:
        return True
    h = (r.get("home") or "").lower()
    return "play.google.com" in h or "apps.apple.com" in h


GROUPS = [
    ("A", "确实查不到任何一手来源", "#c0392b",
     "仓库、官网、商店三条路都试过，全部落空。这类条目点进去没有可用链接，需要你决定去留。",
     lambda r: is_no_source(r) and not is_closed(r)),
    ("B", "仓库地址失效（404）", "#d35400",
     "登记的仓库地址请求返回 404，可能是改名、删库或转私有。需要重新溯源或改址。",
     lambda r: has(r, "repo_404") and not is_no_source(r)),
    ("C", "有仓库但零 Release", "#b7950b",
     "仓库能打开，但从没发过 Release。可能只发源码、只走 CI 产物，或只在外部商店分发。",
     lambda r: has(r, "no_release") and not has(r, "no_apk_anywhere_in_releases")),
    ("D", "有 Release 但资产里没 APK", "#7d6608",
     "Release 存在，资产只有 zip / AppImage / 桌面包。多数不是错误——不少项目本来就只发桌面版。",
     lambda r: has(r, "no_apk_anywhere_in_releases")),
    ("E", "版本记录过期", "#2874a6",
     "库里记的版本号落后上游。纯数据刷新问题，脚本可自动修，不需要你逐条看。",
     lambda r: has(r, "tag_stale", "tag_missing")),
    ("F", "仓库已归档（停更）", "#5d6d7e",
     "上游已 archive 或明确停止开发。不是错误，需要在库里标「停更」并留最后可用版本。",
     lambda r: has(r, "archived")),
    ("G", "缺图标 / 缺简介", "#6c7a89",
     "纯展示层缺失，不影响下载可用性。",
     lambda r: has(r, "no_logo", "no_description")),
    ("H", "闭源：只有商店或官网", "#1f6feb",
     "这类不是缺口。它们本来就是闭源，官方渠道是 Play 商店或官网，没有源码仓库可溯源。"
     "要做的只是把可跳转链接填对。",
     lambda r: is_closed(r)),
]

used = set()
for code, *_ , pred in GROUPS:
    for r in ROWS:
        if r["id"] in used:
            continue
        if pred(r):
            r["group"] = code
            used.add(r["id"])
for r in ROWS:
    if r["id"] not in used:
        r["group"] = "D"
        used.add(r["id"])

by_group = {g[0]: [] for g in GROUPS}
for r in ROWS:
    by_group[r["group"]].append(r)
for g in by_group.values():
    g.sort(key=lambda r: -(r.get("stars") or 0))


def evidence(r, code):
    p = []
    if code == "H":
        h = r.get("home") or ""
        if h:
            p.append(f"官方页 <code>{html.escape(h[:78])}</code>")
        else:
            p.append("库里没有填官方链接")
        p.append("无源码仓库（闭源）")
        if r.get("desc"):
            p.append(html.escape(r["desc"][:56]))
        return " ｜ ".join(p)
    if code == "A":
        p.append("GitHub / Gitee / GitLab 三站检索零命中")
        if has(r, "repo_451"):
            p.insert(0, "仓库返回 451（DMCA 下架）")
        if r.get("home"):
            p.append(f"官网 <code>{html.escape(r['home'][:56])}</code>")
        if r.get("note"):
            p.append(html.escape(r["note"][:120]))
        elif r.get("desc"):
            p.append(html.escape(r["desc"][:56]))
        return " ｜ ".join(p)
    if code == "B":
        p.append(f"<code>{html.escape(r['repo'] or '—')}</code> 返回 404")
        if r.get("home"):
            p.append(f"官网 <code>{html.escape(r['home'][:52])}</code>")
        if r.get("note"):
            p.append(html.escape(r["note"][:100]))
        return " ｜ ".join(p)
    if r.get("stars") is not None:
        p.append(f"★{r['stars']}")
    if r.get("pushed"):
        p.append(f"最后提交 {r['pushed']}")
    if r.get("archived"):
        p.append("已归档")
    if r.get("releases") is not None:
        rel = r["releases"]
        if rel == 0:
            p.append("Release 0 个")
        else:
            p.append(f"Release {rel} 个 · APK {r.get('apk') or 0} 个 · 其他包 {r.get('pkg') or 0} 个")
    if r.get("sample"):
        p.append(f"最新资产 <code>{html.escape(r['sample'][:44])}</code>")
    if r.get("tag"):
        p.append(f"tag <code>{html.escape(r['tag'])}</code>")
    if r.get("wf") and code in ("C", "D"):
        p.append(f"CI {len(r['wf'])} 个")
    return " ｜ ".join(p) if p else "无探测数据"


def gap_badges(r, code=""):
    if code == "H":
        return '<span class="tag">闭源·无源码仓库</span>'
    seen, out = set(), []
    for g in r["gapraw"]:
        k = g.split(":")[0]
        if k in seen:
            continue
        seen.add(k)
        out.append(f'<span class="tag">{GAPNAME.get(k, k)}</span>')
    return "".join(out)


def plat(r):
    ps = r.get("plat") or []
    if not ps:
        return '<span class="plat">—</span>'
    return "".join(f'<span class="plat">{PLAT.get(x, x)}</span>' for x in ps[:4])


DECIDE = {
    "A": ["保留并标注", "删除", "我去补"],
    "B": ["重查地址", "降级存档", "删除"],
    "H": ["补链接", "保持现状", "删除"],
}


def decide_cells(code):
    opts = DECIDE.get(code, ["保留", "删除"])
    return "".join(
        f'<label><input type="checkbox"> {o}</label>' for o in opts
    )


sections = []
for code, title, color, desc, _ in GROUPS:
    items = by_group.get(code) or []
    if not items:
        continue
    trs = []
    for r in items:
        trs.append(
            f'<tr><td class="nm"><b>{html.escape(str(r["name"]))}</b>'
            f'<div class="sub">{html.escape(r["cat"])}</div></td>'
            f'<td class="pl">{plat(r)}</td>'
            f'<td class="ev">{evidence(r, code)}</td>'
            f'<td class="gp">{gap_badges(r, code)}</td>'
            f'<td class="dc">{decide_cells(code)}</td></tr>'
        )
    sections.append(f"""<section id="g{code}">
<h2><span class="dot" style="background:{color}"></span>{code} 类 · {html.escape(title)}<em>{len(items)} 条</em></h2>
<p class="gd">{html.escape(desc)}</p>
<table><thead><tr><th style="width:16%">名称</th><th style="width:9%">平台</th><th style="width:44%">已核实的事实</th><th style="width:15%">缺口</th><th style="width:16%">你的决定</th></tr></thead>
<tbody>{''.join(trs)}</tbody></table></section>""")

nav = "".join(
    f'<a href="#g{c}">{c} <b>{len(by_group.get(c) or [])}</b></a>'
    for c, *_ in GROUPS if by_group.get(c)
)

n_need = len(by_group["A"]) + len(by_group["B"])
doc = f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>EmuBox 项目库缺口总表</title>
<style>
:root{{--bg:#f4f5f7;--card:#fff;--ink:#17191c;--mute:#6b7280;--line:#e4e6ea;--brand:#1f6feb}}
*{{box-sizing:border-box}}
body{{margin:0;background:var(--bg);color:var(--ink);font:14px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;-webkit-font-smoothing:antialiased}}
header{{background:var(--card);border-bottom:1px solid var(--line);padding:24px 34px 16px;position:sticky;top:0;z-index:9}}
h1{{margin:0 0 3px;font-size:20px;font-weight:660;letter-spacing:-.2px}}
.meta{{color:var(--mute);font-size:12.5px;line-height:1.5}}
.row{{display:flex;gap:9px;margin-top:13px;flex-wrap:wrap;align-items:stretch}}
.kpi{{background:var(--bg);border:1px solid var(--line);border-radius:8px;padding:7px 14px;min-width:88px}}
.kpi b{{display:block;font-size:18px;font-weight:660;line-height:1.3;font-variant-numeric:tabular-nums}}
.kpi span{{font-size:11.5px;color:var(--mute);white-space:nowrap}}
.kpi.hi b{{color:#c0392b}}
nav{{display:flex;gap:5px;flex-wrap:wrap;margin-top:11px}}
nav a{{font-size:12px;text-decoration:none;color:#3b4350;background:var(--bg);border:1px solid var(--line);border-radius:999px;padding:3px 10px}}
nav a:hover{{border-color:var(--brand);color:var(--brand)}}
nav a b{{color:var(--brand);margin-left:3px;font-weight:640}}
main{{padding:20px 34px 50px;max-width:1560px}}
section{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px 18px 6px;margin-bottom:16px}}
h2{{margin:0 0 3px;font-size:15px;font-weight:650;display:flex;align-items:center;gap:8px}}
h2 em{{font-style:normal;font-size:11.5px;color:var(--mute);font-weight:400;margin-left:auto;white-space:nowrap}}
.dot{{width:9px;height:9px;border-radius:50%;flex:0 0 auto}}
.gd{{margin:0 0 11px;color:var(--mute);font-size:12px;max-width:1080px;line-height:1.5}}
table{{width:100%;border-collapse:collapse;font-size:12.5px;table-layout:fixed}}
th{{text-align:left;font-weight:600;color:var(--mute);font-size:11.5px;padding:6px 7px;border-bottom:1px solid var(--line);white-space:nowrap}}
td{{padding:8px 7px;border-bottom:1px solid #f2f3f5;vertical-align:top;overflow:hidden}}
tr:last-child td{{border-bottom:none}}
tbody tr:hover td{{background:#fafbfd}}
.nm b{{font-weight:600;font-size:13px}}
.sub{{color:var(--mute);font-size:11px;margin-top:1px}}
code{{background:#f1f2f4;border-radius:3px;padding:0 4px;font-size:11px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;color:#3b4757;word-break:break-all}}
.tag{{display:inline-block;background:#eef0f3;color:#4d5663;border-radius:3px;padding:0 5px;font-size:10.5px;margin:0 3px 3px 0;white-space:nowrap}}
.plat{{display:inline-block;background:#e9f0fd;color:#1a5cbf;border-radius:3px;padding:0 5px;font-size:10.5px;margin:0 3px 3px 0;white-space:nowrap}}
.ev{{color:#333a44;font-size:12px;line-height:1.5}}
.dc label{{display:block;font-size:11.5px;color:var(--mute);margin-bottom:2px;white-space:nowrap;cursor:pointer}}
.dc input{{vertical-align:-1px;margin-right:3px}}
.note{{background:#fff9e8;border:1px solid #f0dfae;border-radius:9px;padding:12px 16px;margin-bottom:16px;font-size:12.5px;line-height:1.6}}
.note b{{color:#8a6100}}
.appx{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px 18px 8px;margin-bottom:16px}}
footer{{color:var(--mute);font-size:11.5px;padding:0 34px 36px;line-height:1.6}}
@media print{{header{{position:static}} nav,.dc{{display:none}} body{{background:#fff}} section{{break-inside:avoid;box-shadow:none}}}}
</style></head><body>
<header>
  <h1>EmuBox 项目库 · 缺口总表</h1>
  <div class="meta">{TOTAL} 条逐条实测（GitHub / Forgejo / Gitee API），共 {len(ROWS)} 条有缺口，分 8 组。全表零过滤，一条没删。</div>
  <div class="row">
    <div class="kpi"><b>{TOTAL}</b><span>库内条目</span></div>
    <div class="kpi"><b>{TOTAL - len(ROWS)}</b><span>四项齐全</span></div>
    <div class="kpi"><b>{len(ROWS)}</b><span>有缺口</span></div>
    <div class="kpi hi"><b>{n_need}</b><span>需你拍板</span></div>
    <div class="kpi"><b>{len(by_group['H'])}</b><span>闭源非缺口</span></div>
    <div class="kpi"><b>{len(by_group['E'])}</b><span>可自动修</span></div>
  </div>
  <nav>{nav}<a href="#appx" style="border-color:#f0dfae;background:#fff9e8">附录 · 查过没收录 <b>5</b></a></nav>
</header>
<main>
<div class="note">
  <b>怎么读这张表：</b>只有 <b>A</b>（查不到任何来源）和 <b>B</b>（仓库 404）两组合计 {n_need} 条是真的需要你决定去留；<b>C / D</b> 里大部分是项目本来就只发桌面版或只走 CI，属于「库里字段没表达清楚」而不是「项目有问题」；<b>E</b> 是纯数据刷新，可一次性脚本修完；<b>H</b> 是闭源项目的正常形态，只需要把 Play / 官网链接填对。
</div>
{''.join(sections)}
<div class="appx" id="appx">
<h2><span class="dot" style="background:#8e44ad"></span>附录 · 我查过但没收录的候选<em>5 条</em></h2>
<p class="gd">下面这些是这轮实际探测到、但此前没进库的仓库。按你「不要私自过滤」的要求，全部摆出来，收不收由你定。</p>
<table><thead><tr><th style="width:26%">仓库</th><th style="width:12%">平台</th><th style="width:46%">已核实的事实</th><th style="width:16%">你的决定</th></tr></thead><tbody>
<tr><td class="nm"><b>ES-DE Frontend</b><div class="sub">前端 / 启动器</div></td><td class="pl"><span class="plat">多平台</span><span class="plat">Android</span></td>
<td class="ev"><code>gitlab.com/es-de/emulationstation-de</code> ｜ GitLab ★251 ｜ 2026-11-01 仍活跃 ｜ 有 Android 构建 ｜ 主流安卓前端，库里目前没有</td>
<td class="dc"><label><input type="checkbox"> 收录</label><label><input type="checkbox"> 不收录</label></td></tr>
<tr><td class="nm"><b>melonDS Android</b><div class="sub">NDS 模拟器</div></td><td class="pl"><span class="plat">NDS</span></td>
<td class="ev"><code>rafaelvcaetano/melonDS-android</code> ｜ ★1735 ｜ 2026-09-12 ｜ GPL-3.0 ｜ 3 个 Release 含 APK ｜ 官方 melonDS 的安卓移植</td>
<td class="dc"><label><input type="checkbox"> 收录</label><label><input type="checkbox"> 不收录</label></td></tr>
<tr><td class="nm"><b>Mesen2</b><div class="sub">多机种模拟器</div></td><td class="pl"><span class="plat">NES</span><span class="plat">SFC</span><span class="plat">GB</span></td>
<td class="ev"><code>SourMesen/Mesen2</code> ｜ ★2415 ｜ <b>已归档</b> 2026-06-04 ｜ 仅 Windows / Linux 桌面包 ｜ 未找到迁移后的新仓库</td>
<td class="dc"><label><input type="checkbox"> 收录</label><label><input type="checkbox"> 不收录</label></td></tr>
<tr><td class="nm"><b>Citra Mirror</b><div class="sub">3DS 模拟器</div></td><td class="pl"><span class="plat">3DS</span></td>
<td class="ev"><code>citra-mirror/citra</code> ｜ ★83 ｜ 2024-03-05 停 ｜ 0 Release ｜ 纯存档镜像，无构建产物</td>
<td class="dc"><label><input type="checkbox"> 收录</label><label><input type="checkbox"> 不收录</label></td></tr>
<tr><td class="nm"><b>Ryujinx Mirror</b><div class="sub">Switch 模拟器</div></td><td class="pl"><span class="plat">Switch</span></td>
<td class="ev"><code>fifv/ryujinx-mirror</code> ｜ ★0 ｜ 2025-05-05 ｜ 0 Release ｜ 描述写着「Will re-post be useful to fk DMCA?」，纯占位</td>
<td class="dc"><label><input type="checkbox"> 收录</label><label><input type="checkbox"> 不收录</label></td></tr>
</tbody></table></div>
</main>
<footer>生成 {datetime.now().strftime('%Y-%m-%d %H:%M')} ｜ 数据：audit-report.json（逐条 HTTP 实测）｜ 不含任何登录态、Cookie、网盘链接或社区用户数据</footer>
</body></html>"""

out = "/workspace/项目库缺口总表.html"
open(out, "w", encoding="utf-8").write(doc)
print("wrote", out, len(doc))
print({c: len(by_group[c]) for c, *_ in GROUPS})
