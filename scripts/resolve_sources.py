#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「发布面」逐条探明：一个项目到底在哪发源码、在哪发构建包。

六种可机器判定的发布面：
  P1 GitHub Releases 直发 APK        assets 里有 .apk/.apks
  P2 GitHub Releases 发压缩包         .zip 里含 APK（Android 包常这样）
  P3 GitHub Actions 构建            workflow + artifact → nightly.link 公开直链
  P4 官方站点下载页                  首页/下载页里的 .apk 链接
  P5 应用商店                        Play 包名
  P6 仅桌面构建 / 仅源码 / 已归档      无 Android 产物，属正常，不算缺口

输出 resolve-report.json；不改 registry，改由 apply_fixes.py 统一落库。
"""
import json, os, re, sys, time, urllib.error, urllib.request
from concurrent.futures import ThreadPoolExecutor
from html.parser import HTMLParser

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN = open(os.path.expanduser("~/.codebuddy/.secrets/gh-token")).read().strip()
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36"

APK_RE = re.compile(r"\.(apk|apks)$", re.I)
ANDROID_HINT = re.compile(r"android|apk", re.I)


def api(path, tries=3):
    for i in range(tries):
        req = urllib.request.Request("https://api.github.com" + path, headers={
            "Authorization": "Bearer " + TOKEN, "Accept": "application/vnd.github+json",
            "User-Agent": "emubox-resolve"})
        try:
            with urllib.request.urlopen(req, timeout=25) as r:
                return json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return {"__err__": 404}
            if e.code in (403, 429):
                time.sleep(6 * (i + 1))
                continue
            return {"__err__": e.code}
        except Exception:
            time.sleep(2)
    return {"__err__": "fail"}


class LinkGrab(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links, self.text = [], []

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            for k, v in attrs:
                if k == "href" and v:
                    self.links.append(v)

    def handle_data(self, data):
        self.text.append(data)


def http_text(url, timeout=10):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read(600000)
        return raw.decode("utf-8", "ignore")


def probe_site(home):
    """官方站点找 APK：首页 + 常见下载页，抓 <a href> 与裸链接。"""
    if not home:
        return None
    base = home.rstrip("/")
    cands = [base, base + "/download", base + "/downloads", base + "/releases", base + "/android"]
    seen, found = set(), []
    for u in cands:
        if u in seen:
            continue
        seen.add(u)
        try:
            html = http_text(u)
        except Exception:
            continue
        g = LinkGrab()
        try:
            g.feed(html)
        except Exception:
            pass
        for href in g.links + re.findall(r'https?://[^\s"\'<>]+\.(?:apk|apks)', html):
            if not re.search(r"\.(apk|apks)(\?|$)", href, re.I):
                continue
            full = urllib.parse.urljoin(u + "/", href) if not href.startswith("http") else href
            if full not in found:
                found.append(full)
        if found:
            break
        time.sleep(0.4)
    return found[0] if found else None


def gh_android_zip(assets):
    """Releases 里含 Android 的压缩包（apk 被打进 zip 的常见做法）。"""
    for a in assets:
        n = a["name"]
        if n.lower().endswith((".zip", ".7z")) and ANDROID_HINT.search(n):
            return a
    return None


def nightly_for(repo):
    """P3：找构建 workflow 的 artifact，拼 nightly.link 公开直链。"""
    wf = api(f"/repos/{repo}/actions/workflows")
    if not isinstance(wf, dict):
        return None
    for w in wf.get("workflows", []):
        if not re.search(r"build|apk|android|release|ci", w.get("name", ""), re.I):
            continue
        arts = api(f"/repos/{repo}/actions/artifacts?per_page=20")
        if not isinstance(arts, dict):
            continue
        for a in arts.get("artifacts", []):
            if a.get("expired"):
                continue
            if not ANDROID_HINT.search(a["name"]):
                continue
            path = w.get("path", "").split("/")[-1]
            return {
                "workflow": w.get("name"), "workflow_file": path,
                "artifact": a["name"], "created": a.get("created_at", "")[:10],
                "url": f"https://nightly.link/{repo}/workflows/{path}/main/{a['name']}.zip",
            }
    return None


def main():
    rep = json.load(open(os.path.join(ROOT, "audit-report.json"), encoding="utf-8"))["items"]
    reg = {e["id"]: e for e in json.load(
        open(os.path.join(ROOT, "emulator-registry.json"), encoding="utf-8"))["emulators"]}

    targets = [r for r in rep if "no_apk_anywhere_in_releases" in r["gaps"] and r.get("repo_full")]
    print(f"待探明 {len(targets)} 条", flush=True)

    out = []
    lock = __import__("threading").Lock()

    def one(pair):
        i, r = pair
        e = reg[r["id"]]
        repo = r["repo_full"]
        rec = {"id": r["id"], "name": e.get("name"), "repo": repo,
               "homepage": e.get("homepage") or "", "result": None, "tried": []}

        # P1/P2：再扫一遍 release 资产（含旧版本，latest 可能只挂源码）
        rel = api(f"/repos/{repo}/releases?per_page=10")
        apk_url = None
        if isinstance(rel, list):
            for rr in rel:
                assets = rr.get("assets") or []
                direct = next((a for a in assets if APK_RE.search(a["name"])), None)
                if direct:
                    apk_url = direct["browser_download_url"]
                    rec["result"] = {"kind": "release_apk", "tag": rr["tag_name"],
                                     "name": direct["name"], "url": apk_url,
                                     "size": direct["size"]}
                    break
                z = gh_android_zip(assets)
                if z:
                    rec["result"] = {"kind": "release_zip", "tag": rr["tag_name"],
                                     "name": z["name"], "url": z["browser_download_url"],
                                     "size": z["size"], "note": "zip 内含 APK，需解包"}
                    break

        # P4：官方站点
        if not rec["result"]:
            u = probe_site(e.get("homepage"))
            rec["tried"].append("site")
            if u:
                rec["result"] = {"kind": "official_site", "url": u, "name": u.split("/")[-1]}

        # P3：Actions 构建
        if not rec["result"]:
            n = nightly_for(repo)
            rec["tried"].append("nightly")
            if n:
                rec["result"] = {"kind": "nightly_link", **n}

        # P5：Play 商店
        if not rec["result"]:
            hp = e.get("homepage") or ""
            m = re.search(r"id=([\w.]+)", hp)
            if m:
                rec["result"] = {"kind": "play", "package": m.group(1),
                                 "url": f"https://play.google.com/store/apps/details?id={m.group(1)}"}

        with lock:
            out.append(rec)
            k = rec["result"]["kind"] if rec["result"] else "—"
            print(f"[{len(out):02d}/{len(targets)}] {r['id'][:30]:32s} {k}", flush=True)
            json.dump({"items": out}, open(os.path.join(ROOT, "resolve-report.json"), "w", encoding="utf-8"),
                      ensure_ascii=False, indent=1)

    with ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(one, list(enumerate(targets, 1))))

    json.dump({"items": out}, open(os.path.join(ROOT, "resolve-report.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    from collections import Counter
    print("\n结果分布:", dict(Counter(x["result"]["kind"] if x["result"] else "unresolved" for x in out)))


if __name__ == "__main__":
    import urllib.parse
    main()
