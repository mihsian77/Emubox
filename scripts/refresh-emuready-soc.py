#!/usr/bin/env python3
"""按 SoC 聚合 EmuReady 兼容性数据（离线包）。

动机
----
App 里原先只对「本机型号」查一次 catalog.getDeviceCompatibility，
结果往往只有 1~2 个平台有报告（例：只有 Switch + Windows），
用户看到的就是「信息不全，没有对应处理器/机型的实测」。

真实原因是 EmuReady 的报告是按**具体机型**累积的：同 SoC 的其他机型
（Thor Max / RP5 / 一加 13 …）有大量报告，但不属于本机 deviceId。

本脚本按 SoC 把同芯片全部机型（取 listings 最多的前 N 台）的报告并集聚合，
输出到 assets/emuready-soc-compat.json，App 端离线秒开、不需要跳官网。

用法
----
    python3 scripts/refresh-emuready-soc.py            # 全量（约 400 次请求）
    python3 scripts/refresh-emuready-soc.py --limit 20 # 只跑前 20 个 SoC（联调）

同一报错最多重试 2 次（仓库纪律）。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request

BASE = "https://www.emuready.com/api/mobile/trpc/"
UA = "EmuHub-CN/1.0 (+https://github.com/mihsian77/Emubox)"
CATALOG = os.path.join("app", "src", "main", "assets", "emuready-catalog.json")
OUT = os.path.join("app", "src", "main", "assets", "emuready-soc-compat.json")
TIMEOUT = 30
RETRY = 2
# 每个 SoC 取报告最多的前 N 台设备（越多越全，越慢）
DEVICES_PER_SOC = 3


def call(endpoint: str, payload: dict):
    url = BASE + endpoint + "?input=" + urllib.parse.quote(
        json.dumps({"json": payload}, ensure_ascii=False), safe=""
    )
    last = None
    for attempt in range(RETRY + 1):
        try:
            req = urllib.request.Request(
                url, headers={"User-Agent": UA, "Accept": "application/json"}
            )
            with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
                body = json.loads(resp.read().decode("utf-8", "ignore"))
            data = body.get("result", {}).get("data", {}).get("json")
            if data is None:
                raise ValueError("响应缺少 result.data.json")
            return data
        except Exception as exc:  # noqa: BLE001
            last = exc
            if attempt < RETRY:
                time.sleep(1.5 + attempt)
    print("  ! %s 失败：%s" % (endpoint, last), file=sys.stderr)
    return None


def norm_soc(raw: str) -> str:
    """与 App 侧 EmuReadyStore.normalizeSoc 保持一致。"""
    t = (raw or "").lower()
    t = t.replace("骁龙", "snapdragon").replace("天玑", "dimensity")
    t = t.replace("麒麟", "kirin").replace("猎户座", "exynos")
    for drop in ("qualcomm", "高通", "mediatek", "联发科", "samsung"):
        t = t.replace(drop, "")
    return "".join(ch for ch in t if ch.isalnum())


def merge_systems(bucket: dict, systems: list, device_label: str) -> None:
    """把一台设备的平台报告并进 SoC 桶：同平台取报告数最多的一份。"""
    for s in systems or []:
        score = s.get("compatibilityScore")
        if score is None or score < 0:
            continue
        key = s.get("key") or s.get("name")
        metrics = s.get("metrics") or {}
        listings = int(metrics.get("totalListings") or 0)
        emus = s.get("emulators") or []
        best_name, best_score = "", -1
        for e in emus:
            es = e.get("avgCompatibilityScore")
            if es is not None and es > best_score:
                best_score, best_name = es, e.get("name", "")
        item = {
            "key": key,
            "name": s.get("name", ""),
            "score": int(score),
            "confidence": s.get("confidence") or "low",
            "dataSource": s.get("dataSource") or "device",
            "bestEmulator": best_name,
            "listings": listings,
            "uniqueGames": int(metrics.get("uniqueGames") or 0),
            "device": device_label,
        }
        prev = bucket.get(key)
        if prev is None or item["listings"] > prev["listings"]:
            bucket[key] = item


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0, help="只处理前 N 个 SoC（联调用）")
    args = ap.parse_args()

    with open(CATALOG, encoding="utf-8") as fh:
        catalog = json.load(fh)
    devices = catalog.get("devices") or []

    by_soc: dict[str, list] = {}
    for d in devices:
        soc = (d.get("socName") or "").strip()
        if not soc:
            continue
        by_soc.setdefault(soc, []).append(d)

    soc_list = sorted(by_soc.items(), key=lambda kv: -sum(x.get("listingsCount", 0) for x in kv[1]))
    if args.limit:
        soc_list = soc_list[: args.limit]

    out_socs = []
    total = len(soc_list)
    for i, (soc, devs) in enumerate(soc_list, 1):
        devs = sorted(devs, key=lambda x: -x.get("listingsCount", 0))[:DEVICES_PER_SOC]
        bucket: dict = {}
        for d in devs:
            data = call("catalog.getDeviceCompatibility", {"deviceId": d["id"]})
            if not data:
                continue
            merge_systems(bucket, data.get("systems"), "%s %s" % (d.get("brandName", ""), d.get("modelName", "")))
        if not bucket:
            continue
        systems = sorted(bucket.values(), key=lambda x: -x["listings"])
        out_socs.append(
            {
                "soc": soc,
                "key": norm_soc(soc),
                "devices": ["%s %s" % (d.get("brandName", ""), d.get("modelName", "")) for d in devs],
                "systems": systems,
            }
        )
        print("[%d/%d] %s → %d 平台" % (i, total, soc, len(systems)))

    payload = {
        "version": 1,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source": "https://www.emuready.com/api/mobile/trpc/catalog.getDeviceCompatibility",
        "note": "按 SoC 聚合的同芯片机型兼容报告（每平台取报告数最多的一份），供 App 离线展示，替代跳转官网。",
        "devices_per_soc": DEVICES_PER_SOC,
        "socs": out_socs,
    }
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, separators=(",", ":"))
    print("写入 %s（%d 个 SoC，%.1f KB）" % (OUT, len(out_socs), os.path.getsize(OUT) / 1024))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
