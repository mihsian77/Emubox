#!/usr/bin/env python3
"""给主库补 hosts / method / builds 三个字段。

只做「从已有证据推断」，不新增条目、不改其他字段。
默认 dry-run，只打报告；加 --write 才落盘。

为什么必须拉 release：registry 里的 `releases` 字段只有 tag/name/date/body，
**不含 assets**。而 assets 的文件名后缀是判定「这个项目出不出 Windows 包」
最硬的证据——描述可以吹，包名骗不了人。

证据不写进主库（会让每条记录膨胀且污染字段表），单独落 schema-audit.json。
"""
import argparse
import json
import os
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from infer import infer, builds_from_assets  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN_FILE = "/root/.codebuddy/.secrets/gh-token"

# 人工核定表：仓库已 404/归档，资产拉不到，只能按项目已知事实填。
# 每条都必须有出处，不许凭印象——这是「自动提取」唯一允许的人工兜底口。
HOST_OVERRIDES = {
    # 官方仓库已被 DMCA 删除，平台信息来自项目文档与官方站
    "ryujinx-official": ["windows", "linux", "macos"],
    "yuzu-official": ["windows", "linux"],
    "yuzu-citrus": ["windows", "linux", "android"],
    "torzu-emulator": ["windows", "linux", "macos"],
    "skyline-official": ["android"],
    "strato-emulator": ["android"],
    "ryubing-emulator": ["windows", "linux", "macos"],
    "ryujinx-benji-sc": ["windows", "linux", "macos"],
    "lime3ds-3ds": ["windows", "linux", "macos", "android"],
    "dukestation-ps1": ["windows", "linux", "macos"],
    "dukeps3-android": ["android"],
    "freej2me": ["windows", "linux", "macos"],
    "afeimod-linbox": ["android"],
    "winlatorbrasil-winlator-brasil": ["android"],
    "jaycore-mobox-patched": ["android"],
    "maxjivi05-gamenative-performance": ["android"],
    # 构建通道 / 镜像 / 移植版，仓库无 release 资产
    "ryubing-canary": ["windows", "linux", "macos"],
    "melonx-ios": ["ios"],
    "prospero-eden-encore": ["android", "windows"],
    "mupen64plus-ae-fzurita": ["android"],
    "citra-mirror": ["windows", "linux", "macos", "android"],
}


def token():
    if os.environ.get("GH_TOKEN"):
        return os.environ["GH_TOKEN"].strip()
    with open(TOKEN_FILE) as f:
        return f.read().strip()


def gh(url, tok, retry=2):
    for i in range(retry + 1):
        p = subprocess.run(
            ["curl", "-s", "--max-time", "25",
             "-H", f"Authorization: Bearer {tok}",
             "-H", "Accept: application/vnd.github+json", url],
            capture_output=True, text=True,
        )
        try:
            return json.loads(p.stdout)
        except Exception:
            if i == retry:
                return None
            time.sleep(1.5)


def fetch_assets(e, tok):
    repo = e.get("repo") or ""
    if "/" not in repo:
        return e["id"], [], ""
    d = gh(f"https://api.github.com/repos/{repo}/releases?per_page=3", tok)
    if not isinstance(d, list) or not d:
        return e["id"], [], ""
    rel = d[0]
    return e["id"], rel.get("assets") or [], rel.get("tag_name") or ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args()

    tok = token()
    path = os.path.join(ROOT, "emulator-registry.json")
    data = json.load(open(path, encoding="utf-8"))
    es = data["emulators"]
    todo = [e for e in es if (e.get("repo") or "").count("/") == 1]
    print(f"共 {len(es)} 条，其中 {len(todo)} 条有可用 repo，开始拉 assets…", file=sys.stderr)

    with ThreadPoolExecutor(max_workers=8) as ex:
        results = list(ex.map(lambda e: fetch_assets(e, tok), todo))
    amap = {i: (a, t) for i, a, t in results}

    stat_method, stat_hosts, review, audit = {}, {}, [], {}
    for e in es:
        assets, tag = amap.get(e["id"], ([], ""))
        ev = {
            "name": e.get("name"),
            "description": e.get("description"),
            "category": e.get("category"),
            "topics": e.get("topics") or [],
            "homepage": e.get("homepage"),
            "runtime": e.get("runtime"),
            "release_assets": assets,
        }
        r = infer(ev)
        hosts = r["hosts"]
        src = "asset"
        if not hosts and e["id"] in HOST_OVERRIDES:
            hosts, src = HOST_OVERRIDES[e["id"]], "override"

        e["method"] = r["method"]
        e["hosts"] = hosts
        b = builds_from_assets(ev, tag=tag, default_url=e.get("latest_apk_url") or "")
        if b:
            e["builds"] = b
        e["needs_review"] = not hosts

        audit[e["id"]] = {
            "method": r["method"], "method_evidence": r["_evidence"]["method"],
            "hosts": hosts, "hosts_source": src, "hosts_evidence": r["_evidence"]["hosts"],
            "confidence": r["confidence"], "asset_count": len(assets),
        }

        stat_method[r["method"]] = stat_method.get(r["method"], 0) + 1
        k = "+".join(hosts) or "（空）"
        stat_hosts[k] = stat_hosts.get(k, 0) + 1
        if not hosts:
            review.append(e["id"])

    print("\n=== method 分布 ===")
    for k, v in sorted(stat_method.items(), key=lambda x: -x[1]):
        print(f"  {v:4}  {k}")
    print("\n=== hosts 分布 ===")
    for k, v in sorted(stat_hosts.items(), key=lambda x: -x[1]):
        print(f"  {v:4}  {k}")

    print(f"\n=== 仍需人工确认 {len(review)} 条 ===")
    for i in review:
        print(" ", i)

    if args.write:
        json.dump(data, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
        json.dump(audit, open(os.path.join(ROOT, "schema-audit.json"), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=1)
        print(f"\n已写回 {path}，证据写入 schema-audit.json")
    else:
        print("\n（dry-run，未写盘。加 --write 落盘）")


if __name__ == "__main__":
    main()
