#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把随附的《A319/A320/A321 机型正常检查单（RNP APCH 版）》PDF 转成文字版检查单。

为什么要转：PDF 是整页光栅化渲染的，放大到 2.5 倍就是把位图拉大，必然发虚。
检查单本质是「项目 + 应答」两列文字，转成结构化数据后可以逐条勾选、按阶段折叠，
字号跟随系统设置，任何缩放级别都清晰。

输入：app/src/main/assets/docs/a320_normal_checklist.pdf（2 页，含真实文字层）
输出：app/src/main/assets/checklists_cn.json

解析规则（对着原版版式写的）：
1. 阶段标题是独立一行，去掉排版用的空格后与 PHASES 里的一项完全相等；
2. 条目行用连续的点（… 或 .）把「项目」和「应答」分开；
3. 没有点线的行是「只有挑战、没有应答」的项目（比如 客舱准备好*）；
4. 页眉页脚、"注：" 说明、纯分隔线一律丢弃；
5. 原文标记保留：(B) 双人确认、# RVSM 项目、* 客舱项目、【】RNP 项目。
"""

import json
import os
import re
import sys

from pypdf import PdfReader

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PDF = os.path.join(ROOT, "app", "src", "main", "assets", "docs", "a320_normal_checklist.pdf")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "checklists_cn.json")

# 阶段顺序照原版。名字里的空格在原 PDF 里是排版撑开的，这里全部去掉。
PHASE_NAMES = [
    "开车前",
    "开车后",
    "起飞前",
    "起飞后/爬升",
    "平飞巡航#",
    "下降（距下降顶点50海里）",
    "进近（过渡高度层后）FAF点前",
    "着陆",
    "着陆后",
    "停机",
    "离机安全",
    "紧急撤离",
]

# 「允许推出/开车后：」是开车前里的一条分界，原版用虚线隔开；
# 它后面的项目要等推出/开车许可之后才做，单独拆成一个阶段更贴近实际用法。
PUSHBACK_PHASE = "开车前 · 推出开车后"
PUSHBACK_MARK = "允许推出/开车后"

# 页眉页脚与说明行
DROP_PREFIXES = (
    "A319/A320/A321 机型正常检查单",
    "【RNP APCH】版",
    "注：",
)

DOT_RUN = re.compile(r"[.…]{2,}")


CJK = "\u4e00-\u9fff\u3000-\u303f\uff00-\uffef"


def squeeze(text: str) -> str:
    """压掉排版空格：连续空格留一个，汉字之间的空格直接删掉。

    原 PDF 用拉开字距来对齐两列，抽出来就是「自动 推力」「复 飞」这种，
    不处理的话手机上看着像错别字。
    """
    text = re.sub(r"\s+", " ", text).strip()
    return re.sub(r"(?<=[%s])\s+(?=[%s])" % (CJK, CJK), "", text)


def phase_key(text: str) -> str:
    return re.sub(r"\s+", "", text)


def is_separator(text: str) -> bool:
    return bool(re.fullmatch(r"[-—_]{3,}", text.strip()))


def parse_page(text: str):
    """逐行归类：(阶段名, [(项目, 应答), ...])，按出现顺序返回。"""
    blocks = []
    current = None
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if is_separator(line):
            continue
        if any(line.startswith(p) for p in DROP_PREFIXES):
            continue

        key = phase_key(line)
        if key in PHASE_NAMES:
            current = (key, [])
            blocks.append(current)
            continue
        if key.startswith(phase_key(PUSHBACK_MARK)):
            current = (PUSHBACK_PHASE, [])
            blocks.append(current)
            continue
        if current is None:
            continue

        parts = DOT_RUN.split(line, maxsplit=1)
        item = squeeze(parts[0])
        state = squeeze(parts[1]) if len(parts) > 1 else ""
        # 只由点线组成的行没有意义
        if not item or re.fullmatch(r"[.…]+", item):
            continue

        # 【】是 RNP 项目的标记，点线常把右括号切到应答里，这里补回项目一侧
        if item.startswith("【"):
            if state.endswith("】"):
                state = state[:-1].strip()
            if not item.endswith("】"):
                item = item + "】"

        # 续行：原版把一句说明拆成两行（紧急撤离里的「若不为零…」）。
        # 只在上一行是「没有应答、带逗号的整句、还没写完」时才接，
        # 否则会把「旅客信号牌接通 / 客舱准备好*」这种连续条目粘成一条。
        if current[1] and not state and not item.startswith(("-", "■", "【")):
            prev_item, prev_state = current[1][-1]
            if not prev_state and "，" in prev_item and not prev_item.endswith(("。", "：")):
                current[1][-1] = (prev_item + item, "")
                continue

        current[1].append((item, state))
    return blocks


def main():
    if not os.path.isfile(PDF):
        print("找不到 PDF：%s" % PDF, file=sys.stderr)
        return 1

    reader = PdfReader(PDF)
    phases = []
    seen = {}
    for page in reader.pages:
        for name, items in parse_page(page.extract_text() or ""):
            if not items:
                continue
            if name in seen:
                # 同名阶段跨页时合并
                phases[seen[name]][1].extend(items)
            else:
                seen[name] = len(phases)
                phases.append([name, list(items)])

    if not phases:
        print("没有解析出任何阶段，检查 PDF 是否仍是文字版", file=sys.stderr)
        return 1

    payload = {
        "schemaVersion": 1,
        "source": "用户提供的《A319/A320/A321 机型正常检查单（RNP APCH 版）》",
        "notice": "由 tools/build_a320_cn.py 从 PDF 文字层转换；标记说明："
                  "(B) 双人确认，# RVSM 项目，* 客舱项目，【】RNP 项目。仅用于模拟飞行。",
        "aircraft": [
            {
                "id": "cn_a320_normal",
                "category": "airbus",
                "name": "A319/A320/A321 正常检查单",
                "note": "RNP APCH 版 · 文字版",
                "source": "app/src/main/assets/docs/a320_normal_checklist.pdf",
                "license": "用户自备资料",
                "phases": [
                    {
                        "name": name,
                        "gloss": "",
                        "items": [{"item": i, "state": s} for i, s in items],
                    }
                    for name, items in phases
                ],
            },
        ],
    }

    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=1)
        fh.write("\n")

    total = sum(len(p["items"]) for p in payload["aircraft"][0]["phases"])
    print("写出 %s" % OUT)
    for p in payload["aircraft"][0]["phases"]:
        print("  %-28s %3d 条" % (p["name"], len(p["items"])))
    print("合计 %d 个阶段 / %d 条" % (len(payload["aircraft"][0]["phases"]), total))
    return 0


if __name__ == "__main__":
    sys.exit(main())
