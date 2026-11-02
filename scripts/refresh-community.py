#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""社区情报每日刷新：B站模拟器UP主 + dreamboyn81 云盘仓库 + gitee 梦想空间。

数据源（从本地迁移包 123mon/monitor_dreaming.py 重新实现）：
- B站两位UP主投稿+动态：dreamboyn81 (mid=33538030)、非梦nanjing (mid=670994596)。
  主通道纯 Python（wbi 签名 + buvid 指纹 + 3s 请求间隔）；B站对无浏览器环境的
  IP 常见 412/-799 风控（实测），失败自动降级 Playwright 浏览器通道（可选依赖）。
  登录态 SESSDATA 从环境变量 BILI_SESSDATA 或 ~/.emubox/bili_cookie.txt 读取，
  凭据不进仓库（CI 用 repo secret 注入；无凭据也能跑，只是风控概率更高）。
- 123 云盘 dreamboyn81「造梦空间」49 个分享深层目录树：与 community/123-snapshot.json
  对比，报告新增/归档；「归档清理」≠ 项目下线，多为作者整理旧预览包。
- gitee dreamboyn81/dreaming-space：仓库最近推送（API 失败降级抓页面）。

输出：
  community-data.json + app/src/main/assets/community_offline.json
快照（供跨次运行 diff，随仓库提交）：
  community/bili-snap.json（投稿标题）、community/dyn-snap.json（动态指纹）、
  community/123-snapshot.json（云盘目录树）
