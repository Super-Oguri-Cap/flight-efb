# -*- coding: utf-8 -*-
import io
import os
import sys
import tarfile
import urllib.request

# simchecklists 的清单文本。GitHub API 每小时只有 60 次配额，
# raw.githubusercontent.com 在部分网络下不通，因此整包下载仓库归档再解压。
ARCHIVE = "https://codeload.github.com/rkaw92/simchecklists/tar.gz/refs/heads/main"
PREFIX = "simchecklists-main/lists/"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "source")


def main():
    os.makedirs(OUT, exist_ok=True)
    print("下载", ARCHIVE)
    req = urllib.request.Request(ARCHIVE, headers={"User-Agent": "efb-index-builder"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        payload = resp.read()
    print("归档 %.1f KB" % (len(payload) / 1024))

    count = 0
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:gz") as tar:
        for member in tar.getmembers():
            if not member.isfile() or not member.name.startswith(PREFIX):
                continue
            name = member.name[len(PREFIX):]
            if not name.endswith(".txt") or name.startswith("su") or name.startswith("masterlist"):
                continue
            data = tar.extractfile(member).read()
            with open(os.path.join(OUT, name), "wb") as f:
                f.write(data)
            print("  %-22s %5d 字节" % (name, len(data)))
            count += 1
    print("共 %d 份清单写入 %s" % (count, OUT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
