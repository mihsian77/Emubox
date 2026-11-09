#!/usr/bin/env python3
"""证据 → 字段 的规则引擎（纯函数，无网络）。

设计原则：
1. **只从项目自带的证据推**，不靠人工印象。每条判定都返回命中的证据原文，
   写进 `_evidence`，出错时能一眼看出是哪条规则判的。
2. **规则集中在这一个文件**。迁移老数据、抓新项目、Action 里跑，用的都是它。
   规则升级只改这里，不用回头重跑抓取。
3. **推不出就返回 None，不猜**。返回 None 的条目会被标 `needs_review`，
   由人补，而不是让脚本编一个看起来合理的值。

用法：
    from infer import infer
    r = infer(evidence_dict)
    r["hosts"], r["method"], r["confidence"], r["_evidence"]
"""
import re

# ── 枚举 ──────────────────────────────────────────────────────────────
METHODS = {"emulation", "translation", "recomp", "engine", "virtualization"}
HOSTS = {"android", "windows", "linux", "macos", "ios", "harmonyos", "ps5"}

# ── method 关键词 ─────────────────────────────────────────────────────
# 顺序即优先级：先判更具体的，再落到 emulation。
METHOD_RULES = [
    ("translation", [
        r"\bwinlator\b", r"\bbox64\b", r"\bbox86\b", r"\bwine\b", r"\bproton\b",
        r"\bexagear\b", r"\bgamehub\b", r"\bgamenative\b", r"\bwinlator\s*cn\b",
        r"兼容层", r"转译", r"翻译层",
        # 直接跑 Windows 原版 exe（不重建系统、不做引擎适配）属于翻译层，
        # 不是模拟器。Hitode 这类自研 x86→ARM64 JIT 走的正是这条路。
        r"x86(_64)?\s*(→|->|to)\s*arm64",
        r"run(ning)?\s+the\s+original\s+windows",
        r"原版\s*(exe|windows\s*程序)",
    ]),
    ("recomp", [
        r"\brecomp(iled|ilation)?\b", r"\bstatic\s+recomp", r"\bnative\s+port\b",
        r"\bnative\s+android\s+port\b", r"\bpc\s+port\b", r"重编译", r"原生移植",
    ]),
    ("virtualization", [
        # 只认专有名词。不收 "virtual machine" 这个短语——SKVM（SK Virtual
        # Machine）这类 J2ME 模拟器会被误判成虚拟机，实测踩过。
        r"\bvirtualbox\b", r"\bvmware\b", r"\bhyper-?v\b", r"\butm\b",
        r"\bqemu\b",
    ]),
    ("engine", [
        r"\bkirikiri\b", r"\bkrkr\b", r"\bonscripter\b", r"\bons\b", r"\btyrano\b",
        r"\bartemis\b", r"\bsiglus\b", r"\bre[n']?py\b", r"\brpg\s*maker\b",
        r"\bvisual\s+novel\s+(engine|runtime|platform)\b",
        r"\bmulti-?runtime\b", r"\bvn\s+engine\b", r"视觉小说引擎", r"引擎运行环境",
        r"多引擎", r"内置.{0,8}引擎", r"multi-?engine", r"bundled\s+engine",
    ]),
]

# ── 资产后缀 → 宿主 ───────────────────────────────────────────────────
ASSET_HOST = [
    (r"\.apk$", "android"),
    (r"\.aab$", "android"),
    (r"\.exe$", "windows"),
    (r"\.msi$", "windows"),
    (r"\.dmg$", "macos"),
    (r"\.pkg$", "macos"),
    (r"\.ipa$", "ios"),
    (r"\.hap$", "harmonyos"),
    (r"\.appimage$", "linux"),
    (r"\.deb$", "linux"),
    (r"\.rpm$", "linux"),
    (r"\.flatpak$|\.snap$", "linux"),
]

# zip/tar 无法从后缀判平台，要看文件名里有没有平台词
ARCHIVE_PLATFORM_HINT = [
    (r"android|arm64-v8a|armeabi", "android"),
    (r"win(dows)?|win64|x86_64-windows|\.exe", "windows"),
    (r"macos|osx|darwin|apple", "macos"),
    (r"linux|ubuntu", "linux"),
    (r"ios", "ios"),
    (r"harmony|hmos|openharmony", "harmonyos"),
]

