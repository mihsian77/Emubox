#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""由 audit-report.json 生成项目库缺口清单 CSV。

缺口字段沿用既有口径：
  完整                —— 版本、下载、日志、官网、图标齐全
  无日志              —— 有下载但缺更新日志正文
  无官网              —— 无外部官网
  无下载              —— 无可跳转的下载入口
  无release           —— 仓库无 release 产物
  无图标              —— 无 logo

只依据 audit-report.json 里已实测的结论，不做推测。
"""
import csv
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REG = os.path.join(ROOT, "emulator-registry.json")
AUDIT = os.path.join(ROOT, "audit-report.json")
OUT_CSV = os.path.join(ROOT, "缺口清单.csv")
OUT_MD = os.path.join(ROOT, "缺口清单.md")

# 审计 gap → 清单缺口的映射
GAP_MAP = {
    "no_apk_anywhere_in_releases": "无下载",
    "no_release": "无release",
    "no_description": "无描述",
    "no_logo": "无图标",
}


def main():
    reg = json.load(open(REG, encoding="utf-8"))
    audit = json.load(open(AUDIT, encoding="utf-8"))
    amap = {x["id"]: x for x in audit["items"]}

    rows = []
    for e in reg["emulators"]:
        eid = e.get("id")
        a = amap.get(eid, {})
        gaps = a.get("gaps", [])
        probe = a.get("probe", {})

        miss = set()
        for g in gaps:
            head = g.split(":")[0]
            if head in GAP_MAP:
                miss.add(GAP_MAP[head])
        # 日志：latest_body 为空即缺日志
        if not (e.get("latest_body") or "").strip():
            miss.add("无日志")
        # 官网：registry 缺且仓库 meta 也取不到
        if not (e.get("homepage") or "").strip() and not probe.get("homepage_from_meta"):
            miss.add("无官网")
        # 下载：既无 APK 直链也无 download_url，且审计未确认 release 里有 APK
        if not (e.get("latest_apk_url") or "").strip() and not (e.get("download_url") or "").strip():
            if "apk_recoverable" not in gaps:
                miss.add("无下载")

        if not miss:
            state = "完整"
        else:
            state = "+".join(sorted(miss, key=lambda x: ["无下载", "无release", "无日志", "无官网", "无图标", "无描述"].index(x)
                                     if x in ["无下载", "无release", "无日志", "无官网", "无图标", "无描述"] else 99))
        reason = "; ".join(gaps[:4]) if gaps else ""
        rows.append({
            "id": eid,
            "name": e.get("name", ""),
            "category": e.get("category", ""),
            "source": e.get("source", ""),
            "gaps": state,
            "repo": e.get("repo_url", "") or e.get("homepage", ""),
            "reason": reason,
        })

    with open(OUT_CSV, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["id", "name", "category", "source", "gaps", "repo", "reason"])
        w.writeheader()
        w.writerows(rows)

    # Markdown 版
    from collections import Counter
    cnt = Counter(r["gaps"] for r in rows)
    lines = ["# 项目库缺口清单", "",
             f"共 {len(rows)} 条，完整 {cnt.get('完整', 0)} 条，"
             f"有缺口 {len(rows) - cnt.get('完整', 0)} 条。", "",
             "缺口口径：无下载=无可跳转下载入口；无release=仓库无 release 产物；",
             "无日志=缺更新日志正文；无官网=无外部官网；无图标=无 logo。", "",
             "## 缺口分布", "",
             "| 缺口状态 | 条数 |", "| --- | --- |"]
    for k, v in cnt.most_common():
        lines.append(f"| {k} | {v} |")
    lines += ["", "## 明细", "", "| id | 名称 | 品类 | 来源 | 缺口 | 链接 |", "| --- | --- | --- | --- | --- | --- |"]
    for r in rows:
        if r["gaps"] == "完整":
            continue
        lines.append(f"| {r['id']} | {r['name']} | {r['category']} | {r['source']} | "
                     f"{r['gaps']} | {r['repo']} |")
    lines.append("")
    with open(OUT_MD, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    print(f"缺口清单已生成: {len(rows)} 条，完整 {cnt.get('完整', 0)} 条")
    for k, v in cnt.most_common():
        print(f"  {k:40s} {v}")


if __name__ == "__main__":
    main()
