#!/usr/bin/env python3
"""
EmuBox i18n 校验脚本
功能：
1. 检查默认(values)与各语言(values-*)的词条完整性（漏译/多译）
2. 检查占位符一致性（%1$s, %1$d, %% 等）
3. **检查默认 values 里是否混入中文**（默认语言是英文，中文只该出现在 values-zh-rCN）
4. 扫描 Kotlin 源码中的硬编码用户可见字符串（中文），仅告警
用法：python3 scripts/check-i18n.py [--strict]
退出码：0=通过，1=有错误
"""
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
RES_DIR = PROJECT_ROOT / "app" / "src" / "main" / "res"
KOTLIN_DIR = PROJECT_ROOT / "app" / "src" / "main" / "java"

# 资源文件名。这里必须和 res/values 下的真实文件一致：
# 之前写成 emuhub_strings.xml（拼错），结果 848 条主文案一条都没被检查，
# 校验一直「通过」，实际默认英文里躺着 29 条中文没人发现。
STRING_FILES = ["strings.xml", "emubox_strings.xml"]

# 需要检查的语言目录（除默认 values 外）
TARGET_LOCALES = ["zh-rCN"]

CJK_RE = re.compile(r"[\u4e00-\u9fff]")

# 默认（英文）里允许保留中文的词条：
# 语言选择器按惯例用母语自称，翻成英文反而让中文用户找不到自己的语言。
DEFAULT_CJK_ALLOWLIST = {
    "language_chinese",
}

# 硬编码检测：Kotlin 中直接传入字符串字面量的用户可见 API
HARDCODED_PATTERNS = [
    (r'Toast\.makeText\([^,]+,\s*"([^"]*[\u4e00-\u9fff][^"]*)"', "Toast 硬编码"),
    (r'Text\(\s*"([^"]*[\u4e00-\u9fff][^"]*)"', "Text 硬编码"),
    (r'title\s*=\s*"([^"]*[\u4e00-\u9fff][^"]*)"', "title 硬编码"),
    (r'label\s*=\s*\{?\s*Text\(\s*"([^"]*[\u4e00-\u9fff][^"]*)"', "label 硬编码"),
]


def parse_strings(xml_path: Path) -> dict:
    """解析 strings.xml / emubox_strings.xml，返回 {name: text}"""
    result = {}
    if not xml_path.exists():
        return result
    try:
        tree = ET.parse(xml_path)
        root = tree.getroot()
        for string_elem in root.findall("string"):
            name = string_elem.get("name")
            text = string_elem.text or ""
            if name:
                result[name] = text
    except ET.ParseError as e:
        print(f"  ❌ XML 解析失败 {xml_path}: {e}")
    return result


def collect_all_strings(values_dir: Path) -> dict:
    """收集一个 values 目录下所有 strings 文件的词条"""
    all_strings = {}
    for xml_file in STRING_FILES:
        all_strings.update(parse_strings(values_dir / xml_file))
    return all_strings


def extract_placeholders(text: str) -> list:
    """提取字符串中的格式化占位符"""
    pattern = r'(%\d+\$[sd]|%[sd]|%%)'
    return sorted(re.findall(pattern, text))


def check_default_locale(default_strings: dict) -> int:
    """默认 values 是英文。混进中文 = 英文界面直接露中文，属于硬错误。"""
    bad = [
        (name, text) for name, text in default_strings.items()
        if CJK_RE.search(text) and name not in DEFAULT_CJK_ALLOWLIST
    ]
    if not bad:
        print("  ✅ [values] 默认语言无中文残留")
        return 0
    print(f"  ❌ [values] 默认语言混入 {len(bad)} 条中文（英文界面会直接显示中文）:")
    for name, text in bad[:20]:
        print(f"      - {name}: {text[:50]}")
    if len(bad) > 20:
        print(f"      ... 还有 {len(bad) - 20} 条")
    return len(bad)


