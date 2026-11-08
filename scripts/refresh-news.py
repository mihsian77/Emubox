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
import datetime
import html as htmllib
import json
import os
import re
import subprocess
import sys
import time
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET
from collections import Counter

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO_ROOT, "news-data.json")
OFFLINE = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "news_offline.json")
UA = "EmuHub-CN news-refresh/1.3 (+https://github.com/mihsian77/EmuHub-CN)"

# ===== IT之家 官方 JSON 接口（资讯主源，Option A）=====
# 全站分页 newslistpageget：每页实返 25 条，page 递增；字段带 cid/sid（栏目ID）、
# image/imagelist（首图）、description（摘要）、commentcount（评论数）、isad（广告标记）。
# 注：接口不按栏目筛选（cid/sortId/channelId 参数全无效），只能抓全站后按 cid 本地分组；
# 且当前全站只覆盖 13 个 cid 栏目（业界/网络/微软/科学/数码之家/软件之家/苹果/智能时代/智车/促销/Win11）。
ITHOME_API = "https://m.ithome.com/api/news/newslistpageget"
API_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) Mobile/15E148"

# cid（栏目ID）→ 一级栏目（XX之家）：接口内容随最新滚动变化，cid 全集不固定，
# 每次抓取仅覆盖当前有更新的栏目；未在此表/未命中标题的条目走标题 fallback 兜底。
CID_CHANNEL = {
    31: "业界", 32: "网络", 49: "微软", 81: "科学探索",
    100: "数码之家", 103: "数码之家", 109: "软件之家", 150: "苹果",
    158: "智能时代", 160: "智车之家", 166: "数码之家", 183: "Win11", 192: "智车之家",
    34: "智能时代", 93: "数码之家", 99: "数码之家", 178: "数码之家", 82: "科学探索",
}
# 一级栏目 → 分区：device 设备 / emulator 模拟器宽覆盖 / tech 泛科技
CHANNEL_SECTION = {
    "数码之家": "device",
    "软件之家": "emulator", "Win11": "emulator", "微软": "emulator",
    "Linux": "emulator", "游戏电竞": "emulator", "Windows": "emulator",
    "苹果": "tech", "智能时代": "tech", "智车之家": "tech",
    "业界": "tech", "网络": "tech", "科学探索": "tech",
    "手机": "device", "数码": "device", "穿戴": "device", "VR": "device",
    "安卓": "device",
    "汽车": "tech", "鸿蒙": "tech",
}
# 标题关键词 → 分区 fallback（未映射 cid / 无 cid 时用；模拟器宽覆盖：游戏/Linux/软件工具都收）
SECTION_WORDS = [
    ("emulator", ["模拟器", "emulator", "RetroArch", "Cemu", "Ryujinx", "Yuzu", "Dolphin", "PCSX2",
                  "PPSSPP", "RPCS3", "Xenia", "Citra", "MAME", "DuckStation", "AetherSX2", "Vita3K",
                  "Skyline", "Winlator", "Wine", "Box64", "Proton", "DXVK", "VKD3D", "Mesa", "Rufus",
                  "Linux"]),
    ("device", ["手机", "安卓", "Android", "电脑", "平板", "折叠屏", "芯片", "骁龙", "天玑", "麒麟",
                "SoC", "GPU", "显卡", "处理器", "VR", "耳机", "显示器"]),
]


def section_by_title(text: str) -> str:
    t = text.lower()
    for sec, words in SECTION_WORDS:
        if any(w.lower() in t for w in words):
            return sec
    return "tech"


def parse_ithome_time(s) -> int:
    """IT之家 postdate/orderdate 为 ISO 字符串（'2026-10-31T15:32:29.743'），转 epoch 秒。
    接口内容随最新滚动变化，cid 全集不稳定，未映射栏目一律走标题 fallback。"""
    if not s:
        return 0
    if isinstance(s, (int, float)):
        return int(s)
    t = str(s)[:19]
    for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d"):
        try:
            return int(datetime.datetime.strptime(t, fmt).timestamp())
        except Exception:
            pass
    return 0