# runtime → hosts 的兜底（仅当资产无证据时用）
RUNTIME_HOST_FALLBACK = {
    "android-native": ["android"],
    "winlator": ["android"],
    "libretro": ["android", "windows", "linux", "macos"],
    # desktop 不兜底：37 条里 Windows/Linux/macOS 混杂，必须看资产
}


def _blob(ev):
    parts = [
        ev.get("name") or "",
        ev.get("description") or "",
        ev.get("category") or "",
        " ".join(ev.get("topics") or []),
        ev.get("homepage") or "",
    ]
    return " ".join(parts).lower()


def _hit(blob, patterns):
    for p in patterns:
        m = re.search(p, blob, re.I)
        if m:
            return m.group(0)
    return None


def infer_method(ev):
    """返回 (method, 命中的词, 置信度)。推不出时 method='emulation' 但置信度低。"""
    blob = _blob(ev)
    cat = (ev.get("category") or "").lower()
    for method, pats in METHOD_RULES:
        hit = _hit(blob, pats)
        if hit:
            # 名字/描述里直接写出来的，可信度高
            return method, hit, "high"
    # 终端 / 前端 / 工具不是模拟器，硬塞 emulation 会让「按实现方式筛选」失真
    if re.search(r"terminal|工具|frontend|前端", cat):
        return None, f"category={ev.get('category')}（非模拟类）", "high"

    # category 已经人工归好的 port 类，直接采信：库里 28 条 Game Port /
    # PC 移植 / PS5 Port 都是重编译或原生移植，描述里未必写 recomp。
    if re.search(r"game port|pc 移植|ps5 port|移植", cat):
        return "recomp", f"category={ev.get('category')}", "high"

    # 兜底：明确是模拟器类目
    if re.search(r"emulator|模拟器|emulation", blob, re.I):
        return "emulation", "emulator 类目", "medium"
    return "emulation", "无明确证据，按默认", "low"


def infer_hosts(ev):
    """返回 (hosts 列表, 证据列表, 置信度)。hosts 为空表示推不出来。"""
    import os

    hosts, evi = [], []

    for a in ev.get("release_assets") or []:
        n = (a.get("name") if isinstance(a, dict) else str(a)) or ""
        low = n.lower()
        base = os.path.basename(low)
        matched = False
        for pat, h in ASSET_HOST:
            if re.search(pat, base):
                if h not in hosts:
                    hosts.append(h)
                evi.append(f"资产 {n} → {h}")
                matched = True
                break
        if matched:
            continue
        if re.search(r"\.(zip|tar\.gz|tgz|7z|tar\.xz)$", base):
            for pat, h in ARCHIVE_PLATFORM_HINT:
                if re.search(pat, base):
                    if h not in hosts:
                        hosts.append(h)
                    evi.append(f"资产 {n} 文件名含平台词 → {h}")
                    break

    if hosts:
        return sorted(hosts, key=lambda x: ["android", "windows", "macos", "linux", "ios", "harmonyos"].index(x)), evi, "high"

    rt = (ev.get("runtime") or "").strip()
    if rt in RUNTIME_HOST_FALLBACK:
        return RUNTIME_HOST_FALLBACK[rt], [f"runtime={rt} 兜底"], "medium"

    # desktop 且无资产证据：看 homepage / 描述里有没有平台词
    blob = _blob(ev)
    guess = []
    # harmonyos 放在 windows 前面：鸿蒙项目 README 必然大谈 Wine/Windows，
    # 先判 windows 会把宿主判错（VintagePomelo 实测踩过）。
    # 鸿蒙是独占宿主：HAP 只能装鸿蒙，不会同时是 Windows 程序。
    # 命中即返回，避免和 README 里的 Windows 字样叠加成多宿主。
    if re.search(r"\bharmonyos\b|\bopenharmony\b|鸿蒙", blob):
        return ["harmonyos"], ["描述含 HarmonyOS"], "medium"
    for pat, h in [
                   (r"\bwindows\b", "windows"), (r"\bmacos?\b|\bos\s?x\b", "macos"),
                   (r"\blinux\b", "linux"), (r"\bandroid\b", "android")]:
        if re.search(pat, blob):
            guess.append(h)
    if guess:
        return sorted(set(guess)), ["描述/主页含平台词（弱证据）"], "low"
    return [], [], "none"