def check_locale(default_strings: dict, locale: str) -> int:
    """检查单个语言的翻译完整性，返回错误数"""
    errors = 0
    locale_dir = RES_DIR / f"values-{locale}"
    locale_strings = collect_all_strings(locale_dir)

    if not locale_strings:
        print(f"  ❌ [{locale}] 未找到任何翻译文件")
        return 1

    # 漏译检查
    missing = set(default_strings.keys()) - set(locale_strings.keys())
    if missing:
        errors += len(missing)
        print(f"  ❌ [{locale}] 漏译 {len(missing)} 条:")
        for name in sorted(missing)[:20]:
            print(f"      - {name}")
        if len(missing) > 20:
            print(f"      ... 还有 {len(missing) - 20} 条")

    # 多译检查（翻译文件中有但默认没有的）
    extra = set(locale_strings.keys()) - set(default_strings.keys())
    if extra:
        errors += len(extra)
        print(f"  ⚠️  [{locale}] 多译 {len(extra)} 条（默认语言中不存在）:")
        for name in sorted(extra)[:10]:
            print(f"      - {name}")

    # 空翻译检查
    empty = [name for name, text in locale_strings.items() if not text.strip()]
    if empty:
        errors += len(empty)
        print(f"  ❌ [{locale}] 空翻译 {len(empty)} 条:")
        for name in sorted(empty)[:10]:
            print(f"      - {name}")

    # 占位符一致性检查
    placeholder_errors = 0
    for name in default_strings:
        if name not in locale_strings:
            continue
        en_ph = extract_placeholders(default_strings[name])
        locale_ph = extract_placeholders(locale_strings[name])
        if en_ph != locale_ph:
            placeholder_errors += 1
            if placeholder_errors <= 10:
                print(f"  ❌ [{locale}] 占位符不一致: {name}")
                print(f"      默认: {en_ph}")
                print(f"      翻译: {locale_ph}")
    errors += placeholder_errors

    if errors == 0:
        print(f"  ✅ [{locale}] 翻译完整，共 {len(locale_strings)} 条，占位符一致")
    return errors


def scan_hardcoded_strings() -> int:
    """扫描 Kotlin 源码中的硬编码中文，仅告警不阻断（存量大，逐步清）"""
    warnings = 0
    if not KOTLIN_DIR.exists():
        return 0

    for kt_file in KOTLIN_DIR.rglob("*.kt"):
        try:
            content = kt_file.read_text(encoding="utf-8")
        except Exception:
            continue

        rel_path = kt_file.relative_to(PROJECT_ROOT)
        for pattern, desc in HARDCODED_PATTERNS:
            for match in re.finditer(pattern, content):
                text = match.group(1)
                line_num = content[:match.start()].count('\n') + 1
                warnings += 1
                if warnings <= 20:
                    print(f"  ⚠️  [{rel_path}:{line_num}] {desc}: \"{text[:50]}\"")

    if warnings == 0:
        print("  ✅ 未发现硬编码中文")
    else:
        print(f"  ⚠️  共发现 {warnings} 处硬编码中文（建议抽到资源，非阻断）")
    return 0


def main():
    print("=" * 60)
    print("EmuBox i18n 校验")
    print("=" * 60)

    default_strings = collect_all_strings(RES_DIR / "values")
    print(f"\n📋 默认语言（英文）词条数: {len(default_strings)}")

    total_errors = 0

    print("\n🔍 默认语言纯净度:")
    total_errors += check_default_locale(default_strings)

    print("\n🔍 翻译完整性检查:")
    for locale in TARGET_LOCALES:
        total_errors += check_locale(default_strings, locale)

    print("\n🔍 硬编码字符串扫描:")
    scan_hardcoded_strings()

    print("\n" + "=" * 60)
    if total_errors > 0:
        print(f"❌ 校验失败：{total_errors} 个错误")
        sys.exit(1)
    else:
        print("✅ 校验通过")
        sys.exit(0)


if __name__ == "__main__":
    main()
