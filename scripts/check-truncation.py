#!/usr/bin/env python3
"""
截断闸门：绑定长字段的 Text 不许只给 1 行（文件名要 3 行，版本 tag 要 2 行）。

为什么要单独一个脚本：这类问题在编译、单测、截图里都看不出来。
`maxLines = 1` 是合法代码，Lint 不报，只有真把 71 字符的文件名
画到 336dp 宽的屏幕上才会发现——而那时六个 Termux 产物已经长得一模一样了
（apt 版本、debug/release、ABI 全在尾部，被一起砍掉）。

判定只看「显式写了 maxLines」的调用：
Compose 的 Text 默认 maxLines = Int.MAX_VALUE，不写就是自动换行，不截断。
没有 maxLines 却有 overflow 的也不管——不设上限时 overflow 不生效。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/emubox/app"

# 文件名：截断 = 用户选错包，至少要 3 行
FILENAME_HINTS = ("asset.name", "assetName", "fileName", "zipName", "latestApkName")

# 版本号：截断 = 分不清版本，至少要 2 行
TAG_HINTS = ("tagName", "latestTag", "rel.tag", "result.tag", "release.tag", ".tag")

# 文本：读不全不致命，但 1 行基本读不出意思
PROSE_HINTS = ("description", "summary", "note", "body", "blurb")

# 派生串：由长字段算出来的短标签（「arm64 · APK」「1.2 MB」），本身不可能超长
DERIVED_MARKERS = (
    "assetArchLabel(",
    "assetTypeLabel(",
    "assetSizeText(",
    "formatBytes(",
    "emulatorDownloadCount(",
)

MIN_LINES = {"filename": 3, "tag": 2}


def text_blocks(src: str):
    """切出每个 Text( 调用的参数区间（括号平衡，跳过字符串里的括号）"""
    out = []
    for m in re.finditer(r"\bText\(", src):
        start = m.end() - 1
        depth = 0
        i = start
        n = len(src)
        in_str = False
        in_raw = False
        while i < n:
            c = src[i]
            if in_str:
                if c == "\\":
                    i += 2
                    continue
                if c == '"':
                    in_str = False
            elif in_raw:
                if src.startswith('"""', i):
                    in_raw = False
                    i += 3
                    continue
            else:
                if src.startswith('"""', i):
                    in_raw = True
                    i += 3
                    continue
                if c == '"':
                    in_str = True
                elif c == "(":
                    depth += 1
                elif c == ")":
                    depth -= 1
                    if depth == 0:
                        out.append((start, i + 1))
                        break
            i += 1
    return out


def first_arg(block: str) -> str:
    """取 Text( 的第一个实参（可能跨行、可能是 text = ...）"""
    inner = block[1:-1]
    m = re.match(r"\s*text\s*=\s*", inner)
    if m:
        inner = inner[m.end():]
    depth = 0
    in_str = False
    for i, c in enumerate(inner):
        if in_str:
            if c == '"' and (i == 0 or inner[i - 1] != "\\"):
                in_str = False
            continue
        if c == '"':
            in_str = True
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            return inner[:i].strip()
    return inner.strip()


def explicit_max_lines(block: str):
    """只认显式数字；返回 None 表示没写（= 不截断，跳过）"""
    m = re.search(r"maxLines\s*=\s*(\d+)", block)
    return int(m.group(1)) if m else None


def main() -> int:
    errors = []
    warns = []
    scanned = 0
    for path in sorted(SRC.rglob("*.kt")):
        src = path.read_text(encoding="utf-8")
        for start, _end in text_blocks(src):
            block = src[start:_end]
            arg = first_arg(block)
            ml = explicit_max_lines(block)
            if ml is None:
                continue  # 自动换行，不截断
            scanned += 1
            flat = " ".join(arg.split())
            line = src.count("\n", 0, start) + 1
            if "LongFieldText" in block:
                continue
            if any(k in flat for k in DERIVED_MARKERS) and "asset.name" not in flat:
                continue
            hit = level = None
            for h in FILENAME_HINTS:
                if h in flat:
                    hit, level = h, "filename"
                    break
            if not hit:
                for h in TAG_HINTS:
                    if h in flat:
                        hit, level = h, "tag"
                        break
            if not hit:
                for h in PROSE_HINTS:
                    if h in flat:
                        hit, level = h, "prose"
                        break
            if not hit:
                continue
            entry = (
                f"{path.relative_to(ROOT)}:{line}  maxLines={ml}  "
                f"字段={hit}  {flat[:88]}"
            )
            if level == "prose":
                if ml <= 1:
                    warns.append(entry)
                continue
            if ml < MIN_LINES[level]:
                errors.append(entry + f"  → 至少 {MIN_LINES[level]} 行")

    print(f"检查显式限行的 Text 调用 {scanned} 处")
    if errors:
        print(f"\n[ERROR] 长字段行数不足，用户看不到关键后缀：{len(errors)} 处")
        for e in errors:
            print("  " + e)
    if warns:
        print(f"\n[WARN] 文本字段被压成 1 行：{len(warns)} 处（人工判断是否可接受）")
        for w in warns:
            print("  " + w)
    if not errors and not warns:
        print("通过：没有长字段被压成单行")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