def infer(ev):
    """输入证据字典，输出可写入 registry 的字段 + 证据 + 置信度。"""
    method, m_evi, m_conf = infer_method(ev)
    hosts, h_evi, h_conf = infer_hosts(ev)

    conf_rank = {"high": 3, "medium": 2, "low": 1, "none": 0}
    # 只看 hosts 的置信度：method 有 emulation 兜底，判错顶多归类不准；
    # hosts 判错会让 UI 显示错误的下载按钮（给安卓用户看 exe），必须人工核。
    conf = h_conf

    out = {
        "method": method,
        "hosts": hosts,
        "confidence": "high" if conf_rank[conf] >= 3 else ("medium" if conf_rank[conf] == 2 else "low"),
        "needs_review": not hosts,
        "_evidence": {
            "method": m_evi,
            "hosts": h_evi[:4],
        },
    }
    return out


def builds_from_assets(ev, tag="", default_url=""):
    """把 release 资产整理成 builds 列表（每个宿主取最大的一个包）。"""
    import os

    best = {}
    for a in ev.get("release_assets") or []:
        if not isinstance(a, dict):
            continue
        name = a.get("name") or ""
        low = name.lower()
        base = os.path.basename(low)
        host = None
        for pat, h in ASSET_HOST:
            if re.search(pat, base):
                host = h
                break
        if host is None and re.search(r"\.(zip|tar\.gz|tgz|7z|tar\.xz)$", base):
            for pat, h in ARCHIVE_PLATFORM_HINT:
                if re.search(pat, base):
                    host = h
                    break
        if not host:
            continue
        size = a.get("size") or 0
        if host not in best or size > (best[host].get("size") or 0):
            best[host] = {
                "host": host,
                "format": os.path.splitext(base)[1].lstrip(".") or "zip",
                "name": name,
                "size": size,
                "url": a.get("browser_download_url") or default_url,
                "downloads": a.get("download_count") or 0,
                "tag": tag,
            }
    order = ["android", "windows", "macos", "linux", "ios", "harmonyos"]
    return sorted(best.values(), key=lambda b: order.index(b["host"]))


