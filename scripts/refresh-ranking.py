#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""模拟器社区排行全量合并脚本 v4

主源 the412banner/emulator-ranking（字段最全：logo/homepage/stars/description/pushedAt），
t3st31/Ranking-Emulators-Download 补其独有条目。两个站均为静态 GitHub Pages JSON，每日自动更新。
排除 Drivers 分类（驱动走驱动专区，不混入模拟器）。
输出：仓库根目录 emulator-ranking.json（全量 releases/assets）+ scripts/emu_builtin_block.txt（内置块）。
"""
import json, os, sys, time, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "emulator-ranking.json")
BUILTIN = os.path.join(ROOT, "scripts", "emu_builtin_block.txt")

T412 = "https://the412banner.github.io/emulator-ranking/data/rankings.json"
T331 = "https://t3st31.github.io/Ranking-Emulators-Download/data/rankings.json"
HIST331 = "https://t3st31.github.io/Ranking-Emulators-Download/data/history.json"
RECIPES331 = "https://t3st31.github.io/Ranking-Emulators-Download/data/recipes.json"
DRIVERSRC331 = "https://t3st31.github.io/Ranking-Emulators-Download/data/driver-sources.json"
VISITORS412 = "https://the412banner.github.io/emulator-ranking/data/visitors.json"
COMPAT331 = "https://t3st31.github.io/Ranking-Emulators-Download/data/compatibility.json"
LOGO_T412 = "https://the412banner.github.io/emulator-ranking/logos/"
LOGO_T331 = "https://t3st31.github.io/Ranking-Emulators-Download/logos/"

CAT_ZH = {
    "Winlator": "Winlator 专区", "GameHub": "GameHub 专区", "PC Emulator": "PC 模拟器",
    "GameNative": "GameNative 专区", "All In One": "聚合前端", "Nintendo 3DS": "任天堂 3DS",
    "PSVITA": "PS Vita", "Emulator PS2": "PS2 模拟", "Emulator PS3": "PS3 模拟",
    "Emulator PS5": "PS5 模拟", "Wii U Emulator": "Wii U 模拟", "Sega Dreamcast": "Dreamcast 模拟",
    "Nintendo GameCube / Wii": "NGC-Wii 模拟", "Xbox": "Xbox 模拟",
    "Nintendo Switch Emulator": "Switch 模拟", "GAME": "游戏",
}

OFFICIAL = {  # 官方/主渠道条目（真分类，避免乱分）
    "brunodev85/winlator": ("Winlator", True),
    "gamehub-studio/gamehub": ("GameHub", True),
    "eden-emulator/releases": ("Nintendo Switch Emulator", True),
    "termux/termux-app": ("PC Emulator", True),
    "hrydgard/ppsspp": ("Emulator PS2", True),
    "stenzek/duckstation": ("Emulator PS2", True),
    "libretro/retroarch": ("All In One", True),
    "dolphin-emu/dolphin": ("Nintendo GameCube / Wii", True),
    "Vita3K/Vita3K": ("PSVITA", True),
}


def fetch(url, retries=3):
    """拉取 JSON；4xx/5xx/网络失败返回 None（条件接入数据源未产出时容忍失败）。
    网络抖动（IncompleteRead/超时）自动重试 3 次。"""
    for attempt in range(retries):
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 emubox-refresh"})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except Exception as e:
            print(f"  fetch 失败 {url}（第 {attempt + 1}/3 次）: {e}")
            if attempt < retries - 1:
                time.sleep(2 + attempt * 2)
    return None


def norm_releases(rels):
    out = []
    for r in rels or []:
        assets = [{
            "name": a.get("name", ""),
            "size": int(a.get("size") or 0),
            "url": a.get("url", ""),
        } for a in (r.get("assets") or []) if a.get("name")]
        body = (r.get("body") or "").replace("\r", "")
        out.append({
            "tag": r.get("tag", ""),
            "name": r.get("name", ""),
            "date": r.get("date", ""),
            "body": body[:1200],
            "prerelease": bool(r.get("prerelease")),
            "assets": assets,
        })
    return out


def gh_releases(repo):
    """CI 内用 GITHUB_TOKEN 给原站无 releases 的条目补最新版本（本地无 token 返回空）。"""
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        return []
    url = f"https://api.github.com/repos/{repo}/releases?per_page=30"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "User-Agent": "emubox"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            rels = json.load(r)
    except Exception as e:
        print(f"  API 失败 {repo}: {e}")
        return []
    out = []
    for r in rels:
        assets = [{"name": a["name"], "size": int(a.get("size") or 0), "url": a.get("browser_download_url", "")}
                  for a in (r.get("assets") or []) if a.get("name")]
        out.append({
            "tag": r.get("tag_name", ""),
            "name": r.get("name", ""),
            "date": r.get("published_at", ""),
            "body": (r.get("body") or "").replace("\r", "")[:1200],
            "prerelease": bool(r.get("prerelease")),
            "assets": assets,
        })
    return out


def main():
    t412 = fetch(T412)
    t331 = fetch(T331)
    # 两个主源任一失败则中止，避免以空/残缺数据覆盖现有产物
    if not t412 or not t331:
        print("主数据源拉取失败，跳过本轮刷新，保留现有产物")
        return
    t412r = t412.get("results") or []
    t331r = t331.get("results") or []
    hist = fetch(HIST331) or {}
    recipes_raw = (fetch(RECIPES331) or {}).get("recipes") or []
    driver_src = (fetch(DRIVERSRC331) or {}).get("sources") or []
    # 条件接入：412 站点访问统计（GoatCounter 每小时写 data/visitors.json，当前可能未产出）
    visitors_raw = fetch(VISITORS412)
    # 条件接入：t3st31 兼容性测试报告（schema 存在但 reports 由上游逐步填充）
    compat_raw = fetch(COMPAT331) or {}

    # ── 14 天下载增量（t3st31 history 每日快照）──
    snaps = hist.get("projectSnapshots") or []
    trend14 = []
    if len(snaps) >= 2:
        latest = snaps[-1]
        base = snaps[0]
        if len(snaps) > 14:
            base = snaps[-14]
        latest_dl = {p.get("repo", "").lower(): int(p.get("downloads") or 0) for p in latest.get("projects") or []}
        base_dl = {p.get("repo", "").lower(): int(p.get("downloads") or 0) for p in base.get("projects") or []}
        for repo, dl in latest_dl.items():
            delta = dl - base_dl.get(repo, 0)
            if delta > 0:
                trend14.append({"repo": repo, "delta": delta, "date": latest.get("date", "")})
        trend14.sort(key=lambda x: -x["delta"])

    by_repo = {}
    order = []

    def add(e, logo_base):
        repo = e.get("repo") or ""
        if not repo or e.get("category") == "Drivers":
            return

        key = repo.lower()
        if key in by_repo:
            return
        cat, off = OFFICIAL.get(repo, (e.get("category", ""), False))
        logo = e.get("logo") or ""
        rels = norm_releases(e.get("releases"))
        if not rels:
            rels = gh_releases(repo)
        by_repo[key] = {
            "id": key.replace("/", "-"),
            "name": e.get("name", ""),
            "repo": repo,
            "repo_url": e.get("repoUrl") or f"https://github.com/{repo}",
            "category": cat,
            "official": off,
            "channel": e.get("channel", "stable"),
            "downloads": int(e.get("downloads") or 0),
            "stars": 0,
            "description": "",
            "pushed_at": "",
            "homepage": "",
            "logoUrl": (logo_base + logo) if logo else "",
            "releases": rels,
        }
        order.append(key)

    # 主源 the412banner（字段全）
    for e in t412r:
        add(e, LOGO_T412)
    # t331 补独有（repo 未收录的条目）
    for e in t331r:
        repo = e.get("repo") or ""
        if repo.lower() in by_repo:
            continue
        add(e, LOGO_T331)
    # 合并后补 t412 元数据（stars/description/pushed_at/homepage/forks/watchers/topics/license/createdAt）
    stats = {e["repo"].lower(): (e.get("repoStats") or {}) for e in t412r}
    for key in order:
        s = stats.get(key, {})
        e = by_repo[key]
        e["stars"] = int(s.get("stars") or 0)
        e["forks"] = int(s.get("forks") or 0)
        e["watchers"] = int(s.get("watchers") or 0)
        e["description"] = s.get("description") or ""
        e["pushed_at"] = s.get("pushedAt") or ""
        e["created_at"] = s.get("createdAt") or ""
        e["homepage"] = s.get("homepage") or ""
        e["license"] = s.get("license") or ""
        e["topics"] = s.get("topics") or []
        e["archived"] = bool(s.get("archived"))

    emus = [by_repo[k] for k in order]
    # 补充最新版本信息：latestTag/latestDate 从最新 release 取（releases 已按时间倒序）
    for e in emus:
        rel0 = (e.get("releases") or [{}])[0]
        e["latestTag"] = rel0.get("tag", "")
        e["latestDate"] = rel0.get("date", "")
    emus.sort(key=lambda e: (-(1 if e["official"] else 0), -(e.get("downloads") or 0)))
    cats = {}
    cat_dl = {}
    for e in emus:
        cats[e["category"]] = cats.get(e["category"], 0) + 1
        cat_dl[e["category"]] = cat_dl.get(e["category"], 0) + (e.get("downloads") or 0)

    total_dl = sum(e.get("downloads") or 0 for e in emus)
    total_stars = sum(e.get("stars") or 0 for e in emus)

    # 食谱（t3st31 recipes，葡萄牙语 → 中文翻译）
    PT = {
        "perfil inicial": "入门配置", "Ponto de partida para aparelhos Qualcomm": "高通设备的起点配置",
        "Ajuste por jogo e confirme os relatos da comunidade": "按游戏微调，并以社区反馈为准",
        "Ponto de partida para aparelhos com GPU Mali": "Mali GPU 设备的起点配置",
        "Driver gráfico": "图形驱动", "Turnip / AdrenoTools quando compatível": "兼容时使用 Turnip / AdrenoTools",
        "Renderer": "渲染器", "Wrapper": "Wrapper", "Começar com DXVK; testar VKD3D apenas para DirectX 12":
        "先用 DXVK；仅 DirectX 12 游戏再试 VKD3D", "Resolução": "分辨率", "1280×720 ou nativa reduzida": "1280×720 或降低原生分辨率",
        "Box64": "Box64", "Preset padrão do projeto antes de ajustar manualmente": "先保持项目默认预设，再手动微调",
        "Áudio": "音频", "ALSA como primeira tentativa": "首选 ALSA",
        "Vortek / driver do sistema": "Vortek / 系统驱动", "Vortek / driver de sistema": "Vortek / 系统驱动",
        "Vulkan com fallback OpenGL": "Vulkan，必要时回退 OpenGL",
        "Confirmar se o driver corresponde à GPU e à versão do Android.": "确认驱动匹配 GPU 与 Android 版本。",
        "Testar primeiro sem modificar várias opções ao mesmo tempo.": "先单点修改，避免同时改多个选项。",
        "Comparar FPS entre as duas APIs antes de escolher a definitiva.": "最终确定前对比两种 API 的 FPS。",
        "Confirmar se o driver corresponde à GPU e à versão do Android": "确认驱动匹配 GPU 与 Android 版本",
        "Testar primeiro sem modificar várias opções ao mesmo tempo": "先单点修改，避免同时改多个选项",
        "Comparar FPS entre as duas APIs antes de escolher a definitiva": "最终确定前对比两种 API 的 FPS",
        "Driver gráfico (Adreno)": "图形驱动（Adreno）", "Driver gráfico (Mali)": "图形驱动（Mali）",
        "Registrar versão, driver, wrapper e resultado ao relatar compatibilidade.":
        "反馈兼容性时记录版本、驱动、wrapper 与结果。",
        "Ponto de partida experimental para Mali. O resultado varia muito por geração, driver e jogo.":
        "Mali 的试验性起点配置，效果随 GPU 代际、驱动与游戏差异很大。",
        "Vortek ou driver do sistema, conforme suporte": "Vortek 或系统驱动（视支持情况）",
        "Vulkan quando disponível; testar OpenGL como fallback": "优先 Vulkan；OpenGL 作为回退测试",
        "Começar com DXVK e reduzir a complexidade se houver artefatos":
        "先用 DXVK；出现画面错误再降低配置复杂度",
        "Confirmar o renderer realmente usado pelo container.": "确认容器实际使用的渲染器。",
        "Testar um jogo conhecido antes de concluir que o emulador é incompatível.":
        "下结论前先用已知可玩的游戏验证。",
        "Registrar artefatos gráficos, travamentos e temperatura observada.":
        "记录图形错误、崩溃与设备温度。",
    }

    def tr(s):
        s = s or ""
        for k, v in PT.items():
            s = s.replace(k, v)
        return s

    def fmt_recipe(r):
        sets = []
        for it in r.get("settings") or []:
            if isinstance(it, dict):
                k = it.get("label") or it.get("key") or it.get("name") or ""
                v = it.get("value")
                sets.append({"key": tr(k), "value": tr(v) if v else ""})
            else:
                sets.append({"key": tr(str(it)), "value": ""})
        checks = []
        for it in r.get("checks") or []:
            if isinstance(it, dict):
                checks.append({"title": tr(it.get("title") or it.get("name") or ""), "detail": tr(it.get("detail") or "")})
            else:
                checks.append({"title": tr(str(it)), "detail": ""})
        return {
            "id": r.get("id") or r.get("name") or "",
            "title": tr(r.get("title") or r.get("name") or ""),
            "summary": tr(r.get("summary") or r.get("description") or ""),
            "settings": sets,
            "checks": checks,
            "sources": r.get("sources") or [],
        }

    recipes = [fmt_recipe(r) for r in recipes_raw if isinstance(r, dict)]
    driver_sources_out = [
        {"repo": d.get("repo") or "", "family": d.get("family") or "", "apiUrl": d.get("apiUrl") or "",
         "extensions": d.get("extensions") or [], "notes": d.get("notes") or ""}
        for d in driver_src if isinstance(d, dict)
    ]

    doc = {
        "updated_at": t412.get("updatedAt", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())),
        "source": "the412banner/emulator-ranking + t3st31/Ranking-Emulators-Download（每日自动刷新）",
        "insights": {
            "total_projects": len(emus),
            "total_downloads": total_dl,
            "total_stars": total_stars,
            "trending14": trend14[:15],
            "trend_base_date": (snaps[-14].get("date") if len(snaps) > 14 else (snaps[0].get("date") if snaps else "")),
            "trend_latest_date": (snaps[-1].get("date") if snaps else ""),
            "category_downloads": sorted(
                [{"category": k, "label": CAT_ZH.get(k, k), "downloads": v} for k, v in cat_dl.items()],
                key=lambda x: -x["downloads"]),
            # 本周发布数：按 release date 近 7 天统计（412 首页 Releases this week 等价物）
            "weekly_releases": sum(
                1 for e in emus for r in (e.get("releases") or [])
                if (r.get("date") or "")[:10] >= (time.strftime("%Y-%m-%d", time.gmtime(time.time() - 7 * 86400)))),
            # 条件接入：412 站点访问统计（visitors.json 未产出时省略，App 端无值不显示）
            **({"total_visits": int(visitors_raw.get("totalVisits") or 0),
                "online_now": int(visitors_raw.get("onlineNow") or 0),
                "visits": visitors_raw.get("visits") or []} if visitors_raw and isinstance(visitors_raw, dict) else {}),
            # 条件接入：t3st31 兼容性测试报告（空壳时省略，上游填充后自动显示）
            **({"compat_reports": [
                {"project": (c.get("project") or c.get("repo") or ""), "status": (c.get("status") or c.get("level") or ""),
                 "game": (c.get("game") or ""), "note": (c.get("note") or c.get("details") or "")[:200]}
                for c in (compat_raw.get("reports") or []) if isinstance(c, dict) and c.get("project")]
               } if (compat_raw.get("reports") or []) else {}),
        },
        "recipes": recipes,
        "driver_sources": driver_sources_out,
        "categories": [{"key": c, "label": CAT_ZH.get(c, c), "count": n}
                       for c, n in sorted(cats.items(), key=lambda x: -x[1])],
        "emulators": emus,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
        f.write("\n")
    total_rel = sum(len(e["releases"]) for e in emus)
    total_as = sum(len(a) for e in emus for r in e["releases"] for a in r["assets"])
    print(f"已更新：{len(emus)} 条 / {len(cats)} 分类 / {total_rel} releases / {total_as} 资产 / {os.path.getsize(OUT)/1024/1024:.2f} MB")
    print("分类:", cats)

    # 内置块（离线兜底：元数据+最新版本+最新文件；全量 releases 走远程拉取）
    def ks(v: str) -> str:
        """Kotlin 字符串字面量转义（换行/引号/反斜杠）"""
        return (v or "").replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\r", "")

    block_lines = []
    for e in emus:
        rel0 = (e["releases"] or [{}])[0]
        asset0 = (rel0.get("assets") or [{}])[0]
        block_lines.append(
            "EmulatorEntry("
            f"id = \"{e['id']}\", name = \"{ks(e['name'])}\", category = \"{ks(e['category'])}\", "
            f"description = \"{ks(e['description'][:160])}\", owner = \"{ks((e['repo'].split('/') + [''])[0])}\", "
            f"repo = \"{ks(e['repo'].split('/')[-1] if '/' in e['repo'] else e['repo'])}\", "
            f"homepage = \"{ks(e['homepage'])}\", repoUrl = \"{ks(e['repo_url'])}\", official = {str(e['official']).lower()}, "
            f"channel = \"{ks(e['channel'])}\", downloads = {e['downloads']}, stars = {e['stars']}, "
            f"pushedAt = \"{ks(e['pushed_at'])}\", latestTag = \"{ks(rel0.get('tag', ''))}\", "
            f"latestDate = \"{ks(rel0.get('date', ''))}\", latestBody = \"{ks(rel0.get('body', '')[:200])}\", "
            f"logoUrl = \"{ks(e['logoUrl'])}\", "
            f"latestApkName = \"{ks(asset0.get('name', ''))}\", latestApkUrl = \"{ks(asset0.get('url', ''))}\", "
            f"latestApkSize = {asset0.get('size', 0)}"
            "),"
        )
    with open(BUILTIN, "w", encoding="utf-8") as f:
        f.write("\n".join(block_lines))
    print(f"内置块：{len(block_lines)} 条 -> {BUILTIN}")


if __name__ == "__main__":
    main()