def fetch_ithome_api(max_pages: int = 12) -> list:
    """IT之家 官方 JSON 接口：翻页全站拉新闻，按 cid 分栏目/分区，带图/评论数/广告过滤。"""
    items = []
    for page in range(1, max_pages + 1):
        url = f"{ITHOME_API}?page={page}&pageSize=25"
        req = urllib.request.Request(url, headers={"User-Agent": API_UA, "Referer": "https://m.ithome.com/"})
        try:
            d = json.loads(urllib.request.urlopen(req, timeout=15).read())
        except Exception:
            break
        if not d.get("Success"):
            break
        for it in d.get("Result", []):
            if it.get("isad"):  # 官方广告标记直接丢弃
                continue
            title = hide_source(clean_text(it.get("title")))
            if not title or is_promo(title):
                continue
            cid = it.get("cid")
            channel = CID_CHANNEL.get(cid, "")
            u = (it.get("url") or it.get("WapNewsUrl") or "").strip()
            if u.startswith("//"):
                u = "https:" + u
            elif u.startswith("/"):
                u = "https://www.ithome.com" + u
            if not u or "ithome.com" not in u:
                continue
            img = it.get("image") or ""
            if not img and it.get("imagelist"):
                il = it["imagelist"]
                img = il[0] if isinstance(il, list) and il else ""
            if img.startswith("//"):
                img = "https:" + img
            section = CHANNEL_SECTION.get(channel, "") or section_by_title(title + " " + (it.get("description") or ""))
            ts = parse_ithome_time(it.get("postdate"))
            uid = hashlib.md5(u.encode()).hexdigest()[:8]
            items.append({
                "id": uid,
                "title": title[:120],
                "url": u,
                "published_at": ts,
                "category": section,          # 分区：device / emulator / tech
                "section": section, "channel": channel, "cid": cid,
                "summary": hide_source(clean_text(it.get("description"))[:160]),
                "image": img,
                "commentcount": it.get("commentcount", 0),
            })
        time.sleep(0.3)
    return items

# 模拟器区补全：官方 JSON 接口只返回最新 25 条（翻页/多次抓取全无效），且当前
# 不含软件/Linux/游戏栏目；模拟器新作区用栏目页数据补全（用户确认，只补模拟器区）。
# 2026-11-02 全站栏目清点（移动版 27 tab 实测 19 个栏目页可抓）后重筛：
# - 移除 auto（智能汽车）：栏目内容全是智车/新车资讯，与模拟器/驱动项目无关；
# - 新增 android（安卓）、harmonyos（鸿蒙）：安卓系统层动态与模拟器强相关，
#   鸿蒙归泛科技生态参考；
# - 其余可用栏目（science/discovery 科普、internet 网络焦点、it 行业前沿、ai、
#   pc 电脑等）与项目无关，不抓。
SOFT_COLUMNS = {
    "soft": "软件之家",
    "linux": "Linux",
    "game": "游戏电竞",
    "win11": "Windows",
    "microsoft": "微软",
    "apple": "苹果",
    "phone": "手机",
    "digi": "数码",
    "android": "安卓",
    "harmonyos": "鸿蒙",
    "wearable": "穿戴",
    "vr": "VR",
}


def _soft_time(s: str, now: datetime.datetime) -> int:
    """栏目页时间转 epoch：支持“10月24日”“12:04”“昨日 23:00”。
    无年份按当前年，日期在未来则回退一年；解析失败返 0。"""
    s = (s or "").strip()
    mm = re.match(r"(\d{1,2})月(\d{1,2})日", s)
    if mm:
        y, mo, d = now.year, int(mm.group(1)), int(mm.group(2))
        dt = datetime.datetime(y, mo, d)
        if dt > now:
            dt = datetime.datetime(y - 1, mo, d)
        return int(dt.timestamp())
    yd = re.match(r"昨日\s*(\d{1,2}):(\d{1,2})", s)
    if yd:
        yt = now - datetime.timedelta(days=1)
        dt = datetime.datetime(yt.year, yt.month, yt.day, int(yd.group(1)), int(yd.group(2)))
        return int(dt.timestamp())
    hm = re.match(r"(\d{1,2}):(\d{1,2})", s)
    if hm:
        dt = datetime.datetime(now.year, now.month, now.day, int(hm.group(1)), int(hm.group(2)))
        return int(dt.timestamp())
    return 0