# ── guest（玩什么）判定 ────────────────────────────────────────────────
# platforms 是库里最成熟的一轴（35 种取值），新项目必须自动落进去，
# 否则每条都要人工从 topics 里翻。规则按「专有名词优先」排：
# 命中 yuzu 就一定是 switch，不管描述里还写了什么。
GUEST_RULES = [
    # switch2 必须排在 switch 之前：命中 NS2 的项目描述里几乎都同时写着 switch。
    # 口径按「能跑 Switch 2 游戏」判定，纯 Switch 1 项目不会被这条捞进来。
    ("switch2", [r"\bswitch\s*2\b", r"\bswitch2\b", r"\bns2\b", r"nintendo\s*switch\s*2",
                 r"\bswitch\s*二代\b"]),
    ("switch", [r"\bswitch\b", r"\bns\b", r"\bns模拟器\b", r"\byuzu\b", r"\bryujinx\b", r"\beden\b", r"\bsudachi\b",
                r"\bsuyu\b", r"\btorzu\b", r"\bhorizon\b", r"\bnx2?\b", r"\bsx2\b"]),
    ("xbox360", [r"\bxbox\s*360\b", r"\bxenia\b", r"\bx360\b", r"\bxendroid\b", r"\bxenra\b"]),
    ("xbox", [r"\bxbox\b"]),
    ("ps5", [r"\bps5\b", r"\bplaystation\s*5\b"]),
    ("ps4", [r"\bps4\b", r"\bplaystation\s*4\b"]),
    ("ps3", [r"\bps3\b", r"\brpcs3\b", r"\bplaystation\s*3\b"]),
    ("ps2", [r"\bps2\b", r"\bpcsx2\b", r"\bplaystation\s*2\b", r"\bplay!\b"]),
    ("ps1", [r"\bps1\b", r"\bpsx\b", r"\bduckstation\b", r"\bplaystation\b"]),
    ("psvita", [r"\bvita3k\b", r"\bps\s*vita\b", r"\bvita\b"]),
    ("psp", [r"\bppsspp\b", r"\bpsp\b"]),
    ("3ds", [r"\b3ds\b", r"\bcitra\b", r"\bazahar\b", r"\bnintendo\s*3ds\b"]),
    ("nds", [r"\bnds\b", r"\bds\b", r"\bdesmume\b", r"\bmelonds\b", r"\bdraStic\b"]),
    ("gamecube", [r"\bgamecube\b", r"\bdolphin\b", r"\bgcn?\b"]),
    ("wiiu", [r"\bwii\s*u\b", r"\bcemu\b"]),
    ("wii", [r"\bwii\b(?!\s*u)"]),
    ("n64", [r"\bn64\b", r"\bmupen\b", r"\bproject64\b"]),
    ("gba", [r"\bgba\b", r"\bmgba\b"]),
    ("gbc", [r"\bgbc\b", r"\bgame\s*boy\s*color\b"]),
    ("snes", [r"\bsnes\b", r"\bsuper\s*nintendo\b", r"\bares\b"]),
    ("nes", [r"\bnes\b", r"\bfamicom\b", r"\bfceux\b", r"\bmesen\b",
             r"nintendo\s*entertainment\s*system"]),
    ("dreamcast", [r"\bdreamcast\b", r"\bflycast\b"]),
    ("saturn", [r"\bsaturn\b", r"\byabause\b"]),
    ("mega-drive", [r"\bmega\s*drive\b", r"\bgenesis\b"]),
    ("arcade", [r"\barcade\b", r"\bmame\b", r"\bfba\b"]),
    ("windows-pc", [r"\bwinlator\b", r"\bwine\b", r"\bbox64\b", r"\bwindows\s*(app|program|game)",
                    r"windows\s*应用", r"pc\s*游戏", r"\bexagear\b", r"\bgamehub\b"]),
    ("visual-novel", [r"\bvisual\s*novel\b", r"\bgalgame\b", r"\bkirikiri\b", r"\bkrkr\b",
                      r"\bonscripter\b", r"\btyrano\b", r"\bartemis\b", r"\bsiglus\b",
                      r"视觉小说", r"文字冒险"]),
    ("java-me", [r"\bj2me\b", r"\bjava\s*(me|emulator)\b", r"\bsymbian\b"]),
    ("android-app", [r"\bandroid\s*app\b", r"\bapk\s*runner\b"]),
    ("dos", [r"\bdosbox\b", r"\bdos\b", r"\b86box\b", r"\bpcem\b"]),
    # ── 复古电脑与冷门平台：库里此前一条都没有，全靠这些词兜住 ──
    ("pc-98", [r"pc-?98", r"\bnp2k", r"pc98"]),
    ("msx", [r"\bmsx\b"]),
    ("atari-2600", [r"atari\s*2600", r"\bstella\b"]),
    ("amiga", [r"\bamiga\b", r"\bfs-uae\b"]),
    ("c64", [r"\bc64\b", r"commodore\s*64", r"\bvice\b"]),
    ("3do", [r"\b3do\b"]),
    ("wonderswan", [r"\bwonderswan\b"]),
    # NGP 必须先于 neo-geo 判断，否则 ngpcraft 会被宽泛的 neo-geo 吞掉
    ("neo-geo-pocket", [r"neo\s*geo\s*pocket", r"\bngpc?\b", r"ngpcraft"]),
    ("neo-geo", [r"neo\s*geo"]),
    ("wiiu", [r"wii\s*u", r"\bcemu\b"]),
    ("pc-engine", [r"pc\s*engine", r"turbografx", r"supergrafx", r"\bgeargrafx\b"]),
    ("apple2", [r"apple\s*(ii|2|\[|\\\])", r"\bpom2\b", r"izapple2"]),
    ("pc-6001", [r"pc-?6001"]),
    ("adventure", [r"\bscummvm\b", r"point[-\s]and[-\s]click", r"\badventure\s+game"]),
]


def infer_guests(ev):
    """返回 (platforms 列表, 证据列表)。命中多个就全收（如 Dolphin → gc+wii）。"""
    blob = _blob(ev)
    # 描述优先，其次 topics（topics 是作者自己打的标签，准确度高）
    top = " ".join(ev.get("topics") or []).lower()
    out, evi = [], []
    for g, pats in GUEST_RULES:
        hit = _hit(blob, pats) or _hit(top, pats)
        if hit:
            if g not in out:
                out.append(g)
                evi.append(f"{hit} → {g}")
    # 去掉被更具体项覆盖的：playstation 会带上 ps1，xbox 会带上 xbox360
    if "ps1" in out and any(x in out for x in ("ps2", "ps3", "ps4", "ps5")):
        out.remove("ps1")
    if "xbox" in out and "xbox360" in out:
        out.remove("xbox")
    # Switch 2 向下兼容 Switch 1：能跑 NS2 的项目一定能跑 NS1，
    # 反过来不成立，所以 switch2 命中时补一个 switch，但不做反向补齐。
    if "switch2" in out and "switch" not in out:
        out.insert(out.index("switch2") + 1, "switch")
    return out, evi
