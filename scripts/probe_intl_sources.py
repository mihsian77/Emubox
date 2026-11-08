"""探针 v2：YouTube channelId 多模式提取、RSS 实测、Reddit RSS 限速、B站搜索质量。"""
import json
import re
import time
import urllib.error
import urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
HANDLES = ["MrSujano", "ETAPrime", "RetroGameCorps", "TechDweeb"]
PATS = [
    r'"channelId":"(UC[\w-]{22})"',
    r'"externalId":"(UC[\w-]{22})"',
    r'"browseId":"(UC[\w-]{22})"',
    r'/channel/(UC[\w-]{22})',
    r'channel_id=(UC[\w-]{22})',
    r'"(UC[\w-]{22})"',
]


def get(url, headers=None, timeout=25):
    req = urllib.request.Request(url, headers=headers or {"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read()


print("=== YouTube channelId 提取（多模式）===")
ids = {}
for h in HANDLES:
    try:
        st, b = get("https://www.youtube.com/@" + h,
                    {"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9",
                     "Cookie": "CONSENT=YES+cb.20210328-17-p0.en+FX+123; SOCS=CAI"})
        s = b.decode("utf-8", "ignore")
        found = []
        for p in PATS:
            m = re.search(p, s)
            if m and m.group(1) not in found:
                found.append(m.group(1))
        ids[h] = found[0] if found else None
        title = re.search(r"<title>([^<]{0,60})", s)
        print(f"{h:16s} {st} {len(s)//1024}KB  ids={found[:3]}  title={title.group(1) if title else '?'}"[:150])
    except Exception as e:
        print(f"{h:16s} ERR {type(e).__name__} {e}")
    time.sleep(2)

print("\n=== YouTube RSS（用上面拿到的 id）===")
for h, cid in ids.items():
    if not cid:
        print(f"{h:16s} SKIP 无 id")
        continue
    try:
        st, b = get(f"https://www.youtube.com/feeds/videos.xml?channel_id={cid}")
        s = b.decode("utf-8", "ignore")
        n = len(re.findall(r"<entry>", s))
        t = re.findall(r"<title>([^<]{0,50})", s)[1:3]
        print(f"{h:16s} {st} entries={n}  {t}")
    except Exception as e:
        print(f"{h:16s} ERR {type(e).__name__} {e}")
    time.sleep(1)

print("\n=== Reddit RSS（间隔 8 秒，遇 429 退避）===")
for sub in ["EmulationOnAndroid", "Winlator", "emulation"]:
    for attempt in range(3):
        try:
            st, b = get(f"https://www.reddit.com/r/{sub}/new/.rss?limit=25")
            s = b.decode("utf-8", "ignore")
            n = len(re.findall(r"<entry>", s))
            titles = re.findall(r"<title>([^<]{0,60})", s)[1:3]
            print(f"r/{sub:18s} {st} entries={n} {titles}")
            break
        except urllib.error.HTTPError as e:
            print(f"r/{sub:18s} HTTP {e.code} (try {attempt+1})")
            if e.code == 429:
                time.sleep(30)
            else:
                break
        except Exception as e:
            print(f"r/{sub:18s} ERR {type(e).__name__} {e}")
            break
    time.sleep(8)

print("\n=== B 站搜索（看命中的是不是模拟器内容）===")
for kw in ["Winlator", "安卓 模拟器", "yuzu 安卓", "小龙 模拟器"]:
    try:
        import urllib.parse
        u = ("https://api.bilibili.com/x/web-interface/wbi/search/type?search_type=video&keyword="
             + urllib.parse.quote(kw))
        st, b = get(u, {"User-Agent": UA, "Referer": "https://www.bilibili.com/"})
        d = json.loads(b)
        res = (d.get("data") or {}).get("result") or []
        print(f"[{kw}] code={d.get('code')} n={len(res)}")
        for r in res[:4]:
            print("    ", str(r.get("title", ""))[:60].replace("<em class=\"keyword\">", "").replace("</em>", ""))
    except Exception as e:
        print(f"[{kw}] ERR {type(e).__name__} {e}")
    time.sleep(2)

print("\n=== 机核 / 少数派 / IT之家 ===")
for tag, u in [("机核", "https://www.gcores.com/rss"), ("少数派", "https://sspai.com/feed")]:
    try:
        st, b = get(u)
        s = b.decode("utf-8", "ignore")
        print(f"{tag} {st} items={len(re.findall(r'<item[ >]', s))} titles={re.findall(r'<title>([^<]{0,40})', s)[1:3]}")
    except Exception as e:
        print(f"{tag} ERR {type(e).__name__} {e}")
try:
    st, b = get("https://api.ithome.com/json/newslist/news", {"User-Agent": UA})
    print("IT之家 api.ithome.com", st, len(b))
except Exception as e:
    print("IT之家 api.ithome.com ERR", type(e).__name__, e)
