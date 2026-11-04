#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""项目库缺口审计：按「分发形态」判断每条该有什么、实际有什么。

不假设所有项目都发 APK：
  standalone  Android 应用，应有 APK 直链
  libretro    核心库，随 RetroArch 分发，本身无 APK（不算缺口）
  pc-port     Winlator/PC 侧工具，产物是 zip/exe/wcp
  component   驱动/组件包，产物是 zip/wcp/tzst/adpkg
  source-only 只发源码或已归档，无构建物

输出 audit-report.json，供人工复核后再改 registry。
用法: python3 scripts/audit_registry.py [--limit N]
"""
import json, os, re, sys, time, threading, urllib.error, urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN = open(os.path.expanduser("~/.codebuddy/.secrets/gh-token")).read().strip()

APK_RE = re.compile(r"\.(apk|apks|xapk)$", re.I)
PKG_RE = re.compile(r"\.(zip|7z|wcp|tzst|adpkg|tar\.gz|tgz)$", re.I)

# 明确属于 libretro 核心 / 无独立客户端的项目（按 repo 或 id 命中）
LIBRETRO_HINT = ("libretro", "mame2003", "fbneo", "snes9x", "virtualjaguar", "handy",
                 "freej2me", "raport", "prosystem", "stella", "mednafen")
COMPONENT_HINT = ("driver", "turnip", "vkd3d", "box64", "wcp", "component", "dxvk")


def api(path, tries=3):
    url = "https://api.github.com" + path
    for i in range(tries):
        req = urllib.request.Request(url, headers={
            "Authorization": "Bearer " + TOKEN,
            "Accept": "application/vnd.github+json",
            "User-Agent": "emubox-audit",
        })
        try:
            with urllib.request.urlopen(req, timeout=25) as r:
                return json.loads(r.read().decode()), r.headers.get("X-RateLimit-Remaining")
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return {"__err__": "404"}, None
            if e.code in (403, 429):
                time.sleep(8 * (i + 1))
                continue
            return {"__err__": str(e.code)}, None
        except Exception:
            time.sleep(3)
    return {"__err__": "fail"}, None


def classify(e):
    blob = (e.get("id", "") + " " + e.get("repo", "") + " " + (e.get("category") or "")).lower()
    pkg = (e.get("latest_apk_name") or "").lower()
    if any(h in blob for h in COMPONENT_HINT) or pkg.endswith((".wcp", ".tzst")):
        return "component"
    if any(h in blob for h in LIBRETRO_HINT):
        return "libretro"
    if e.get("source") in ("official-play",):
        return "play-only"
    if e.get("runtime") in ("desktop", "libretro"):
        return "desktop"
    if e.get("source") in ("gitea", "gitee", "gitlab"):
        return "forge-release"
    if e.get("latest_apk_name", "").lower().endswith((".zip", ".7z", ".exe")):
        return "pc-port"
    return "standalone"


def forgejo_api(base, path):
    """Forgejo / Gitea / Gitee / GitLab 通用 JSON 探测。"""
    url = base.rstrip("/") + path
    req = urllib.request.Request(url, headers={"User-Agent": "emubox-audit",
                                               "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return {"__err__": str(e.code)}
    except Exception:
        return {"__err__": "fail"}


def _forge_paths(e, repo_full):
    """返回 (仓库API地址, releaseAPI地址, 页面地址)。路径以 repo_url 为准，避免 repo 字段与 url 不一致。"""
    url = (e.get("repo_url") or "").rstrip("/")
    m = re.match(r"^(https?://[^/]+)/(.+?)(?:\.git)?$", url)
    if not m:
        return None, None, url
    host, path = m.group(1), m.group(2)
    if path.startswith("api/"):
        path = "/".join(path.split("/")[3:])
    src = e.get("source")
    if src == "gitee":
        return (f"https://gitee.com/api/v5/repos/{path}",
                f"https://gitee.com/api/v5/repos/{path}/releases?per_page=20", url)
    if src == "gitlab":
        enc = path.replace("/", "%2F")
        return (f"https://gitlab.com/api/v4/projects/{enc}",
                f"https://gitlab.com/api/v4/projects/{enc}/releases", url)
    return (f"{host}/api/v1/repos/{path}",
            f"{host}/api/v1/repos/{path}/releases?limit=20", url)


def audit_forge(e, repo_full):
    """非 GitHub 源（自建 Forgejo / Gitee / GitLab）的一手产物探测。
    先探仓库本体，再探 release；release 端点 404 视为「无 release」而不是「仓库不存在」。"""
    out = {"id": e["id"], "name": e.get("name"), "form": classify(e),
           "repo": repo_full, "gaps": [], "probe": {}}
    repo_api, rel_api, page = _forge_paths(e, repo_full)
    if not repo_api:
        out["gaps"].append("unresolvable")
        return out

    meta = forgejo_api(repo_api, "")
    if isinstance(meta, dict) and meta.get("__err__"):
        code = meta["__err__"]
        out["gaps"].append("repo_" + code)
        return out

    out["probe"].update({
        "archived": meta.get("archived"),
        "pushed_at": meta.get("updated_at") or meta.get("last_activity_at"),
        "stars": meta.get("stars_count") if meta.get("stars_count") is not None else meta.get("star_count"),
        "default_branch": meta.get("default_branch"),
        "homepage": meta.get("website") or meta.get("web_url"),
        "open_issues": meta.get("open_issues_count"),
    })
    if meta.get("archived"):
        out["gaps"].append("archived")
    if not e.get("description") and not (meta.get("description") or "").strip():
        out["gaps"].append("no_description")
    if not e.get("logo_url"):
        out["gaps"].append("no_logo")

    rel = forgejo_api(rel_api, "")
    if isinstance(rel, dict):
        # release 端点不可用（功能关闭 / 无权限）按「无 release」处理
        rel = []
    if not isinstance(rel, list):
        rel = []

    apks, pkgs = [], []
    for r in rel:
        for a in r.get("assets") or []:
            # GitLab 的 assets.links 是字符串数组，GitHub/Gitea 是对象
            if isinstance(a, str):
                name = a.rstrip("/").split("/")[-1].split("?")[0]
                item = {"tag": r.get("tag_name"), "date": (r.get("published_at") or "")[:10],
                        "name": name, "size": 0, "url": a, "dl": 0}
            else:
                item = {"tag": r.get("tag_name"), "date": (r.get("published_at") or "")[:10],
                        "name": a.get("name"), "size": a.get("size"),
                        "url": a.get("browser_download_url"), "dl": a.get("download_count", 0)}
            if APK_RE.search(item.get("name") or ""):
                apks.append(item)
            elif PKG_RE.search(item.get("name") or ""):
                pkgs.append(item)
    out["probe"]["releases"] = len(rel)
    # 按发布时间取最新，避免 GitHub 返回顺序把旧 nightly 当成最新
    latest = max(rel, key=lambda r: r.get("published_at") or "") if rel else None
    out["probe"]["latest_release_tag"] = latest.get("tag_name") if latest else None
    out["probe"]["apk_assets"] = len(apks)
    out["probe"]["pkg_assets"] = len(pkgs)
    if rel:
        out["probe"]["apk_sample"] = apks[0] if apks else None
        out["probe"]["pkg_sample"] = pkgs[0] if pkgs else None
    if not rel:
        out["gaps"].append("no_release")
    cur = (e.get("latest_tag") or "").strip()
    real = out["probe"]["latest_release_tag"]
    ver_like = lambda t: bool(re.search(r"\d+\.\d+", t or ""))
    if real and cur and cur != real and ver_like(cur) and ver_like(real):
        out["gaps"].append(f"tag_stale:{cur}->{real}")
    if not cur and real and ver_like(real):
        out["gaps"].append(f"tag_missing:{real}")
    if out["form"] == "standalone" and not e.get("latest_apk_url") and not apks:
        out["gaps"].append("no_apk_anywhere_in_releases")
    if out["form"] == "standalone" and apks and not e.get("latest_apk_url"):
        out["gaps"].append("apk_recoverable")
    return out


def audit(e):
    out = {"id": e["id"], "name": e.get("name"), "form": classify(e),
           "repo": e.get("repo") or "", "gaps": [], "probe": {}}
    repo = (e.get("repo") or "").strip()
    # repo 字段常只有仓库名（缺 owner），先补全再判 404
    if repo and "/" not in repo and e.get("owner"):
        repo = f"{e['owner']}/{repo}"
    if repo and "/" not in repo and e.get("repo_url"):
        m = re.search(r"github\.com/([^/]+/[^/?#]+)", e["repo_url"])
        if m:
            repo = m.group(1)
    # 自建 Forgejo / Gitee：owner + repo 字段缺失时，从实例路径反推
    if not repo and e.get("repo_url") and e.get("source") in ("gitea", "gitee", "gitlab"):
        m = re.search(r"://[^/]+/(?:api/v\d+/repos/)?([^/?#]+/[^/?#]+)", e["repo_url"])
        if m:
            repo = m.group(1)
            out_probe_repo_from_url = repo
    out["repo_full"] = repo
    if not repo:
        out["gaps"].append("no_repo_field")
        if e.get("repo_url"):
            m = re.search(r"github\.com/([^/]+/[^/?#]+)", e["repo_url"])
            if m:
                repo = m.group(1)
                out["probe"]["repo_from_url"] = repo
        if not repo:
            out["gaps"].append("unresolvable")
            return out

    # 非 GitHub 源走各自的实例 API
    if e.get("source") in ("gitea", "gitee", "gitlab") and "github.com" not in (e.get("repo_url") or ""):
        return audit_forge(e, repo)
    meta, _ = api(f"/repos/{repo}")
    if "__err__" in meta:
        out["gaps"].append("repo_" + meta["__err__"])
        return out

    out["probe"].update({
        "archived": meta.get("archived"),
        "pushed_at": meta.get("pushed_at"),
        "stars": meta.get("stargazers_count"),
        "default_branch": meta.get("default_branch"),
        "homepage": meta.get("homepage"),
        "has_discussions": meta.get("has_discussions"),
        "open_issues": meta.get("open_issues_count"),
    })
    if meta.get("archived"):
        out["gaps"].append("archived")
    if not e.get("description") and not meta.get("description"):
        out["gaps"].append("no_description")
    if not e.get("logo_url"):
        out["gaps"].append("no_logo")

    # 版本真实值
    rel, _ = api(f"/repos/{repo}/releases?per_page=8")
    releases = rel if isinstance(rel, list) else []
    apks, pkgs = [], []
    for r in releases:
        for a in r.get("assets") or []:
            item = {"tag": r["tag_name"], "date": (r.get("published_at") or "")[:10],
                    "name": a["name"], "size": a["size"], "url": a["browser_download_url"],
                    "dl": a.get("download_count", 0), "pre": r.get("prerelease")}
            if APK_RE.search(a["name"]):
                apks.append(item)
            elif PKG_RE.search(a["name"]):
                pkgs.append(item)

    out["probe"]["releases"] = len(releases)
    out["probe"]["latest_release_tag"] = releases[0]["tag_name"] if releases else None
    out["probe"]["apk_assets"] = len(apks)
    out["probe"]["pkg_assets"] = len(pkgs)
    if releases:
        out["probe"]["apk_sample"] = apks[0] if apks else None
        out["probe"]["pkg_sample"] = pkgs[0] if pkgs else None

    # tag 是否过期
    cur = (e.get("latest_tag") or "").strip()
    real = out["probe"]["latest_release_tag"]
    ver_like = lambda t: bool(re.search(r"\d+\.\d+", t or ""))
    if real and cur and cur != real and ver_like(cur) and ver_like(real):
        out["gaps"].append(f"tag_stale:{cur}->{real}")
    if not cur and real and ver_like(real):
        out["gaps"].append(f"tag_missing:{real}")
    if not releases:
        out["gaps"].append("no_release")

    # 该有 APK 却没有
    if out["form"] == "standalone" and not e.get("latest_apk_url") and not apks:
        out["gaps"].append("no_apk_anywhere_in_releases")

    # 只在 release 里有 APK，registry 却没记 → 可直接补
    if out["form"] == "standalone" and apks and not e.get("latest_apk_url"):
        out["gaps"].append("apk_recoverable")

    # 仓库根目录有没有 apk / nightly 工作流
    if out["form"] == "standalone" and not apks:
        cont, _ = api(f"/repos/{repo}/contents/?ref={meta.get('default_branch', 'main')}")
        if isinstance(cont, list):
            roots = [c["name"] for c in cont if c["type"] == "file" and APK_RE.search(c["name"])]
            if roots:
                out["probe"]["root_apk"] = roots
                out["gaps"].append("apk_in_repo_root")
        wf, _ = api(f"/repos/{repo}/actions/workflows")
        if isinstance(wf, dict):
            names = [w["name"] for w in wf.get("workflows", [])]
            out["probe"]["workflows"] = names[:8]
            if any(re.search(r"build|apk|release|nightly", n, re.I) for n in names):
                out["gaps"].append("has_build_workflow")

    if e.get("homepage") in ("", None) and meta.get("homepage"):
        out["probe"]["homepage_from_meta"] = meta["homepage"]
        out["gaps"].append("homepage_recoverable")
    return out


def main():
    limit = None
    if "--limit" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--limit") + 1])
    reg = json.load(open(os.path.join(ROOT, "emulator-registry.json"), encoding="utf-8"))
    ems = reg["emulators"][:limit] if limit else reg["emulators"]

    results, done = [], [0]
    lock = threading.Lock()

    def work(e):
        try:
            r = audit(e)
        except Exception as ex:
            r = {"id": e.get("id"), "gaps": ["audit_error:" + type(ex).__name__], "probe": {}}
        with lock:
            done[0] += 1
            if done[0] % 25 == 0:
                print(f"  ...{done[0]}/{len(ems)}", flush=True)
        return r

    with ThreadPoolExecutor(max_workers=5) as pool:
        results = list(pool.map(work, ems))

    json.dump({"total": len(ems), "items": results},
              open(os.path.join(ROOT, "audit-report.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)

    # 汇总
    from collections import Counter
    forms = Counter(r.get("form") for r in results)
    gaps = Counter(g.split(":")[0] for r in results for g in r["gaps"])
    print("\n分发形态:", dict(forms))
    print("缺口类型:")
    for k, v in gaps.most_common():
        print(f"  {k:34s} {v}")


if __name__ == "__main__":
    main()
