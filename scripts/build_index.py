#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 registry 拆成「首屏索引 + 按需详情」。

背景：emulator-registry.json 3.2MB，其中 91% 是 `releases`（每条 5 个版本 ×
assets 列表）。App 首屏只用到卡片上的十几二十个字段，却要先把 3MB 下完。

拆分后：
  emulator-index.json        首屏：卡片 + 筛选 + 排序需要的字段，目标 < 400KB
  emulator-details-N.json    详情：releases / 更新日志 / 构建列表，8 片，进详情页只拉一片
  app/src/main/assets/emulator-offline.json  离线兜底，内容同 index

索引里每条带 `detail_shard`，App 直接读，不用自己算哈希（算错就对不上片）。
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "emulator-registry.json")
INDEX = os.path.join(ROOT, "emulator-index.json")
SHARD_TPL = os.path.join(ROOT, "emulator-details-{}.json")
OFFLINE = os.path.join(ROOT, "app", "src", "main", "assets", "emulator-offline.json")

SHARDS = 8

# 首屏要用的字段。判断标准：列表卡片、筛选、排序、以及不点进去就能看到的信息。
INDEX_FIELDS = [
    "id", "name", "name_zh", "category", "section", "family", "family_label", "owner",
    "repo", "repo_url", "homepage", "description", "summary", "logo_url",
    "official", "source", "kind", "runtime", "platforms", "hosts", "status",
    "needs_review", "method", "latest_tag", "latest_date", "channel",
    "latest_apk_name", "latest_apk_url", "latest_apk_size",
    "stars", "forks", "watchers", "downloads_total", "downloads_latest",
    "downloads_basis", "download_url", "download_kind", "distribution",
    "note", "repo_status", "dead", "dead_reason", "pushed_at",
]

# 只有点进详情页才需要的
DETAIL_FIELDS = ["releases", "latest_body", "builds", "community_sources",
                 "license", "topics"]


def dump(path, obj, indent=None):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, separators=(",", ":") if indent is None else None,
                  indent=indent)
    return os.path.getsize(path)


def main():
    doc = json.load(open(SRC, encoding="utf-8"))
    ems = doc.get("emulators") or []
    if not ems:
        sys.exit("registry 为空")

    # 稳定分片：按 id 排序后轮转，同一批数据每次生成的分片内容一致，
    # 避免「内容没变但每个分片都变了」让缓存全部失效。
    order = sorted(range(len(ems)), key=lambda i: ems[i]["id"])
    shard_of = {}
    for n, i in enumerate(order):
        shard_of[ems[i]["id"]] = n % SHARDS

    index_entries, shards = [], {k: {} for k in range(SHARDS)}
    for e in ems:
        item = {k: e[k] for k in INDEX_FIELDS if k in e}
        item["detail_shard"] = shard_of[e["id"]]
        index_entries.append(item)

        detail = {k: e[k] for k in DETAIL_FIELDS if k in e}
        if detail:
            shards[shard_of[e["id"]]][e["id"]] = detail

    index_doc = {
        "schema": 2,
        "updated_at": doc.get("updated_at") or "",
        "count": len(index_entries),
        "detail_shards": SHARDS,
        "emulators": index_entries,
    }
    size_index = dump(INDEX, index_doc)

    total_detail = 0
    for k in range(SHARDS):
        if not shards[k]:
            continue
        total_detail += dump(SHARD_TPL.format(k), {"shard": k, "details": shards[k]})

    # 离线兜底：App 首启无网时用，字段与 index 一致但去掉 detail_shard
    offline = [{k: v for k, v in e.items() if k != "detail_shard"} for e in index_entries]
    os.makedirs(os.path.dirname(OFFLINE), exist_ok=True)
    size_offline = dump(OFFLINE, {"schema": 2, "emulators": offline})

    src_size = os.path.getsize(SRC)
    kb = lambda n: f"{n / 1024:.0f}KB"
    print(f"源文件      {kb(src_size)} / {len(ems)} 条")
    print(f"index       {kb(size_index)}  (首屏，{len(index_entries)} 条)")
    print(f"details     {kb(total_detail)}  分 {SHARDS} 片，最大 "
          f"{kb(max(os.path.getsize(SHARD_TPL.format(k)) for k in range(SHARDS) if shards[k]))}")
    print(f"offline     {kb(size_offline)}  assets 兜底")
    print(f"首屏流量    {kb(src_size)} → {kb(size_index)} "
          f"（-{100 - size_index * 100 // src_size}%）")


if __name__ == "__main__":
    main()
