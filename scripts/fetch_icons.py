#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给模拟器条目找「真正的应用图标」，替代作者头像。

三条路径，按质量排序：

1. `--probe`  仓库里现成的图标（F-Droid 的 fastlane icon / Android 的 mipmap），
              走 jsDelivr 按约定路径 HEAD，命中即停，单仓库 1~4 次请求。
              命中率约 22%（302 条里 ~60 条），但对开源安卓工程最准。
2. `--apk`    从 release APK 里提取 launcher 图标。APK 是 zip，
              只 Range 读尾部中央目录 + 图标本体，12MB 的包实际只拉 200KB 左右。
              混淆过的包（资源名 res/as.png 这种）按「体积 3~60KB + 正方形 + 96~512px」
              猜，实测 10/10 能抽到图，但约三成需要人工复核（可能抽到手柄贴图）。
3. `--sheet`  把抽到的图拼成对照表，人工过一遍，把错的写进 icons/overrides.json。

单条命令：
    python3 scripts/fetch_icons.py --probe
    python3 scripts/fetch_icons.py --apk --limit 20
    python3 scripts/fetch_icons.py --sheet
"""
import argparse, json, os, re, struct, sys, urllib.error, urllib.request, zlib
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(ROOT, "emulator-registry.json")
ICON_DIR = os.path.join(ROOT, "icons")
RESULT = os.path.join(ROOT, "icons", "resolved.json")
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
PROXY = "https://ghfast.top/"

# F-Droid / 常见 Android 工程约定，命中即停
CANDIDATE_PATHS = [
    "fastlane/metadata/android/en-US/images/icon.png",
    "fastlane/metadata/android/zh-CN/images/icon.png",
    "app/src/main/ic_launcher-playstore.png",
    "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
    "app/src/main/res/mipmap-xxhdpi/ic_launcher.png",
    "app/src/main/res/mipmap-xhdpi/ic_launcher.png",
    "android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
    "android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png",
    "res/mipmap-xxxhdpi/ic_launcher.png",
]


def http(url, start=None, end=None, timeout=20, method=None):
    h = dict(UA)
    if start is not None:
        h["Range"] = f"bytes={start}-{end if end is not None else ''}"
    req = urllib.request.Request(url, headers=h, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(), r.headers


def probe_repo(repo):
    for branch in ("main", "master"):
        for path in CANDIDATE_PATHS:
            url = f"https://cdn.jsdelivr.net/gh/{repo}@{branch}/{path}"
            try:
                _, hdrs = http(url, method="HEAD", timeout=12)
                size = int(hdrs.get("Content-Length") or 0)
                if size > 1500:
                    return url, path
            except Exception:
                continue
    return None, None


# ---------- APK：zip 中央目录 + 图标条目 ----------

def _apk_tail(url, n=131072):
    _, hdrs = http(url, method="HEAD")
    total = int(hdrs.get("Content-Length") or 0)
    if not total:
        raise ValueError("no content-length")
    body, _ = http(url, max(0, total - n), total - 1)
    return body, total


def _apk_entries(url):
    tail, total = _apk_tail(url)
    i = tail.rfind(b"PK\x05\x06")
    if i < 0:
        raise ValueError("EOCD not found")
    *_, cd_size, cd_off, _clen = struct.unpack("<IHHHHIIH", tail[i:i + 22])
    if cd_off + cd_size <= total:
        cd, _ = http(url, cd_off, cd_off + cd_size - 1)
    else:
        start = len(tail) - (total - cd_off)
        cd = tail[start:start + cd_size]
    out, p = [], 0
    while p + 46 <= len(cd) and cd[p:p + 4] == b"PK\x01\x02":
        (_sig, _vm, _vn, _fl, method, _tm, _dt, _crc, csize, usize,
         nlen, elen, clen, _dsk, _ia, _ea, lho) = struct.unpack("<IHHHHHHIIIHHHHHII", cd[p:p + 46])
        out.append(dict(name=cd[p + 46:p + 46 + nlen].decode("utf-8", "replace"),
                        method=method, csize=csize, usize=usize, lho=lho))
        p += 46 + nlen + elen + clen
    return out


def _entry_bytes(url, ent):
    hdr, _ = http(url, ent["lho"], ent["lho"] + 29)
    nlen, elen = struct.unpack("<HH", hdr[26:30])
    start = ent["lho"] + 30 + nlen + elen
    raw, _ = http(url, start, start + ent["csize"] - 1)
    if ent["method"] == 0:
        return raw
    if ent["method"] == 8:
        return zlib.decompress(raw, -15)
    raise ValueError("unsupported compression")


def _png_size(blob):
    if blob[:8] != b"\x89PNG\r\n\x1a\n" or blob[12:16] != b"IHDR":
        return None
    return struct.unpack(">II", blob[16:24])


def pick_launcher(url, entries):
    """先按标准名取；混淆包按几何特征猜（3~60KB、正方形、96~512px）。"""
    named = [e for e in entries if re.search(r"(ic_launcher|app_?icon|launcher)\.png$", e["name"], re.I)]
    if named:
        named.sort(key=lambda e: -e["usize"])
        return named[0]
    pool = [e for e in entries if e["name"].lower().endswith(".png") and 3000 <= e["usize"] <= 60000]
    pool.sort(key=lambda e: -e["usize"])
    scored = []
    for ent in pool[:18]:
        try:
            head = _entry_bytes_head(url, ent)
        except Exception:
            continue
        size = _png_size(head)
        if not size:
            continue
        w, h = size
        if w == h and 96 <= w <= 512:
            scored.append((w, ent))
    if not scored:
        return None
    scored.sort(key=lambda x: -x[0])
    return scored[0][1]


def _entry_bytes_head(url, ent, n=33):
    hdr, _ = http(url, ent["lho"], ent["lho"] + 29)
    nlen, elen = struct.unpack("<HH", hdr[26:30])
    start = ent["lho"] + 30 + nlen + elen
    blob, _ = http(url, start, start + n - 1)
    return blob


# ---------- 三种模式 ----------

def load_registry():
    return json.load(open(REGISTRY, encoding="utf-8"))["emulators"]


def cmd_probe(limit):
    ems = [e for e in load_registry() if e.get("repo") and "/" in e["repo"]][:limit or None]
    hits = {}

    def work(e):
        try:
            return e["id"], probe_repo(e["repo"])
        except Exception:
            return e["id"], (None, None)

    with ThreadPoolExecutor(16) as pool:
        for i, (eid, (url, path)) in enumerate(pool.map(work, ems), 1):
            if url:
                hits[eid] = {"url": url, "path": path, "via": "repo"}
            if i % 50 == 0:
                print(f"{i}/{len(ems)} 命中 {len(hits)}", flush=True)
    merge(hits)
    print(f"repo 命中 {len(hits)}")


def cmd_apk(limit, ids):
    os.makedirs(ICON_DIR, exist_ok=True)
    ems = load_registry()
    if ids:
        ems = [e for e in ems if e["id"] in ids]
    else:
        ems = [e for e in ems if e.get("latest_apk_url")]
    ems = ems[:limit or None]
    hits = {}

    def work(e):
        url = PROXY + e["latest_apk_url"]
        try:
            entries = _apk_entries(url)
            ent = pick_launcher(url, entries)
            if not ent:
                return e["id"], "no-icon"
            data = _entry_bytes(url, ent)
            open(os.path.join(ICON_DIR, e["id"] + ".png"), "wb").write(data)
            return e["id"], "ok"
        except Exception as ex:
            return e["id"], str(ex)[:60]

    with ThreadPoolExecutor(5) as pool:
        for i, (eid, st) in enumerate(pool.map(work, ems), 1):
            if st == "ok":
                hits[eid] = {"url": f"icons/{eid}.png", "via": "apk"}
            print(i, eid, st, flush=True)
    merge(hits)
    print(f"apk 命中 {len(hits)}")


def merge(hits):
    cur = {}
    if os.path.exists(RESULT):
        cur = json.load(open(RESULT, encoding="utf-8"))
    cur.update(hits)
    os.makedirs(os.path.dirname(RESULT), exist_ok=True)
    json.dump(cur, open(RESULT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"累计 {len(cur)} 条")


def cmd_sheet(out="icons/contact-sheet.png"):
    from PIL import Image, ImageDraw, ImageFont
    if not os.path.exists(RESULT):
        sys.exit("先跑 --probe 或 --apk")
    data = json.load(open(RESULT, encoding="utf-8"))
    items = []
    for eid, v in data.items():
        p = os.path.join(ROOT, v["url"])
        if os.path.exists(p):
            items.append((eid, p))
    if not items:
        sys.exit("没有已下载的图标")
    cols, cell = 6, 140
    rows = (len(items) + cols - 1) // cols
    canvas = Image.new("RGB", (cols * cell, rows * (cell + 22)), "white")
    d = ImageDraw.Draw(canvas)
    font = ImageFont.load_default()
    for i, (eid, p) in enumerate(items):
        im = Image.open(p).convert("RGBA")
        im.thumbnail((cell - 24, cell - 24))
        x, y = (i % cols) * cell, (i // cols) * (cell + 22)
        bg = Image.new("RGBA", (cell, cell), (247, 247, 247, 255))
        bg.alpha_composite(im, ((cell - im.width) // 2, (cell - im.height) // 2))
        canvas.paste(bg.convert("RGB"), (x, y))
        d.text((x + 6, y + cell + 4), eid[:22], fill="black", font=font)
    canvas.save(os.path.join(ROOT, out))
    print(out, canvas.size, len(items))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--probe", action="store_true")
    ap.add_argument("--apk", action="store_true")
    ap.add_argument("--sheet", action="store_true")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--ids", default="")
    a = ap.parse_args()
    if a.probe:
        cmd_probe(a.limit)
    elif a.apk:
        cmd_apk(a.limit, [x for x in a.ids.split(",") if x])
    elif a.sheet:
        cmd_sheet()
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
