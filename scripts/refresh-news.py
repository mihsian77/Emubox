"""刷新 news-data.json：资讯源（模拟器/生态/泛科技）+ 组件与运行库官方 GitHub releases。

数据源：
- 资讯 RSS：仅供 emulator / eco / general。该源几乎不报道 DXVK/Wine 类生态，
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
# 分类口径：DXVK/VKD3D-Proton 属组件区；Wine/Box64/Proton-GE/Wine Mono/Wine Gecko/Mono 属运行库区。
# 注：wine 官方公告站（winehq.org）对云服务器 IP 有 Cloudflare 拦截（403，实测），
# wine-mirror/wine 无 release 对象；wine 本体小版本改走 dl.winehq.org 源码目录源
# （见 collect_runtime_extra）；wine-staging 有 GitHub release（补完整日志维度）。
OFFICIAL_SOURCES = [
    ("doitsujin/dxvk", "component", "DXVK", "release"),
    ("HansKristian-Work/vkd3d-proton", "component", "VKD3D-Proton", "release"),
    ("ptitSeb/box64", "runtime", "Box64", "release"),
    ("GloriousEggroll/proton-ge-custom", "runtime", "Proton-GE", "release"),
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
    # 注意：不再从资讯 RSS 产生 driver 分类（词表过宽会把散热器/耳机等
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


def hide_source(s: str) -> str:
    """展示文本统一去掉来源站名（含带空格的写法，作为隐藏源不暴露）。"""
    return re.sub(r"IT\s*之家\s*", "", s).strip()


PROMO_SPLIT = re.compile(r"直达链接|点此抽红包|点此领券|券后|无门槛红包|领券|红包")


def trim_promo(s: str) -> str:
    """摘要里的促销导购片段（直达链接/红包/领券等）之后的文字去掉，保留有效信息。"""
    return PROMO_SPLIT.split(s)[0].strip().rstrip("：:。 ")


def fetch_news_body(url: str) -> str:
    """抓文章正文（post_content 容器 → 纯文本，div 深度计数取完整容器），供 App 内全文阅读；失败返回空串。"""
    try:
        html = _fetch_text(url, timeout=15)
        i = html.find('class="post_content')
        if i < 0:
            return ""
        start = html.rfind("<div", 0, i + 1)
        if start < 0:
            start = i
        # div 深度计数：正文容器可能内嵌 div（如投稿致谢行），按深度取到容器真正闭合
        depth = 0
        p = start
        seg = html[start:]
        while p < len(html):
            nxt = re.search(r"<div[\s>]|</div>", html[p:])
            if not nxt:
                break
            tag = html[p + nxt.start():p + nxt.end()]
            if tag.startswith("</"):
                depth -= 1
                if depth == 0:
                    seg = html[start:p + nxt.end()]
                    break
            else:
                depth += 1
            p += nxt.end()
        seg = re.sub(r"<script.*?</script>", "", seg, flags=re.S)
        seg = re.sub(r"<style.*?</style>", "", seg, flags=re.S)
        seg = re.sub(r"<!--.*?-->", "", seg, flags=re.S)
        seg = re.sub(r"<br\s*/?>", "\n", seg)
        seg = re.sub(r"</p>", "\n", seg)
        seg = re.sub(r"<[^>]+>", "", seg)
        for ent in ("&nbsp;", "&amp;", "&quot;", "&#39;", "&lt;", "&gt;"):
            seg = seg.replace(ent, {"&nbsp;": " ", "&amp;": "&", "&quot;": '"',
                                    "&#39;": "'", "&lt;": "<", "&gt;": ">"}[ent])
        seg = re.sub(r"\n{3,}", "\n\n", seg).strip()
        # 去掉投稿致谢/关注引导等模板噪声行（非正文内容）
        seg = re.sub(r"(?m)^(点此|关注我们|微信关注|更多阅读|责任编辑|未经授权|欢迎关注|"
                     r"扫描二维码|点击上方|加星标|设为星标|感谢\S*网友|.*直达链接|.*广告声明.*|"
                     r"京东.*(券|红包)|淘宝.*红包|.*点此抽红包|.*券后.*|.*点击(下方卡片|宝贝|弹出页面).*|"
                     r".*点此领券.*|.*领券.*)$", "", seg)
        return hide_source(re.sub(r"\n{2,}", "\n", seg).strip())
    except Exception:
        return ""


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
        items.append({"title": hide_source(t.strip()), "url": link.strip(), "summary": hide_source(desc), "pubDate": pub})
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


def _fetch_text(url: str, timeout: int = 25, ua: str = None) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": ua or UA})
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

    - Wine：dl.winehq.org/wine/source/{10,11}.x/wine-{v}.tar.xz 小版本源码文件源
      （winehq 主站被 Cloudflare 拦但 dl 子域可直连；小版本 tar.xz 文件名即版本号，
      HEAD Last-Modified 为发布时间）——覆盖用户"中间小版本缺失"问题。
    - Wine Mono / Wine Gecko：dl.winehq.org 版本目录 + 文件 HEAD Last-Modified。
    - Mono：download.mono-project.com 官方源码目录（Apache 行内日期）。
    低频项目（数月~数年一版）标记 low_freq，不受 180 天窗口过滤，只取最新 2~3 条。
    分类口径：DXVK/VKD3D-Proton 属组件区（category=component，App 组件 Tab 推荐必备组件）；
    Wine / Box64 / Proton-GE / Wine Mono / Wine Gecko / Mono 属运行库区（category=runtime，App 运行库 Tab + 支撑库）。"""
    items = []

    # Wine 小版本：10.x / 11.x 系列源码文件（wine-{小版本}.tar.xz），取最近若干条
    # dl.winehq.org 对非浏览器 UA 有响应延迟/超时，此处用浏览器 UA
    BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
    try:
        wine_series = []  # (ver, url)
        for series in ("11.x", "10.x"):
            html = _fetch_text(f"https://dl.winehq.org/wine/source/{series}/", timeout=20, ua=BROWSER_UA)
            # href 在页面里每个文件出现两次（图标+文字），set 去重
            for m in sorted({m for m in re.findall(r'href="(wine-[0-9]+\.[0-9]+\.tar\.xz)"', html)}):
                ver = m[len("wine-"):-len(".tar.xz")]
                wine_series.append((ver, f"https://dl.winehq.org/wine/source/{series}/{m}"))
        # 系列内按版本排序，11.x 全取（每周开发版），10.x 取最近 2 个
        v11 = sorted([(v, u) for v, u in wine_series if v.startswith("11.")], key=lambda x: _ver_key(x[0]))
        v10 = sorted([(v, u) for v, u in wine_series if v.startswith("10.")], key=lambda x: _ver_key(x[0]))
        for v, u in v11[-12:] + v10[-2:]:
            ts = _head_last_modified(u)
            uid = hashlib.md5(f"runtime:Wine:{v}".encode()).hexdigest()[:8]
            items.append({
                "id": uid,
                "title": f"[Wine] {v}",
                "url": u,
                "published_at": ts,
                "category": "component",
                "type": "Wine",
                "summary": f"Wine {v} 开发版源码发布（WineHQ 下载站）",
                "low_freq": True,
            })
    except Exception as e:
        print(f"  Wine 小版本抓取失败: {e}", file=sys.stderr)

    # Wine Mono / Wine Gecko：dl.winehq.org 版本目录 + HEAD 日期（低频）
    for base, typ, pfx in [
        ("https://dl.winehq.org/wine/wine-mono/", "Wine Mono", "wine-mono"),
        ("https://dl.winehq.org/wine/wine-gecko/", "Wine Gecko", "wine-gecko"),
    ]:
        try:
            html = _fetch_text(base)
            vers = sorted({m for m in re.findall(r'href="([0-9.]+(?:-rc[0-9]+)?)/"', html)},
                          key=_ver_key)
            for v in vers[-2:]:
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

    # 4. Mono：官方源码目录（Apache 行内日期）——归运行库区
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
                    # 完整 release 正文（详情弹窗全文阅读用，不截断）
                    "body": body,
                })
        except Exception as e:
            print(f"  {repo} 抓取失败: {e}", file=sys.stderr)
            continue
    return items


