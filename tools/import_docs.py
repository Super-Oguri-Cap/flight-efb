#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把用户的检查单 PDF 导入 APK 的 assets/docs 目录。

为什么要单独一步：仓库的 .gitignore 里有 `*.pdf`（航图与手册体积大、
且属于第三方内容），所以这两份文件不进版本库。换机器 clone 之后
跑一次本脚本即可恢复，否则检查单页打开手册时会提示「APK 里没有收录」。

用法（不给参数就用下面 DEFAULTS 里的路径）：
    python tools/import_docs.py
    python tools/import_docs.py --a320 "D:/某处/A320正常检查单.pdf" --qrh "D:/某处/QRH.pdf"
"""

import argparse
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSET_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "docs")

DEFAULTS = {
    "a320": (r"C:\Users\14644\Desktop\A320正常检查单.pdf", "a320_normal_checklist.pdf"),
    "qrh": (r"C:\Users\14644\Desktop\吉祥航空快速检查单（全机队版）-手册.pdf", "juneyao_qrh.pdf"),
}


def copy_one(src, name):
    if not os.path.isfile(src):
        print("跳过（源文件不存在）: %s" % src)
        return False
    os.makedirs(ASSET_DIR, exist_ok=True)
    dst = os.path.join(ASSET_DIR, name)
    shutil.copyfile(src, dst)
    print("已导入 %-28s <- %s" % (name, src))
    print("            %d 字节" % os.path.getsize(dst))
    return True


def main():
    parser = argparse.ArgumentParser(description="导入检查单 PDF 到 assets/docs")
    parser.add_argument("--a320", default=DEFAULTS["a320"][0], help="A320 正常检查单 PDF 路径")
    parser.add_argument("--qrh", default=DEFAULTS["qrh"][0], help="快速检查单手册 PDF 路径")
    args = parser.parse_args()

    ok_a320 = copy_one(args.a320, DEFAULTS["a320"][1])
    ok_qrh = copy_one(args.qrh, DEFAULTS["qrh"][1])

    if not (ok_a320 and ok_qrh):
        print("\n有文件没导入成功，检查单页对应的条目会打不开。")
        print("目标目录: %s" % ASSET_DIR)
        return 1
    print("\n两份都就位，重新打包即可带上这两份手册。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
