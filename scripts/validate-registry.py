#!/usr/bin/env python3
"""校验 driver-registry.json 与 app/src/main/assets/driver-registry.json 的 schema 完整性。

背景：registry 曾混入排行榜格式条目（Weab-chan）且 StevenMXZ 缺 companion 字段，
App 旧版 parseRegistry 用强制 getString/getJSONArray，任一条目缺字段 → 整个解析
返回 null → 三级加载全失败 → 跌到硬编码兜底 3 源（"源少了一大截"的根因）。
此脚本在 CI 阻止此类坏条目再次进入。

必需字段（与 DriverRegistryRepository.parseRegistry 对应）：
id, name, owner, repo, api_url, gpu_vendor, gpu_arch(数组), gpu_models(数组),
driver_type, companion(数组)
"""
import json
import os
import sys

REQUIRED = ["id", "name", "owner", "repo", "api_url", "gpu_vendor", "gpu_arch",
            "gpu_models", "driver_type", "companion"]
ARRAY_FIELDS = ["gpu_arch", "gpu_models", "companion"]

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FILES = [
    os.path.join(ROOT, "driver-registry.json"),
    os.path.join(ROOT, "app", "src", "main", "assets", "driver-registry.json"),
]

def validate(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    repos = data.get("repositories", [])
    errors = []
    for r in repos:
        rid = r.get("id", "?")
        for field in REQUIRED:
            if field not in r:
                errors.append(f"{rid}: 缺字段 {field}")
            elif field in ARRAY_FIELDS and not isinstance(r[field], list):
                errors.append(f"{rid}: {field} 应为数组")
        # 排行榜格式残留字段（不属于 registry schema）检查
        for legacy in ("label", "vendor", "target", "chipset", "priority"):
            if legacy in r:
                errors.append(f"{rid}: 含排行榜格式残留字段 {legacy}")
    return repos, errors

def main():
    failed = False
    for path in FILES:
        if not os.path.exists(path):
            print(f"SKIP {path} 不存在")
            continue
        repos, errors = validate(path)
        if errors:
            failed = True
            print(f"FAIL {path} ({len(repos)} 条目):")
            for e in errors[:20]:
                print(f"  - {e}")
        else:
            print(f"OK   {path} ({len(repos)} 条目全部通过)")
    sys.exit(1 if failed else 0)

if __name__ == "__main__":
    main()
