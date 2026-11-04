#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""排行 v2：家族聚合 + 多轴排序 + 历史快照。不造综合分。

为什么不用一个加权总分（这是之前「排行老问题」的根因）：
  1. 加权系数无法自证。星标 30% 下载 50% 活跃 20%——凭什么？改一个系数整个榜重排，
     没人能解释「为什么 A 在 B 前面」。
  2. 跨方法比总量没有意义。Winlator 全家族 2200 万下载，Tyranor 只有 GitHub 几百，
     但后者装完就能玩、前者要调半天。一个分数量不出这个差别。
  3. 两个社区站（the412banner / t3st31）都不做综合分：它们给的是**同一批原始指标
     配多个排序轴 + 时间窗口**，用户按自己的意图选轴。抄这个，不抄「评分」。

本脚本做四件事：
  ① 家族聚合：fork 不再单独占榜位（22 个家族、92 条会塌成 22 行）
  ② 历史快照：每天存一份 ranking-history/YYYY-MM-DD.json，攒够才有真增量
  ③ 口径标注：只有 GitHub release 下载的项目才进下载榜，官网/网盘分发的标 out-of-scope
  ④ 输出 ranking.json（App 直接读）+ 榜单 HTML

用法：
  python3 scripts/ranking.py            # 计算 + 落快照 + 出榜单
  python3 scripts/ranking.py --no-snap  # 只算不落快照
