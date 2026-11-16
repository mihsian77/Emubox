#!/usr/bin/env python3
"""APK 资源完整性校验：代码引用的 string 资源必须全部存在于 release 包内。

为什么需要这道闸：R8 会把 R.string 常量内联进调用点，资源收缩器的可达性
分析依赖 R$string 字段引用，追踪失效时会静默删掉仍在使用的字符串。此类
缺陷编译通过、单测通过（单测跑 debug 变体、不经过收缩），只有装到真机
渲染到该界面才崩，属最难在发布前发现的回归。2026-11-16 的 v1.4.0-pre
就因此缺失 19 个字符串（含底部导航标签），由本脚本的原型检出。

判定标准：
  - 源码中出现的每一个 R.string.<key>，都必须能在 APK 资源表中找到同名 entry
  - 缺失即退出码 1，并逐条列出（附源码位置，方便直接定位）

用法：
  python3 scripts/check-apk-resources.py [APK 路径]
  APK 路径缺省为 app/build/outputs/apk/release/app-release.apk
  aapt2 从 ANDROID_HOME / ANDROID_SDK_ROOT 下自动查找
"""

import glob
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC_DIRS = [ROOT / "app/src/main/java", ROOT / "app/src/main/res"]
DEFAULT_APK = ROOT / "app/build/outputs/apk/release/app-release.apk"


def find_aapt2() -> str:
    """优先用 PATH，其次在 Android SDK 的 build-tools 里找最新的 aapt2。"""
    exe = shutil.which("aapt2")
    if exe:
        return exe
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(env)
        if not sdk:
            continue
        hits = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt2")))
        if hits:
            return hits[-1]  # build-tools 目录按版本号字典序，取最新
    sys.exit("找不到 aapt2：请设置 ANDROID_HOME/ANDROID_SDK_ROOT，或把 build-tools 加入 PATH")


def collect_references() -> dict:
    """扫描 Kotlin/Java 源码，返回 {string 键: [引用位置, ...]}。

    只认代码里的 R.string.<key>，不认 XML 里的 @string/<key>（XML 引用
    由 aapt2 静态解析，收缩器不会误删，无需纳入闸门）。
    """
    pattern = re.compile(r"\bR\.string\.([A-Za-z0-9_]+)")
    refs: dict = {}
    for src in SRC_DIRS:
        for path in list(src.rglob("*.kt")) + list(src.rglob("*.java")):
            text = path.read_text(encoding="utf-8", errors="ignore")
            for lineno, line in enumerate(text.splitlines(), 1):
                for key in pattern.findall(line):
                    refs.setdefault(key, []).append(f"{path.relative_to(ROOT)}:{lineno}")
    return refs


def collect_apk_strings(aapt2: str, apk: Path) -> set:
    """从 APK 资源表提取全部 string 键名（跨所有语言限定符去重）。"""
    out = subprocess.run(
        [aapt2, "dump", "resources", str(apk)],
        capture_output=True, text=True, check=True,
    ).stdout
    return set(re.findall(r"string/([A-Za-z0-9_]+)", out))


def main() -> int:
    apk = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_APK
    if not apk.exists():
        sys.exit(f"APK 不存在：{apk}（先执行 assembleRelease）")

    aapt2 = find_aapt2()
    refs = collect_references()
    apk_keys = collect_apk_strings(aapt2, apk)
    missing = sorted(k for k in refs if k not in apk_keys)

    print(f"APK: {apk}")
    print(f"代码引用的 string 键: {len(refs)}    APK 内 string 条目: {len(apk_keys)}")

    if not missing:
        print("✓ 资源完整性通过：代码引用的字符串全部在包内")
        return 0

    print(f"\n✗ 发现 {len(missing)} 个被引用的字符串在 APK 中缺失，真机渲染将抛 Resources$NotFoundException：")
    for key in missing:
        where = ", ".join(refs[key][:3])
        more = f" 等 {len(refs[key])} 处" if len(refs[key]) > 3 else ""
        print(f"  - {key}    ← {where}{more}")
    print("\n常见原因：开启 isShrinkResources 后收缩器误判可达性；勿以「包变小」作为验收标准。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
