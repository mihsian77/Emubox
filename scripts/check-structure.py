#!/usr/bin/env python3
"""
结构闸门：单文件行数、单函数行数、未用 import。

为什么要单独一个脚本：这三样编译器都不管。
- 单文件膨胀（Screens.kt 曾到 15250 行）纯靠人自觉，拆完没人拦着重新堆回去；
- 单函数膨胀（DriverDetectionList 1099 行）同理，Compose 里越堆越难读；
- 未用 import：Kotlin 编译器默认不报，Android Lint 的 UnusedImport 只是
  warning，而 CI 不因 warning 失败——上次拆分会拆出 1090 条，就是这么来的。

阈值取「当前最大值 + 余量」，不是理想值。目的是拦住回退，不是逼一次到位。
真要往下压，先改这里的数字，再让 CI 逼着拆。

判定规则（全部确定性，无推断）：
1. 单文件 > LIMIT_FILE 行 → 失败
2. 单个顶层函数 > LIMIT_FUN 行 → 失败
3. 存在文件内完全没被引用的 import → 失败
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/emubox/app"

# 当前最大：DriverCards.kt 2297 行；DriverDetectionList 1099 行。
# 留约一成余量，避免小改就红，同时挡住"再堆几百行"的渐进膨胀。
LIMIT_FILE = 2500
LIMIT_FUN = 1200

# import 的简单名可能是隐式使用（委托属性、运算符重载），这些一律不判未用。
IMPLICIT_OK = {
    "getValue", "setValue", "provideDelegate",
    "component1", "component2", "component3", "component4", "component5",
    "iterator", "hasNext", "next",
    "plus", "minus", "times", "div", "rem", "inc", "dec",
    "contains", "get", "set", "invoke", "compareTo", "rangeTo",
    "unaryMinus", "not", "equals", "hashCode", "toString",
}


def strip_strings(line: str) -> str:
    """去掉行内字符串字面量，避免把注释/文案里的花括号当结构"""
    line = re.sub(r'"""(?:.|\n)*?"""', '""', line)
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', line)


def top_level_functions(lines):
    """列出 (起始行号, 名称, 结束行号)。只看列 0 的 fun 声明。

    结束位置取「列 0 的 }」——Kotlin 顶层声明的闭括号一定顶格。
    表达式体函数（fun x() = ...）没有顶格 }，遇到下一个声明即视为结束，
    这类函数不可能超长，跳过不影响判定。
    """
    starts = []
    for i, ln in enumerate(lines):
        m = re.match(r'^(?:(?:private|internal|public|inline|suspend|operator|infix)\s+)*fun\s+(?:<[^>]+>\s+)?(?:[\w.<>?,\s\[\]:]+?\.)?([A-Za-z_]\w*)', ln)
        if m and not ln.startswith('@'):
            starts.append((i, m.group(1)))
    out = []
    for k, (i, name) in enumerate(starts):
        end = None
        for j in range(i + 1, len(lines)):
            if lines[j].rstrip() == '}':
                end = j
                break
            # 遇到下一个顶层声明，说明当前是表达式体
            if j > i and re.match(r'^(?:(?:private|internal|public|inline|suspend)\s+)*(?:fun|object|class|val|var|const)\s', lines[j]):
                end = j - 1
                break
        if end is not None:
            out.append((i, name, end))
    return out


def check_file(path: Path):
    problems = []
    lines = path.read_text(encoding="utf-8").split("\n")
    rel = path.name

    if len(lines) > LIMIT_FILE:
        problems.append(f"{rel}: {len(lines)} 行，超过单文件上限 {LIMIT_FILE}")

    for start, name, end in top_level_functions(lines):
        size = end - start + 1
        if size > LIMIT_FUN:
            problems.append(f"{rel}:{start + 1} {name} {size} 行，超过单函数上限 {LIMIT_FUN}")

    # 未用 import：简单名在去掉 import 行后的全文里完全不出现
    body = "\n".join(l for l in lines if not l.lstrip().startswith("import "))
    for ln in lines:
        s = ln.strip()
        if not s.startswith("import "):
            continue
        spec = s[len("import "):].strip()
        if spec.endswith(".*"):
            continue
        alias = None
        if " as " in spec:
            spec, alias = spec.split(" as ", 1)
        name = (alias or spec.split(".")[-1]).strip()
        if name in IMPLICIT_OK:
            continue
        if not re.search(r"\b" + re.escape(name) + r"\b", body):
            problems.append(f"{rel}: 未使用的 import → {s[len('import '):]}")

    return problems


def main():
    files = sorted(SRC.glob("*.kt"))
    if not files:
        print(f"::error::没找到 Kotlin 源文件：{SRC}")
        return 1

    problems = []
    for f in files:
        problems.extend(check_file(f))

    if problems:
        print("结构闸门未通过：\n")
        for p in problems:
            print("  " + p)
        print(f"\n共 {len(problems)} 条。阈值：单文件 {LIMIT_FILE} 行、单函数 {LIMIT_FUN} 行、未用 import 清零。")
        return 1

    widest = max(files, key=lambda f: len(f.read_text(encoding='utf-8').split('\n')))
    print(f"结构闸门通过：{len(files)} 个文件，最大 {widest.name} "
          f"{len(widest.read_text(encoding='utf-8').split(chr(10)))} 行（上限 {LIMIT_FILE}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