def main():
    fresh_news = []
    try:
        rss_items = [it for it in parse_rss(fetch_rss())
                     if classify(it["title"], it["summary"]) != "drop"]
        # 正文抓取范围：emulator/eco 全量 + general 最新 20 条（控制 CI 时长与反爬频率）
        body_targets = [it for it in rss_items
                        if classify(it["title"], it["summary"]) in ("emulator", "eco")][:60]
        body_targets += [it for it in rss_items
                         if classify(it["title"], it["summary"]) == "general"][:20]
        body_map = {}
        for it in body_targets:
            b = fetch_news_body(it["url"])
            if b:
                body_map[it["url"]] = b
            time.sleep(0.3)
        for it in rss_items:
            cat = classify(it["title"], it["summary"])
            h = hashlib.md5(it["url"].encode()).hexdigest()[:8]
            fresh_news.append({
                "id": h,
                "title": it["title"][:120],
                "url": it["url"],
                "published_at": iso_epoch(it["pubDate"]),
                "category": cat,
                "summary": it["summary"][:160],
                "body": body_map.get(it["url"], ""),
            })
    except Exception as e:
        print(f"资讯 RSS 抓取失败（继续用官方源）: {e}", file=sys.stderr)

    fresh_official = collect_official()
    fresh_extra = collect_runtime_extra()

    # 与历史合并去重（url/repo+tag 指纹）；窗口：资讯 7 天，官方 release 180 天，
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
    merged = {it["id"]: it for it in old + fresh_news + list(official_now) + fresh_extra}
    merged = [
        it for it in merged.values()
        if not (it.get("category") == "driver" and not it.get("type"))  # 清除历史杂项误标 driver
        and not (it.get("type") == "Box64" and it.get("category") == "component")  # Box64 归运行库，清历史 component 重复
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
    # 分类口径迁移：DXVK/VKD3D-Proton 归组件；Wine/Box64/Proton-GE/Wine Mono/Wine Gecko/Mono 归运行库
    # （历史旧数据原地迁移，不丢条目）
    merged = [
        {**it, "category": "component"} if it.get("type") in ("DXVK", "VKD3D-Proton") else
        {**it, "category": "runtime"} if it.get("type") in ("Wine", "Box64", "Proton-GE", "Wine Mono", "Wine Gecko", "Mono") else it
        for it in merged
    ]
    merged.sort(key=lambda x: x.get("published_at", 0), reverse=True)
    # 同 url 去重：历史条目分类迁移（component→runtime）与新抓取因 id 含分类名而
    # 各自生成一条，同一 release 会重复收录。按 url 去重，body 非空（新抓）优先保留。
    seen_urls = set()
    dedup = []
    for it in merged:
        u = it.get("url") or ""
        if not u:
            dedup.append(it)
            continue
        if u in seen_urls:
            for i, prev in enumerate(dedup):
                if prev.get("url") == u and not prev.get("body") and it.get("body"):
                    dedup[i] = it
                    break
            continue
        seen_urls.add(u)
        dedup.append(it)
    merged = dedup
    # 历史资讯条目曾因旧解析器（嵌套 div 提前截断）抓不到正文，这里对 body 为空的
    # 资讯条目补抓一次完整正文（增量：已非空的跳过）。
    missing = [it for it in merged if "ithome.com" in it.get("url", "") and not it.get("body")]
    if missing:
        print(f"补抓 {len(missing)} 条资讯正文（历史条目旧解析器未抓到）…", file=sys.stderr)
        for it in missing:
            b = fetch_news_body(it["url"])
            if b:
                it["body"] = b
                it["summary"] = it.get("summary") or b[:180]
    # 历史条目标题/摘要可能是旧解析器未清洗的，统一再清洗一次来源站名字样，
    # 摘要里促销导购片段（直达链接/红包/领券）截掉。
    for it in merged:
        if "ithome.com" in it.get("url", ""):
            it["title"] = hide_source(it.get("title", ""))
            it["summary"] = trim_promo(hide_source(it.get("summary", "")))
    # 官方 release 条目优先保留（180 天窗口内），避免被 300 条上限
    # 与高频资讯条目挤掉（用户反馈组件/运行库资讯时常为空）
    official_items = [it for it in merged if it.get("type")]
    other_items = [it for it in merged if not it.get("type")]
    merged = (official_items + other_items)[:300]

    data = {
        "version": 3,
        "updated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source": "aggregated",
        "items": merged,
    }
    json.dump(data, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    json.dump(data, open(OFFLINE, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    print(f"资讯 {len(fresh_news)} 条 + 官方 {len(fresh_official)} 条 + 目录源 {len(fresh_extra)} 条，合并后 {len(merged)} 条")
    print("分类分布:", dict(Counter(it["category"] for it in merged)))
    types = Counter(it.get("type") for it in merged if it.get("type"))
    print("官方类型分布:", dict(types))
    print(f"已写 {OUT} 与 assets 离线副本")


if __name__ == "__main__":
    main()
