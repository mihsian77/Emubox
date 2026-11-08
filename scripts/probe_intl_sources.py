"""一次性可达性探针：在 CI runner 上实测各信源能否真拿到数据。

只为回答「YouTube / Reddit / 国内源 到底能不能取到」，跑完即可删。
"""
import json
import sys
import urllib.error
import urllib.request

UA_BROWSER = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

HANDLES = ["MrSujano", "ETAPrime", "RetroGameCorps", "TechDweeb"]


def get(url, headers=None, timeout=20):
    req = urllib.request.Request(url, headers=headers or {"User-Agent": UA_BROWSER})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read()


def show(tag, fn):
    try:
        n, detail = fn()
        print(f"{tag:46s} OK   {n}  {detail}"[:200])
    except urllib.error.HTTPError as e:
        print(f"{tag:46s} HTTP {e.code}")
    except Exception as e:
        print(f"{tag:46s} ERR  {type(e).__name__} {str(e)[:60]}")


# ---------- YouTube ----------
def yt_oembed(h):
    def run():
        st, b = get("https://www.youtube.com/oembed?url=https://www.youtube.com/@%s&format=json" % h)
        d = json.loads(b)
        return len(b), f"{d.get('author_name')} | {d.get('author_url')}"
    return run


def yt_page(h):
    def run():
        st, b = get("https://www.youtube.com/@" + h,
                    {"User-Agent": UA_BROWSER, "Accept-Language": "en-US,en;q=0.9",
                     "Cookie": "CONSENT=YES+cb; SOCS=CAI"})
        s = b.decode("utf-8", "ignore")
        import re
        m = re.search(r'"channelId":"(UC[\w-]{20,24})"', s)
        return len(s), (m.group(1) if m else "no channelId in html")
    return run


def yt_feed(tag, cid):
    def run():
        st, b = get(f"https://www.youtube.com/feeds/videos.xml?channel_id={cid}")
        import re
        n = len(re.findall(r"<entry>", b.decode("utf-8", "ignore")))
        return n, f"{len(b)}B entries={n}"
    return run


# ---------- Reddit ----------
def rd(tag, url, headers=None):
    def run():
        st, b = get(url, headers or {"User-Agent": UA_BROWSER})
        s = b.decode("utf-8", "ignore")
        import re
        n = len(re.findall(r"<entry>", s)) if ".rss" in url else len(json.loads(s).get("data", {}).get("children", []))
        return n, f"{len(b)}B"
    return run


# ---------- 国内 ----------
def bili_rank():
    def run():
        st, b = get("https://api.bilibili.com/x/web-interface/ranking/v2?rid=0&type=all",
                    {"User-Agent": UA_BROWSER, "Referer": "https://www.bilibili.com/"})
        d = json.loads(b)
        return len(d.get("data", {}).get("list", [])), f"code={d.get('code')}"
    return run


def bili_search(kw):
    def run():
        import urllib.parse
        u = ("https://api.bilibili.com/x/web-interface/wbi/search/type?search_type=video&keyword="
             + urllib.parse.quote(kw))
        st, b = get(u, {"User-Agent": UA_BROWSER, "Referer": "https://www.bilibili.com/"})
        d = json.loads(b)
        res = (d.get("data") or {}).get("result") or []
        return len(res), f"code={d.get('code')} msg={d.get('message')}"
    return run


def rss_site(tag, url):
    def run():
        st, b = get(url, {"User-Agent": UA_BROWSER})
        s = b.decode("utf-8", "ignore")
        import re
        return len(re.findall(r"<item[ >]", s)), ""
    return run


print("=== YouTube ===")
for h in HANDLES:
    show(f"oembed @{h}", yt_oembed(h))
for h in HANDLES:
    show(f"channel page @{h}", yt_page(h))

print("\n=== YouTube RSS（channel_id 需上一步拿到）===")
for h in HANDLES:
    try:
        st, b = get("https://www.youtube.com/oembed?url=https://www.youtube.com/@%s&format=json" % h)
        au = json.loads(b).get("author_url", "")
        cid = au.rstrip("/").split("/")[-1] if "/channel/" in au else ""
        if cid.startswith("UC"):
            show(f"feed {cid}", yt_feed(h, cid))
    except Exception as e:
        print(f"{'feed @'+h:46s} SKIP {type(e).__name__}")

print("\n=== Reddit ===")
for sub in ["EmulationOnAndroid", "Winlator", "emulation"]:
    show(f"www json r/{sub}", rd("", f"https://www.reddit.com/r/{sub}/new.json?limit=10"))
    show(f"old rss  r/{sub}", rd("", f"https://old.reddit.com/r/{sub}/new/.rss"))
    show(f"www rss  r/{sub}", rd("", f"https://www.reddit.com/r/{sub}/new/.rss"))
show("api.reddit.com json", rd("", "https://api.reddit.com/r/emulation/new?limit=10"))

print("\n=== 国内 ===")
show("bilibili 排行 API", bili_rank())
show("bilibili 搜索 Winlator", bili_search("Winlator"))
show("bilibili 搜索 模拟器", bili_search("安卓 模拟器"))
show("机核 RSS", rss_site("", "https://www.gcores.com/rss"))
show("少数派 RSS", rss_site("", "https://sspai.com/feed"))
show("IT之家 API", lambda: (lambda r: (len(json.loads(r[1]).get("list", [])), ""))(get(
    "https://m.ithome.com/api/news/newslistpageget?page=1&action=1&is_aft=0",
    {"User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) Mobile/15E148"})))
show("酷安 头条 API", lambda: (lambda r: (len((json.loads(r[1]).get("data") or [])), ""))(get(
    "https://api.coolapk.com/v6/main/init", {"User-Agent": "Dalvik/2.1.0 (Linux; U; Android 12)",
                                             "X-App-Id": "com.coolapk.market"})))
show("X/Twitter 公开页", lambda: (lambda r: (len(r[1]), ""))(get("https://x.com/emuplace")))
show("Telegram 预览", lambda: (lambda r: (len(r[1]), ""))(get("https://t.me/s/durov")))