"""
import hashlib
import hmac
import json
import os
import random
import re
import string
import sys
import time
import urllib.parse
import urllib.request

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO_ROOT, "community-data.json")
OUT_ASSETS = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "community_offline.json")
SNAP_DIR = os.path.join(REPO_ROOT, "community")
BILI_SNAP = os.path.join(SNAP_DIR, "bili-snap.json")
DYN_SNAP = os.path.join(SNAP_DIR, "dyn-snap.json")
C123_SNAP = os.path.join(SNAP_DIR, "123-snapshot.json")

UPS = [("dreamboyn81", 33538030), ("非梦nanjing", 670994596)]
GITEE_REPO = "dreamboyn81/dreaming-space"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/126 Safari/537.36"

# ---- 123 云盘：dreamboyn81 造梦空间全部分享（49 个）----
# (分类, 名称, shareKey, 提取码)；与本地迁移包 123mon/123catalog.py 对齐。
C123_LINKS = [
    ("微软", "PC", "0kZeVv-PLoKd", "2nIA"), ("微软", "WP", "0kZeVv-XLoKd", "cSy1"),
    ("微软", "Xbox", "0kZeVv-nLoKd", "KCJJ"), ("微软", "Xbox360", "0kZeVv-4IMUd", "yjlr"),
    ("微软", "idSoftware", "0kZeVv-2LoKd", "Ud8b"),
    ("索尼", "PS5", "0kZeVv-M7Eed", "txOR"), ("索尼", "PS4", "0kZeVv-ovQ7d", "piRt"),
    ("索尼", "PS3", "0kZeVv-sLoKd", "1C2m"), ("索尼", "PS2", "0kZeVv-FSoKd", "e9FZ"),
    ("索尼", "PS1", "0kZeVv-WLoKd", "mN0P"), ("索尼", "PSV", "0kZeVv-CLoKd", "Flqz"),
    ("索尼", "PSP", "0kZeVv-NLoKd", "FxWp"),
    ("任天堂", "NS1", "0kZeVv-nLmKd", "QnvN"), ("任天堂", "3DS", "0kZeVv-ZLoKd", "LkXq"),
    ("任天堂", "WiiU", "0kZeVv-eLoKd", "jVgE"), ("任天堂", "Wii", "0kZeVv-zLoKd", "TU2P"),
    ("任天堂", "NDS", "0kZeVv-6LoKd", "ymKO"), ("任天堂", "N64", "0kZeVv-jLoKd", "954O"),
    ("任天堂", "GBA", "0kZeVv-d5oKd", "5zON"), ("任天堂", "GBC", "0kZeVv-H5oKd", "8MNr"),
    ("任天堂", "SFC", "0kZeVv-Q5oKd", "H22Y"), ("任天堂", "FC", "0kZeVv-o5oKd", "Dd3u"),
    ("世嘉", "Model3", "0kZeVv-iQ1Kd", "G79j"), ("世嘉", "DC", "0kZeVv-45oKd", "jqjj"),
    ("世嘉", "SS", "0kZeVv-f5oKd", "X6OB"), ("世嘉", "MD", "0kZeVv-J5oKd", "g9V8"),
    ("苹果", "iOS", "0kZeVv-SLoKd", "QdIX"),
    ("梦系列", "EMU合集", "0kZeVv-ULoKd", "MKYZ"),
    ("诺基亚", "塞班", "0kZeVv-BzoKd", "Hj5q"), ("诺基亚", "Java", "0kZeVv-JzoKd", "oO39"),
    ("其它1", "皮克敏", "0kZeVv-DCfed", "yj9a"), ("其它1", "索尼克", "0kZeVv-lIMUd", "1uOR"),
    ("其它1", "丝之歌", "0kZeVv-83mKd", "eCTz"), ("其它1", "无尽之剑", "0kZeVv-cIbcd", "Voxc"),
    ("其它1", "空洞骑士", "0kZeVv-V5oKd", "fasc"), ("其它1", "王国之心", "0kZeVv-Aq7Zd", "azDl"),
    ("其它1", "丁果掌机", "0kZeVv-Jd1Kd", "wU7D"), ("其它1", "开源驱动", "0kZeVv-8LoKd", "Qpk9"),
    ("其它1", "复古街机", "0kZeVv-9zoKd", "vTZx"), ("其它1", "游戏键盘", "0kZeVv-DLoKd", "ynv1"),
    ("其它1", "格式转换", "0kZeVv-QVoKd", "Hfm1"),
    ("其它2", "Pocket8", "0kZeVv-RjmKd", "QJti"), ("其它2", "Termux", "0kZeVv-BSoKd", "PWPX"),
    ("其它2", "Brew", "0kZeVv-azoKd", "UlG3"), ("其它2", "3DO", "0kZeVv-RLoKd", "U6sQ"),
    ("其它2", "雅达利", "0kZeVv-OLoKd", "zgU3"), ("其它2", "NSZ解包器", "0kZeVv-1C4Ud", "onBW"),
    ("其它2", "FinalROM", "0kZeVv-5CiUd", "4k7i"), ("其它2", "PKHeX", "0kZeVv-OlmKd", "R4k1"),
]


def get_text(url, headers=None, timeout=20):
    h = {"User-Agent": UA}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "ignore")


def get_json(url, headers=None, timeout=20):
    return json.loads(get_text(url, headers, timeout))


def _load(path):
    if os.path.exists(path):
        try:
            return json.load(open(path, encoding="utf-8"))
        except Exception:
            pass
    return {}


def _save(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    json.dump(data, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


# ==================== B 站：wbi 签名纯 Python 主通道 ====================
# B站 2026 风控要点（迁移包实测结论）：无指纹直接 412；无 wbi 签名 -403；
# 高频 -799。三件套：buvid 指纹 + wbi 签名 + 相邻请求 >=3s。
MIXIN_KEY_ENC_TAB = [46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
                     27, 43, 25, 56, 33, 0, 34, 14, 49, 9, 57, 39, 16, 29, 37, 19]
_buvid = {}
_wbi_key = None
_last_ts = [0.0]


def _pace():
    d = time.time() - _last_ts[0]
    if d < 3.0:
        time.sleep(3.0 - d)
    _last_ts[0] = time.time()


def _init_buvid():
    if _buvid:
        return _buvid
    try:
        d = get_json("https://api.bilibili.com/x/frontend/finger/spi")
        _buvid["b3"] = (d.get("data") or {}).get("b_3", "")
        _buvid["b4"] = (d.get("data") or {}).get("b_4", "")
    except Exception:
        pass
    if not _buvid.get("b3"):
        rnd = lambda n: "".join(random.choice(string.ascii_letters + string.digits) for _ in range(n))
        _buvid["b3"] = rnd(24) + "infoc"
        _buvid["b4"] = rnd(32) + "infoc"
    return _buvid


def _wbi():
    global _wbi_key
    if _wbi_key:
        return _wbi_key
    try:
        d = get_json("https://api.bilibili.com/x/web-interface/nav")
        w = (d.get("data") or {}).get("wbi_img") or {}
        k = lambda u: u.rsplit("/", 1)[-1].split(".")[0]
        mix = k(w.get("img_url", "")) + k(w.get("sub_url", ""))
        _wbi_key = "".join(mix[i] for i in MIXIN_KEY_ENC_TAB)
    except Exception:
        _wbi_key = None
    return _wbi_key


def _sessdata():
    """登录态：优先环境变量 BILI_SESSDATA，其次本地 ~/.emubox/bili_cookie.txt（不进仓库）"""
    v = os.environ.get("BILI_SESSDATA", "").strip()
    if v:
        return v
    p = os.path.expanduser("~/.emubox/bili_cookie.txt")
    if os.path.exists(p):
        raw = open(p).read().strip()
        m = re.search(r"SESSDATA=([^;\s]+)", raw)
        return m.group(1) if m else (raw if raw else "")
    return ""


def _bili_cookie():
    b = _init_buvid()
    ck = ""
    s = _sessdata()
    if s:
        ck += "SESSDATA=" + s
    if b.get("b3"):
        ck += ("; " if ck else "") + "buvid3=" + b["b3"]
    if b.get("b4"):
        ck += ("; " if ck else "") + "buvid4=" + b["b4"]
    return ck


def bili_videos_api(mid, ps=30):
    """arc/search 投稿列表（wbi 签名）。成功返回视频列表，失败抛异常（含 code/msg）。"""
    key = _wbi()
    if not key:
        raise RuntimeError("wbi_keys_unavailable")
    params = {"mid": mid, "pn": 1, "ps": ps, "order": "pubdate", "platform": "web",
              "wts": str(int(time.time()))}
    q = urllib.parse.urlencode(sorted(params.items()))
    params["w_rid"] = hmac.new(key.encode(), q.encode(), hashlib.md5).hexdigest()
    url = "https://api.bilibili.com/x/space/arc/search?" + urllib.parse.urlencode(params)
    _pace()
    d = get_json(url, {"Cookie": _bili_cookie(), "Referer": "https://space.bilibili.com/%d" % mid})
    if d.get("code") != 0:
        raise RuntimeError("code:%s msg:%s" % (d.get("code"), str(d.get("message"))[:40]))
    vlist = ((d.get("data") or {}).get("list") or {}).get("vlist") or []
    return [{"title": v.get("title", ""), "bvid": v.get("bvid", ""),
             "created": v.get("created", 0)} for v in vlist[:ps]]


# ==================== B 站：Playwright 浏览器降级通道 ====================
def _find_chrome():
    for p in (os.environ.get("BILI_CHROME"), "/usr/bin/chromium", "/usr/bin/google-chrome",
              "/opt/browser/chrome", "/usr/bin/chromium-browser"):
        if p and os.path.exists(p):
            return p
    return None


def bili_videos_pw(mid, ps=30):
    """m.bilibili 空间页拦截 arc/search 响应（浏览器环境对 412/-403 免疫）。"""
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        raise RuntimeError("playwright not installed")
    exe = _find_chrome()
    if not exe:
        raise RuntimeError("no chrome executable")
    sess = _sessdata()
    out = []
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=exe, headless=True,
                                    args=["--no-sandbox", "--disable-dev-shm-usage"])
        ctx = browser.new_context(
            user_agent="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36",
            viewport={"width": 390, "height": 844})
        if sess:
            ctx.add_cookies([{"name": "SESSDATA", "value": sess, "domain": ".bilibili.com", "path": "/"}])
        page = ctx.new_page()

        def on_resp(resp):
            if "arc/search" in resp.url and resp.status == 200:
                try:
                    j = resp.json()
                    for v in ((j.get("data") or {}).get("list") or {}).get("vlist") or []:
                        out.append({"title": v.get("title", ""), "bvid": v.get("bvid", ""),
                                    "created": v.get("created", 0)})
                except Exception:
                    pass
        page.on("response", on_resp)
        page.goto("https://m.bilibili.com/space/%d" % mid, timeout=30000)
        page.wait_for_timeout(5000)
        # 空间页默认"综合"排序会把新稿挤出前 50（迁移包实测漏采教训），
        # 点"最新发布"触发 order=pubdate 的 arc/search
        for _ in range(2):
            try:
                page.click("text=最新发布", timeout=8000)
                page.wait_for_timeout(6000)
                break
            except Exception:
                break
        browser.close()
    seen, dedup = set(), []
    for v in out[:ps]:
        if v["bvid"] and v["bvid"] not in seen:
            seen.add(v["bvid"])
            dedup.append(v)
    if not dedup:
        raise RuntimeError("pw_empty")
    return dedup


def bili_dynamics_pw(mid):
    """浏览器内 fetch web-dynamic 接口（纯 Python 对该接口 412）。"""
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        raise RuntimeError("playwright not installed")
    exe = _find_chrome()
    if not exe:
        raise RuntimeError("no chrome executable")
    sess = _sessdata()
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=exe, headless=True,
                                    args=["--no-sandbox", "--disable-dev-shm-usage"])
        ctx = browser.new_context(user_agent=UA, viewport={"width": 1280, "height": 900})
        if sess:
            ctx.add_cookies([{"name": "SESSDATA", "value": sess, "domain": ".bilibili.com", "path": "/"}])
        page = ctx.new_page()
        page.goto("https://www.bilibili.com/", timeout=20000)
        page.wait_for_timeout(2000)
        j = page.evaluate("""async (mid) => {
            const r = await fetch('https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?host_mid=' + mid, {credentials:'include'});
            return await r.json();
        }""", mid)
        browser.close()
    items = ((j or {}).get("data") or {}).get("items") or []
    out = []
    for it in items:
        try:
            mods = it.get("modules") or {}
            mmod = mods.get("module_dynamic") or {}
            major = mmod.get("major") or {}
            ma = mods.get("module_author") or {}
            ts = int(ma.get("pub_ts") or 0)
            dtyp = it.get("type", "")
            txt = (major.get("archive") or {}).get("title", "")
            if not txt:
                txt = (major.get("opus") or {}).get("summary", {}).get("text", "")
            if not txt:
                txt = "[图文动态] %s" % ma.get("pub_time", "") if ma.get("pub_time") else "[%s]" % dtyp
            out.append({"ts": ts, "type": dtyp, "text": str(txt)[:100]})
        except Exception:
            continue
    if not out:
        raise RuntimeError("dyn_empty")
    return out


def check_ups():
    """逐 UP 抓投稿+动态，与快照 diff 出新增。通道失败记录 err，不中断。"""
    snap = _load(BILI_SNAP)
    dsnap = _load(DYN_SNAP)
    new_snap, new_dyn, ups_out = {}, {}, []
    for name, mid in UPS:
        u = {"name": name, "mid": mid, "url": "https://space.bilibili.com/%d" % mid}
        videos, channel, err = [], "", ""
        for fn in (bili_videos_api, bili_videos_pw):
            try:
                videos = fn(mid)
                channel = "api" if fn is bili_videos_api else "playwright"
                break
            except Exception as e:
                err = str(e)[:60]
        if videos:
            old = set(snap.get(name, []))
            fresh = [v for v in videos if v["title"] not in old]
            u["videos"] = videos[:30]
            u["new_videos"] = fresh[:10]
            u["channel"] = channel
            new_snap[name] = [v["title"] for v in videos]
        else:
            u["err"] = err
            u["videos"] = []
            u["new_videos"] = []
            new_snap[name] = snap.get(name, [])
        dyn, derr = [], ""
        try:
            dyn = bili_dynamics_pw(mid)
        except Exception as e:
            derr = str(e)[:60]
        if dyn:
            fp = lambda d: (str(d["ts"]), d["type"], d["text"])
            old = set(fp(d) for d in dsnap.get(name, []))
            fresh = [d for d in dyn if fp(d) not in old]
            u["dynamics"] = dyn[:20]
            u["new_dynamics"] = fresh[:10]
            u["dyn_err"] = ""
            new_dyn[name] = dyn[:30]
        else:
            u["dyn_err"] = derr
            u["dynamics"] = []
            u["new_dynamics"] = []
            new_dyn[name] = dsnap.get(name, [])
        ups_out.append(u)
    _save(BILI_SNAP, new_snap)
    _save(DYN_SNAP, new_dyn)
    return ups_out


# ==================== 123 云盘目录树 ====================
def c123_list(share_key, pwd, parent):
    q = urllib.parse.urlencode({"shareKey": share_key, "SharePwd": pwd,
                                "ParentFileId": parent, "next": 0, "limit": 500,
                                "orderBy": "file_name", "orderDirection": "asc", "Page": 1})
    d = get_json("https://www.123912.com/api/share/get?" + q)
    if d.get("code") == 0:
        return d["data"].get("InfoList", [])
    raise RuntimeError(str(d.get("message"))[:50])


def c123_walk(share_key, pwd, parent, depth, prefix=""):
    """递归下钻，返回条目列表（D:目录 / F:文件(大小)）；出错返回 (已收集, err)。"""
    out, err = [], None
    try:
        items = c123_list(share_key, pwd, parent)
    except Exception as e:
        return out, str(e)
    for it in items:
        p = (prefix + "/" if prefix else "") + it["FileName"]
        if it.get("Type") == 1:
            out.append("D:" + p)
            if depth > 1:
                sub, err = c123_walk(share_key, pwd, it["FileId"], depth - 1, p)
                out.extend(sub)
                if err:
                    out.append("ERR:" + p + ":" + err)
                time.sleep(0.15)
        else:
            sz = it.get("Size", 0)
            siz = ""
            if sz >= 1024 ** 3:
                siz = " (%.1fGB)" % (sz / 1024 ** 3)
            elif sz >= 1024 ** 2:
                siz = " (%.1fMB)" % (sz / 1024 ** 2)
            elif sz > 0:
                siz = " (%.0fKB)" % (sz / 1024)
            out.append("F:" + p + siz)
    return out, err


def check_cloud123(depth=2):
    """全量 49 分享下钻，与快照 diff。depth 默认 2（CI 友好；本地可传 3）。"""
    old = _load(C123_SNAP)
    tree, errs = {}, []
    for cat, name, key, pwd in C123_LINKS:
        label = "%s/%s" % (cat, name)
        try:
            entries, err = c123_walk(key, pwd, 0, depth)
            if err:
                errs.append("%s:%s" % (label, err[:40]))
            tree[label] = entries
            time.sleep(0.2)
        except Exception as e:
            errs.append("%s:%s" % (label, str(e)[:40]))
    added, gone = [], []
    if old:
        for k, v in tree.items():
            ov = set(old.get(k, []))
            nv = set(v)
            for x in sorted(nv - ov):
                added.append("%s + %s" % (k, x))
            for x in sorted(ov - nv):
                gone.append("%s - %s" % (k, x))
    _save(C123_SNAP, tree)
    return {"shares": len(C123_LINKS), "scanned": len(tree), "errs": errs[:10],
            "first_run": not bool(old), "added": added[:80], "gone": gone[:80],
            "tree": tree}


# ==================== gitee 梦想空间 ====================
def check_gitee():
    try:
        d = get_json("https://gitee.com/api/v5/repos/%s" % GITEE_REPO)
        commits = get_json("https://gitee.com/api/v5/repos/%s/commits?per_page=5" % GITEE_REPO)
        top = commits[0] if commits else {}
        return {"ok": True, "repo_url": "https://gitee.com/%s" % GITEE_REPO,
                "last_push": d.get("pushed_at", ""),
                "latest_msg": (top.get("commit", {}).get("message", "") or "")[:100],
                "latest_date": (top.get("commit", {}).get("committer", {}).get("date", ""))[:16]}
    except Exception as e:
        # 降级：抓仓库页面提取最近时间
        try:
            html = get_text("https://gitee.com/%s" % GITEE_REPO)
            m = re.search(r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2})", html)
            return {"ok": True, "degraded": True, "repo_url": "https://gitee.com/%s" % GITEE_REPO,
                    "last_push": m.group(1) if m else "", "latest_msg": "", "latest_date": ""}
        except Exception as e2:
            return {"ok": False, "err": str(e2)[:60]}


def main():
    depth = int(sys.argv[1]) if len(sys.argv) > 1 else 2
    data = {
        "version": 1,
        "updated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "ups": check_ups(),
        "gitee": check_gitee(),
        "cloud123": check_cloud123(depth),
    }
    json.dump(data, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    json.dump(data, open(OUT_ASSETS, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    # 摘要打印
    for u in data["ups"]:
        ch = u.get("channel", "")
        if u.get("err"):
            print(f"- {u['name']}: 投稿抓取失败（{u['err']}）", file=sys.stderr)
        else:
            print(f"- {u['name']}: 投稿 {len(u['videos'])} 条[{ch}] 新增 {len(u['new_videos'])}")
            for v in u["new_videos"][:5]:
                print("    · " + v["title"][:60])
        if u.get("dyn_err"):
            print(f"  动态: {u['dyn_err']}", file=sys.stderr)
        else:
            print(f"  动态 {len(u['dynamics'])} 条 新增 {len(u['new_dynamics'])}")
    c = data["cloud123"]
    if c["first_run"]:
        print("123 云盘：首次运行，快照已建立")
    else:
        print(f"123 云盘：扫描 {c['scanned']}/{c['shares']} 分享，新增 {len(c['added'])} 归档 {len(c['gone'])}")
        for a in c["added"][:8]:
            print("    + " + a[:70])
    print("已写 %s 与 assets 离线副本" % OUT)


if __name__ == "__main__":
    main()
