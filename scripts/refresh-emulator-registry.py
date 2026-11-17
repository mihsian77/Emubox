#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""模拟器官方 registry 数据补全脚本

emulator-registry.json（官方渠道占位：Termux/PPSSPP/RetroArch/Dolphin/DuckStation/Vita3K）
是静态维护文件，两源排行不含这些项目。本脚本每日用 GitHub API 给每条补全：
- releases 数组（tag/date/body/assets，最新在前）
- latest_tag / latest_date / latest_apk_url / latest_apk_name
- stars / pushed_at（活跃度）
CI 内带 GITHUB_TOKEN 跑；本地无 token 时跳过，保证可重复运行。
"""
import json, os, re, sys, time, urllib.error, urllib.request
from urllib.parse import quote

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import BODY_LIMIT, RELEASE_BODY_LIMIT, clip_body  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(ROOT, "emulator-registry.json")

TOKEN = os.environ.get("GITHUB_TOKEN", "")


class HttpStatusError(Exception):
    """HTTP 非 2xx。code 为状态码，location 为重定向目标（301/302 时有值）。"""

    def __init__(self, code, url, location=""):
        super().__init__(f"HTTP {code} {url}")
        self.code = code
        self.url = url
        self.location = location


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """不跟随重定向：仓库改名会返回 301，我们要读到 Location 才能更新 repo。"""

    def redirect_request(self, *args, **kwargs):
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def gh(url, retries=2, auth=True):
    req = urllib.request.Request(url, headers={
        "User-Agent": "emubox-registry",
        "Accept": "application/vnd.github+json",
    })
    # GitHub 的 token 不能带到 GitLab/Gitea/Gitee：自建 Gitea 收到陌生的
    # Bearer 会直接 401，条目就整轮漏刷。非 GitHub 源一律匿名请求。
    if TOKEN and auth:
        req.add_header("Authorization", f"Bearer {TOKEN}")
    # 网络抖动（IncompleteRead、超时）会让条目整轮漏刷，数据就停在旧版；
    # 404/451 这类确定结果不重试，5xx 与连接异常重试两次。
    for attempt in range(retries + 1):
        try:
            with _OPENER.open(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code < 500 or attempt >= retries:
                raise HttpStatusError(e.code, url, (e.headers or {}).get("Location", "") or "") from None
        except Exception:
            if attempt >= retries:
                raise
        time.sleep(1.5 * (attempt + 1))
    raise HttpStatusError(-1, url)


RELEASES_KEPT = 5


def _norm_releases(rels, platform):
    """GitLab/Gitea(NotABug) 的 release 结构转统一格式（与 GitHub 对齐）"""
    out = []
    for r in rels or []:
        if platform == "gitlab":
            out.append({
                "tag": r.get("tag_name", ""),
                "name": r.get("name", ""),
                "date": r.get("released_at", ""),
                "body": clip_body(r.get("description"), RELEASE_BODY_LIMIT),
                "prerelease": False,
                "assets": [],
            })
        else:  # gitea / notabug / gitee
            out.append({
                "tag": r.get("tag_name", ""),
                "name": r.get("name", ""),
                "date": r.get("published_at") or r.get("created_at") or "",
                "body": clip_body(r.get("body"), RELEASE_BODY_LIMIT),
                "prerelease": bool(r.get("prerelease")),
                "assets": [{
                    "name": a["name"],
                    "size": int(a.get("size") or 0),
                    "url": a.get("browser_download_url", ""),
                } for a in (r.get("assets") or []) if a.get("name")],
            })
    return out


def fetch_meta_and_releases(repo, source="", repo_url=""):
    """拉仓库元数据 + 最多 30 条 releases（按 source 选平台 API），失败返回 (None, None)"""
    try:
        if source == "gitee" and "gitee.com" in (repo_url or ""):
            meta = gh(f"https://gitee.com/api/v5/repos/{repo}", auth=False)
            rels = gh(f"https://gitee.com/api/v5/repos/{repo}/releases?per_page=30", auth=False)
            if isinstance(meta, dict):
                meta = {"stargazers_count": int(meta.get("stargazers_count") or 0),
                        "pushed_at": meta.get("updated_at") or ""}
            return meta, _norm_releases(rels, "gitea")  # Gitee release 结构近 Gitea
        if source == "gitlab" or "gitlab.com" in (repo_url or ""):
            pid = quote(repo, safe="")
            meta = gh(f"https://gitlab.com/api/v4/projects/{pid}", auth=False)
            rels = gh(f"https://gitlab.com/api/v4/projects/{pid}/releases?per_page=30", auth=False)
            if isinstance(meta, dict):
                meta = {"stargazers_count": int(meta.get("star_count") or 0),
                        "pushed_at": meta.get("last_activity_at") or ""}
            return meta, _norm_releases(rels, "gitlab")
        if source in ("gitea", "notabug") and repo_url:
            host = re.sub(r"^https?://([^/]+)/.*", r"\1", repo_url)
            if host == "notabug.org":
                host = "notabug.org"
            base = f"https://{host}/api/v1/repos/{repo}"
            meta = gh(f"{base}", auth=False)
            rels = gh(f"{base}/releases?limit=30", auth=False)
            if isinstance(meta, dict):
                meta = {"stargazers_count": int(meta.get("stars_count") or 0),
                        "pushed_at": meta.get("updated_at") or ""}
            return meta, _norm_releases(rels, "gitea")
        meta = gh(f"https://api.github.com/repos/{repo}")
        rels = gh(f"https://api.github.com/repos/{repo}/releases?per_page=30")
    except HttpStatusError:
        # 状态码交给 main 判断（404/451 → 标记失效，301 → 跟随改名）
        raise
    except Exception as e:
        print(f"  跳过 {repo}: {e}")
        return None, None
    out = []
    for r in rels:
        assets = [{
            "name": a["name"],
            "size": int(a.get("size") or 0),
            "url": a.get("browser_download_url", ""),
        } for a in (r.get("assets") or []) if a.get("name")]
        out.append({
            "tag": r.get("tag_name", ""),
            "name": r.get("name", ""),
            "date": r.get("published_at", ""),
            "body": clip_body(r.get("body"), RELEASE_BODY_LIMIT),
            "prerelease": bool(r.get("prerelease")),
            "assets": assets,
        })
    return meta, out


DEAD_REASON_404 = "仓库已删除或转为私有（GitHub 404）"
DEAD_REASON_451 = "源站因法律要求不可用（HTTP 451）"


def mark_dead(e, reason):
    """把条目标成失效：禁用下载/跳转，但保留在库里当历史索引。

    dead_auto 标记这是脚本判定的，仓库恢复后能自动摘掉；人工标注的
    （如「上游停更」这类需要判断的）不带该标记，不会被脚本覆盖。
    """
    if e.get("dead") and not e.get("dead_auto"):
        return False                       # 人工判定，尊重原标注
    e["dead"] = True
    e["dead_auto"] = True
    e["dead_reason"] = reason
    e["status"] = "dead"
    # 清空下载与跳转：留着死链会让用户点了必然失败
    e["latest_apk_url"] = ""
    e["latest_apk_name"] = ""
    e["latest_apk_size"] = 0
    e["download_url"] = ""
    e["note"] = reason
    return True


def revive(e):
    """上次脚本判死、这次抓通了：摘掉自动标记，恢复正常。"""
    if not e.get("dead_auto"):
        return False
    e.pop("dead", None)
    e.pop("dead_auto", None)
    if e.get("status") == "dead":
        e["status"] = "active"
    if e.get("dead_reason") in (DEAD_REASON_404, DEAD_REASON_451) or e.get("note") in (DEAD_REASON_404, DEAD_REASON_451):
        e["dead_reason"] = ""
        e["note"] = ""
    return True


def main():
    # --ids a,b,c 只刷指定条目：排障时补刷个别漏掉的仓库，不用整轮跑二十分钟
    only = None
    if "--ids" in sys.argv:
        only = set(sys.argv[sys.argv.index("--ids") + 1].split(","))
    d = json.load(open(REGISTRY))
    changed = False
    stats = {"dead404": 0, "dead451": 0, "renamed": 0, "revived": 0, "failed": 0}
    for e in d.get("emulators", []):
        repo = e.get("repo", "")
        if only is not None and e.get("id") not in only:
            continue
        if not repo or "/" not in repo:
            print(f"  跳过（无完整 repo 路径）: {e.get('id')}")
            continue
        try:
            meta, rels = fetch_meta_and_releases(repo, e.get("source", ""), e.get("repo_url", ""))
        except HttpStatusError as err:
            if err.code == 404:
                if mark_dead(e, DEAD_REASON_404):
                    stats["dead404"] += 1
                    print(f"  失效(404): {e.get('id')} ← {repo}")
                changed = True
            elif err.code == 451:
                if mark_dead(e, DEAD_REASON_451):
                    stats["dead451"] += 1
                    print(f"  失效(451): {e.get('id')} ← {repo}")
                changed = True
            elif err.code in (301, 302, 307, 308):
                # GitHub API 的改名跳转给的是数字 ID（api.github.com/repositories/<id>），
                # 网页端才是 owner/repo，两种都处理。
                loc = err.location or ""
                new_repo = ""
                if "api.github.com/repositories/" in loc:
                    try:
                        new_repo = (gh(loc) or {}).get("full_name") or ""
                    except Exception:
                        new_repo = ""
                elif "github.com/" in loc:
                    new_repo = loc.split("github.com/")[-1].strip("/")
                if new_repo and new_repo != repo:
                    print(f"  仓库改名: {repo} → {new_repo}")
                    e["repo"] = new_repo
                    e["repo_url"] = "https://github.com/" + new_repo
                    e["owner"] = new_repo.split("/")[0]
                    stats["renamed"] += 1
                    changed = True
                else:
                    stats["failed"] += 1
                    print(f"  跳过 {repo}: {err}")
            else:
                stats["failed"] += 1
                print(f"  跳过 {repo}: {err}")
            continue
        if rels is None:
            stats["failed"] += 1
            continue
        if revive(e):
            stats["revived"] += 1
            print(f"  恢复: {e.get('id')}（仓库重新可达）")
            changed = True
        e["stars"] = int(meta.get("stargazers_count") or 0)
        e["pushed_at"] = meta.get("pushed_at") or ""
        # 只存最近 5 个版本：详情页最多展开看几个，存 30 个让 registry 从 9MB 涨到 30MB，
        # 而 91% 的体积都在 releases 上（每个 release 带完整 changelog 与 assets 列表）。
        e["releases"] = rels[:RELEASES_KEPT]
        if rels:
            # 最新一条就是「当前版本」，预发布也算。
            # 旧逻辑优先挑「非预发布且带 changelog」的那条，于是只发 nightly/beta 的项目
            # 永远停在旧正式版，发过预发布的项目在「按日期排序」里也会被排到后面。
            # GitHub 的 /releases 按 created_at 排，改过草稿的旧 tag 会插到前面，
            # 这里按 published_at 再排一次，保证「最新发布的」真的是第一条。
            rels = sorted(rels, key=lambda r: r.get("date") or "", reverse=True)
            e["releases"] = rels[:RELEASES_KEPT]
            r0 = rels[0]
            e["latest_tag"] = r0["tag"]
            e["latest_date"] = r0["date"]
            e["latest_body"] = clip_body(r0.get("body"), BODY_LIMIT)
            e["channel"] = "prerelease" if r0.get("prerelease") else "stable"
            # 下载包仍优先取当前版本的 asset；当前版本只有源码包时，回退到最近一个带 APK 的版本，
            # 避免发个 source-only 的 nighty 就把「下载」按钮抹掉。
            target = r0 if any(a.get("name", "").lower().endswith(".apk") for a in (r0.get("assets") or [])) \
                else next((r for r in rels if any(a.get("name", "").lower().endswith(".apk") for a in (r.get("assets") or []))), None)
            if target:
                apks = [a for a in (target.get("assets") or []) if a.get("name", "").lower().endswith(".apk")]
                # 优先 release 版（debug/测试版兜底），避免下载按钮给 debug 包
                apks.sort(key=lambda a: 0 if "release" in a.get("name", "").lower() else (1 if "debug" not in a.get("name", "").lower() else 2))
                a0 = apks[0]
                e["latest_apk_name"] = a0["name"]
                e["latest_apk_url"] = a0["url"]
                e["latest_apk_size"] = int(a0.get("size") or 0)
                # 记下这个包属于哪一版：回退到旧版时详情页要照实说明，
                # 否则用户以为装的是最新版。跟着 URL 一起写，两者永远同源。
                e["latest_apk_tag"] = target.get("tag") or ""
            elif not e.get("latest_apk_url"):
                e["latest_apk_name"] = ""
                e["latest_apk_url"] = ""
                e["latest_apk_tag"] = ""
        changed = True
    if changed:
        d["updated_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        # _section_evidence / _family_evidence 是脚本判断分类时的依据，属于中间产物，
        # 落到仓库里既占体积又容易被误读成数据字段，写出前剔除。
        for e in d.get("emulators", []):
            e.pop("_section_evidence", None)
            e.pop("_family_evidence", None)
        json.dump(d, open(REGISTRY, "w"), ensure_ascii=False, indent=2)
        print(f"已更新 {len(d['emulators'])} 条，releases 总量: "
              f"{sum(len(e.get('releases') or []) for e in d['emulators'])}")
        print(f"失效标记 404={stats['dead404']} 451={stats['dead451']}；"
              f"改名跟随={stats['renamed']}；恢复={stats['revived']}；请求失败={stats['failed']}")
    else:
        print("无更新")


if __name__ == "__main__":
    main()
