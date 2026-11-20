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

FILES = ["data/emulator-registry.json", "data/emulator-ranking.json"]

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
    "latest_body", "latest_apk_name", "latest_apk_url", "latest_apk_size", "latest_apk_tag",
    "releases", "stars", "forks", "watchers", "downloads_total",
    "downloads_lifetime", "downloads_latest", "downloads_basis",
    "download_url", "download_kind",
    "distribution", "builds", "note", "license", "topics", "community_sources",
    "repo_status", "dead", "dead_reason", "dead_auto", "created_at",
    "archived", "pushed_at",
    "community_port", "requires_original", "logo_source",
    # 中文名/中文作品名：原生移植项目原名是英文，列表里补一行译名（人工核对）
    "name_zh",
    # 2026-11-12 新增：按发布文件列表解析出的产物统计与安卓可安装判定
    "artifacts", "android_ready",
    # 2026-11-20 新增：android_ready 的判定依据（release 发布资产 / 人工核实 / 无依据）
    "android_ready_source",
    # 伴随应用/构件：原生移植常需搭配输入桥、驱动或额外 wrapper
    "companion",
    # 平台家族（8 个硬件谱系，由 platforms 派生，供二级分类 chip 使用）
    "platform_families",
    # 二级分类的另一种轴：windows-compat 按容器方案、frontend-tool 按类型
    "subcategory",
}

# 枚举值取自真实数据分布，不是猜的：
#   runtime  android-native 173 / desktop 105 / winlator 11 / libretro 7
#            console-homebrew 4 / ps5-homebrew 2
#   channel  已把 alpha/beta/canary/experimental/archive/stable-unknown 归一成 prerelease/stable
CHANNELS = {"stable", "prerelease"}
RUNTIMES = {"android-native", "desktop", "winlator", "libretro",
            "console-homebrew", "ps5-homebrew"}
HOSTS = {"android", "windows", "macos", "linux", "ios",
         "ps5", "wii", "gamecube", "harmonyos",
         # 主机 homebrew 宿主：izpsx 跑 Switch 2、emufive 跑已越狱 PS4
         "switch2", "ps4"}
STATUS = {"active", "stale", "dead", "archived", "community"}
KINDS = {"emulator", "port", "frontend", "tool", "engine"}
# download_kind 曾是三对近义方言并存（github-releases / github-release、
# play / official-play / google-play、official-site / official-web）+ 341 条空值。
# 2026-11-20 由 scripts/derive_download_kind.py 统一为「链接指向哪种获取方式」，
# 取值只能从下面这张表里长，新增先登记。
DOWNLOAD_KINDS = {
    "github-release", "google-play", "official-site", "desktop-build",
    "libretro-core", "zip", "nightly", "f-droid",
    "forgejo-release", "gitea-release", "gitlab-release", "ps5-package",
    "source-only",
}

# android_ready 的三态：True=有可安装制品，False=确认没有，None=没核过。
# 以前只有布尔，「官网分发」的项目一律记 false，等于拿「我们没看到」冒充「不支持」。
ANDROID_SOURCES = {"release", "manual", "unknown"}
DOWNLOADS_BASIS = {"lifetime", "latest-lower-bound", "none"}

# artifacts 子字段（解析器读 snake_case，界面按它算「共 N 个」）
ARTIFACT_KEYS = {"apk", "android_zip", "ipa", "libretro_core",
                 "desktop", "console", "source", "total"}

INT_FIELDS = ["stars", "forks", "watchers", "downloads_total",
              "downloads_lifetime", "downloads_latest", "latest_apk_size"]
LIST_FIELDS = ["platforms", "hosts", "topics", "releases", "builds",
               "community_sources", "platform_families"]
BOOL_FIELDS = ["official", "dead", "dead_auto", "needs_review", "archived"]

URL_RE = re.compile(r"^https?://", re.I)

