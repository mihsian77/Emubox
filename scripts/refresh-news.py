#!/usr/bin/env python3
"""刷新 news-data.json：抓取 IT之家 RSS，按分类映射落位。

数据源：https://www.ithome.com/rss/（综合 RSS，约 60 条/次，RSS 2.0，无 category 字段）
分类映射：标题+摘要关键词 → 模拟器 / 驱动组件 / 生态 / 泛科技；仅明确无关（汽车、家电）丢弃。
原则：保守宽松，未命中一律进"泛科技"保留，不误过滤有用内容。

输出：news-data.json（仓库根 + app/src/main/assets/news_offline.json 离线副本）
  { version, updated_at, source, items: [{id, title, url, published_at, category, summary}] }
窗口：保留最近 7 天，最多 200 条；url 指纹去重。

用法：python3 scripts/refresh-news.py
"""
import hashlib
import json
import os
import re
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO_ROOT, "news-data.json")
OFFLINE = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "news_offline.json")
RSS_URL = "https://www.ithome.com/rss/"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0"

# 明确无关分类：命中即丢弃（用户确认：汽车/家电与项目完全无关）
DROP_WORDS = [
    "汽车", "车", "新能源车", "电动车", "自动驾驶", "特斯拉", "比亚迪", "蔚来", "理想汽车", "小米汽车",
    "家电", "冰箱", "洗衣机", "空调", "电视", "扫地机", "智能家居", "厨电", "烤箱",
]
# 模拟器 / 新作发布（专有词，避免"游戏/显卡"等宽词误命中游戏本/外设新闻）
EMU_WORDS = [
    "模拟器", "emulator", "RetroArch", "Cemu", "Ryujinx", "Yuzu", "Dolphin", "PCSX2", "PPSSPP",
    "PS5", "PS4", "PS3", "PS2", "Switch", "任天堂", "掌机", "游戏机", "NS", "3DS", "NDS",
    "Winlator", "移植", "手游",
]
# 驱动（GPU 驱动 / Vulkan / Mesa 上游）
DRV_WORDS = [
    "驱动", "Vulkan", "Mesa", "Turnip", "PanVK", "RADV", "OpenGL", "渲染",
    "GPU驱动", "显卡驱动", "图形栈", "图形驱动",
]
# 组件（DXVK / VKD3D 等图形转换层）
COMPONENT_WORDS = [
    "DXVK", "vkd3d", "D7VK", "转换层", "图形转换", "兼容层",
]
# 运行库（Wine / Box64 / Proton 运行时）
RUNTIME_WORDS = [
    "Wine", "Box64", "Proton", "运行时", "WOW64",
]
# 生态（芯片 / 安卓平台 / 设备）
ECO_WORDS = [
    "骁龙", "天玑", "麒麟", "Exynos", "Tensor", "安卓", "Android", "ARM", "SoC", "芯片",
    "GPU", "CPU", "处理器", "高通", "联发科", "三星", "台积电", "工艺", "制程", "5G", "终端",
    "手机", "平板", "折叠屏", "鸿蒙", "HarmonyOS", "笔记本", "显卡",
]

CATS = {
    "emulator": "模拟器", "driver": "驱动", "component": "组件",
    "runtime": "运行库", "eco": "生态", "general": "泛科技",
}


def classify(title: str, summary: str) -> str:
    text = (title + " " + summary).lower()
    if any(w in text for w in DROP_WORDS):
        return "drop"
    if any(w.lower() in text for w in EMU_WORDS):
        return "emulator"
    if any(w.lower() in text for w in DRV_WORDS):
        return "driver"
    if any(w.lower() in text for w in COMPONENT_WORDS):
        return "component"
    if any(w.lower() in text for w in RUNTIME_WORDS):
        return "runtime"
    if any(w.lower() in text for w in ECO_WORDS):
        return "eco"
    return "general"


def fetch_rss():
    req = urllib.request.Request(RSS_URL, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return resp.read().decode("utf-8")


def strip_html(s: str) -> str:
    return re.sub(r"<[^>]+>", "", s).replace("&nbsp;", " ").replace("&amp;", "&").strip()


def parse_rss(xml_text: str):
    root = ET.fromstring(xml_text)
    items = []
    for item in root.iter("item"):
        t = item.findtext("title") or ""
        link = item.findtext("link") or ""
        desc = strip_html(item.findtext("description") or "")[:200]
        pub = item.findtext("pubDate") or ""
        if not t or not link:
            continue
        items.append({"title": t.strip(), "url": link.strip(), "summary": desc, "pubDate": pub})
    return items


def iso_epoch(pub_date: str):
    """RSS pubDate → epoch 秒（无 GMT 解析依赖时返回 0）"""
    try:
        import email.utils
        return int(email.utils.parsedate_to_datetime(pub_date).timestamp())
    except Exception:
        return 0


def main():
    try:
        xml_text = fetch_rss()
    except Exception as e:
        print(f"RSS 抓取失败: {e}", file=sys.stderr)
        sys.exit(1)

    fresh = []
    for it in parse_rss(xml_text):
        cat = classify(it["title"], it["summary"])
        if cat == "drop":
            continue
        h = hashlib.md5(it["url"].encode()).hexdigest()[:8]
        fresh.append({
            "id": h,
            "title": it["title"][:120],
            "url": it["url"],
            "published_at": iso_epoch(it["pubDate"]),
            "category": cat,
            "summary": it["summary"][:160],
        })

    # 与历史合并去重（url id 指纹），窗口 7 天
    old = []
    if os.path.exists(OUT):
        old = json.load(open(OUT, encoding="utf-8")).get("items", [])
    now = int(time.time())
    merged = {it["id"]: it for it in old + fresh}
    merged = [it for it in merged.values() if now - it.get("published_at", 0) <= 7 * 86400]
    merged.sort(key=lambda x: x.get("published_at", 0), reverse=True)
    merged = merged[:200]

    data = {
        "version": 1,
        "updated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source": "ithome",
        "items": merged,
    }
    json.dump(data, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    json.dump(data, open(OFFLINE, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    from collections import Counter
    print(f"抓取 {len(fresh)} 条，合并后 {len(merged)} 条（7 天窗口）")
    print("分类分布:", dict(Counter(it["category"] for it in merged)))
    print(f"已写 {OUT} 与 assets 离线副本")


if __name__ == "__main__":
    main()
