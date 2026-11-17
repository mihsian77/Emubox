#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""数据入库的公共清洗与派生逻辑。

只放「多个刷新脚本都要用、写错一次就会让线上数据变脏」的那几件小事。
目前两项：正文字段截断、下载包版本归属。
"""
import re
from urllib.parse import unquote

# 最新版本的更新日志。详情页整段展示，够放一个完整发布说明。
BODY_LIMIT = 2000
# 历史版本列表里每条只给一段摘要，列表本身有 5 条，给多了体积翻倍。
RELEASE_BODY_LIMIT = 1200
# 驱动卡片只显示一两行，但要够读出「改了什么」。
DRIVER_BODY_LIMIT = 500

# `<` 后面必须是字母 / 斜杠 / 叹号才算标签开头，避免把「2 < 3」当成标签。
_TAG_START = re.compile(r"<[A-Za-z/!]")


def clip_body(text, limit=BODY_LIMIT):
    """按行截断正文，不把一行切在 HTML 标签或链接中间。

    按字符数直接切会把 `<a href="https://…` 切成两半：后半段没有闭合的 `>`，
    任何标签正则都剥不掉，于是 App 里就显示成半截地址。这里做两件事：

    1. 截断点回退到最近的换行符，把被切开的那半行整行丢掉；
    2. 万一整段没有换行，最后一个 `<` 若没有配对的 `>`，就切在这个 `<` 之前。

    已经短于上限的正文也会走第 2 步——历史数据里就有「入库时切在标签中间」
    留下的尾巴，重新入库时顺手修掉。
    """
    s = (text or "").replace("\r\n", "\n").replace("\r", "\n")
    original = s
    if len(s) > limit:
        s = s[:limit]
        nl = s.rfind("\n")
        # 只在前半段有换行时才回退，否则等于把内容砍掉一大半。
        if nl > limit // 2:
            s = s[:nl]
    lt, gt = s.rfind("<"), s.rfind(">")
    if lt > gt and _TAG_START.match(s[lt:lt + 2]):
        s = s[:lt]
    # 没动过的正文原样返回，避免重跑脚本时把整库的空白差异都写进 diff。
    return s.rstrip() if s != original else s


def derive_apk_tag(url, releases):
    """查出下载链接属于哪个版本。

    优先在 releases 的资产列表里精确比对 URL——同一版本可能带多个 APK，
    文件名里也未必写版本号，只有 URL 是唯一的。
    URL 来自已淘汰出「最近 5 个版本」列表的旧版本时，退而从
    `/releases/download/<tag>/` 里取，取不到就返回空串（UI 不显示版本说明）。
    """
    if not url:
        return ""
    for r in releases or []:
        for a in r.get("assets") or []:
            if a.get("url") == url:
                return r.get("tag") or ""
    m = re.search(r"/releases/download/([^/]+)/", url)
    return unquote(m.group(1)) if m else ""
