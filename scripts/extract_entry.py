#!/usr/bin/env python3
"""单个仓库 → 一条完整 registry 记录（自动提取，不写库）。

这个脚本的存在意义：以前收录一个项目要人肉翻 README、数资产、猜分类，
一条 10 分钟。现在跑一行命令，把 GitHub 上能拿到的信息全提出来，
人只做「看一眼、改不对的」，不做「从零填空」。

抓取范围（凡是 API 有的全抓，存进 _raw 供规则升级后重放）：
  repo 元数据 / 最新 release + 全部资产 / README / 顶层文件树

输出：
  --json   一条可直接 merge 进 emulator-registry.json 的记录
  --md     给人看的判定说明（含每步证据）

用法：
  python3 scripts/extract_entry.py owner/repo
  python3 scripts/extract_entry.py owner/repo --json --out /tmp/x.json
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from infer import infer, infer_guests, builds_from_assets  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN_FILE = "/root/.codebuddy/.secrets/gh-token"

# guest → category 的中文/英文命名，沿用库里现有写法
CATEGORY_NAME = {
    "switch": "Nintendo Switch Emulator", "xbox360": "Microsoft Xbox 360",
    "xbox": "Xbox", "ps5": "Emulator PS5", "ps4": "Emulator PS4",
    "ps3": "Emulator PS3", "ps2": "Emulator PS2", "ps1": "PlayStation 1 Emulator",
    "psvita": "PSVITA", "psp": "PSP", "3ds": "Nintendo 3DS", "nds": "Nintendo DS Emulator",
    "gamecube": "Nintendo GameCube / Wii", "wii": "Nintendo GameCube / Wii",
    "n64": "Nintendo 64 Emulator", "gba": "Game Boy Advance Emulator",
    "gbc": "Game Boy Color Emulator", "snes": "Super Nintendo Emulator",
    "dreamcast": "Sega Dreamcast", "saturn": "Sega Saturn",
    "mega-drive": "Sega Mega Drive", "arcade": "Arcade Emulator",
    "windows-pc": "Winlator", "visual-novel": "Visual Novel Runtime",
    "java-me": "Java Emulator", "dos": "DOS Emulator", "android-app": "Android App Runner",
    "multi": "All In One",
}


def token():
    if os.environ.get("GH_TOKEN"):
        return os.environ["GH_TOKEN"].strip()
    with open(TOKEN_FILE) as f:
        return f.read().strip()


def gh(url, tok, retry=2):
    for i in range(retry + 1):
        p = subprocess.run(
            ["curl", "-s", "--max-time", "30",
             "-H", f"Authorization: Bearer {tok}",
             "-H", "Accept: application/vnd.github+json", url],
            capture_output=True, text=True,
        )
        try:
            return json.loads(p.stdout)
        except Exception:
            if i == retry:
                return None
            time.sleep(2)


def fetch_all(repo, tok):
    """把能拿的全拿回来。一个请求失败不影响其他。"""
    meta = gh(f"https://api.github.com/repos/{repo}", tok) or {}
    rels = gh(f"https://api.github.com/repos/{repo}/releases?per_page=5", tok) or []
    rd = gh(f"https://api.github.com/repos/{repo}/readme", tok) or {}
    contents = gh(f"https://api.github.com/repos/{repo}/contents/", tok) or []
    readme = ""
    if isinstance(rd, dict) and rd.get("content"):
        try:
            readme = base64.b64decode(rd["content"]).decode("utf-8", "ignore")
        except Exception:
            readme = ""
    top = [c["name"] for c in contents] if isinstance(contents, list) else []
    return meta, rels if isinstance(rels, list) else [], readme, top


def make_id(repo):
    s = repo.lower().replace("/", "-")
    return re.sub(r"[^a-z0-9\-]+", "-", s).strip("-")


def pick_name(meta, rels, repo):
    """优先用 release 标题，其次 repo 名。'Xyz v1.2.3' 这种尾巴去掉。"""
    if rels:
        n = (rels[0].get("name") or "").strip()
        n = re.sub(r"\s*[vV]?\d+(\.\d+)+.*$", "", n).strip()
        if n and len(n) <= 40:
            return n
    return (repo.split("/")[-1]).replace("-", " ").replace("_", " ").strip()


def status_of(meta):
    if meta.get("archived"):
        return "archived"
    from datetime import datetime, timezone
    p = meta.get("pushed_at") or ""
    if not p:
        return "unknown"
    try:
        dt = datetime.strptime(p, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except Exception:
        return "unknown"
    days = (datetime.now(timezone.utc) - dt).days
    if days <= 60:
        return "active"
    if days <= 365:
        return "stale"
    return "dead"


def find_site(meta, readme):
    """官网：先用 homepage，没有再扫 README 里的第一条非 GitHub 外链。"""
    hp = (meta.get("homepage") or "").strip()
    if hp and "github.com" not in hp:
        return hp
    for m in re.finditer(r"https?://([a-z0-9.\-]+\.[a-z]{2,})[^\s)\"'<>]*", readme[:6000], re.I):
        host = m.group(1).lower()
        if any(x in host for x in ("github.com", "githubusercontent", "shields.io",
                                   "img.shields", "discord", "twitter", "youtube",
                                   "t.me", "ko-fi", "patreon", "badge")):
            continue
        return m.group(0).rstrip(".,)")
    return ""


def extract(repo, tok):
    meta, rels, readme, top = fetch_all(repo, tok)
    if not meta.get("full_name"):
        return None, {"error": meta.get("message") or "仓库不可访问"}

    latest = rels[0] if rels else {}
    assets = latest.get("assets") or []
    ev = {
        "name": meta.get("name"),
        "description": meta.get("description"),
        "category": "",           # 还没有，靠 guest 反推
        "topics": meta.get("topics") or [],
        "homepage": meta.get("homepage"),
        "runtime": "",
        "release_assets": assets,
        "readme": readme[:8000],
        "top_files": top,
    }
    # README 也参与判定：很多项目的 description 是空的，信息全在 README
    ev["description"] = ((meta.get("description") or "") + " " + readme[:1500]).strip()

    guests, g_evi = infer_guests(ev)
    method = infer(ev)
    hosts = method["hosts"]
    # 只出了 APK 的，hosts 补 android（有些资产命名不带 .apk 后缀）
    if not hosts and re.search(r"\.apk\b", readme):
        hosts = ["android"]

    # kind：描述/名字里写明「安装器/管理器/工具/前端」的，不是模拟器本体
    blob = ((meta.get("description") or "") + " " + meta.get("name", "") + " "
            + " ".join(meta.get("topics") or [])).lower()
    # 「聚合启动器 + 内置多套引擎」是运行环境（Tyranor 这类），不是纯管理器
    if re.search(r"多引擎|内置.{0,8}引擎|multi-?engine|bundled\s+engine|多\s*runtime|multi-?runtime", blob):
        kind = "engine"
    elif re.search(r"installer|manager|管理|安装|tool|工具|launcher|启动器|frontend|前端|dashboard", blob):
        kind = "frontend" if re.search(r"manager|launcher|前端|dashboard|管理", blob) else "tool"
    else:
        kind = "emulator"

    platforms = guests or (["multi"] if len(hosts) > 1 else [])
    # kind=engine 的项目，guest 就是它承载的内容。Tyranor 描述里提到
    # "PSP / Switch 外置模拟器跳转"，被规则当成了 psp/switch 模拟器——
    # 那是它调用别的模拟器，不是它自己模拟。收敛掉。
    if kind == "engine" and "visual-novel" in platforms:
        platforms = ["visual-novel"]
    category = CATEGORY_NAME.get(platforms[0], "") if platforms else ""

    build = builds_from_assets(ev, tag=latest.get("tag_name") or "",
                               default_url="")
    apk = next((b for b in build if b["host"] == "android"), None)

    rec = {
        "id": make_id(meta["full_name"]),
        "name": pick_name(meta, rels, repo),
        "category": category,
        "description": (meta.get("description") or "").strip(),
        "owner": meta["owner"]["login"],
        "repo": meta["full_name"],
        "homepage": find_site(meta, readme) or (meta.get("homepage") or ""),
        "repo_url": meta["html_url"],
        "official": True,
        "channel": "prerelease" if latest.get("prerelease") else "stable",
        "latest_tag": latest.get("tag_name") or "",
        "latest_date": latest.get("published_at") or "",
        "latest_body": (latest.get("body") or "")[:2000],
        "latest_apk_name": apk["name"] if apk else "",
        "latest_apk_url": apk["url"] if apk else "",
        "latest_apk_size": apk["size"] if apk else 0,
        "pushed_at": meta.get("pushed_at") or "",
        "stars": meta.get("stargazers_count") or 0,
        "forks": meta.get("forks_count") or 0,
        "watchers": meta.get("watchers_count") or 0,
        "downloads": sum(a.get("download_count") or 0 for a in assets),
        "logo_url": (meta["owner"].get("avatar_url") or ""),
        "releases": [{"tag": r.get("tag_name"), "name": r.get("name"),
                      "date": r.get("published_at"), "body": (r.get("body") or "")[:800]}
                     for r in rels[:5]],
        "source": "github",
        "note": "",
        "kind": kind,
        "runtime": "android-native" if hosts == ["android"] else "desktop",
        "platforms": platforms,
        "status": status_of(meta),
        "hosts": hosts,
        "method": ("engine" if kind == "engine"
                   else None if kind in ("frontend", "tool")
                   else method["method"]),
        "builds": build,
        "license": ((meta.get("license") or {}).get("spdx_id")),
        "topics": meta.get("topics") or [],
        "needs_review": method["needs_review"],
    }

    trace = {
        "repo": meta["full_name"],
        "repository": {
            "stars": meta.get("stargazers_count"), "forks": meta.get("forks_count"),
            "pushed_at": meta.get("pushed_at"), "archived": meta.get("archived"),
            "license": (meta.get("license") or {}).get("spdx_id"),
            "topics": meta.get("topics") or [], "language": meta.get("language"),
        },
        "release": {
            "tag": latest.get("tag_name"), "date": latest.get("published_at"),
            "assets": [{"name": a["name"], "size": a["size"],
                        "dl": a.get("download_count")} for a in assets],
        },
        "top_files": top[:25],
        "guest": {"value": guests, "evidence": g_evi},
        "method": {"value": None, "evidence": method["_evidence"]["method"]},
        "hosts": {"value": hosts, "evidence": method["_evidence"]["hosts"]},
        "confidence": method["confidence"],
        "needs_review": method["needs_review"],
    }
    trace["method"]["value"] = rec["method"]
    trace["kind"] = rec["kind"]
    return rec, trace


def to_md(rec, tr):
    L = []
    L.append(f"## {rec['name']}  `{rec['id']}`\n")
    if tr.get("error"):
        L.append(f"**抓取失败**：{tr['error']}")
        return "\n".join(L)
    r = tr["repository"]
    L.append(f"> {rec['description'] or '（无描述）'}\n")
    L.append("| 项 | 值 |")
    L.append("|---|---|")
    L.append(f"| 仓库 | {rec['repo']} |")
    L.append(f"| ★ / fork | {r['stars']} / {r['forks']} |")
    L.append(f"| 最近推送 | {r['pushed_at']} |")
    L.append(f"| 许可 | {r['license']} |")
    L.append(f"| 最新版 | {tr['release']['tag']}（{tr['release']['date']}） |")
    L.append(f"| 判定状态 | {rec['status']} |")
    L.append(f"| 玩什么 | {', '.join(tr['guest']['value']) or '**未判定**'} |")
    L.append(f"| 怎么跑 | {tr['method']['value']} ← {tr['method']['evidence']} |")
    L.append(f"| 跑在哪 | {', '.join(tr['hosts']['value']) or '**未判定**'} |")
    L.append(f"| 置信度 | {tr['confidence']}{'　**需人工核**' if tr['needs_review'] else ''} |")
    if tr["release"]["assets"]:
        L.append("\n**资产**\n")
        L.append("| 文件 | 平台 | 大小 | 下载 |")
        L.append("|---|---|--:|--:|")
        for b in rec["builds"]:
            L.append(f"| {b['name']} | {b['host']} | {b['size']/1048576:.1f} MB | {b['downloads']} |")
    if tr["top_files"]:
        L.append(f"\n**顶层文件**：{', '.join(tr['top_files'][:18])}")
    L.append(f"\n**证据**：guest={tr['guest']['evidence']}；hosts={tr['hosts']['evidence']}")
    return "\n".join(L)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("repos", nargs="+")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    tok = token()
    recs = []
    for repo in args.repos:
        rec, tr = extract(repo, tok)
        if rec is None:
            print(to_md(None, tr))
            continue
        recs.append(rec)
        if not args.json:
            print(to_md(rec, tr))
            print()

    if args.json:
        payload = recs[0] if len(recs) == 1 else recs
        txt = json.dumps(payload, ensure_ascii=False, indent=2)
        if args.out:
            with open(args.out, "w", encoding="utf-8") as f:
                f.write(txt)
            print(f"写入 {args.out}", file=sys.stderr)
        else:
            print(txt)


if __name__ == "__main__":
    main()
