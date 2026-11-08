#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""注册表 schema 硬校验。

存在的理由：字段名曾经分裂成两套（registry 用 snake_case、ranking 用 camelCase，
`logo_url` 296 条 / `logoUrl` 11 条，`downloads` 281 条 / `downloads_latest` 162 条），
App 端只能靠 `a.ifBlank { b }` 兜底，兜到最后谁也说不清哪个是权威值。

现在规定：JSON 一律 snake_case，别名不再接受；新字段先在本文件登记。
CI 里跑，任何一条不合规直接失败。
"""
import json
import re
import sys

FILES = ["emulator-registry.json", "emulator-ranking.json"]

# 已退役的字段名 → 现行字段名。出现在数据里就是迁移没做干净。
RENAMED = {
    "logoUrl": "logo_url",
    "downloads": "downloads_total",
    "latestTag": "latest_tag",
    "latestDate": "latest_date",
    "desc": "summary",
    "repoUrl": "repo_url",
    "pushedAt": "pushed_at",
    "latestApkUrl": "latest_apk_url",
    "_pool_score": None,
    "_dup": None,
    "skip": None,
}

REQUIRED = ["id", "name", "category", "repo", "repo_url", "homepage", "description"]

# 允许出现的字段（新增字段必须先登记，否则 CI 报错——避免又长出一套方言）
ALLOWED = set(REQUIRED) | {
    "section", "family", "family_label", "owner", "summary", "logo_url",
    "official", "source", "kind", "runtime", "platforms", "hosts", "status",
    "needs_review", "method", "latest_tag", "latest_date", "channel",
    "latest_body", "latest_apk_name", "latest_apk_url", "latest_apk_size",
    "releases", "stars", "forks", "watchers", "downloads_total",
    "downloads_latest", "downloads_basis", "download_url", "download_kind",
    "distribution", "builds", "note", "license", "topics", "community_sources",
    "repo_status", "dead", "dead_reason", "created_at", "archived", "pushed_at",
    "community_port", "requires_original", "logo_source",
}

# 枚举值取自真实数据分布，不是猜的：
#   runtime  android-native 173 / desktop 105 / winlator 11 / libretro 7
#            console-homebrew 4 / ps5-homebrew 2
#   channel  已把 alpha/beta/canary/experimental/archive/stable-unknown 归一成 prerelease/stable
CHANNELS = {"stable", "prerelease"}
RUNTIMES = {"android-native", "desktop", "winlator", "libretro",
            "console-homebrew", "ps5-homebrew"}
HOSTS = {"android", "windows", "macos", "linux", "ios",
         "ps5", "wii", "gamecube", "harmonyos"}
STATUS = {"active", "stale", "dead", "archived", "community"}
KINDS = {"emulator", "port", "frontend", "tool", "engine"}
DOWNLOADS_BASIS = {"lifetime", "latest-lower-bound", "none"}

INT_FIELDS = ["stars", "forks", "watchers", "downloads_total",
              "downloads_latest", "latest_apk_size"]
LIST_FIELDS = ["platforms", "hosts", "topics", "releases", "builds",
               "community_sources"]
BOOL_FIELDS = ["official", "dead", "needs_review", "archived"]

URL_RE = re.compile(r"^https?://", re.I)


def check_entry(path, i, e):
    p = []
    where = f"{path}[{i}] {e.get('id', '<no-id>')}"

    for k in REQUIRED:
        if k not in e:
            p.append(f"{where}: 缺必填字段 {k}")

    for k in e:
        if k in RENAMED:
            tgt = RENAMED[k]
            p.append(f"{where}: 用了已退役字段 {k}（应为 {tgt or '删除'}）")
        elif k not in ALLOWED:
            p.append(f"{where}: 未登记字段 {k}（先加进 validate-schema.py 的 ALLOWED）")

    for k in INT_FIELDS:
        v = e.get(k)
        if v is not None and not isinstance(v, int):
            p.append(f"{where}: {k} 应为整数，实为 {type(v).__name__}")
    for k in BOOL_FIELDS:
        v = e.get(k)
        if v is not None and not isinstance(v, bool):
            p.append(f"{where}: {k} 应为布尔，实为 {type(v).__name__}")
    for k in LIST_FIELDS:
        v = e.get(k)
        if v is not None and not isinstance(v, list):
            p.append(f"{where}: {k} 应为数组，实为 {type(v).__name__}")

    ch = e.get("channel")
    if ch is not None and ch not in CHANNELS:
        p.append(f"{where}: channel={ch!r} 不在 {sorted(CHANNELS - {''})}")
    for h in e.get("hosts") or []:
        if h not in HOSTS:
            p.append(f"{where}: hosts 含未知值 {h!r}")
    rt = e.get("runtime")
    if rt is not None and rt not in RUNTIMES:
        p.append(f"{where}: runtime={rt!r} 不在 {sorted(RUNTIMES)}")
    st = e.get("status")
    if st is not None and st not in STATUS:
        p.append(f"{where}: status={st!r} 不在 {sorted(STATUS)}")
    kd = e.get("kind")
    if kd is not None and kd not in KINDS:
        p.append(f"{where}: kind={kd!r} 不在 {sorted(KINDS)}")
    basis = e.get("downloads_basis")
    if basis is not None and basis not in DOWNLOADS_BASIS:
        p.append(f"{where}: downloads_basis={basis!r} 未知口径")

    # 有 latest_apk_url 却没版本号，详情页会出现「空版本 + 有安装包」
    if e.get("latest_apk_url") and not e.get("latest_tag"):
        p.append(f"{where}: 有 latest_apk_url 但缺 latest_tag")

    for k in ("homepage", "repo_url", "logo_url", "download_url"):
        v = (e.get(k) or "").strip()
        if v and not URL_RE.match(v) and not v.startswith("icons/"):
            p.append(f"{where}: {k} 不是 http(s) 或 icons/ 路径：{v[:60]}")

    # 安装包必须是 apk/aab，不能把 png/zip 当下载按钮（历史上真发生过）
    apk = (e.get("latest_apk_url") or "").lower()
    apk_name = (e.get("latest_apk_name") or "").lower()
    # nightly.link 的 artifact 直链路径末段是数字 id，但资产名是 apk，属合法
    ok_apk = apk.endswith((".apk", ".aab")) or (
        "nightly.link" in apk and apk_name.endswith((".apk", ".aab")))
    if apk and not ok_apk:
        p.append(f"{where}: latest_apk_url 不是 apk/aab：{apk.rsplit('/', 1)[-1][:50]}")

    return p


def main():
    total = 0
    problems = []
    for path in FILES:
        try:
            d = json.load(open(path, encoding="utf-8"))
        except FileNotFoundError:
            continue
        ems = d.get("emulators") or []
        total += len(ems)
        seen = set()
        for i, e in enumerate(ems):
            problems += check_entry(path, i, e)
            eid = e.get("id")
            if eid in seen:
                problems.append(f"{path}: 重复 id {eid}")
            seen.add(eid)

    if problems:
        print(f"schema 校验失败：{len(problems)} 个问题 / {total} 条")
        for x in problems[:60]:
            print("  -", x)
        if len(problems) > 60:
            print(f"  …… 还有 {len(problems) - 60} 条")
        return 1

    print(f"schema 校验通过：{total} 条，字段命名与类型全部合规")
    return 0


if __name__ == "__main__":
    sys.exit(main())
