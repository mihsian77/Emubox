#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 PR 改动摘要并贴到 PR 评论。

把 diff 按目录聚合，标出高风险路径（脚本、工作流、数据文件），
让人扫一眼就知道这个 PR 实际动了什么，而不是只看作者自己的描述。

用法：
  python3 scripts/pr-digest.py <base_sha> <head_sha> <pr_number> <repo>
未设置 GITHUB_TOKEN 时只打印到 stdout，便于本地检查。
"""
import collections
import json
import os
import subprocess
import sys
import urllib.request

MARKER = "<!-- pr-digest -->"

RISK_PATHS = {
    "scripts/": "脚本 / 抓取逻辑",
    ".github/": "CI / 工作流",
    "app/src/main/assets/": "内置数据",
}
RISK_FILES = {
    "emulator-registry.json": "模拟器注册表",
    "driver-registry.json": "驱动注册表",
    "news-data.json": "新闻数据",
}


def git(*args):
    return subprocess.check_output(["git"] + list(args), text=True).strip()


def parse_numstat(base, head):
    rows = []
    for line in git("diff", "--numstat", "%s..%s" % (base, head)).splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        add, delete, path = parts
        rows.append({
            "path": path,
            "add": 0 if add == "-" else int(add),
            "delete": 0 if delete == "-" else int(delete),
        })
    return rows


def top_dir(path):
    parts = path.split("/")
    if len(parts) == 1:
        return "(根目录)"
    depth = 3 if len(parts) > 3 else len(parts) - 1
    return "/".join(parts[:depth]) + "/"


def build(base, head):
    rows = parse_numstat(base, head)
    if not rows:
        return MARKER + "\n**改动摘要**：没有文件差异。\n"

    commits = git("rev-list", "--count", "%s..%s" % (base, head))
    total_add = sum(r["add"] for r in rows)
    total_del = sum(r["delete"] for r in rows)

    groups = collections.OrderedDict()
    for r in rows:
        g = groups.setdefault(top_dir(r["path"]), {"files": 0, "add": 0, "delete": 0})
        g["files"] += 1
        g["add"] += r["add"]
        g["delete"] += r["delete"]

    risks = []
    for prefix, label in RISK_PATHS.items():
        hit = [r for r in rows if r["path"].startswith(prefix)]
        if hit:
            risks.append("%s（%d 个文件）" % (label, len(hit)))
    for name, label in RISK_FILES.items():
        if any(r["path"].endswith(name) for r in rows):
            risks.append(label)

    lines = [
        MARKER,
        "**改动摘要** · %s 个提交 · %d 个文件 · +%d / −%d" % (commits, len(rows), total_add, total_del),
        "",
    ]
    if risks:
        lines.append("⚠️ 涉及：" + "、".join(risks))
        lines.append("")
    if len(rows) > 30:
        lines.append("⚠️ 文件数超过 30，建议拆分。")
        lines.append("")

    lines += ["| 目录 | 文件 | + | − |", "|---|---|---|---|"]
    for d, g in sorted(groups.items(), key=lambda kv: -kv[1]["add"]):
        lines.append("| `%s` | %d | %d | %d |" % (d, g["files"], g["add"], g["delete"]))

    lines += ["", "<details><summary>文件清单（%d）</summary>" % len(rows), ""]
    for r in sorted(rows, key=lambda x: -(x["add"] + x["delete"])):
        lines.append("- `%s` +%d −%d" % (r["path"], r["add"], r["delete"]))
    lines += ["", "</details>"]
    return "\n".join(lines) + "\n"


def api(method, path, payload, token):
    req = urllib.request.Request(
        "https://api.github.com" + path,
        data=json.dumps(payload).encode(),
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "User-Agent": "emubox-pr-digest",
        },
        method=method,
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        body = r.read()
    return json.loads(body) if body else {}


def upsert(repo, pr, body, token):
    req = urllib.request.Request(
        "https://api.github.com/repos/%s/issues/%s/comments?per_page=100" % (repo, pr),
        headers={"Authorization": "Bearer " + token, "User-Agent": "emubox-pr-digest"},
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        comments = json.loads(r.read())
    mine = [c for c in comments if MARKER in (c.get("body") or "")]
    if mine:
        api("PATCH", "/repos/%s/issues/comments/%s" % (repo, mine[0]["id"]), {"body": body}, token)
    else:
        api("POST", "/repos/%s/issues/%s/comments" % (repo, pr), {"body": body}, token)


def main():
    if len(sys.argv) < 5:
        print(__doc__)
        sys.exit(1)
    base, head, pr, repo = sys.argv[1:5]
    body = build(base, head)
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if token:
        upsert(repo, pr, body, token)
        print("已更新 PR #%s 摘要" % pr)
    else:
        print(body)


if __name__ == "__main__":
    main()
