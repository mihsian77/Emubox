#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""下载量口径审计：每条到底走哪个分发渠道、能不能计数、用什么接口。

背景：库里 243 条只有 73 条有 downloads。不是「没抓」，是**渠道不同，多数渠道
根本不公开计数**。这个脚本不猜，按 distribution 字段 + 实际直链域名给每条定渠道，
再按渠道判「能不能自动拿数」，输出覆盖率报告。

渠道分三档：
  A 精确可自动   GitHub / Gitea / GitLab 的 release asset download_count
  B 只能拿分档   Play 商店的安装量分级（10K+ / 1M+），不给精确值
  C 拿不到       官网 CDN、网盘、纯源码、Actions artifacts

用法：
  python3 scripts/audit_download_sources.py            # 出报告
  python3 scripts/audit_download_sources.py --probe    # 额外实探 Gitea 实例
"""
import argparse
import json
import os
from collections import Counter, defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REG = os.path.join(ROOT, "emulator-registry.json")
OUT_MD = os.path.join(ROOT, "docs", "download-source-audit.md")
OUT_JSON = os.path.join(ROOT, "download-source-audit.json")

# distribution 字段 → 渠道
DIST_CHANNEL = {
    "release-apk": "github-release",
    "release-zip": "github-release",
    "nightly": "github-actions",
    "play": "play-store",
    "official-play": "play-store",
    "official-site": "official-cdn",
    "forgejo-release": "gitea-release",
    "gitea-release": "gitea-release",
    "gitea-nightly": "gitea-release",
    "gitlab-release": "gitlab-release",
    "f-droid": "fdroid",
    "source-only": "none",
    "no-official-channel": "none",
    "community-archive": "archive",
    "archived-repo": "none",
}

CHANNEL_INFO = {
    "github-release": ("A", "精确", "Repo Releases API → assets[].download_count"),
    "gitea-release": ("A*", "精确（逐实例）", "GET /api/v1/repos/{o}/{r}/releases → assets[].download_count；值为 0 视为该实例未开统计"),
    "gitlab-release": ("A*", "精确", "GET /api/v4/projects/{id}/releases → assets.links 无计数，需看 packages"),
    "play-store": ("B", "分档", "页面文本里的安装量区间（10K+ / 100K+ / 1M+），需浏览器或第三方 API"),
    "fdroid": ("C", "无", "F-Droid 不公开下载计数"),
    "official-cdn": ("C", "无", "自家 CDN，除非项目方暴露统计"),
    "github-actions": ("C", "无", "Actions artifacts 不计下载"),
    "archive": ("C", "无", "归档站，链接多已失效"),
    "none": ("C", "无", "纯源码或无官方渠道"),
}

HOST_RULES = [
    ("github.com", "github-release"),
    ("play.google.com", "play-store"),
    ("f-droid.org", "fdroid"),
    ("gitlab.com", "gitlab-release"),
    ("git.eden-emu.dev", "gitea-release"),
    ("git.ryujinx.app", "gitea-release"),
    ("123pan", "official-cdn"),
    ("lanzou", "official-cdn"),
    ("mediafire", "official-cdn"),
    ("pan.baidu", "official-cdn"),
    ("quark", "official-cdn"),
]


def dl_of(e):
    return e.get("downloads") or e.get("downloads_latest") or 0


def host_of(url):
    if not url or "//" not in url:
        return ""
    return url.split("//")[1].split("/")[0].lower()


def channel_of(e):
    dist = e.get("distribution")
    dists = dist if isinstance(dist, list) else ([dist] if dist else [])
    for d in dists:
        if d in DIST_CHANNEL:
            return DIST_CHANNEL[d], f"distribution={d}"

    # 没有 distribution 的，按直链域名推
    urls = []
    if e.get("download_url"):
        urls.append(e["download_url"])
    for b in (e.get("builds") or []):
        if b.get("url"):
            urls.append(b["url"])
    if e.get("latest_apk_url"):
        urls.append(e["latest_apk_url"])
    for u in urls:
        h = host_of(u)
        for pat, ch in HOST_RULES:
            if pat in h:
                return ch, f"直链域名 {h}"
    if urls:
        return "official-cdn", f"未知域名 {host_of(urls[0])}"
    if e.get("repo_status") in ("archived",):
        return "none", "仓库已归档且无直链"
    return "none", "无 distribution、无直链"


def probe_gitea(entries):
    """只探 Gitea：唯一在本沙箱可达、且有 download_count 的 A 档来源。"""
    import urllib.request
    results = {}
    for e in entries:
        repo = e.get("repo") or ""
        url = e.get("repo_url") or ""
        base = None
        for h in ("git.eden-emu.dev", "git.ryujinx.app"):
            if h in url:
                base = f"https://{h}/api/v1"
                break
        if not base or "/" not in repo:
            continue
        api = f"{base}/repos/{repo}/releases?limit=3"
        try:
            req = urllib.request.Request(api, headers={"User-Agent": "emubox-audit"})
            with urllib.request.urlopen(req, timeout=15) as r:
                rels = json.load(r)
            tot = sum(
                (a.get("download_count") or 0)
                for rel in rels for a in (rel.get("assets") or [])
            )
            n = sum(len(rel.get("assets") or []) for rel in rels)
            results[e["id"]] = {"assets": n, "downloads_3_releases": tot,
                                "usable": tot > 0}
        except Exception as ex:
            results[e["id"]] = {"error": str(ex)[:120], "usable": False}
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--probe", action="store_true")
    args = ap.parse_args()

    with open(REG, encoding="utf-8") as f:
        emus = json.load(f)["emulators"]

    by_channel = defaultdict(list)
    rows = []
    for e in emus:
        ch, why = channel_of(e)
        by_channel[ch].append(e["id"])
        tier, precision, how = CHANNEL_INFO.get(ch, ("C", "无", ""))
        rows.append({
            "id": e["id"],
            "name": e["name"],
            "family": e.get("family"),
            "section": e.get("section"),
            "stars": e.get("stars") or 0,
            "downloads": dl_of(e),
            "basis": e.get("downloads_basis"),
            "channel": ch,
            "channel_why": why,
            "tier": tier,
            "precision": precision,
            "how": how,
        })

    probe = {}
    if args.probe:
        targets = [e for e in emus if channel_of(e)[0] == "gitea-release"]
        probe = probe_gitea(targets)
        print(f"实探 {len(targets)} 个 Gitea 条目：")
        for k, v in probe.items():
            print(f"  {k}: {v}")

    # 覆盖率
    total = len(emus)
    a_like = sum(len(v) for k, v in by_channel.items() if k in ("github-release", "gitea-release", "gitlab-release"))
    b_like = sum(len(v) for k, v in by_channel.items() if k == "play-store")
    c_like = sum(len(v) for k, v in by_channel.items() if k in ("fdroid", "official-cdn", "github-actions", "archive", "none"))
    has_data = sum(1 for e in emus if dl_of(e))

    print()
    print("=" * 70)
    print(f"条目 {total}｜A 档可自动 {a_like}｜B 档只有分档 {b_like}｜C 档拿不到 {c_like}")
    print(f"当前实际有下载数：{has_data} 条（{has_data/total*100:.0f}%）")
    print("=" * 70)
    for ch, ids in sorted(by_channel.items(), key=lambda x: -len(x[1])):
        tier, prec, _ = CHANNEL_INFO.get(ch, ("C", "无", ""))
        print(f"  [{tier:<2s}] {ch:<16s}{len(ids):>4d} 条  {prec}")

    # A 档里「渠道支持但还没取到数」的，才是真正该补的
    fixable = [r for r in rows if r["tier"].startswith("A") and not r["downloads"]]
    print()
    print(f"A 档渠道支持、但还没有下载数的：{len(fixable)} 条 —— 这才是能补回来的")
    for r in sorted(fixable, key=lambda x: -x["stars"])[:15]:
        print(f"  {r['id']:<42s}{r['channel']:<16s}★{r['stars']}")

    os.makedirs(os.path.dirname(OUT_MD), exist_ok=True)
    with open(OUT_JSON, "w", encoding="utf-8") as f:
        json.dump({
            "summary": {
                "total": total, "tier_a": a_like, "tier_b": b_like, "tier_c": c_like,
                "with_downloads": has_data,
            },
            "channels": {k: {"count": len(v), **dict(zip(("tier", "precision", "how"), CHANNEL_INFO.get(k, ("C", "无", ""))))}
                         for k, v in by_channel.items()},
            "entries": rows,
            "probe": probe,
        }, f, ensure_ascii=False, indent=1)

    # Markdown
    lines = [
        "# 下载量口径审计",
        "",
        f"共 {total} 条：A 档可自动 {a_like}｜B 档只有分档 {b_like}｜C 档拿不到 {c_like}。"
        f"当前有下载数 {has_data} 条（{has_data/total*100:.0f}%）。",
        "",
        "## 渠道分布",
        "",
        "| 档 | 渠道 | 条数 | 精度 | 取法 |",
        "|---|---|--:|---|---|",
    ]
    for ch, ids in sorted(by_channel.items(), key=lambda x: -len(x[1])):
        tier, prec, how = CHANNEL_INFO.get(ch, ("C", "无", ""))
        lines.append(f"| {tier} | {ch} | {len(ids)} | {prec} | {how} |")
    lines += ["", "## A 档但缺数（可补）", "", "| id | 渠道 | ★ |", "|---|--:|--:|"]
    for r in sorted(fixable, key=lambda x: -x["stars"]):
        lines.append(f"| {r['id']} | {r['channel']} | {r['stars']} |")
    with open(OUT_MD, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    print(f"\n报告：{OUT_MD}")


if __name__ == "__main__":
    main()