def fetch_ithome_soft() -> list:
    """抓 IT之家 模拟器相关栏目页，解析文章归入 emulator 区（带图/时间/评论数）。"""
    items = []
    now = datetime.datetime.now()
    for col, label in SOFT_COLUMNS.items():
        url = f"https://m.ithome.com/{col}/"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": API_UA, "Referer": "https://m.ithome.com/"})
            raw = urllib.request.urlopen(req, timeout=15).read().decode("utf-8", "ignore")
        except Exception as e:
            print(f"{label} 栏目抓取失败: {e}", file=sys.stderr)
            continue
        for m in re.finditer(r'data-news-id="(\d+)"', raw):
            end = raw.find("</a>", m.end())
            if end < 0:
                continue
            block = raw[m.start():end + 4]
            hm = re.search(r'href="(https://m\.ithome\.com/html/\d+\.htm)"', block)
            tm = re.search(r'class="plc-title">(.*?)</p>', block, re.S)
            if not hm or not tm:
                continue
            title = clean_text(tm.group(1))
            if not title or is_promo(title):
                continue
            u = hm.group(1)
            im = re.search(r'data-original="([^"]+)"', block)
            tmt = re.search(r'class="post-time">(.*?)</span>', block)
            rm = re.search(r'class="review-num">(\d+)', block)
            sec = CHANNEL_SECTION.get(label, "tech")
            items.append({
                "id": hashlib.md5(u.encode()).hexdigest()[:8],
                "title": title[:120],
                "url": u,
                "published_at": _soft_time(tmt.group(1) if tmt else "", now),
                "category": sec,
                "section": sec, "channel": label, "cid": None,
                "summary": "", "image": im.group(1) if im else "",
                "commentcount": int(rm.group(1)) if rm else 0,
            })
        time.sleep(0.3)
    return items

# 明确无关分类：命中即丢弃（汽车/家电与项目完全无关）
DROP_WORDS = [
    "汽车", "车", "新能源车", "电动车", "自动驾驶", "特斯拉", "比亚迪", "蔚来", "理想汽车", "小米汽车",
    "家电", "冰箱", "洗衣机", "空调", "电视", "扫地机", "智能家居", "厨电", "烤箱",
]
# 模拟器相关（只保留模拟器本身/知名模拟器名，避免"NS/PS5/Switch/手游"等
# 主机名与泛词把游戏发售、硬件、系统新闻误标进模拟器区）
EMU_WORDS = [
    "模拟器", "emulator", "RetroArch", "Cemu", "Ryujinx", "Yuzu", "Dolphin", "PCSX2", "PPSSPP",
    "RPCS3", "Xenia", "Citra", "MAME", "DuckStation", "AetherSX2", "Vita3K", "Skyline", "Mupen",
    "Winlator",
]
# 纯促销导购条目：标题特征命中即整条丢弃（红包/秒杀/价格对比/国补价等）
# 后三条是补漏：实测「京东自营 46.9 元：卡夫多款洗发水 12.9 元/斤闭眼囤」被归进
# device/数码之家，成了设备页「芯片与设备动态」的第一条；「无糖速度囤：屈臣氏
# 电解质水 480ml 官方 1.5 元」同款。原来只管红包/秒杀那类字眼，不管「囤、好价、
# 到手价」这类写法。要求促销词与价格同现，避免误伤「新机发布 369 元」这类报道。
PROMO_DROP_RE = re.compile(
    r"(红包|秒杀|速抢|蹲点|券后|领券|抽红包|免费领取|直降|返场|新低|黑五|11\.11|双 ?11)"
    r"|(联合会员|PLUS会员|超级会员).*\d+\s*元"
    r"|^\d+\s*[→\-]\s*\d+\s*元"
    r"|^国补后?\s*\d+"
    r"|(京东自营|到手价|折合|折算).{0,20}\d+(\.\d+)?\s*元"
    r"|\d+(\.\d+)?\s*元\s*/\s*(斤|克|千克|瓶|盒|包|件|袋)"
    r"|(囤|好价|闭眼|凑单|满减|包邮|特价|清仓|百亿补贴).{0,24}\d+(\.\d+)?\s*元"
)


def is_promo(title: str) -> bool:
    """纯促销导购标题判定：命中即不入资讯池。"""
    return bool(PROMO_DROP_RE.search(title))
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

