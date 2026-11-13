#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按 2026-11-13 监控交付包核对结果修正两份注册表。

只处理「用 API/HTTP 实测能确认」的条目，不按监控报告的字面判定直接改：
监控的死链检查只探 github.com，自建 GitLab（git.eden-emu.dev / git.ryujinx.app）
会被误判成 404，驱动版本比对也会被 `v39` 与 `v891.14` 这类命名差异带偏。
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ER = os.path.join(ROOT, "emulator-registry.json")
DR = os.path.join(ROOT, "driver-registry.json")
RET = os.path.join(ROOT, "emulator-retired.json")
DRET = os.path.join(ROOT, "driver-retired.json")


def load(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def save(p, d):
    with open(p, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
        f.write("\n")


# ── 模拟器注册表 ────────────────────────────────────────────────
er = load(ER)
items = er["emulators"]
by_id = {e["id"]: e for e in items}
log = []

DEAD = {
    "prosperoeden-ps5": "2026-11-13 实测：仓库 404，v1.005.000 的 release 资产也 404，无可下载物",
    "torzu-emulator": "2026-11-13 实测：github.com/litucks/torzu 返回 451（DMCA），notabug 镜像 404",
}
for i, why in DEAD.items():
    e = by_id[i]
    if e["status"] != "dead":
        log.append(f"  {i}: status {e['status']} → dead")
        e["status"] = "dead"
    e["note"] = why

# BananaNative 搬到自建 GitLab，GitHub 那条是空壳（404）
e = by_id["moceure-banananative"]
e["status"] = "active"
e["repo_url"] = "https://git.eden-emu.dev/moceure/BananaNative"
e["homepage"] = "https://git.eden-emu.dev/moceure/BananaNative"
e["latest_tag"] = "v0.3.14"
e["latest_date"] = "2026-11-12T00:00:00Z"
e["latest_apk_url"] = (
    "https://git.eden-emu.dev/moceure/BananaNative/releases/download/"
    "v0.3.14/banana-native-v0.3.14.apk"
)
e["download_url"] = e["repo_url"] + "/releases"
e["note"] = "2026-11-13 复核：GitHub 上的 moceure/BananaNative 已 404，主线迁到自建 GitLab，v0.3.14 直链可用"
log.append("  moceure-banananative: 迁址 git.eden-emu.dev，v0.1.5 → v0.3.14")

# NeXium 源码迁到自建 GitLab；原先的 github nightly 下载链已失效
e = by_id["nexium-switch1"]
e["status"] = "active"
e["repo"] = ""
e["repo_url"] = "https://git.mythrax-rs.org/nexium-emu/nexium"
e["download_url"] = "https://nexium-emu.org"
e["latest_tag"] = ""
e["latest_date"] = ""
e["note"] = (
    "2026-11-13 复核：源码迁至 git.mythrax-rs.org，官网 nexium-emu.org 提供 nightly 下载；"
    "原先指向 github.com/nexium-emu/nexium-nightly 的直链已 404，改为落到官网"
)
log.append("  nexium-switch1: stale → active，改指 git.mythrax-rs.org / 官网下载")

# Termux 增强镜像：repo 字段是 fork，repo_url 却写成了官方仓
e = by_id["termux-jiaxinchen"]
e["repo_url"] = "https://github.com/jiaxinchen-max/termux-app"
e["homepage"] = "https://github.com/jiaxinchen-max/termux-app"
e["download_url"] = "https://github.com/jiaxinchen-max/termux-app/releases"
e["note"] = "2026-11-13 修正：repo 是 jiaxinchen-max 的 fork，repo_url 原本错填成官方 termux/termux-app"
log.append("  termux-jiaxinchen: repo_url 指向自己的 fork")

# AzaharPlus(f4mrfaux)：纯源码、无资产、Play 链接指向 Lime3DS，且 15 个月未推 → 退役
drop = by_id["azaharplus-android"]
ret = load(RET)
if any(x["id"] == drop["id"] for x in ret):
    raise SystemExit("azaharplus-android 已在退役表里，本次不重复执行")
drop["note"] = (
    "2026-11-13 退役：仓库纯源码无 release 资产；download_url 指向 Play 上的 Lime3DS（不是本项目）；"
    "最后推送 2025-08-19。同名主流 fork 为 AzaharPlus/AzaharPlus（★1449），已单独收录"
)
drop["skip"] = "no_artifact"
ret.append(drop)
items = [e for e in items if e["id"] != "azaharplus-android"]
er["emulators"] = items
er["updated_at"] = "2026-11-13T16:10:00Z"
save(ER, er)
save(RET, ret)
log.append("  azaharplus-android: 移入 emulator-retired.json，条目 %d → %d" % (len(items) + 1, len(items)))

# ── 驱动注册表 ──────────────────────────────────────────────────
dr = load(DR)
repos = dr["repositories"]
rid = {r["id"]: r for r in repos}

# 1) 上游确实发了新版（同名体系内递增，已逐个用 releases API 核对）
BUMP = {
    "banner": ("v27.0.0-20261113-r2", "2026-11-13T00:00:00Z"),
    "wintermist-a810": ("tu_v4N-PR3", "2026-11-09T00:00:00Z"),
    "faux123-8xx": ("v1.2.10", "2026-11-10T00:00:00Z"),
    "faux123-ace": ("v1.1.6", "2026-11-11T00:00:00Z"),
    "faux123-rg556": ("v1.1.6", "2026-11-11T00:00:00Z"),
    "faux123-pixel7": ("v1.1.6", "2026-11-11T00:00:00Z"),
    "faux123-rp4pro": ("v1.1.6", "2026-11-11T00:00:00Z"),
    "xclipse940-radv": ("v1.9.0", "2026-11-11T00:00:00Z"),
    "xodios-g57": ("panvk-test3", "2026-10-02T00:00:00Z"),
}
for i, (tag, date) in BUMP.items():
    r = rid[i]
    log.append(f"  {i}: {r.get('latest_tag') or '(空)'} → {tag}")
    r["latest_tag"] = tag
    r["latest_date"] = date

# 2) 一直没记版本的条目，补上游最新 tag（都没有「同一仓多个命名体系」的问题）
FILL = {
    "luke-g52": ("v0.0.2-alpha", "2026-09-30T00:00:00Z"),
    "noysz-g99": ("fourfectvk-g57-v0.1.0-test9", "2026-10-28T00:00:00Z"),
    "dkdndje-g720": ("v0.1.0-xhyn", "2026-10-01T00:00:00Z"),
    "koadzba-droid": ("v0.3.5", "2026-06-04T00:00:00Z"),
    "msx-g615-eden": ("Panvk-Mali-6", "2026-10-08T00:00:00Z"),
    "pandxvk-lloyd262": ("PDXVK-2.4.1-GPLASYNC-2", "2026-10-08T00:00:00Z"),
    "lukevalen-kbase": ("panvk-rc3-switch-safety2", "2026-10-12T00:00:00Z"),
    "ghaelv-turnip-lemon-ade": ("Lemon-Ade-v0.0.7", "2026-10-13T00:00:00Z"),
    "panvk-kirin990": ("v1.3.0", "2026-11-11T00:00:00Z"),
}
for i, (tag, date) in FILL.items():
    if i not in rid:
        continue
    rid[i]["latest_tag"] = tag
    rid[i]["latest_date"] = date
    log.append(f"  {i}: 补版本 {tag}")

# 3) 一个可下载包都没有的仓：驱动区是从 release 拉文件列表的，空仓会渲染成红色错误块
NO_ASSET = {
    "turnip-enhanced": "LFRon/Turnip-Enhanced：mesa 纯源码 fork，0 release、0 tag，无可下载包",
    "ps5-vulkan": "mihawk-99/ps5_vulkan：只出 GitHub Actions 产物（需登录），无 release；且为 PS5 硬件驱动，安卓侧装不上",
    "maxi000q-drivers": "maxi000q/drivers：空仓，0 release、0 tag、0 star",
    "bkaneko-mtk": "BkaNeko/panvk-mtk-driver：纯源码，0 release、0 tag",
}
dret = []
if os.path.exists(DRET):
    dret = load(DRET)
for i, why in NO_ASSET.items():
    if i not in rid or any(x["id"] == i for x in dret):
        continue
    r = dict(rid[i])
    r["retired_reason"] = why
    dret.append(r)
    log.append(f"  {i}: 移出驱动区（{why.split('：')[0]}）")
repos = [r for r in repos if r["id"] not in NO_ASSET]
dr["repositories"] = repos
dr["updated_at"] = "2026-11-13T16:10:00Z"
save(DR, dr)
save(DRET, dret)
log.append("  驱动区条目 %d → %d" % (len(repos) + len(NO_ASSET), len(repos)))

print("\n".join(log))
