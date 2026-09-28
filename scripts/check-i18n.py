#!/usr/bin/env python3
"""
EmuHub-CN i18n 校验脚本
功能：
1. 检查默认(values)与各语言(values-*)的词条完整性（漏译/多译）
2. 检查占位符一致性（%1$s, %1$d, %% 等）
3. 扫描 Kotlin 源码中的硬编码用户可见字符串（Toast/Text/Button 等）
4. 检查空翻译
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

# 需要检查的语言目录（除默认 values 外）
TARGET_LOCALES = ["zh-rCN"]

# 硬编码检测：Kotlin 中直接传入字符串字面量的用户可见 API
HARDCODED_PATTERNS = [
    (r'Toast\.makeText\([^,]+,\s*"([^"]+)"', "Toast 硬编码"),
    (r'Text\(\s*"([^"]{2,})"', "Text 硬编码"),
    (r'title\s*=\s*"([^"]{2,})"', "title 硬编码"),
    (r'label\s*=\s*\{?\s*Text\(\s*"([^"]{2,})"', "label 硬编码"),
]

# 允许的硬编码（技术字符串、非用户可见、调试用）
HARDCODED_ALLOWLIST = {
    "EmuHub-Android/1.0",  # User-Agent
    "application/octet-stream",  # MIME type
    "https://", "http://",  # URL 前缀检查
}


def parse_strings(xml_path: Path) -> dict:
    """解析 strings.xml / emuhub_strings.xml，返回 {name: text}"""
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
    for xml_file in ["strings.xml", "emuhub_strings.xml"]:
        all_strings.update(parse_strings(values_dir / xml_file))
    return all_strings


def extract_placeholders(text: str) -> list:
    """提取字符串中的格式化占位符"""
    # 匹配 %1$s, %1$d, %s, %d, %%
    pattern = r'(%\d+\$[sd]|%[sd]|%%)'
    return sorted(re.findall(pattern, text))


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
    """扫描 Kotlin 源码中的硬编码用户可见字符串"""
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
                # 跳过允许列表
                if text in HARDCODED_ALLOWLIST:
                    continue
                # 跳过纯技术字符串（含 /、:、. 等且无中文/空格的短串）
                if re.match(r'^[a-zA-Z0-9_./:-]+$', text) and len(text) < 30:
                    continue
                # 计算行号
                line_num = content[:match.start()].count('\n') + 1
                warnings += 1
                if warnings <= 30:
                    print(f"  ⚠️  [{rel_path}:{line_num}] {desc}: \"{text[:50]}\"")

    if warnings == 0:
        print("  ✅ 未发现硬编码用户可见字符串")
    else:
        print(f"  ⚠️  共发现 {warnings} 处疑似硬编码（建议审查，非阻断）")
    return 0  # 硬编码仅警告，不阻断


def main():
    strict = "--strict" in sys.argv
    print("=" * 60)
    print("EmuHub-CN i18n 校验")
    print("=" * 60)

    # 收集默认语言词条
    default_strings = collect_all_strings(RES_DIR / "values")
    print(f"\n📋 默认语言词条数: {len(default_strings)}")

    total_errors = 0

    # 检查各语言
    print("\n🔍 翻译完整性检查:")
    for locale in TARGET_LOCALES:
        total_errors += check_locale(default_strings, locale)

    # 硬编码扫描
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
