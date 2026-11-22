#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按「客观链接」派生 download_kind，消灭 341 条 null 与两套近义方言。

背景：download_kind 曾同时存在 github-releases / github-release、play /
official-play / google-play、official-site / official-web 三对近义值，另有 341
条为空（占 72%），「可下载性」统计因此不可信。

本脚本不猜：download_kind 只由 latest_apk_url / download_url 的域名决定，
没有链接的才退回 distribution。distribution 本身不动（那是「项目实际分发形态」，
与「链接指向哪」不是一回事）。

  latest_apk_url 有值                → github-release
  download_url 域名 github.com       → github-release
                  play.google.com    → google-play
                  nightly.link       → nightly
                  gitlab.com         → gitlab-release
                  codeberg.org       → forgejo-release
                  f-droid.org        → f-droid
                  其它               → official-site
  两者都没有                          → 按 distribution 映射（桌面构建/核心/PS5 包），
                                        其余一律 source-only

用法: python3 scripts/derive_download_kind.py [--dry]
"""
import os
import re
import sys
from collections import Counter

from jsonio import load, save

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REG = os.path.join(ROOT, "data/emulator-registry.json")

HOST_RULES = [
    (re.compile(r"(^|\.)github\.com$"), "github-release"),
    (re.compile(r"(^|\.)play\.google\.com$"), "google-play"),
    (re.compile(r"(^|\.)nightly\.link$"), "nightly"),
    (re.compile(r"(^|\.)gitlab\.com$"), "gitlab-release"),
    (re.compile(r"(^|\.)codeberg\.org$"), "forgejo-release"),
    (re.compile(r"(^|\.)f-droid\.org$"), "f-droid"),
]

# 无链接时按分发形态兜底。distribution 里的 near-duplicate 在这里统一。
DIST_FALLBACK = {
    "desktop-build": "desktop-build",
    "libretro-core": "libretro-core",
    "ps5-package": "ps5-package",
}

VALID = {"github-release", "desktop-build", "libretro-core", "official-site",
         "google-play", "zip", "source-only", "nightly", "forgejo-release",
         "gitea-release", "gitlab-release", "ps5-package", "f-droid"}


def host_of(url):
    m = re.match(r"^https?://([^/?#]+)", url or "")
    return (m.group(1).lower() if m else "").split(":")[0]


def derive(e):
    dist = e.get("distribution") or ""
    # 发布物是安卓压缩包（latest_apk_url 也可能是 .zip，如 FivePocket），
    # 先吃掉这一档，别把 zip 包说成 APK
    if dist == "android-archive":
        return "zip"
    if e.get("latest_apk_url"):
        h = host_of(e["latest_apk_url"])
        for pat, kind in HOST_RULES:
            if pat.search(h):
                return kind
        return "github-release"
    url = e.get("download_url") or ""
    if url:
        h = host_of(url)
        for pat, kind in HOST_RULES:
            if pat.search(h):
                return kind
        # 自建 Forgejo/Gitea 实例的发布页（git.ryujinx.app 之类），域名认不出来看路径
        if re.search(r"/releases/download/|/releases/tag/|/-/releases/", url):
            return "forgejo-release"
        return "official-site"
    if dist in DIST_FALLBACK:
        return DIST_FALLBACK[dist]
    if dist in ("play", "official-play"):
        return "google-play"
    if dist in ("forgejo-release", "gitea-release"):
        return dist
    if dist in ("nightly", "gitea-nightly"):
        return "nightly"
    if dist in ("gitlab-release", "ps5-package", "f-droid"):
        return dist
    # release-apk 但没有抓到直链：制品确实在某个 release 里，别自相矛盾地标成纯源码
    if dist == "release-apk":
        return "github-release"
    return "source-only"


def main():
    d = load(REG)
    ems = d["emulators"]
    moves = Counter()
    changed = 0
    for e in ems:
        old = e.get("download_kind")
        new = derive(e)
        if new not in VALID:
            raise SystemExit(f"派生出未登记取值 {new!r}: {e['id']}")
        if old != new:
            moves[(str(old), new)] += 1
            e["download_kind"] = new
            changed += 1
    after = Counter(e.get("download_kind") for e in ems)

    print(f"改动 {changed}/{len(ems)}")
    print("取值变化（前 15 条）:")
    for (o, n), c in moves.most_common(15):
        print(f"  {o:<18} -> {n:<16} {c}")
    print("派生后分布:")
    for k, v in after.most_common():
        print(f"  {k:<18}{v}")
    assert None not in after, "仍有空值"

    if "--dry" in sys.argv or not changed:
        return
    save(REG, d)


if __name__ == "__main__":
    main()
