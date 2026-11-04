#!/usr/bin/env python3
"""检测 GitHub 仓库是否提供中文 README。

为什么单独做：我上一轮判断「Aether / Hitode 是英文项目」，只看默认 README.md
就下了结论，而两者根目录都有 README.zh-CN.md。GitHub 默认渲染哪个文件，
和作者母语无关——很多中文作者把英文版设为主文件以求国际曝光。

所以「是不是中文项目」必须查文件列表，不能读默认 README 的字数。

用法：
  python3 scripts/check_zh_readme.py --registry         # 查主库 218 条
  python3 scripts/check_zh_readme.py --pool             # 查候选池
  python3 scripts/check_zh_readme.py owner/repo ...     # 查指定仓库
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN_FILE = "/root/.codebuddy/.secrets/gh-token"

# 认定「中文 README」的文件名模式。覆盖常见写法：
#   README.zh-CN.md / README_zh_CN.md / readme-zh-cn.md / README.zh_TW.md
# 以及 docs/ 下的 zh/ 目录
ZH_FILE = re.compile(
    r"readme[._-]?(zh|cn|chs|cht|chinese|zh[_-]?(cn|tw|hk|hans|hant))",
    re.I,
)


def token():
    if os.environ.get("GH_TOKEN"):
        return os.environ["GH_TOKEN"].strip()
    with open(TOKEN_FILE) as f:
        return f.read().strip()


def api(url, tok, retry=2):
    for i in range(retry + 1):
        p = subprocess.run(
            ["curl", "-s", "--max-time", "25",
             "-H", f"Authorization: Bearer {tok}",
             "-H", "Accept: application/vnd.github+json", url],
            capture_output=True, text=True,
        )
        try:
            return json.loads(p.stdout)
        except Exception:
            if i == retry:
                return None
            time.sleep(2)


def zh_files(full_name, tok):
    """返回该仓库里的中文 README 文件名列表（只查根目录 + docs/zh*）。"""
    hits = []
    top = api(f"https://api.github.com/repos/{full_name}/contents/", tok)
    if not isinstance(top, list):
        return None  # 429 / 404 / 空仓库，未知而不是「没有」
    for x in top:
        n = x["name"]
        if x["type"] == "file" and ZH_FILE.search(n):
            hits.append(n)
        elif x["type"] == "dir" and re.match(r"^(docs?|doc)$", n, re.I):
            sub = api(f"https://api.github.com/repos/{full_name}/contents/{n}", tok)
            if isinstance(sub, list):
                for y in sub:
                    if y["type"] == "file" and ZH_FILE.search(y["name"]):
                        hits.append(f"{n}/{y['name']}")
                    elif y["type"] == "dir" and re.match(r"^zh([_-]?(cn|tw|hk|hans|hant))?$", y["name"], re.I):
                        hits.append(f"{n}/{y['name']}/**")
    return hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("repos", nargs="*")
    ap.add_argument("--registry", action="store_true")
    ap.add_argument("--pool", action="store_true")
    args = ap.parse_args()

    tok = token()
    targets = []
    if args.registry:
        d = json.load(open(os.path.join(ROOT, "emulator-registry.json"), encoding="utf-8"))
        for e in d["emulators"]:
            r = e.get("repo") or ""
            # repo 字段已经是 owner/name；只有它是光名字时才拼 owner
            if r:
                targets.append(r if "/" in r else f"{e.get('owner')}/{r}")
    if args.pool:
        p = "/workspace/生态发现/候选池-20261104.json"
        if os.path.exists(p):
            d = json.load(open(p, encoding="utf-8"))
            targets += [r["full_name"] for r in d["rows"] if not r["already_in_registry"]]
    targets += args.repos
    targets = list(dict.fromkeys(targets))

    print(f"检查 {len(targets)} 个仓库…", file=sys.stderr)
    found, unknown = [], []
    for i, fn in enumerate(targets, 1):
        r = zh_files(fn, tok)
        if r is None:
            unknown.append(fn)
        elif r:
            found.append((fn, r))
        if i % 25 == 0:
            print(f"  {i}/{len(targets)}", file=sys.stderr)
        time.sleep(0.15)

    print(f"\n=== 有中文 README：{len(found)} 个 ===")
    for fn, h in found:
        print(f"{fn:52} {', '.join(h)}")
    if unknown:
        print(f"\n=== 查询失败/空仓库：{len(unknown)} 个 ===")
        for fn in unknown:
            print(" ", fn)


if __name__ == "__main__":
    main()