# 官方组件源：repo / category / type / mode（release=GitHub release，tag=只用 tag）
# 分类口径（2026-11 修订）：App 组件页 8 个类型全部归 component，与「运行库」区分——
#   运行库指 Windows 运行库（VC++/.NET/DirectX），数据来自 winlator-contents 的
#   components.json，不走 GitHub release，因此这里不再产生 runtime 条目。
# type 名必须与 App 组件页的类型名逐字一致，否则该区块仍然是空的。
# 注：wine 官方公告站（winehq.org）对云服务器 IP 有 Cloudflare 拦截（403，实测），
# wine-mirror/wine 无 release 对象；wine 本体小版本改走 dl.winehq.org 源码目录源
# （见 collect_runtime_extra）；wine-staging 有 GitHub release（补完整日志维度）。
OFFICIAL_SOURCES = [
    # (repo, category, type, mode, 标题前缀)
    ("doitsujin/dxvk", "component", "DXVK", "release", "DXVK"),
    ("HansKristian-Work/vkd3d-proton", "component", "VKD3D", "release", "VKD3D-Proton"),
    ("WinterSnowfall/d7vk", "component", "D7VK", "release", "D7VK"),
    ("FEX-Emu/FEX", "component", "FEXCore", "release", "FEX"),
    ("ptitSeb/box64", "component", "Box64", "release", "Box64"),
    ("butilly/box64", "component", "WOWBox64", "tag", "WOWBox64"),
    ("ValveSoftware/Proton", "component", "Proton", "release", "Proton"),
    ("GloriousEggroll/proton-ge-custom", "component", "Proton", "release", "GE-Proton"),
    # 驱动官方源（driver 分类专属；与 driver-registry.json 同名仓库对齐）
    ("K11MCH1/AdrenoToolsDrivers", "driver", "K11MCH1 AdrenoTools", "release", "K11MCH1 AdrenoTools"),
    ("StevenMXZ/Adrenotools-Drivers", "driver", "StevenMXZ Adrenotools", "release", "StevenMXZ Adrenotools"),
    ("MrPurple666/purple-turnip", "driver", "Purple-Turnip", "release", "Purple-Turnip"),
    ("faux123/turnip-faux123-driver", "driver", "faux123 Turnip", "release", "faux123 Turnip"),
    ("faux123/panvk-faux123-driver-ace", "driver", "faux123 PanVK", "release", "faux123 PanVK"),
    ("xodiosx/mesa-panvk-g57", "driver", "xodiosx PanVK", "release", "xodiosx PanVK"),
    ("adesyaefull/panvk-kbase-android-g925-mesa27", "driver", "adesyaefull PanVK G925", "release", "adesyaefull PanVK G925"),
    ("isygold/panDXVK", "driver", "panDXVK", "release", "panDXVK"),
    ("JimVulkan/radv-xclipse", "driver", "RADV Xclipse", "release", "RADV Xclipse"),
]

CATS = {
    "emulator": "模拟器", "driver": "驱动", "component": "组件",
    "runtime": "运行库", "eco": "生态", "general": "泛科技",
}

# 组件类型白名单：必须与 App 组件页 8 个区块逐字一致
COMPONENT_TYPES = {
    "Wine", "Proton", "Box64", "WOWBox64", "DXVK", "FEXCore", "VKD3D", "D7VK",
    # 历史名 / 附加项（Wine 自带运行库），一并归组件区
    "VKD3D-Proton", "Proton-GE", "Wine Mono", "Wine Gecko", "Mono",
}
# 历史 type → 现行 type（旧数据原地改名，不丢条目）
TYPE_ALIAS = {"VKD3D-Proton": "VKD3D", "Proton-GE": "Proton"}

