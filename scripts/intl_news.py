#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""境外 + 国内信源采集：YouTube 频道、Reddit 板块、B 站视频。

为什么单独一个脚本、并且只在 GitHub Actions 里跑：
  本机/沙箱出口对 youtube.com、reddit.com、t.me、x.com 全部不通（curl 000，
  TLS 都建不起来），只有 GitHub 系可达。Actions 的 runner 在海外，天然可用。
  所以这条链路的数据生产在 Actions，消费在本地：产物 commit 回 data/intl-news/。
  B 站反而相反：境内外都通，放这里只是为了「所有外采信源一份产物」。

2026-11-08 实测（runner 上逐源跑过，见 scripts/probe_intl_sources.py）：
  - YouTube 频道页 200 但 oembed 404；channelId 要从页面 HTML 抠，
    正则需覆盖 "channelId" / "externalId" / "browseId" / 裸 "(UC…)" 四种；
    拿到 id 后 feeds/videos.xml 稳定返回 15 条。
  - Reddit 的 new.json / old.reddit 一律 403（机房 IP 被拒），只有
    www.reddit.com/r/<sub>/new/.rss 可读；连续请求会 429，需 8 秒间隔 + 退避。
  - B 站 search/type 可用（但 wbi 搜索对泛词噪声大，必须过标题白名单）。

不依赖第三方库：urllib + xml.etree，runner 上零安装。

用法：
  python3 intl_news.py --out data/intl-news/2026-11-05.json
  python3 intl_news.py --probe     # 只测可达性
