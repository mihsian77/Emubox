#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""国外信源采集：YouTube 频道更新 + Reddit 板块新帖。

为什么单独一个脚本、并且只在 GitHub Actions 里跑：
  本机/沙箱出口对 youtube.com、reddit.com、t.me、x.com 全部不通（curl 000，
  TLS 都建不起来），只有 GitHub 系可达。Actions 的 runner 在海外，天然可用。
  所以这条链路的数据生产在 Actions，消费在本地：产物 commit 回 data/intl-news/。

不依赖第三方库：urllib + xml.etree，runner 上零安装。

用法：
  python3 intl_news.py --out data/intl-news/2026-11-05.json
  python3 intl_news.py --probe     # 只测可达性
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

# handle → 定位。只放「专门追模拟器/安卓移植新闻」的频道，泛数码大号不收。
CHANNELS = [
    ("MrSujano", "每日模拟器新闻，覆盖 PC/安卓/掌机，最贴我们的一条线"),
    ("ETAPrime", "安卓设备跑 PC 游戏、Winlator 实机演示"),
    ("RetroGameCorps", "掌机与模拟器教程，偏评测但更新稳定"),
    ("TechDweeb", "复古游戏与模拟器工具"),
]

SUBREDDITS = [
    ("EmulationOnAndroid", "安卓模拟器主板块，项目首发常在这里"),
    ("Winlator", "Winlator 生态"),
    ("emulation", "综合，量大但杂"),
]

_NOW = datetime.now(timezone.utc)


def get(url, timeout=25):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept-Language": "en-US,en;q=0.9",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def resolve_channel_id(handle):
    """频道页 HTML 里抠 channelId。RSS 只认 channel_id，不认 @handle。"""
    html = get(f"https://www.youtube.com/@{handle}").decode("utf-8", "ignore")
    m = re.search(r'"channelId":"(UC[\w-]{20,24})"', html) or \
        re.search(r'channel_id=(UC[\w-]{20,24})', html)
    return m.group(1) if m else None


def youtube_feed(handle, cid):
    raw = get(f"https://www.youtube.com/feeds/videos.xml?channel_id={cid}")
    ns = {"a": "http://www.w3.org/2005/Atom",
          "yt": "http://www.youtube.com/xml/schemas/2015",
          "media": "http://search.yahoo.com/mrss/"}
    root = ET.fromstring(raw)
    out = []
    for e in root.findall("a:entry", ns):
        pub = e.findtext("a:published", "", ns)
        try:
            dt = datetime.fromisoformat(pub.replace("Z", "+00:00"))
        except Exception:
            dt = _NOW
        out.append({
            "handle": handle,
            "id": e.findtext("yt:videoId", "", ns),
            "title": (e.findtext("a:title", "", ns) or "").strip(),
            "published": pub,
            "days": (_NOW - dt).days,
            "url": f"https://youtu.be/{e.findtext('yt:videoId', '', ns)}",
        })
    return out


def reddit_new(sub, limit=40):
    raw = get(f"https://www.reddit.com/r/{sub}/new.json?limit={limit}")
    d = json.loads(raw)
    out = []
    for c in (d.get("data") or {}).get("children") or []:
        p = c.get("data") or {}
        dt = datetime.fromtimestamp(p.get("created_utc") or 0, timezone.utc)
        out.append({
            "sub": sub,
            "id": p.get("id"),
            "title": (p.get("title") or "").strip(),
            "author": p.get("author"),
            "score": p.get("score"),
            "comments": p.get("num_comments"),
            "flair": p.get("link_flair_text"),
            "url": "https://reddit.com" + (p.get("permalink") or ""),
            "external": p.get("url_overridden_by_dest") or "",
            "days": (_NOW - dt).days,
            "created": dt.strftime("%Y-%m-%dT%H:%M:%SZ"),
        })
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="")
    ap.add_argument("--days", type=int, default=14, help="只保留最近 N 天")
    ap.add_argument("--probe", action="store_true")
    args = ap.parse_args()

    if args.probe:
        for name, url in [("youtube", "https://www.youtube.com/"),
                          ("reddit", "https://www.reddit.com/r/emulation/new.json?limit=1"),
                          ("telegram", "https://t.me/s/durov")]:
            try:
                n = len(get(url, 15))
                print(f"{name}: OK {n}B")
            except Exception as ex:
                print(f"{name}: FAIL {type(ex).__name__} {ex}")
        return 0

    errors, yt, rd = [], [], []
    for handle, why in CHANNELS:
        try:
            cid = resolve_channel_id(handle)
            if not cid:
                errors.append(f"{handle}: 未解析到 channelId")
                continue
            rows = youtube_feed(handle, cid)
            for r in rows:
                r["why"] = why
            yt += rows
            print(f"[yt] {handle:<16s} {len(rows)} 条", flush=True)
        except Exception as ex:
            errors.append(f"{handle}: {type(ex).__name__} {ex}")
            print(f"[yt] {handle:<16s} 失败 {ex}", flush=True)
        time.sleep(1.2)

    for sub, why in SUBREDDITS:
        try:
            rows = reddit_new(sub)
            for r in rows:
                r["why"] = why
            rd += rows
            print(f"[rd] r/{sub:<18s} {len(rows)} 条", flush=True)
        except Exception as ex:
            errors.append(f"r/{sub}: {type(ex).__name__} {ex}")
            print(f"[rd] r/{sub:<18s} 失败 {ex}", flush=True)
        time.sleep(2.5)

    yt = [r for r in yt if r["days"] <= args.days]
    rd = [r for r in rd if r["days"] <= args.days]
    payload = {
        "generated_at": _NOW.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "youtube": sorted(yt, key=lambda r: r["published"], reverse=True),
        "reddit": sorted(rd, key=lambda r: r["created"], reverse=True),
        "errors": errors,
        "counts": {"youtube": len(yt), "reddit": len(rd)},
    }

    print(f"\n合计 YouTube {len(yt)} / Reddit {len(rd)}，错误 {len(errors)}")
    if args.out:
        os.makedirs(os.path.dirname(args.out), exist_ok=True)
        json.dump(payload, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(f"→ {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