# 资讯具体主题标签（App 新闻卡显示用，替代大类名；按顺序首个命中为准）
TAG_RULES = [
    ("模拟器", ["模拟器", "RetroArch", "Cemu", "Ryujinx", "Yuzu", "Dolphin", "PCSX2", "PPSSPP",
                "RPCS3", "Xenia", "Citra", "MAME", "DuckStation", "AetherSX2", "Vita3K", "Winlator"]),
    ("系统", ["Windows", "Win11", "Win10", "预览版", "鸿蒙", "HarmonyOS", "iOS", "Android", "系统更新"]),
    ("芯片", ["骁龙", "天玑", "麒麟", "Exynos", "Tensor", "芯片", "处理器", "SoC", "工艺",
              "制程", "RISC-V", "CPU", "晶圆", "光刻"]),
    ("GPU", ["GPU", "显卡", "Vulkan", "Mesa", "图形", "渲染"]),
    ("手机", ["手机", "iPhone", "折叠屏", "平板", "首发", "开售"]),
    ("游戏", ["游戏", "PS5", "Switch", "Xbox", "任天堂", "Steam", "Epic", "手游", "主机"]),
    ("AI", ["AI", "大模型", "智能", "机器人", "机器学习", "生成式"]),
    ("安全", ["安全", "漏洞", "攻击", "勒索", "泄露", "钓鱼"]),
    ("应用", ["应用", "App", "软件", "会员", "云服务"]),
    ("硬件", ["硬盘", "内存", "机箱", "外设", "显示器", "耳机", "路由器", "键盘", "鼠标", "充电器"]),
]


def extract_tag(title: str, summary: str) -> str:
    """按标题+摘要提取具体主题标签（无命中归"科技"）。"""
    text = (title + " " + summary).lower()
    for tag, words in TAG_RULES:
        if any(w.lower() in text for w in words):
            return tag
    return "科技"


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
    """去标签并解码 HTML 实体（`&#183;` → `·`、`&nbsp;` → 空格）。

    原来只硬编码替换 &nbsp;/&amp; 两个，其余实体（`&#183;`、`&#165;`、`&quot;`）
    会原样落库，App 的 Text 又不做 HTML 解析，界面上直接露出「酷狗音乐 22 周年
    &#183; 鸿蒙特别版」这种字符串。统一走 html.unescape，脚本端不再维护实体表。
    """
    return htmllib.unescape(re.sub(r"<[^>]+>", "", s)).replace("\u00a0", " ").strip()


def clean_text(s: str) -> str:
    """标题 / 摘要通用清洗：去标签、解 HTML 实体、折叠空白。

    接口和栏目页返回的标题都带实体（`&#165;` 是 ¥、`&#183;` 是 ·），原来只做
    strip()，实体直接落库。App 端虽然也解了一遍，但数据本身干净更好排障，
    而且 is_promo / norm_title 都依赖标题文本，带实体容易漏判。
    """
    s = re.sub(r"<[^>]+>", "", s or "")
    return re.sub(r"\s+", " ", htmllib.unescape(s)).strip()


def norm_title(t: str) -> str:
    """标题归一化，只用于判重：全角转半角、去空白与标点、统一小写。

    同一篇稿子被「数码之家」「手机」等多个栏目收录时 url 不同，而 id = md5(url)[:8]，
    于是同一条新闻会各存一份（「荣耀 WIN2 联名款」在设备页出现两次就是这么来的）。
    按 url 去重挡不住，需要按标题再收一遍。
    """
    s = unicodedata.normalize("NFKC", t or "").lower()
    return re.sub(r"[\W_]+", "", s)


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
        # IT之家移动版改版后正文容器由 post_content 改为 news-content，
        # 旧类名找不到导致所有新文章正文全空、每次运行都全量重抓。两个都试。
        i = html.find('class="post_content')
        if i < 0:
            i = html.find('class="news-content')
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
        seg = htmllib.unescape(seg).replace("\u00a0", " ")
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


# 本次抓取失败的目录源类型：失败时保留上一轮条目，避免一次网络抖动把整条线清空
EXTRA_FAILED = set()


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
        EXTRA_FAILED.add("Wine")
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
                    "category": "component",
                    "type": typ,
                    "summary": f"{pfx} {v} 官方发布（WineHQ 下载站）",
                    "low_freq": True,
                })
        except Exception as e:
            EXTRA_FAILED.add(typ)
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
                "category": "component",
                "type": "Mono",
                "summary": "Mono 官方源码发布（mono-project.com）",
                "low_freq": True,
            })
    except Exception as e:
        EXTRA_FAILED.add("Mono")
        print(f"  mono 抓取失败: {e}", file=sys.stderr)

    return items


