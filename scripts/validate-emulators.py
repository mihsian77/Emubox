#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""模拟器注册表自动验证：来源标记完整性、dead 一致性、下载直链合法性。
输出逐条问题 + 通过率，供 CI 接入与提交前自检。"""
import json
import re
import sys
import urllib.request

REG = "emulator-registry.json"
DEAD_SOURCES = {"yuzu-official", "yuzu-citrus", "yuzu-plus", "suyu-emulator",
                "skyline-official", "skyline-pine", "skyline-tagline",
                "skyline-enhanced-infinity", "citron-emulator",
                "strato-emulator", "ryujinx-official"}
VALID_SOURCES = {"github", "gitea", "gitlab", "notabug",
                 "official-web", "official-play", "community-archive"}
BAD_EXT = {".json", ".md", ".txt", ".whp", ".whpx"}


def head_check(url, timeout=12):
    try:
        req = urllib.request.Request(url, method="HEAD",
                                     headers={"User-Agent": "emubox-validate"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status < 400
    except Exception:
        return None


def main():
    d = json.load(open(REG, encoding="utf-8"))
    ems = d.get("emulators", [])
    problems = []
    seen_ids = set()
    for e in ems:
        eid = e.get("id", "")
        if not eid:
            problems.append("[无id] 条目缺 id")
            continue
        if eid in seen_ids:
            problems.append(f"[重复id] {eid}")
        seen_ids.add(eid)

        src = e.get("source", "")
        if not src:
            problems.append(f"[{eid}] 缺 source 字段")
        elif src not in VALID_SOURCES:
            problems.append(f"[{eid}] source 非法: {src}")

        url = e.get("repo_url") or ""
        if url:
            if not url.startswith("http"):
                problems.append(f"[{eid}] repo_url 非 http: {url[:50]}")
            if src == "github" and "github.com" not in url:
                problems.append(f"[{eid}] source=github 但 repo_url 非 GitHub: {url[:60]}")
            if src == "gitlab" and "gitlab.com" not in url:
                problems.append(f"[{eid}] source=gitlab 但 repo_url 非 GitLab: {url[:60]}")
            if src == "gitea" and "git.eden" not in url:
                problems.append(f"[{eid}] source=gitea 但 repo_url 非 Gitea: {url[:60]}")

        dead = e.get("dead", False)
        if dead and eid not in DEAD_SOURCES:
            problems.append(f"[{eid}] dead=true 但不在 DEAD 清单（需同步清单）")
        if eid in DEAD_SOURCES and not dead:
            problems.append(f"[{eid}] DEAD 清单含此 id 但未标 dead")
        if dead and not e.get("dead_reason"):
            problems.append(f"[{eid}] dead=true 缺 dead_reason")

        apk = e.get("latest_apk_url") or ""
        if apk:
            if not apk.startswith("http"):
                problems.append(f"[{eid}] latest_apk_url 非 http: {apk[:50]}")
            low = apk.lower()
            if any(low.endswith(x) for x in BAD_EXT):
                problems.append(f"[{eid}] 下载直链为可疑文件类型(非安装包): {apk[-30:]}")
        elif not dead and e.get("source") in ("github", "gitlab", "gitea", "notabug"):
            # 活跃代码源条目应有下载或明确 note
            if not e.get("note"):
                problems.append(f"[{eid}] 活跃代码源无下载直链且无 note 说明")

    print(f"条目总数: {len(ems)}")
    print(f"发现问题: {len(problems)}")
    for p in problems:
        print("  ", p)
    if problems:
        print(f"\n通过率: {len(ems) - len(problems)}/{len(ems)}（按条目计，问题逐条列出）")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
