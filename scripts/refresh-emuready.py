#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""EmuReady 公开数据抓取：设备目录 / 系统清单 / 模拟器清单 → assets/emuready-catalog.json

为什么要有这个脚本：
  App 原先是"联网模糊搜设备名 → 拿第一条结果查兼容性"，但 EmuReady 的 search 是
  前缀/包含匹配且按内部顺序返回，搜「Xiaomi 15」第一条给的是「Xiaomi 15T Pro」，
  于是拿着别的机器的兼容分当本机显示；搜 SoC 名（Snapdragon 8 Gen 3）则 0 命中，
  兜底链路整条失效 —— 这就是"兼容性卡片完全不显示"的根因。

  改成：把 EmuReady 的 803 台设备目录离线落到 assets，App 端先做本地精确匹配
  （归一化后比对型号/品牌/组合键），拿到 deviceId 再查兼容性，一次请求、结果准确；
  本机未收录时按 SoC 找同芯片的代表机型做参考，并如实标注"同 SoC 参考"。

数据来源：EmuReady 移动端公开接口（无需鉴权）
  https://www.emuready.com/api/mobile/trpc/<endpoint>?input=<SuperJSON>
  接口契约见其仓库 public/api-docs/mobile-openapi.json

用法：
  python3 scripts/refresh-emuready.py            # 抓取并写入 assets
  python3 scripts/refresh-emuready.py --dry-run  # 只打印统计，不落盘
"""

import argparse
import json
import os
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://www.emuready.com/api/mobile/trpc/"
UA = "EmuHub-CN/1.0 (+https://github.com/mihsian77/Emubox)"
OUT = os.path.join("app", "src", "main", "assets", "emuready-catalog.json")
RETRY = 2          # 同一请求最多重试次数（老板纪律：同一报错最多重试 2 次）
TIMEOUT = 40


def call(endpoint, payload=None):
    """调一个 tRPC GET 接口，返回 result.data.json 里的业务数据。"""
    url = BASE + endpoint
    if payload is not None:
        url += "?input=" + urllib.parse.quote(
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
                raise ValueError("响应缺少 result.data.json：%s" % str(body)[:200])
            return data
        except Exception as exc:  # noqa: BLE001 - 网络异常统一重试
            last = exc
            if attempt < RETRY:
                time.sleep(2 + attempt * 2)
    raise RuntimeError("%s 抓取失败（重试 %d 次）：%s" % (endpoint, RETRY, last))


def norm(text):
    """归一化：全角转半角、小写、去括号补充说明、只留字母数字。

    例："KONKR Pocket FIT (G3 Gen 3)" → "konkrpocketfit"
        "OnePlus 13R"                 → "oneplus13r"
    """
    if not text:
        return ""
    t = unicodedata.normalize("NFKC", str(text)).lower()
    t = re.sub(r"\([^)]*\)|\[[^\]]*\]", " ", t)      # 去括号内的补充规格
    t = re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", t)     # 只留字母数字与汉字
    return t


def match_keys(brand, model):
    """生成匹配键：型号、品牌+型号、去品牌前缀的型号。

    App 端拿本机的品牌/型号/营销名逐个比对这批键即可，无需再做字符串猜测。
    """
    keys = []
    mb, mm = norm(brand), norm(model)
    if mm:
        keys.append(mm)
    if mb and mm:
        keys.append(mb + mm)
        # 上游型号常自带品牌（"Vivo iQOO 13"），去掉前缀再存一份
        if mm.startswith(mb) and len(mm) > len(mb) + 2:
            keys.append(mm[len(mb):])
    # 去重保序
    seen, out = set(), []
    for k in keys:
        if k and k not in seen:
            seen.add(k)
            out.append(k)
    return out


def build_catalog():
    print("[1/3] 拉取设备目录 …")
    devices_raw = call("devices.get", {"limit": 1000}).get("devices") or []
    print("      %d 台设备" % len(devices_raw))

    print("[2/3] 拉取系统清单 …")
    systems_raw = call("general.systems") or []
    print("      %d 个平台" % len(systems_raw))

    print("[3/3] 拉取模拟器清单 …")
    emulators_raw = call("emulators.get", {"limit": 100}) or []
    print("      %d 个模拟器" % len(emulators_raw))

    devices = []
    for d in devices_raw:
        brand = (d.get("brandName") or "").strip()
        model = (d.get("modelName") or "").strip()
        if not model:
            continue
        devices.append({
            "id": d.get("id", ""),
            "modelName": model,
            "brandName": brand,
            "socName": (d.get("socName") or "").strip(),
            "listingsCount": int(d.get("listingsCount") or 0),
            "keys": match_keys(brand, model),
        })
    # 报告数降序：App 端 SoC 兜底时直接取第一条即"该芯片最有代表性的机型"
    devices.sort(key=lambda x: (-x["listingsCount"], x["brandName"], x["modelName"]))

    systems = [
        {"id": s.get("id", ""), "key": s.get("key", ""), "name": s.get("name", "")}
        for s in systems_raw if s.get("name")
    ]

    emulators = []
    for e in emulators_raw:
        if not e.get("name"):
            continue
        desc = " ".join((e.get("description") or "").split())
        emulators.append({
            "id": e.get("id", ""),
            "name": e.get("name", ""),
            "logo": e.get("logo") or "",
            "repositoryUrl": e.get("repositoryUrl") or "",
            "officialUrl": e.get("officialUrl") or "",
            "androidRepoUrl": e.get("androidGithubRepoUrl") or "",
            "systems": [s.get("key") for s in (e.get("systems") or []) if s.get("key")],
            "listings": int(((e.get("_count") or {}).get("listings")) or 0),
            "description": desc[:280],
        })
    emulators.sort(key=lambda x: -x["listings"])

    brands = sorted({d["brandName"] for d in devices if d["brandName"]})
    socs = sorted({d["socName"] for d in devices if d["socName"]})

    return {
        "version": 1,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source": "https://www.emuready.com/api/mobile/trpc",
        "note": ("EmuReady 公开接口离线目录：设备用于本地精确匹配（避免联网模糊搜索命中错设备），"
                 "systems/emulators 供界面展示。由 scripts/refresh-emuready.py 生成。"),
        "stats": {
            "devices": len(devices),
            "brands": len(brands),
            "socs": len(socs),
            "systems": len(systems),
            "emulators": len(emulators),
        },
        "brands": brands,
        "systems": systems,
        "devices": devices,
        "emulators": emulators,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="只统计不写文件")
    ap.add_argument("--out", default=OUT, help="输出路径")
    args = ap.parse_args()

    try:
        catalog = build_catalog()
    except Exception as exc:  # noqa: BLE001
        print("抓取失败：%s" % exc, file=sys.stderr)
        return 1

    text = json.dumps(catalog, ensure_ascii=False, separators=(",", ":"))
    print("统计：%(devices)d 设备 / %(brands)d 品牌 / %(socs)d SoC / "
          "%(systems)d 平台 / %(emulators)d 模拟器" % catalog["stats"])
    print("体积：%.1f KB" % (len(text.encode("utf-8")) / 1024))

    if args.dry_run:
        print("dry-run，未写入")
        return 0

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        fh.write(json.dumps(catalog, ensure_ascii=False, indent=1))
    print("已写入 %s" % args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