def fetch_github_tags(repo: str, limit: int = 3):
    """只打 tag、不发 release 的仓库（WOWBox64 这类同步打包仓库）。

    GitHub tags 接口不带时间，需要再查一次 commit 拿 committer date；
    每个仓最多取 limit 个 tag，控制 API 调用量。
    """
    tags = github_get(f"https://api.github.com/repos/{repo}/tags?per_page={limit}")
    out = []
    for t in (tags if isinstance(tags, list) else [])[:limit]:
        sha = (t.get("commit") or {}).get("sha", "")
        ts = ""
        try:
            c = github_get(f"https://api.github.com/repos/{repo}/commits/{sha}")
            ts = ((c.get("commit") or {}).get("committer") or {}).get("date", "")
        except Exception:
            ts = ""
        out.append({
            "tag_name": t.get("name", ""),
            "html_url": f"https://github.com/{repo}/tree/{t.get('name', '')}",
            "published_at": ts,
            "body": "",
        })
    return out


def collect_official():
    """官方 GitHub releases → news items（category + type，App 端按 type 筛选）"""
    items = []
    for repo, cat, typ, mode, label in OFFICIAL_SOURCES:
        try:
            rels = fetch_github_tags(repo) if mode == "tag" else fetch_github_releases(repo)
            if not isinstance(rels, list):
                continue
            for r in rels:
                tag = r.get("tag_name") or ""
                if not tag:
                    continue
                body = (r.get("body") or "").strip()
                summary = re.sub(r"\s+", " ", body)[:180] if body else (
                    "上游只打 tag、不发 release，点开看该版本源码" if mode == "tag" else "查看官方发布说明")
                ts = iso_epoch_iso(r.get("published_at") or r.get("created_at") or "")
                uid = hashlib.md5(f"{cat}:{repo}:{tag}".encode()).hexdigest()[:8]
                items.append({
                    "id": uid,
                    "title": f"[{label}] {tag}",
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
        api_items = fetch_ithome_api(max_pages=12) + fetch_ithome_soft()
        # 正文抓取范围：emulator/device 全量 + tech 最新 20 条（控制 CI 时长与反爬频率）
        body_targets = [it for it in api_items if it["category"] in ("emulator", "device")][:60]
        body_targets += [it for it in api_items if it["category"] == "tech"][:20]
        body_map = {}
        for it in body_targets:
            b = fetch_news_body(it["url"])
            if b:
                body_map[it["url"]] = b
            time.sleep(0.3)
        fresh_news = [{**it, "body": body_map.get(it["url"], "")} for it in api_items]
    except Exception as e:
        print(f"资讯官方接口抓取失败（继续用官方源）: {e}", file=sys.stderr)

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
    # 资讯源已切换官方接口：历史 RSS 资讯（无 type、无 section/cid）口径不符且无栏目，丢弃；
    # 历史官方组件/运行库（type 条目）与 fresh_official 合并保留。
    merged = {it["id"]: it for it in fresh_news + list(official_now) + fresh_extra}
    merged = [
        it for it in merged.values()
        if not (it.get("category") == "driver" and not it.get("type"))  # 清除历史杂项误标 driver
        and (it.get("low_freq") or
             (now - it.get("published_at", 0) <= (180 * 86400 if it.get("type") else 7 * 86400)))
    ]
    # 低频目录源以本次抓取为准：旧的低频条目（Wine 2005 之类镜像残留）不在
    # fresh_extra 中则丢弃，避免过期低频版本长期占用列表。
    fresh_extra_ids = {it["id"] for it in fresh_extra}
    merged = [
        it for it in merged
        if not it.get("low_freq") or it["id"] in fresh_extra_ids
        or it.get("type") in EXTRA_FAILED  # 本类型本次抓取失败 → 保留上一轮，不清空
    ]
    # 分类口径迁移（2026-11）：组件页 8 类一律 component；运行库不再由 GitHub 源产生。
    # 同时把历史 type 名改成现行名（VKD3D-Proton→VKD3D、Proton-GE→Proton），
    # 否则旧条目落在旧名下，App 的组件区块按新名筛选时看不到。
    merged = [
        {**it, "type": TYPE_ALIAS.get(it["type"], it["type"]), "category": "component",
         "title": it.get("title", "").replace("[Proton-GE]", "[GE-Proton]")}
        if it.get("type") in COMPONENT_TYPES else it
        for it in merged
    ]
    merged.sort(key=lambda x: x.get("published_at", 0), reverse=True)
    # 资讯来自官方接口，已按 cid 分好 section/category；不再用旧词表 classify 重分类，
    # 否则智车之家（归泛科技）等栏目会被 DROP_WORDS 误删。
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
    # 标题级二级去重：不同栏目收录同一篇稿子时 url 不同，上面那层挡不住。
    # 保留「正文更全 → 时间更新」的那条；官方 release 条目不参与（同名 tag 会误杀）。
    best_by_title = {}
    for it in merged:
        if it.get("type"):
            continue
        key = norm_title(it.get("title", ""))
        if not key:
            continue
        prev = best_by_title.get(key)
        if prev is None:
            best_by_title[key] = it
        elif (bool(it.get("body")), it.get("published_at", 0)) > (bool(prev.get("body")), prev.get("published_at", 0)):
            best_by_title[key] = it
    before = len(merged)
    merged = [
        it for it in merged
        if it.get("type")
        or not norm_title(it.get("title", ""))
        or best_by_title.get(norm_title(it.get("title", ""))) is it
    ]
    if before != len(merged):
        print(f"标题级去重：{before} → {len(merged)} 条（-{before - len(merged)}）", file=sys.stderr)
    # 历史资讯条目曾因旧解析器（嵌套 div 提前截断）抓不到正文，这里对 body 为空的
    # 资讯条目补抓一次完整正文（增量：已非空的跳过）。
    # 旧库同 id（= 同 url）条目已抓到的正文直接继承：merged 每次以 fresh_news
    # 重建，旧条目的 body 不会自动带过来；不继承的话每次运行都要全量重抓一遍。
    old_body = {it["id"]: it["body"] for it in old
                if it.get("body") and "ithome.com" in it.get("url", "")}
    inherited = 0
    for it in merged:
        if not it.get("body") and it["id"] in old_body:
            b = old_body[it["id"]]
            it["body"] = b
            it["summary"] = it.get("summary") or b[:180]
            inherited += 1
    if inherited:
        print(f"从旧库继承正文 {inherited} 条", file=sys.stderr)
    # 剩余仍无正文的（新文章或正文源变了）才补抓一次。
    missing = [it for it in merged if "ithome.com" in it.get("url", "") and not it.get("body")]
    if missing:
        print(f"补抓 {len(missing)} 条资讯正文（历史条目旧解析器未抓到）…", file=sys.stderr)
        for it in missing:
            b = fetch_news_body(it["url"])
            if b:
                it["body"] = b
                it["summary"] = it.get("summary") or b[:180]
    # 纯促销导购标题（红包/秒杀/价格对比/国补价等）整条丢弃，不占资讯位
    merged = [
        it for it in merged
        if not ("ithome.com" in it.get("url", "") and is_promo(it.get("title", "")))
    ]
    # 汽车/家电类整条丢弃：栏目页已移除 auto（智车），但全站接口流与鸿蒙等栏目
    # 仍会带进车企战报/家电稿（实测「北汽新能源享界汽车战报」混进鸿蒙栏目）。
    # 命中无关词但同时命中模拟器词的保留，避免误杀赛车游戏/模拟器相关稿。
    def _car_drop(it) -> bool:
        if "ithome.com" not in it.get("url", "") or it.get("type"):
            return False
        title = it.get("title", "")
        if any(w.lower() in title.lower() for w in EMU_WORDS):
            return False
        return any(w in title for w in DROP_WORDS)
    dropped = sum(1 for it in merged if _car_drop(it))
    if dropped:
        print(f"汽车/家电无关稿丢弃 {dropped} 条", file=sys.stderr)
    merged = [it for it in merged if not _car_drop(it)]
    # 历史条目标题/摘要可能是旧解析器未清洗的，统一再清洗一次来源站名字样，
    # 摘要里促销导购片段（直达链接/红包/领券）截掉；资讯条目加具体主题标签（tag）。
    for it in merged:
        if "ithome.com" in it.get("url", ""):
            # 最后一道兜底：历史条目可能带着旧解析器留下的实体或标签
            it["title"] = hide_source(clean_text(it.get("title", "")))
            it["summary"] = trim_promo(hide_source(clean_text(it.get("summary", ""))))
            if not it.get("type"):
                it["tag"] = extract_tag(it.get("title", ""), it.get("summary", ""))
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