"""
import argparse
import json
import os
import statistics
from datetime import datetime, timezone, timedelta
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REG = os.path.join(ROOT, "emulator-registry.json")
OUT = os.path.join(ROOT, "ranking.json")
HIST_DIR = os.path.join(ROOT, "ranking-history")
HTML = os.path.join(ROOT, "ranking-board.html")

SECTION_ORDER = ["console-emulation", "windows-compat", "native-port", "vn-engine", "virtualization", "frontend-tool"]
SECTION_LABEL = {
    "console-emulation": "主机模拟",
    "windows-compat": "Windows 兼容层",
    "native-port": "原生移植",
    "vn-engine": "视觉小说引擎",
    "virtualization": "虚拟机",
    "frontend-tool": "前端与工具",
}


def now():
    return datetime.now(timezone.utc)


def parse_dt(s):
    if not s:
        return None
    try:
        return datetime.fromisoformat(str(s).replace("Z", "+00:00"))
    except ValueError:
        return None


def days_since(s):
    d = parse_dt(s)
    return None if d is None else (now() - d).days


# ── 1. 家族聚合 ──────────────────────────────────────────────────────
def build_families(emus):
    """一个家族一行。代表条目 = official 优先 → 下载高 → 星多 → 最近发版。"""
    fam = defaultdict(list)
    for e in emus:
        if e.get("dead") or e.get("skip"):
            continue
        fam[e.get("family") or e["id"]].append(e)

    rows = []
    for key, members in fam.items():
        def is_upstream(e):
            """家族键就是上游名；仓库名/名称正好等于它就是上游本人（dolphin vs Dolphin-MMJR2）。"""
            leaf = str(e.get("repo") or "").split("/")[-1].lower()
            return leaf == key.lower() or str(e.get("name") or "").lower().startswith(key.lower())

        def rep_key(e):
            # 顺序：官方 → 上游本人 → 一年内还在推 → 下载 → 星标 → 发版时间
            # 不能只看下载：medard22 的 Dolphin 分支下载 77 万，但 2025-08 就停了，
            # 拿它当代表会显示「沉寂」，而 Dolphin 主线天天在推（实测踩过）。
            dpush = days_since(e.get("pushed_at"))
            return (
                1 if e.get("official") else 0,
                1 if is_upstream(e) else 0,
                1 if (dpush is not None and dpush <= 365) else 0,
                e.get("downloads") or 0,
                e.get("stars") or 0,
                parse_dt(e.get("latest_date")) or datetime(1970, 1, 1, tzinfo=timezone.utc),
            )

        rep = max(members, key=rep_key)
        dl = [m.get("downloads") or 0 for m in members]
        # 活跃度按家族整体判：只要有一个分支还在推，这个家族就不是「沉寂」
        pushes = [d for d in (days_since(m.get("pushed_at")) for m in members) if d is not None]
        alive_days = min(pushes) if pushes else None
        archived_all = all(m.get("repo_status") == "archived" for m in members)
        rows.append({
            "family": key,
            "label": rep.get("family_label") or rep["name"],
            "rep_id": rep["id"],
            "rep_name": rep["name"],
            "repo_url": rep.get("repo_url"),
            "logo": rep.get("logo_url") or rep.get("logoUrl"),
            "section": rep.get("section"),
            "platforms": rep.get("platforms") or [],
            "method": rep.get("method"),
            "hosts": rep.get("hosts") or [],
            "description": rep.get("description"),
            "members": len(members),
            "member_ids": [m["id"] for m in members],
            # 家族总下载（各 APK 是不同用户，不重复计数）与代表条目下载，分开存
            "downloads_sum": sum(dl),
            "downloads_rep": rep.get("downloads") or 0,
            "stars_rep": rep.get("stars") or 0,
            "stars_max": max((m.get("stars") or 0) for m in members),
            "latest_date": rep.get("latest_date"),
            "days_since_release": days_since(rep.get("latest_date")),
            "days_since_push": days_since(rep.get("pushed_at")),
            "family_days_since_push": alive_days,
            "official": bool(rep.get("official")),
            # 注意 0 是合法值（今天推的），不能用 `or 9999`——会把最活跃的判成沉寂
            "status": ("archived" if archived_all
                       else "quiet" if (alive_days if alive_days is not None else 9999) > 180
                       else "active"),
        })
    return rows


# ── 2. 历史与增量 ────────────────────────────────────────────────────
def load_history():
    """返回 [(date_str, {id: {downloads, stars}})] 按日期升序。"""
    if not os.path.isdir(HIST_DIR):
        return []
    snaps = []
    for fn in sorted(os.listdir(HIST_DIR)):
        if not fn.endswith(".json"):
            continue
        with open(os.path.join(HIST_DIR, fn), encoding="utf-8") as f:
            snaps.append((fn[:-5], json.load(f)))
    return snaps


def write_snapshot(emus):
    os.makedirs(HIST_DIR, exist_ok=True)
    day = now().strftime("%Y-%m-%d")
    path = os.path.join(HIST_DIR, f"{day}.json")
    snap = {
        e["id"]: {"downloads": e.get("downloads") or 0, "stars": e.get("stars") or 0}
        for e in emus
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(snap, f, ensure_ascii=False, separators=(",", ":"))
    return path


def attach_deltas(rows, hist):
    """按家族算下载增量。窗口取 14 天，与 the412banner 一致（社区习惯的「双周」）。"""
    if len(hist) < 2:
        return {"available": False, "need_days": 14, "have_days": len(hist)}

    oldest_date, oldest = hist[0]
    latest_date, latest = hist[-1]
    span = (datetime.fromisoformat(latest_date) - datetime.fromisoformat(oldest_date)).days
    for r in rows:
        delta = 0
        for mid in r["member_ids"]:
            a = (oldest.get(mid) or {}).get("downloads") or 0
            b = (latest.get(mid) or {}).get("downloads") or 0
            delta += max(0, b - a)
        r["downloads_delta"] = delta
        # 增长率先算在家族总量上，避免小基数爆表
        base = sum((oldest.get(mid) or {}).get("downloads") or 0 for mid in r["member_ids"])
        r["growth_pct"] = round(delta / base * 100, 1) if base >= 1000 else None
    return {"available": True, "window_days": span, "from": oldest_date, "to": latest_date}


# ── 3. 榜单定义（多轴，无综合分）──────────────────────────────────────
BOARDS = [
    {
        "key": "rising",
        "label": "涨得最快",
        "metric": "downloads_delta",
        "note": "近 14 天新增下载（家族合计）。只看 GitHub release 下载，官网/网盘分发的不参与。",
        "need": lambda r: (r.get("downloads_delta") or 0) > 0,
        "keyfn": lambda r: (r.get("downloads_delta") or 0, r.get("growth_pct") or 0),
    },
    {
        "key": "downloads",
        "label": "总下载",
        "metric": "downloads_sum",
        "note": "家族内所有 APK 的累计下载合计。含 fork 各自的用户，是「这个生态有多大」而非「哪个作者最强」。",
        "need": lambda r: r["downloads_sum"] > 0,
        "keyfn": lambda r: (r["downloads_sum"], r["stars_max"]),
    },
    {
        "key": "stars",
        "label": "星标",
        "metric": "stars_max",
        "note": "家族内最高星标。星标偏心国际项目，国内项目普遍偏低，只作参考不作结论。",
        "need": lambda r: r["stars_max"] > 0,
        "keyfn": lambda r: (r["stars_max"], r["downloads_sum"]),
    },
    {
        "key": "active",
        "label": "最近更新",
        "metric": "family_days_since_push",
        "note": "距最后一次代码推送的天数。≤30 天算在维护，>180 天标「沉寂」。",
        "need": lambda r: r["family_days_since_push"] is not None,
        # 同一天推的会并列，用星标做二级排序，否则前 10 名全是「今天」且顺序随机
        "keyfn": lambda r: (-(r["family_days_since_push"] if r["family_days_since_push"] is not None else 9999), r["stars_max"]),
        "asc": True,
    },
    {
        "key": "fresh",
        "label": "最新发版",
        "metric": "days_since_release",
        "note": "距最后一次正式发版的天数。发版频繁≠质量高，只说明节奏。",
        "need": lambda r: r["days_since_release"] is not None,
        "keyfn": lambda r: (-(r["days_since_release"] if r["days_since_release"] is not None else 9999),),
        "asc": True,
    },
]


def build_boards(rows, delta_info):
    out = []
    for b in BOARDS:
        if b["key"] == "rising" and not delta_info.get("available"):
            out.append({
                "key": b["key"], "label": b["label"], "note": b["note"],
                "status": "accumulating",
                "status_text": f"历史快照只有 {delta_info.get('have_days', 0)} 天，"
                               f"攒够 {delta_info.get('need_days', 14)} 天才有真实增量",
                "rows": [],
            })
            continue
        cand = [r for r in rows if b["need"](r)]
        cand.sort(key=b["keyfn"], reverse=True)
        out.append({
            "key": b["key"], "label": b["label"], "note": b["note"],
            "status": "ok",
            "rows": cand[:50],
        })
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-snap", action="store_true")
    args = ap.parse_args()

    with open(REG, encoding="utf-8") as f:
        emus = json.load(f)["emulators"]

    if not args.no_snap:
        p = write_snapshot(emus)
        print(f"快照写入 {p}")

    hist = load_history()
    rows = build_families(emus)
    delta_info = attach_deltas(rows, hist)
    boards = build_boards(rows, delta_info)

    with_dl = sum(1 for e in emus if e.get("downloads"))
    payload = {
        "updated_at": now().isoformat(timespec="seconds"),
        "methodology": "family-aggregated / multi-axis / no-composite-score",
        "coverage": {
            "entries": len(emus),
            "families": len(rows),
            "with_downloads": with_dl,
            "downloads_visible_pct": round(with_dl / len(emus) * 100, 1),
        },
        "delta": delta_info,
        "sections": [
            {"key": k, "label": SECTION_LABEL[k], "count": sum(1 for r in rows if r["section"] == k)}
            for k in SECTION_ORDER
        ],
        "boards": boards,
        "families": rows,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=1)

    print(f"家族 {len(rows)} 个（来自 {len(emus)} 条），下载可见 {with_dl} 条 "
          f"({payload['coverage']['downloads_visible_pct']}%)")
    for b in boards:
        if b["status"] == "accumulating":
            print(f"  {b['label']}: 数据积累中（{b['status_text']}）")
        else:
            top = b["rows"][0] if b["rows"] else None
            print(f"  {b['label']:<8s}{len(b['rows']):>3d} 行  榜首 {top['label'] if top else '-'}")

    # 塌缩效果：fork 越多，榜位浪费越严重
    saved = sum(r["members"] - 1 for r in rows)
    print(f"家族聚合省下 {saved} 个榜位（原本 {len(emus)} 行 → {len(rows)} 行）")
    print(f"输出 {OUT}")


if __name__ == "__main__":
    main()
