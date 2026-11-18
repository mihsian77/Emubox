#!/usr/bin/env python3
"""
结构闸门：单文件行数、单函数行数、未用 import。

为什么单独一个脚本：这三样编译器都不管。
- 单文件膨胀（Screens.kt 曾到 15250 行）纯靠人自觉，拆完没人拦着重新堆回去；
- 单函数膨胀（DriverDetectionList 曾到 1099 行、MainActivity.onCreate 1065 行）
  同理——Compose 里越堆越难读，而编译器只关心语法能不能过；
- 未用 import：Kotlin 编译器默认不报，Android Lint 的 UnusedImport 只是
  warning，CI 不因 warning 失败。

判定规则（全部确定性，无推断）：
1. 单文件 > LIMIT_FILE 行，且超过 baseline 记录值 → 失败
2. 单函数 > LIMIT_FUN 行，且超过 baseline 记录值 → 失败
3. 存在文件内完全没被引用的 import → 失败

阈值是理想值，不是现状值。现有超标项写进 structure-baseline.json，
只许变小、不许变大：任何一项再涨 1 行就红。拆小了要顺手把 baseline
里的数字调下来，否则棘轮不会真的往前转。

函数扫描覆盖任意缩进层级（顶层函数、类内成员、companion 内、局部函数），
靠花括号深度定位结束行，不依赖「顶格 }」这种排版假设。
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/emubox/app"
BASELINE = Path(__file__).resolve().parent / "structure-baseline.json"

# 理想值。现有超标项在 baseline 里豁免，不靠抬高阈值凑绿灯。
LIMIT_FILE = 1500
LIMIT_FUN = 300

# import 的简单名可能是隐式使用（委托属性、运算符重载），这些一律不判未用。
IMPLICIT_OK = {
    "getValue", "setValue", "provideDelegate",
    "component1", "component2", "component3", "component4", "component5",
    "iterator", "hasNext", "next",
    "plus", "minus", "times", "div", "rem", "inc", "dec",
    "contains", "get", "set", "invoke", "compareTo", "rangeTo",
    "unaryMinus", "not", "equals", "hashCode", "toString",
}

FUN_DECL = re.compile(
    r'^(\s*)'
    r'(?:(?:private|internal|public|protected|inline|suspend|override|open|'
    r'operator|infix|tailrec|external|actual|expect)\s+)*'
    r'fun\s+'
)


def strip_strings(line: str) -> str:
    """去掉字符串与字符字面量。

    顺序不能反：必须先去字符串，再去行注释。否则 "https://..." 里的 //
    会被当成注释起点，引号截断后花括号全错位，函数长度会一路算到文件末尾。
    """
    line = re.sub(r'"""(?:.|\n)*?"""', '""', line)
    line = re.sub(r'"(?:\\.|[^"\\])*"', '""', line)
    line = re.sub(r"'(?:\\.|[^'\\])'", "''", line)
    return re.sub(r'//.*$', '', line)


def depths(lines):
    """每行结束时的花括号深度"""
    d = 0
    out = []
    for ln in lines:
        c = strip_strings(ln)
        d += c.count('{') - c.count('}')
        out.append(d)
    return out


def functions(lines):
    """列出 (起始行, 结束行, 名称)。任意缩进层级。

    结束行 = 深度回落到声明前深度的第一行。表达式体函数（fun x() = ...）
    没有自己的花括号，记 1 行，不参与超长判定。
    """
    dep = depths(lines)
    out = []
    for i, ln in enumerate(lines):
        m = FUN_DECL.match(ln)
        if not m:
            continue
        # fun interface 是接口声明，不是函数
        if re.match(r'fun\s+interface\b', ln[m.end() - 4:]):
            continue
        rest = ln[m.end():]
        nm = re.match(r'(?:<[^>]*>\s*)?(?:[\w.<>?,\s\[\]:]+?\.)?([A-Za-z_]\w*)', rest)
        name = nm.group(1) if nm else '?'
        d0 = dep[i - 1] if i else 0
        if dep[i] <= d0:
            out.append((i, i, name))
            continue
        end = i
        for j in range(i, len(lines)):
            if dep[j] == d0:
                end = j
                break
        else:
            end = len(lines) - 1
        out.append((i, end, name))
    return out


def load_baseline():
    if not BASELINE.exists():
        return {}
    try:
        return json.loads(BASELINE.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"::error::baseline 读不出来：{BASELINE} ({e})")
        raise SystemExit(1)


def check_file(path: Path, base_files, base_funs):
    problems = []
    notes = []
    lines = path.read_text(encoding="utf-8").split("\n")
    rel = path.relative_to(SRC).as_posix()

    size = len(lines)
    allowed = base_files.get(rel)
    if size > LIMIT_FILE:
        if allowed is None:
            problems.append(
                f"{rel}: {size} 行，超过单文件上限 {LIMIT_FILE}，未在 baseline 豁免"
            )
        elif size > allowed:
            problems.append(
                f"{rel}: {size} 行，超过 baseline 记录的 {allowed} 行（已增长 {size - allowed} 行）"
            )
    elif allowed is not None:
        notes.append(f"{rel}: 已降到 {size} 行（≤ {LIMIT_FILE}），可从 baseline 移除")

    seen = {}
    for start, end, name in functions(lines):
        seen[name] = seen.get(name, 0) + 1
        key = f"{rel}::{name}"
        if seen[name] > 1:
            key += f"#{seen[name]}"
        n = end - start + 1
        allowed = base_funs.get(key)
        if n > LIMIT_FUN:
            if allowed is None:
                problems.append(
                    f"{rel}:{start + 1} {name} {n} 行，超过单函数上限 {LIMIT_FUN}，未在 baseline 豁免"
                )
            elif n > allowed:
                problems.append(
                    f"{rel}:{start + 1} {name} {n} 行，超过 baseline 记录的 {allowed} 行"
                    f"（已增长 {n - allowed} 行）"
                )
        elif allowed is not None:
            notes.append(
                f"{rel}:{start + 1} {name} 已降到 {n} 行（≤ {LIMIT_FUN}），可从 baseline 移除"
            )

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

    return problems, notes


def main():
    files = sorted(SRC.rglob("*.kt"))
    if not files:
        print(f"::error::没找到 Kotlin 源文件：{SRC}")
        return 1

    base = load_baseline()
    base_files = base.get("files", {})
    base_funs = base.get("functions", {})

    problems = []
    notes = []
    for f in files:
        p, n = check_file(f, base_files, base_funs)
        problems.extend(p)
        notes.extend(n)

    if problems:
        print("结构闸门未通过：\n")
        for p in problems:
            print("  " + p)
        print(
            f"\n共 {len(problems)} 条。阈值：单文件 {LIMIT_FILE} 行、"
            f"单函数 {LIMIT_FUN} 行、未用 import 清零。"
        )
        print("已超标项记在 scripts/structure-baseline.json，只许变小。")
        return 1

    sizes = [(len(f.read_text(encoding='utf-8').split('\n')),
              f.relative_to(SRC).as_posix()) for f in files]
    big_file = max(sizes)
    funcs = []
    for f in files:
        for start, end, name in functions(f.read_text(encoding='utf-8').split('\n')):
            funcs.append((end - start + 1, f.relative_to(SRC).as_posix(), name))
    big_fun = max(funcs) if funcs else (0, '-', '-')
    print(
        f"结构闸门通过：{len(files)} 个文件 {sum(s for s, _ in sizes)} 行，"
        f"最大 {big_file[1]} {big_file[0]} 行（上限 {LIMIT_FILE}）；"
        f"{len(funcs)} 个函数，最大 {big_fun[2]} {big_fun[0]} 行"
        f"（上限 {LIMIT_FUN}；baseline 豁免 {len(base_files)} 文件 / {len(base_funs)} 函数）"
    )
    if notes:
        print(f"另有 {len(notes)} 项已达标，建议同步下调 baseline：")
        for x in notes:
            print("  " + x)
    return 0


if __name__ == "__main__":
    sys.exit(main())
