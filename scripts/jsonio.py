#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""数据文件的统一读写入口：写回时沿用文件原有格式，保证字节稳定。

为什么需要这个文件
------------------
仓库里曾有 29 个脚本各自调 ``json.dump``，缩进写成 0 / 1 / 2 / 单行四种。
``data/emulator-registry.json`` 一天里被 refresh / derive / apply 三组脚本
轮流写，谁最后跑、文件就变成谁的缩进——2026-11-22 收录 45 条时因此报出
18.3 万行假 diff，把真正的 5913 行新增淹没在里面。

这里的规则只有一条：**写回时保留文件原本的格式**（缩进宽度、是否单行、
末尾有没有换行）。不强制统一风格——那会产生一次同样巨大的重排 diff；
只保证「同一份文件，不管被哪个脚本写，字节都一样」。

新文件用 ``indent=2``、无末尾换行，与库里绝大多数数据文件一致。

用法
----
    from jsonio import load, save

    doc = load(REGISTRY)                 # 读，等价于 json.load
    doc["emulators"].append(item)
    save(REGISTRY, doc)                  # 写，格式跟着原文件走

    save(path, doc, indent=2)            # 显式指定缩进（新文件或不信任嗅探时）
    save(path, doc, sort_keys=True)      # build_playable 这类要求键有序的产物

``save`` 返回 ``True`` 表示磁盘内容确实变了，``False`` 表示写出去和原来
一模一样（此时不落盘，mtime 不动，后续 ``git diff`` 也不会多出一条）。
"""
import json
import os
import re
import tempfile
from typing import Any, NamedTuple, Optional

__all__ = ["Style", "load", "save", "dumps", "sniff", "AUTO"]

DEFAULT_INDENT = 2
AUTO = "auto"


class Style(NamedTuple):
    """一份 JSON 文件在磁盘上的写法。"""

    indent: Optional[int]  # None 表示整个文件压成一行
    compact: bool          # 分隔符不带空格：{"a":1,"b":2}
    trailing_newline: bool  # 文件末尾是否有换行


DEFAULT_STYLE = Style(DEFAULT_INDENT, False, False)

_LEADING_SPACES = re.compile(r" *")


def load(path: str, default: Any = None) -> Any:
    """读 JSON；文件不存在时返回 ``default``（而不是抛异常）。"""
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return default


def sniff(path: str) -> Style:
    """从磁盘上的文件反推它的写法；文件不存在或为空时给默认写法。

    只做够用的启发式，不追求覆盖所有历史写法：
    第二行的前导空格数就是 indent；整个文件只有一行就是压缩输出。
    """
    try:
        with open(path, encoding="utf-8", newline="") as f:
            raw = f.read()
    except (FileNotFoundError, IsADirectoryError):
        return DEFAULT_STYLE
    if not raw.strip():
        return DEFAULT_STYLE

    trailing = raw.endswith("\n")
    body = raw.rstrip("\n")
    first_break = body.find("\n")
    if first_break < 0:
        # 单行：要么 separators 无空格，要么是纯短对象。两者都按压缩处理，
        # 差别只在逗号后有没有空格，这里用是否有 '", "' 判断。
        return Style(None, '", "' not in body, trailing)

    second = body[first_break + 1:]
    if not second.strip():
        # 形如 "{\n}" 的空对象
        return Style(DEFAULT_INDENT, False, trailing)
    indent = len(_LEADING_SPACES.match(second).group(0))
    return Style(indent, False, trailing)


def dumps(obj: Any, style: Style = DEFAULT_STYLE, *,
          sort_keys: bool = False, ensure_ascii: bool = False) -> str:
    """按给定写法序列化，结果与 ``json.dump`` 写出的字节一致。"""
    if style.indent is None:
        separators = (",", ":") if style.compact else (", ", ": ")
        text = json.dumps(obj, ensure_ascii=ensure_ascii,
                          separators=separators, sort_keys=sort_keys)
    else:
        text = json.dumps(obj, ensure_ascii=ensure_ascii,
                          indent=style.indent, sort_keys=sort_keys)
    if style.trailing_newline:
        text += "\n"
    return text


def save(path: str, obj: Any, *, indent: Any = AUTO, style: Optional[Style] = None,
         sort_keys: bool = False, ensure_ascii: bool = False) -> bool:
    """把 ``obj`` 写回 ``path``，保留原文件格式；内容没变则不动磁盘。

    同一份内容要写多个副本时（data/ 主库 + app assets 副本），先对主库
    ``sniff`` 一次再传 ``style=``，两份才会写成同一格式——各自嗅探的话，
    副本一旦被历史遗留格式污染，就会永远和主库对不齐。

    先写临时文件再 ``os.replace``：中途被中断时，读者看到的仍是上一份
    完整文件，不会读到截断的半截 JSON。
    """
    if style is None:
        if indent is AUTO:
            style = sniff(path)
        else:
            base = sniff(path)
            # 显式要求单行时，保留原文件的分隔符写法（`{"a": 1}` vs `{"a":1}`）；
            # 文件还不存在则按紧凑写，这是「单行」这个要求的本意。
            compact = base.compact if os.path.exists(path) else indent is None
            style = Style(indent, compact if indent is None else False, base.trailing_newline)

    text = dumps(obj, style, sort_keys=sort_keys, ensure_ascii=ensure_ascii)

    try:
        with open(path, encoding="utf-8", newline="") as f:
            if f.read() == text:
                return False
    except FileNotFoundError:
        pass

    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=directory, prefix=".jsonio-", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
    return True
