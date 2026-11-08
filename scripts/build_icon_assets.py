#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 icons/*.png 压成 APK 内的本地图标（144px WebP），并生成 id 清单。

为什么要在 APK 里再放一份：
- 图标托管在 jsDelivr，国内网络首屏经常加载不出来，列表会退化成整片字母牌；
- assets 里 115 张 WebP 一共 415KB（平均 3.6KB），比一张截图还小，
  换来的是「离线也有真图标、且秒开」。

App 侧逻辑见 `EmulatorRegistryRepository.applyLocalIcons`：命中清单就用
`file:///android_asset/icons/<id>.webp`，没命中才回落到远程 `logo_url`。
"""
import glob
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "icons")
DST = os.path.join(ROOT, "app", "src", "main", "assets", "icons")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "assets", "icon-manifest.json")
SIZE = 144


def main():
    try:
        from PIL import Image
    except ImportError:
        sys.exit("需要 Pillow：pip install pillow")

    os.makedirs(DST, exist_ok=True)
    for old in glob.glob(os.path.join(DST, "*.webp")):
        os.remove(old)

    ids, total = [], 0
    for p in sorted(glob.glob(os.path.join(SRC, "*.png"))):
        eid = os.path.basename(p)[:-4]
        im = Image.open(p).convert("RGBA")
        im.thumbnail((SIZE, SIZE), Image.LANCZOS)
        canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        canvas.alpha_composite(im, ((SIZE - im.width) // 2, (SIZE - im.height) // 2))
        out = os.path.join(DST, eid + ".webp")
        canvas.save(out, "WEBP", quality=82, method=4)
        total += os.path.getsize(out)
        ids.append(eid)

    json.dump(ids, open(MANIFEST, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    print(f"{len(ids)} 张 -> assets/icons，共 {total / 1024:.0f}KB，清单 {os.path.getsize(MANIFEST)}B")


if __name__ == "__main__":
    main()
