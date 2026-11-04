#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""由 audit-report.json + emulator-registry.json 生成 /tmp/rows.json。

补齐此前缺失的一步：原 /tmp/rows.json 是一次性脚本产物，无法复现。
本脚本只做字段合并，不新增/删除任何条目，缺口判定沿用 audit 结果。
"""
import json, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AUDIT = os.path.join(ROOT, "audit-report.json")
REG = os.path.join(ROOT, "emulator-registry.json")
OUT = "/tmp/rows.json"


def main():
    audit = json.load(open(AUDIT, encoding="utf-8"))
    reg = {e["id"]: e for e in json.load(open(REG, encoding="utf-8"))["emulators"]}
    rows = []
    for it in audit["items"]:
        e = reg.get(it["id"], {})
        raw = it.get("gaps") or []
        # tag_stale:old->new 这类带附件的，归一化成基础码
        gaps = []
        for g in raw:
            base = g.split(":")[0]
            if base not in gaps:
                gaps.append(base)
        if not gaps:
            continue
        p = it.get("probe") or {}
        apk = p.get("apk_sample")
        pkg = p.get("pkg_sample")
        rows.append({
            "id": it["id"],
            "name": it.get("name") or e.get("name", ""),
            "cat": e.get("category", ""),
            "plat": e.get("platforms") or [],
            "runtime": e.get("runtime", ""),
            "status": e.get("status", ""),
            "dist": e.get("distribution", ""),
            "source": e.get("source", ""),
            "repo": it.get("repo_full") or e.get("repo", ""),
            "gaps": gaps,
            "gapraw": raw,
            "stars": p.get("stars", e.get("stars")),
            "pushed": p.get("pushed_at", e.get("pushed_at", "")),
            "archived": p.get("archived", e.get("status") == "archived"),
            "releases": p.get("releases"),
            "apk": apk,
            "pkg": pkg,
            "tag": p.get("latest_release_tag") or e.get("latest_tag"),
            "sample": (apk or pkg or {}).get("name"),
            "wf": p.get("workflows") or [],
            "home": p.get("homepage") or p.get("homepage_from_meta") or e.get("homepage", ""),
            "desc": e.get("description", ""),
            "note": e.get("note", ""),
            "logo": bool(e.get("logo_url")),
        })
    json.dump(rows, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"rows: {len(rows)} / registry: {len(reg)}")


if __name__ == "__main__":
    main()
