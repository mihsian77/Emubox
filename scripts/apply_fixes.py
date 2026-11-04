#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把审计 / 发布面探测结果写回 emulator-registry.json。

只做「有据可查」的写入：
  - 版本字段用 GitHub 最新非草稿 release 覆盖（tag/date/body/资产）
  - 直链 APK 才写 latest_apk_url；压缩包/夜间构建/商店写入 download_url + download_kind
  - 仓库 404/451 不标 dead（dead 有单独清单约束），写 repo_status + note 说明
  - 新增 distribution 字段，描述该项目的真实分发形态

用法: python3 scripts/apply_fixes.py [--dry]
"""
import json, os, re, sys, time, urllib.error, urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REG = os.path.join(ROOT, "emulator-registry.json")
TOKEN = open(os.path.expanduser("~/.codebuddy/.secrets/gh-token")).read().strip()


def api(path, tries=3):
    for i in range(tries):
        req = urllib.request.Request("https://api.github.com" + path, headers={
            "Authorization": "Bearer " + TOKEN, "Accept": "application/vnd.github+json",
            "User-Agent": "emubox-fix"})
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


def norm_repo(e):
    r = (e.get("repo") or "").strip()
    if r and "/" not in r and e.get("owner"):
        r = f"{e['owner']}/{r}"
    if r and "/" not in r and e.get("repo_url"):
        m = re.search(r"github\.com/([^/]+/[^/?#]+)", e["repo_url"])
        if m:
            r = m.group(1)
    return r


def refresh(e):
    repo = norm_repo(e)
    if not repo or not re.match(r"^[\w.-]+/[\w.-]+$", repo):
        return e["id"], {"skip": "no_repo"}
    meta = api(f"/repos/{repo}")
    if "__err__" in meta:
        return e["id"], {"repo_status": str(meta["__err__"])}

    upd = {}
    if not e.get("owner") and meta.get("owner"):
        upd["owner"] = meta["owner"]["login"]
    if not e.get("repo"):
        upd["repo"] = repo
    if meta.get("description") and not e.get("description"):
        upd["description"] = meta["description"]
    if meta.get("homepage") and not e.get("homepage"):
        upd["homepage"] = meta["homepage"]
    if meta.get("owner") and not e.get("logo_url"):
        upd["logo_url"] = meta["owner"]["avatar_url"]
    upd["stars"] = meta.get("stargazers_count", e.get("stars"))
    upd["forks"] = meta.get("forks_count", e.get("forks"))
    upd["watchers"] = meta.get("subscribers_count", e.get("watchers"))
    upd["pushed_at"] = meta.get("pushed_at", e.get("pushed_at"))
    if meta.get("archived"):
        upd["repo_status"] = "archived"

    rel = api(f"/repos/{repo}/releases?per_page=8")
    if isinstance(rel, list) and rel:
        # 选版本：优先「带 APK 资产」的，其次「非预发布且非垃圾 tag」，最后才退回最新
        def has_apk(r):
            return any(re.search(r"\.(apk|apks)$", a["name"], re.I) for a in r.get("assets") or [])

        def junk(r):
            return bool(re.search(r"playstore|input|issue-?\d+|test\d*|-link$|^latest$",
                                  r["tag_name"], re.I))

        pool = [r for r in rel if not r.get("draft")]
        top = (next((r for r in pool if has_apk(r)), None)
               or next((r for r in pool if not r.get("prerelease") and not junk(r)), None)
               or (pool[0] if pool else None))
        if not top:
            return e["id"], upd
        upd["latest_tag"] = top["tag_name"]
        upd["latest_date"] = (top.get("published_at") or "")[:19] + "Z" if top.get("published_at") else e.get("latest_date", "")
        upd["latest_body"] = (top.get("body") or "")[:1500]
        apk = next((a for a in top.get("assets") or [] if re.search(r"\.(apk|apks)$", a["name"], re.I)), None)
        if apk:
            upd.update({"latest_apk_name": apk["name"], "latest_apk_url": apk["browser_download_url"],
                        "latest_apk_size": apk["size"], "distribution": "release-apk"})
        else:
            z = next((a for a in top.get("assets") or []
                      if a["name"].lower().endswith((".zip", ".7z")) and re.search(r"android|apk", a["name"], re.I)), None)
            if z:
                upd.update({"download_url": z["browser_download_url"], "download_kind": "zip",
                            "distribution": "release-zip"})
    return e["id"], upd


def main():
    d = json.load(open(REG, encoding="utf-8"))
    ems = d["emulators"]
    res = json.load(open(os.path.join(ROOT, "resolve-report.json"), encoding="utf-8"))["items"]
    resmap = {x["id"]: x for x in res}

    fixes, lock = {}, __import__("threading").Lock()

    def work(e):
        try:
            i, u = refresh(e)
        except Exception as ex:
            i, u = e["id"], {"err": type(ex).__name__}
        with lock:
            fixes[i] = u

    with ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(work, ems))

    changed = 0
    for e in ems:
        u = fixes.get(e["id"], {})
        before = json.dumps(e, ensure_ascii=False, sort_keys=True)
        for k, v in u.items():
            if v not in (None, ""):
                e[k] = v
        r = resmap.get(e["id"])
        if r and r.get("result"):
            k = r["result"]["kind"]
            if k == "official_site":
                e.setdefault("download_url", r["result"]["url"])
                e.setdefault("download_kind", "official-site")
                e.setdefault("distribution", "official-site")
            elif k == "nightly_link":
                e.setdefault("download_url", r["result"]["url"])
                e.setdefault("download_kind", "nightly")
                e.setdefault("distribution", "nightly")
            elif k == "play":
                e.setdefault("download_url", r["result"]["url"])
                e.setdefault("download_kind", "play")
                e.setdefault("distribution", "play")
        if json.dumps(e, ensure_ascii=False, sort_keys=True) != before:
            changed += 1

    print(f"改动条目 {changed}/{len(ems)}")
    if "--dry" in sys.argv:
        return
    d["updated_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    json.dump(d, open(REG, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    open(REG, "a").write("\n")


if __name__ == "__main__":
    main()
