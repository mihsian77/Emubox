"""刷新 news-data.json：IT之家（模拟器/生态/泛科技）+ 组件与运行库官方 GitHub releases。

数据源：
- IT之家 RSS：仅供 emulator / eco / general。IT之家几乎不报道 DXVK/Wine 类生态，
  组件与运行库分类不再依赖关键词猜测——之前正是这样导致组件/运行库资讯空/错分。
- 各组件类型官方 GitHub releases：DXVK / VKD3D-Proton / Box64 / Wine / Proton-GE /
  Mesa / Mono / glibc，条目带 type 字段，App 端按组件类型筛选 + 多页面展示。

输出：
  { version, updated_at, source, items: [{id, title, url, published_at, category, type?, summary}] }
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from collections import Counter

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO_ROOT, "news-data.json")
OFFLINE = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "news_offline.json")
UA = "EmuHub-CN news-refresh/1.3 (+https://github.com/mihsian77/EmuHub-CN)"
RSS_URL = "https://www.ithome.com/rss/"

# 明确无关分类：命中即丢弃（汽车/家电与项目完全无关）
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
# 生态（芯片 / 安卓平台 / 设备）
ECO_WORDS = [
    "骁龙", "天玑", "麒麟", "Exynos", "Tensor", "安卓", "Android", "ARM", "SoC", "芯片",
    "GPU", "CPU", "处理器", "高通", "联发科", "三星", "台积电", "工艺", "制程", "5G", "终端",
    "手机", "平板", "折叠屏", "鸿蒙", "HarmonyOS", "显卡",
]
# 生态排除：整机/外设/显示类消费电子（虽含 CPU/显卡 词，但不是"芯片与设备"要点）
ECO_DROP_WORDS = [
    "笔记本", "显示器", "耳机", "音箱", "音响", "路由器", "键盘", "鼠标",
    "机箱", "电源适配", "充电器", "游戏本", "键鼠", "手柄",
]

# 官方组件/运行库源：repo / category / type / mode（release=GitHub release）
# 注：wine 官方公告站（winehq.org）对云服务器 IP 有 Cloudflare 拦截（403，实测），
# wine-mirror/wine 无 release 对象且 tags API 只能取到 2005 旧 tag；mono/gecko 无
# 稳定公开 GitHub release 源（发布走 NuGet/官网）——以上均为后续接入项。
OFFICIAL_SOURCES = [
    ("doitsujin/dxvk", "component", "DXVK", "release"),
    ("HansKristian-Work/vkd3d-proton", "component", "VKD3D-Proton", "release"),
    ("ptitSeb/box64", "component", "Box64", "release"),
    ("GloriousEggroll/proton-ge-custom", "component", "Proton-GE", "release"),
    ("ptitSeb/box64", "runtime", "Box64", "release"),
    # 驱动官方源（driver 分类专属；与 driver-registry.json 同名仓库对齐）
    ("K11MCH1/AdrenoToolsDrivers", "driver", "K11MCH1 AdrenoTools", "release"),
    ("StevenMXZ/Adrenotools-Drivers", "driver", "StevenMXZ Adrenotools", "release"),
    ("MrPurple666/purple-turnip", "driver", "Purple-Turnip", "release"),
    ("faux123/turnip-faux123-driver", "driver", "faux123 Turnip", "release"),
    ("faux123/panvk-faux123-driver-ace", "driver", "faux123 PanVK", "release"),
    ("xodiosx/mesa-panvk-g57", "driver", "xodiosx PanVK", "release"),
    ("isygold/panDXVK", "driver", "panDXVK", "release"),
    ("JimVulkan/radv-xclipse", "driver", "RADV Xclipse", "release"),
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
    # 注意：不再从 IT之家 RSS 产生 driver 分类（词表过宽会把散热器/耳机等
    # 消费电子杂项误分进驱动资讯）；driver 分类只由下方 OFFICIAL_SOURCES 官方源产生。
    # 生态命中但属整机/外设/显示类 → 归泛科技，避免"芯片与设备"要点混入消费电子
    if any(w.lower() in text for w in ECO_WORDS):
        if any(w.lower() in text for w in ECO_DROP_WORDS):
            return "general"
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


def fetch_github_releases(repo: str, per_page: int = 6):
    """官方 releases：优先带 GH_TOKEN（CI 注入），无则匿名（公共限流 60 次/时，够用）。"""
    url = f"https://api.github.com/repos/{repo}/releases?per_page={per_page}"
    headers = {"User-Agent": UA, "Accept": "application/vnd.github+json"}
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8"))


def github_get(url: str):
    headers = {"User-Agent": UA, "Accept": "application/vnd.github+json"}
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8"))


def iso_epoch_iso(s: str) -> int:
    """GitHub ISO8601 → epoch 秒；失败返回 0"""
    try:
        from datetime import datetime
        return int(datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp())
    except Exception:
        return 0


def _ver_key(name: str):
    """版本号数字段排序键（wine-8.0.2 / wine-991212 / 11.3.0 通用）"""
    return [int(x) for x in re.findall(r"\d+", name)]


def _fetch_text(url: str, timeout: int = 25) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8", "ignore")


def _head_last_modified(url: str) -> int:
    """文件 HEAD → Last-Modified（RFC1123）→ epoch 秒；失败返回 0"""
    try:
        import email.utils
        req = urllib.request.Request(url, headers={"User-Agent": UA}, method="HEAD")
        with urllib.request.urlopen(req, timeout=20) as resp:
            lm = resp.headers.get("Last-Modified")
            if lm:
                return int(email.utils.parsedate_to_datetime(lm).timestamp())
    except Exception:
        pass
    return 0


def collect_runtime_extra():
    """Wine / Wine Mono / Wine Gecko / Mono：官方目录源（补官方 GitHub release 缺口）。

    - Wine：dl.winehq.org/wine/source/ 正式版目录（wine-11.0 等 X.0 + rc）。
      winehq 主站与 gitlab.winehq.org 对云服务器 IP 均有反爬（Cloudflare/Anubis 实测）；
      wine-mirror/wine GitHub 镜像 tag 只镜像到 1999（wine-991212 等，不可用）；
      每周小版本无公开可达渠道，只收正式版（低频）。
    - Wine Mono / Wine Gecko：dl.winehq.org 版本目录 + 文件 HEAD Last-Modified
      （winehq 主站被拦但 dl 子域不受 Cloudflare 保护，目录无日期列，用文件头时间）。
    - Mono：download.mono-project.com 官方源码目录（Apache 行内日期）。
    低频项目（数月~数年一版）标记 low_freq，不受 180 天窗口过滤，只取最新 2~3 条。"""
    items = []

    # Wine + Wine Mono + Wine Gecko：dl.winehq.org 版本目录 + HEAD 日期
    for base, typ, pfx in [
        ("https://dl.winehq.org/wine/source/", "Wine", "wine"),
        ("https://dl.winehq.org/wine/wine-mono/", "Wine Mono", "wine-mono"),
        ("https://dl.winehq.org/wine/wine-gecko/", "Wine Gecko", "wine-gecko"),
    ]:
        try:
            html = _fetch_text(base)
            vers = sorted({m for m in re.findall(r'href="([0-9.]+(?:-rc[0-9]+)?)/"', html)},
                          key=_ver_key)
            for v in vers[-3:] if typ == "Wine" else vers[-2:]:
                ts = 0
                try:
                    vhtml = _fetch_text(base + v + "/", timeout=15)
                    files = re.findall(r'href="([^"]+\.(?:msi|xz))"', vhtml)
                    if files:
                        ts = _head_last_modified(base + v + "/" + files[0])
                except Exception:
                    ts = 0
                uid = hashlib.md5(f"runtime:{typ}:{v}".encode()).hexdigest()[:8]
                items.append({
                    "id": uid,
                    "title": f"[{typ}] {v}",
                    "url": base + v + "/",
                    "published_at": ts,
                    "category": "runtime",
                    "type": typ,
                    "summary": f"{pfx} {v} 官方发布（WineHQ 下载站）",
                    "low_freq": True,
                })
        except Exception as e:
            print(f"  {typ} 抓取失败: {e}", file=sys.stderr)

    # 4. Mono：官方源码目录（Apache 行内日期）
    try:
        html = _fetch_text("https://download.mono-project.com/sources/mono/")
        rows = re.findall(
            r'<a href="(mono-[0-9][0-9.]+\.tar\.xz)">[^<]*</a></td>\s*<td[^>]*>'
            r'([0-9]{4}-[0-9]{2}-[0-9]{2})',
            html,
        )
        seen = {}
        for f, d in rows:
            v = f[len("mono-"):-len(".tar.xz")]
            seen[v] = d
        for v in sorted(seen, key=_ver_key)[-2:]:
            ts = iso_epoch_iso(seen[v] + "T12:00:00Z") if seen[v] else 0
            uid = hashlib.md5(f"runtime:Mono:{v}".encode()).hexdigest()[:8]
            items.append({
                "id": uid,
                "title": f"[Mono] {v}",
                "url": f"https://download.mono-project.com/sources/mono/mono-{v}.tar.xz",
                "published_at": ts,
                "category": "runtime",
                "type": "Mono",
                "summary": "Mono 官方源码发布（mono-project.com）",
                "low_freq": True,
            })
    except Exception as e:
        print(f"  mono 抓取失败: {e}", file=sys.stderr)

    return items


def collect_official():
    """官方 GitHub releases → news items（category + type，App 端按 type 筛选）"""
    items = []
    for repo, cat, typ, mode in OFFICIAL_SOURCES:
        try:
            rels = fetch_github_releases(repo)
            if not isinstance(rels, list):
                continue
            for r in rels:
                tag = r.get("tag_name") or ""
                if not tag:
                    continue
                body = (r.get("body") or "").strip()
                summary = re.sub(r"\s+", " ", body)[:180] if body else "查看官方发布说明"
                ts = iso_epoch_iso(r.get("published_at") or r.get("created_at") or "")
                uid = hashlib.md5(f"{cat}:{repo}:{tag}".encode()).hexdigest()[:8]
                items.append({
                    "id": uid,
                    "title": f"[{typ}] {tag}",
                    "url": r.get("html_url") or f"https://github.com/{repo}/releases",
                    "published_at": ts,
                    "category": cat,
                    "type": typ,
                    "summary": summary,
                })
        except Exception as e:
            print(f"  {repo} 抓取失败: {e}", file=sys.stderr)
            continue
    return items


def main():
    fresh_ithome = []
    try:
        for it in parse_rss(fetch_rss()):
            cat = classify(it["title"], it["summary"])
            if cat == "drop":
                continue
            h = hashlib.md5(it["url"].encode()).hexdigest()[:8]
            fresh_ithome.append({
                "id": h,
                "title": it["title"][:120],
                "url": it["url"],
                "published_at": iso_epoch(it["pubDate"]),
                "category": cat,
                "summary": it["summary"][:160],
            })
    except Exception as e:
        print(f"IT之家 RSS 抓取失败（继续用官方源）: {e}", file=sys.stderr)

    fresh_official = collect_official()
    fresh_extra = collect_runtime_extra()

    # 与历史合并去重（url/repo+tag 指纹）；窗口：ithome 7 天，官方 release 180 天，
    # 目录/tag 低频源（low_freq）不过滤（Wine 每周、Wine Mono/Gecko/Mono 数月~数年一版）。
    old = []
    if os.path.exists(OUT):
        old = json.load(open(OUT, encoding="utf-8")).get("items", [])
    now = int(time.time())
    # 官方源不稳定（api.github.com 常被阻断/部分 repo 超时）：
    # 本次成功 ∪ 上次成功（按 id 去重），180 天窗口过滤兜底，避免组件/驱动
    # 资讯列表随网络波动反复清空。
    old_official = [it for it in old if it.get("type")]
    official_now = {it["id"]: it for it in old_official + fresh_official}.values()
    merged = {it["id"]: it for it in old + fresh_ithome + list(official_now) + fresh_extra}
    merged = [
        it for it in merged.values()
        if not (it.get("category") == "driver" and not it.get("type"))  # 清除历史 IT之家杂项误标 driver
        and (it.get("low_freq") or
             (now - it.get("published_at", 0) <= (180 * 86400 if it.get("type") else 7 * 86400)))
    ]
    # 低频目录源以本次抓取为准：旧的低频条目（Wine 2005 之类镜像残留）不在
    # fresh_extra 中则丢弃，避免过期低频版本长期占用列表。
    fresh_extra_ids = {it["id"] for it in fresh_extra}
    merged = [
        it for it in merged
        if not it.get("low_freq") or it["id"] in fresh_extra_ids
    ]
    merged.sort(key=lambda x: x.get("published_at", 0), reverse=True)
    # 官方 release 条目优先保留（180 天窗口内），避免被 300 条上限
    # 与 IT之家高频条目挤掉（用户反馈组件/运行库资讯时常为空）
    official_items = [it for it in merged if it.get("type")]
    other_items = [it for it in merged if not it.get("type")]
    merged = (official_items + other_items)[:300]

    data = {
        "version": 2,
        "updated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source": "ithome+github-releases+winehq-dl",
        "items": merged,
    }
    json.dump(data, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    json.dump(data, open(OFFLINE, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    print(f"ithome {len(fresh_ithome)} 条 + 官方 {len(fresh_official)} 条 + 目录源 {len(fresh_extra)} 条，合并后 {len(merged)} 条")
    print("分类分布:", dict(Counter(it["category"] for it in merged)))
    types = Counter(it.get("type") for it in merged if it.get("type"))
    print("官方类型分布:", dict(types))
    print(f"已写 {OUT} 与 assets 离线副本")


if __name__ == "__main__":
    main()
