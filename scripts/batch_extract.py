#!/usr/bin/env python3
"""候选池 → 待审条目草稿（批量跑 extract_entry）。

流程位置：
    discover_candidates.py  找项目（宽口径，宁可多抓）
            ↓
    batch_extract.py        把候选变成「填好的记录」（窄口径，带证据）
            ↓
    人看一眼，改不对的       ← 只在这里花时间
            ↓
    合并进 registry

产出：
    drafts/<日期>/drafts.json   可直接 merge 的记录数组
    drafts/<日期>/report.md     人看的判定说明

用法：
    python3 scripts/batch_extract.py --top 15
    python3 scripts/batch_extract.py --pool /workspace/生态发现/候选池-20261104.json --top 5 --dry-run
"""
import argparse
import datetime as dt
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extract_entry import extract, to_md, token  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
POOL = os.environ.get("EMUBOX_POOL", "/workspace/生态发现/候选池-20261104.json")


def load_known():
    known = set()
    for fn, key in (("emulator-registry.json", "emulators"), ("driver-registry.json", "repositories")):
        p = os.path.join(ROOT, fn)
        if not os.path.exists(p):
            continue
        for e in json.load(open(p, encoding="utf-8")).get(key, []):
            if e.get("repo"):
                known.add(e["repo"].lower())
            if e.get("id"):
                known.add(e["id"].lower())
    return known


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pool", default=POOL)
    ap.add_argument("--top", type=int, default=15)
    ap.add_argument("--min-score", type=int, default=70)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    pool = json.load(open(args.pool, encoding="utf-8"))
    known = load_known()
    cand = [r for r in pool["rows"]
            if r["verdict"] == "suggest" and not r["already_in_registry"]
            and r["score"] >= args.min_score]
    cand.sort(key=lambda r: (-r["score"], -r["stars"]))
    cand = cand[: args.top]

    print(f"候选 {len(cand)} 个待提取", file=sys.stderr)
    tok = token()
    drafts, reports, skipped = [], [], []

    for r in cand:
        rec, tr = extract(r["full_name"], tok)
        if rec is None:
            skipped.append((r["full_name"], tr.get("error")))
            continue
        dup = rec["id"] in known or rec["repo"].lower() in known
        # 同名不同物：描述里是 AI 聊天 / 定位 / 社交，且没有模拟器字样。
        # 不自动删（删了就看不见），标记出来让人一眼跳过。
        blob = (rec["description"] + " " + rec["id"]).lower()
        noise = bool(re.search(r"\bchat\b|\bai\b|spoofer|location|social|forum|blog", blob)) \
            and not re.search(r"emulat|模拟|rvm|runtime|port", blob)
        rec["_suspect_noise"] = noise
        rec["_pool_score"] = r["score"]
        rec["_dup"] = dup
        drafts.append(rec)
        reports.append(to_md(rec, tr))
        print(f"  {'重复' if dup else '新增'} {rec['id']:44} {rec['method']} "
              f"{'/'.join(rec['hosts'])}", file=sys.stderr)

    today = dt.date.today().strftime("%Y%m%d")
    outdir = os.path.join(ROOT, "drafts", today)
    if not args.dry_run:
        os.makedirs(outdir, exist_ok=True)
        json.dump(drafts, open(os.path.join(outdir, "drafts.json"), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=2)
        head = [
            f"# 生态候选草稿 · {today}", "",
            f"> 从候选池取分 ≥{args.min_score} 的前 {args.top} 个，逐个抓取生成。",
            f"> 共 {len(drafts)} 条（其中 {sum(1 for d in drafts if d['_dup'])} 条疑重复），"
            f"抓取失败 {len(skipped)} 个。", "",
            "**每条的「怎么跑 / 跑在哪」都有证据行，对不上就改那一条，不用从头查。**", "",
            "## 待人工确认（自动判定没把握的）", "",
        ]
        need = [d for d in drafts if d.get("needs_review")]
        head.append("| id | 缺什么 |" if need else "无")
        if need:
            head.append("|---|---|")
            for d in need:
                miss = "hosts 为空" if not d["hosts"] else "置信度低"
                head.append(f"| `{d['id']}` | {miss} |")
        head.append("")
        sus = [d for d in drafts if d.get("_suspect_noise")]
        if sus:
            head += ["## 疑似同名不同物（建议直接跳过）", "",
                     "| id | 抓到的描述 |", "|---|---|"]
            for d in sus:
                head.append(f"| `{d['id']}` | {(d['description'] or '')[:60]} |")
            head.append("")
        with open(os.path.join(outdir, "report.md"), "w", encoding="utf-8") as f:
            f.write("\n".join(head) + "\n" + "\n\n".join(reports))
        print(f"\n写入 {outdir}/drafts.json 与 report.md")
    else:
        print(json.dumps([{k: v for k, v in d.items() if not k.startswith("latest_body")}
                          for d in drafts], ensure_ascii=False, indent=1)[:2000])


if __name__ == "__main__":
    main()