# 网盘 / 搬运站：这些域名即使在条目里出现也只是「有人转存了一份」，
# 不构成一手来源。历史上 40 多条只有 123 盘镜像的条目已经全部复核清掉，
# 这条规则防止它们再被批量录回来。
NETDISK_RE = re.compile(
    r"(123pan|123684|123865|123912|pan\.baidu\.com|aliyundrive|alipan\.com|"
    r"pan\.quark\.cn|quark\.cn|lanzou[a-z]?\.com|9k9k|huangt\.cn|ctfile|weiyun\.com|"
    # 2026-11-14：国外网盘同样不能当来源（MEGA / MediaFire / Google Drive / OneDrive / 坚果云）
    r"mega\.nz|mega\.io|mediafire\.com|drive\.google\.com|docs\.google\.com|"
    r"1drv\.ms|onedrive\.live\.com|jianguoyun\.com)", re.I)


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
    dk = e.get("download_kind")
    if path.endswith("emulator-registry.json") and dk is None:
        p.append(f"{where}: 缺 download_kind（空值让「可下载性」统计失真，"
                 f"跑 scripts/derive_download_kind.py 派生）")
    elif dk is not None and dk not in DOWNLOAD_KINDS:
        p.append(f"{where}: download_kind={dk!r} 不在 {sorted(DOWNLOAD_KINDS)}")

    # android_ready 三态：None=没核过，别用 false 冒充「不支持」
    ar = e.get("android_ready")
    if ar is not None and not isinstance(ar, bool):
        p.append(f"{where}: android_ready 应为布尔或 null，实为 {type(ar).__name__}")
    src = e.get("android_ready_source")
    if src is not None and src not in ANDROID_SOURCES:
        p.append(f"{where}: android_ready_source={src!r} 不在 {sorted(ANDROID_SOURCES)}")
    if src == "manual" and ar is not True:
        p.append(f"{where}: 人工核实过却没置 true（android_ready={ar!r}）")
    if src == "unknown" and ar is not None:
        p.append(f"{where}: 依据 unknown 就别给结论（android_ready={ar!r}，应为 null）")
    if ar is not None and src is None:
        p.append(f"{where}: 有 android_ready 结论但没写来源")
    basis = e.get("downloads_basis")
    if basis is not None and basis not in DOWNLOADS_BASIS:
        p.append(f"{where}: downloads_basis={basis!r} 未知口径")

    # artifacts 的子字段名曾写成 camelCase（androidZip），解析器读的是
    # android_zip，结果界面「发布文件」永远显示「无」，而校验一路绿灯。
    # 子结构也要按白名单卡住。
    art = e.get("artifacts")
    if art is not None:
        if not isinstance(art, dict):
            p.append(f"{where}: artifacts 应为对象")
        else:
            unknown = set(art) - ARTIFACT_KEYS
            if unknown:
                p.append(f"{where}: artifacts 含未登记子字段 {sorted(unknown)}")
            for kk, vv in art.items():
                if kk in ARTIFACT_KEYS and not isinstance(vv, int):
                    p.append(f"{where}: artifacts.{kk} 应为整数")
            if isinstance(art.get("total"), int) and art["total"] == 0:
                # total 是界面判空的依据，为 0 会让「发布文件」块整体不显示
                rec = sum(art.get(k, 0) for k in ARTIFACT_KEYS if k != "total")
                if rec > 0:
                    p.append(f"{where}: artifacts.total=0 但已识别 {rec} 个产物，界面会显示「无」")

    # 有 latest_apk_url 却没版本号，详情页会出现「空版本 + 有安装包」
    if e.get("latest_apk_url") and not e.get("latest_tag"):
        p.append(f"{where}: 有 latest_apk_url 但缺 latest_tag")

    for k in ("homepage", "repo_url", "logo_url", "download_url"):
        v = (e.get(k) or "").strip()
        if v and not URL_RE.match(v) and not v.startswith("icons/"):
            p.append(f"{where}: {k} 不是 http(s) 或 icons/ 路径：{v[:60]}")

    # 网盘/搬运站一律不算来源
    for k in ("homepage", "repo_url", "download_url", "latest_apk_url"):
        v = e.get(k) or ""
        if NETDISK_RE.search(v):
            p.append(f"{where}: {k} 指向网盘/搬运站，不能作为分发来源：{v[:70]}")

    # 停更条目必须写清停更原因，否则读者只看到一个灰掉的名字
    if e.get("dead") and not (e.get("dead_reason") or "").strip():
        p.append(f"{where}: dead=true 但缺 dead_reason")

    # 安装包必须是 apk/aab，不能把 png/zip 当下载按钮（历史上真发生过）
    apk = (e.get("latest_apk_url") or "").lower()
    apk_name = (e.get("latest_apk_name") or "").lower()
    # nightly.link 的 artifact 直链路径末段是数字 id，但资产名是 apk，属合法
    ok_apk = apk.endswith((".apk", ".aab")) or (
        "nightly.link" in apk and apk_name.endswith((".apk", ".aab")))
    # 安卓包常打成 zip 发（EX-Emulators.zip / nc2000_*.zip / FivePocket-*-android.zip），
    # distribution=android-archive 即属此列，扩展名不是 apk 也算合法可安装分发。
    if e.get("distribution") == "android-archive" and apk.endswith(".zip"):
        ok_apk = True
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