"""
import argparse
import html as htmllib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

# handle, channel_id, 定位。channel_id 是 2026-11-08 实测抓到的，
# 写死是为了不再依赖频道页解析（机房 IP 上那一步只有 1/4 成功率）。
# 只放「专门追模拟器/安卓移植新闻」的频道，泛数码大号不收。
CHANNELS = [
    ("MrSujano", "UCu-NRUdNtfcjdGA5Abt3JUw", "每日模拟器新闻，覆盖 PC/安卓/掌机，最贴我们的一条线"),
    ("ETAPrime", "UC_0CVCfC_3iuHqmyClu59Uw", "安卓设备跑 PC 游戏、Winlator 实机演示"),
    ("RetroGameCorps", "UC3IYhdamzjvEJ9CwkQa4RAw", "掌机与模拟器教程，偏评测但更新稳定"),
    ("TechDweeb", "UCgRaK4A7yi4ZELCLUjdP_pg", "复古游戏与模拟器工具"),
]

SUBREDDITS = [
    ("EmulationOnAndroid", "安卓模拟器主板块，项目首发常在这里"),
    ("Winlator", "Winlator 生态"),
    ("emulation", "综合，量大但杂"),
]

# B 站搜索词。泛词（"安卓 模拟器"）会捞到雷电、夜神这类 PC 安卓模拟器，
# 所以每条标题都要过 TITLE_MUST / TITLE_DENY 两道闸门。
BILI_KEYWORDS = [
    "Winlator",
    "yuzu 安卓",
    "switch 模拟器 安卓",
    "ps2 模拟器 安卓",
    "安卓 模拟器 手机",
    "turnip 驱动",
]
# 标题必须命中其一：说明它讲的是「在手机上跑别的平台」或驱动层
TITLE_MUST = (
    "模拟器", "emulator", "winlator", "yuzu", "ryujinx", "龙神", "citra", "azahar",
    "ppsspp", "dolphin", "melon", "retroarch", "skyline", "sudachi", "eden",
    "pcsx2", "rpcs3", "vita3k", "winlator", "turnip", "驱动", "gpu", "vulkan",
)
# 标题命中即丢弃：PC 上的安卓模拟器（跟我们不是一个方向）、纯教程带货
TITLE_DENY = (
    "雷电模拟器", "夜神", "mumu", "bluestacks", "逍遥模拟器", "手游助手",
    "安卓模拟器电脑", "电脑玩手游", "多开",
)

_NOW = datetime.now(timezone.utc)


def get(url, timeout=25):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept-Language": "en-US,en;q=0.9",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


CID_CACHE = "data/intl-news/.channels.json"


def _load_cid_cache():
    try:
        return json.load(open(CID_CACHE, encoding="utf-8"))
    except Exception:
        return {}


def _save_cid_cache(d):
    try:
        os.makedirs(os.path.dirname(CID_CACHE), exist_ok=True)
        json.dump(d, open(CID_CACHE, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    except Exception:
        pass


CID_PATTERNS = (
    r'"channelId":"(UC[\w-]{22})"',
    r'"externalId":"(UC[\w-]{22})"',
    r'"browseId":"(UC[\w-]{22})"',
    r'/channel/(UC[\w-]{22})',
    r'"(UC[\w-]{22})"',
)


def resolve_channel_id(handle, tries=2):
    """频道页 HTML 里抠 channelId，作为写死 id 失效时的兜底。

    实测：oembed 一律 404，频道页 200；但单靠 "channelId" 一个正则只能命中
    1/4（页面被返回成精简版时字段名不同），所以按 CID_PATTERNS 依次试，
    并带 CONSENT Cookie 绕过同意页。
    """
    cache = _load_cid_cache()
    if cache.get(handle):
        return cache[handle]
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(
                f"https://www.youtube.com/@{handle}",
                headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9",
                         "Cookie": "CONSENT=YES+cb; SOCS=CAI"})
            with urllib.request.urlopen(req, timeout=30) as r:
                html = r.read().decode("utf-8", "ignore")
            for pat in CID_PATTERNS:
                m = re.search(pat, html)
                if m:
                    cache[handle] = m.group(1)
                    _save_cid_cache(cache)
                    return m.group(1)
        except Exception as ex:
            last = ex
        time.sleep(2 + i * 3)
    if last:
        raise last
    return None


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


def reddit_new(sub, limit=40, tries=4):
    """Reddit 只走 .rss。new.json / old.reddit 对机房 IP 是 403 一刀切。

    .rss 会 429：实测三个板块连着请求，第三个必被限。间隔 8 秒 + 遇 429 退避 30 秒。
    RSS 里没有分数和评论数（JSON 才给），所以 score/comments 置 None，
    不做假数据；要这两个数得申请 OAuth，见 docs/monitoring.md 的待定项。
    """
    last = None
    for i in range(tries):
        try:
            raw = get(f"https://www.reddit.com/r/{sub}/new/.rss?limit={limit}", timeout=30)
            break
        except urllib.error.HTTPError as ex:
            last = ex
            if ex.code == 429:
                time.sleep(30)
                continue
            raise
        except Exception as ex:
            last = ex
            time.sleep(10)
    else:
        raise last

    ns = {"a": "http://www.w3.org/2005/Atom"}
    root = ET.fromstring(raw)
    out = []
    for e in root.findall("a:entry", ns):
        link = e.find("a:link", ns)
        url = link.get("href") if link is not None else ""
        pub = e.findtext("a:published", "", ns) or e.findtext("a:updated", "", ns)
        try:
            dt = datetime.fromisoformat(pub.replace("Z", "+00:00"))
        except Exception:
            dt = _NOW
        content = e.findtext("a:content", "", ns) or ""
        cm = re.search(r"(\d+)\s+comments?", content)
        out.append({
            "sub": sub,
            "id": (e.findtext("a:id", "", ns) or "").replace("t3_", ""),
            "title": htmllib.unescape((e.findtext("a:title", "", ns) or "").strip()),
            "author": (e.findtext("a:author/a:name", "", ns) or "").replace("/u/", ""),
            "score": None,
            "comments": int(cm.group(1)) if cm else None,
            "flair": None,
            "url": url,
            "external": "",
            "days": (_NOW - dt).days,
            "created": dt.strftime("%Y-%m-%dT%H:%M:%SZ"),
        })
    return out


def bilibili_search(kw, pages=1):
    """B 站视频搜索，按最新发布排序（综合排序会捞出几年前的爆款教程）。

    wbi 版接口不带签名也能返回 code=0，但字段可能调整，失败即跳过。
    """
    import urllib.parse
    out = []
    for page in range(1, pages + 1):
        u = ("https://api.bilibili.com/x/web-interface/wbi/search/type"
             f"?search_type=video&order=pubdate&page={page}&keyword=" + urllib.parse.quote(kw))
        req = urllib.request.Request(u, headers={
            "User-Agent": UA, "Referer": "https://www.bilibili.com/"})
        with urllib.request.urlopen(req, timeout=25) as r:
            d = json.loads(r.read())
        if d.get("code") != 0:
            raise RuntimeError(f"code={d.get('code')} {d.get('message')}")
        for item in (d.get("data") or {}).get("result") or []:
            title = re.sub(r"<[^>]+>", "", item.get("title") or "")
            out.append({
                "keyword": kw,
                "bvid": item.get("bvid"),
                "title": title.strip(),
                "author": item.get("author"),
                "play": item.get("play"),
                "pubdate": item.get("pubdate"),
                "days": max(0, (_NOW - datetime.fromtimestamp(
                    item.get("pubdate") or 0, timezone.utc)).days),
                "url": f"https://www.bilibili.com/video/{item.get('bvid')}",
            })
        time.sleep(2)
    return out


def bili_relevant(title):
    t = title.lower()
    if any(d in title.lower() for d in TITLE_DENY):
        return False
    return any(k.lower() in t for k in TITLE_MUST)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="")
    ap.add_argument("--days", type=int, default=14, help="只保留最近 N 天")
    ap.add_argument("--probe", action="store_true")
    args = ap.parse_args()

    if args.probe:
        for name, url in [("youtube 频道页", "https://www.youtube.com/@TechDweeb"),
                          ("youtube rss", "https://www.youtube.com/feeds/videos.xml"
                                          "?channel_id=UCgRaK4A7yi4ZELCLUjdP_pg"),
                          ("reddit rss", "https://www.reddit.com/r/emulation/new/.rss"),
                          ("bilibili 搜索", "https://api.bilibili.com/x/web-interface/wbi/"
                                            "search/type?search_type=video&keyword=Winlator"),
                          ("telegram 预览", "https://t.me/s/durov")]:
            try:
                n = len(get(url, 15))
                print(f"{name}: OK {n}B")
            except Exception as ex:
                print(f"{name}: FAIL {type(ex).__name__} {ex}")
        return 0

    errors, yt, rd, bl = [], [], [], []
    for handle, hard_id, why in CHANNELS:
        try:
            cid = hard_id or resolve_channel_id(handle)
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
        time.sleep(8)

    for kw in BILI_KEYWORDS:
        try:
            rows = [r for r in bilibili_search(kw) if bili_relevant(r["title"])]
            bl += rows
            print(f"[bl] {kw:<18s} {len(rows)} 条（已过滤）", flush=True)
        except Exception as ex:
            errors.append(f"bili:{kw}: {type(ex).__name__} {ex}")
            print(f"[bl] {kw:<18s} 失败 {ex}", flush=True)
    # 同一视频可能被多个关键词命中
    seen, dedup = set(), []
    for r in bl:
        if r["bvid"] in seen:
            continue
        seen.add(r["bvid"])
        dedup.append(r)
    bl = dedup

    yt = [r for r in yt if r["days"] <= args.days]
    rd = [r for r in rd if r["days"] <= args.days]
    bl = [r for r in bl if r["days"] <= args.days]
    # 全 0 不能当成功：11-06 实测 YouTube 频道页 404、Reddit 403，
    # 脚本照样 exit 0，CI 显示绿色但其实什么都没收到。用非零退出码让链路故障可见。
    degraded = (len(yt) + len(rd) + len(bl)) == 0
    payload = {
        "generated_at": _NOW.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "youtube": sorted(yt, key=lambda r: r["published"], reverse=True),
        "reddit": sorted(rd, key=lambda r: r["created"], reverse=True),
        "bilibili": sorted(bl, key=lambda r: r["pubdate"] or 0, reverse=True),
        "errors": errors,
        "counts": {"youtube": len(yt), "reddit": len(rd), "bilibili": len(bl)},
        "degraded": degraded,
    }

    print(f"\n合计 YouTube {len(yt)} / Reddit {len(rd)} / B 站 {len(bl)}，错误 {len(errors)}")
    if degraded:
        print("!! 三条线都是 0 条，判定链路失效", flush=True)
    if args.out:
        os.makedirs(os.path.dirname(args.out), exist_ok=True)
        json.dump(payload, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(f"→ {args.out}")
    return 2 if degraded else 0


if __name__ == "__main__":
    sys.exit(main())
