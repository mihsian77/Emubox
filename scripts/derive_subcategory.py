#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按分区派生二级分类，落到 subcategory 字段。

背景：三级筛选的中间层原来统一用 platform_families，但只有「主机模拟」和
「原生移植」两个分区是按机器谱系划分的；另外两个分区套同一张表会失效：

  windows-compat 50 条 -> 49 条都落 pc 一族，二级只有 1 个 chip，被「少于 3 族隐藏」吞掉
  frontend-tool  32 条 -> 22 条无家族（multi / terminal 不入家族），
                          剩下 10 条被按「服务的主机」归类（PS5 工具落索尼系），语义错位

所以二级轴按分区换：
  console-emulation  平台家族（platform_families，已有，不动）
  native-port        平台家族（同上，46 条里 4 族可用）
  windows-compat     容器方案（winlator / gamehub / bannerhub / 其他容器 / 启动器工具 / 桌面版）
  frontend-tool      类型（frontend / tool / emulator）
  vn-engine          不设二级（3 条）

windows-compat 的归组靠 id+name 正则，命中不了就按 runtime 落桌面版；
正则覆盖不到的新条目会在末尾报「未归组」，不静默塞进 other。
"""
import json
import re

REG = "/tmp/eb/emulator-registry.json"

# windows-compat：按 id+name 匹配，顺序即优先级（winlator 改版常带别的词）
COMPAT_RULES = [
    ("winlator", r"winlator|winlite|wb64|skynet"),
    ("gamehub", r"gamehub"),
    ("bannerhub", r"bannerhub|bannerlator"),
    ("other-container", r"gamenative|winnative|winhub|emucorep|steamlator|micewine|box64droid|exagear"),
    ("launcher-tool", r"droiddeck|malibridge|panplay|panprobe"),
]

# frontend-tool：直接用 kind，不另造词
FRONTEND_KINDS = {"frontend", "tool", "emulator"}


def compat_group(e):
    s = f"{e.get('id', '')} {e.get('name', '')}".lower()
    for key, pat in COMPAT_RULES:
        if re.search(pat, s):
            return key
    # PlayDen 是「在越狱 PS5 上跑 Windows 游戏」，产物形态同桌面端：手机装不了
    if e.get("runtime") in ("desktop", "ps5-homebrew"):
        return "desktop"
    return None


def main():
    with open(REG, encoding="utf-8") as f:
        d = json.load(f)

    stat = {}
    ungrouped = []
    for e in d["emulators"]:
        section = e.get("section")
        sub = None
        if section == "windows-compat":
            sub = compat_group(e)
            if sub is None:
                ungrouped.append(f"{e['id']} ({e.get('runtime')})")
        elif section == "frontend-tool":
            kind = e.get("kind")
            if kind in FRONTEND_KINDS:
                sub = kind
            else:
                ungrouped.append(f"{e['id']} (kind={kind})")

        if sub:
            e["subcategory"] = sub
            stat[(section, sub)] = stat.get((section, sub), 0) + 1
        else:
            e.pop("subcategory", None)

    if ungrouped:
        print("!! 未归组条目（需补规则）：")
        for x in ungrouped:
            print("   ", x)
        raise SystemExit(1)

    with open(REG, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"已写入 {len(d['emulators'])} 条 subcategory（仅两个分区有值）")
    for (section, sub), n in sorted(stat.items(), key=lambda kv: (kv[0][0], -kv[1])):
        print(f"  {section:<18}{sub:<18}{n}")


if __name__ == "__main__":
    main()
