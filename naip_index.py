import argparse
import csv
import hashlib
import json
import os
import re
import sqlite3
import sys
from datetime import datetime

# 目录前缀 -> 图型枚举，与 AIP 航图库的二级目录命名一致
CHART_TYPES = [
    ("01_", "ADC"),      # 机场图
    ("02_", "GMC"),      # 滑行道图
    ("03_", "APDC"),     # 机位停靠图
    ("04_", "SID"),      # 离场图
    ("05_", "IAC"),      # 进近图
    ("06_", "STAR"),     # 进场图
    ("07_", "AOC"),      # 障碍物与地形图
    ("08_", "ATCSMAC"),  # 监视最低高度图
    ("09_", "CODING"),   # 编码表与航路点
    ("10_", "OTHER"),
]

AIRPORT_DIR_RE = re.compile(r"^(Z[A-Z]{3})_(.+)$")
RWY_RE = re.compile(r"RWY\s*([0-9]{2}[LRC]?)", re.IGNORECASE)
ENR_PREFIX = "00_"

DB_SCHEMA = """
CREATE TABLE charts(
    id TEXT PRIMARY KEY,
    icao TEXT,
    airport TEXT,
    chartType TEXT,
    title TEXT,
    file TEXT,
    rwys TEXT
);
CREATE INDEX idx_charts_icao ON charts(icao);
CREATE INDEX idx_charts_type ON charts(chartType);
CREATE TABLE airports(
    icao TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    chartCount INTEGER NOT NULL
);
CREATE INDEX idx_airports_name ON airports(name);
"""


def chart_type_of(dirname):
    for prefix, name in CHART_TYPES:
        if dirname.startswith(prefix):
            return name
    return None


def airport_of(dirname):
    # 目录名形如 ZBAA_北京_首都 或 ZSYW_义乌；非机场目录返回空
    m = AIRPORT_DIR_RE.match(dirname)
    if not m:
        return None, None
    return m.group(1), m.group(2).replace("_", "")


def build(root):
    docs = []
    review = []
    now = datetime.now().astimezone().isoformat(timespec="seconds")

    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for fn in sorted(filenames):
            if not fn.lower().endswith(".pdf"):
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            parts = rel.split("/")

            icao = None
            airport = None
            ctype = None
            if len(parts) > 1:
                icao, airport = airport_of(parts[0])
            if len(parts) >= 3:
                ctype = chart_type_of(parts[1])

            if icao is None:
                if parts[0].startswith(ENR_PREFIX):
                    icao = "ENR"
                    airport = "通用资料"
                    ctype = "ENR"
                else:
                    review.append({"file": rel, "reason": "no_icao",
                                   "hint": "一级目录既不是机场目录也不是 00_ 通用资料"})
            elif ctype is None:
                review.append({"file": rel, "reason": "no_type",
                               "hint": "二级目录名不在图型枚举内"})

            title = os.path.splitext(parts[-1])[0]
            docs.append({
                "id": hashlib.sha256(rel.encode("utf-8")).hexdigest()[:12],
                "icao": icao,
                "airport": airport,
                "chartType": ctype,
                "title": title,
                "file": rel,
                "rwys": RWY_RE.findall(title),
            })

    counts = {}
    names = {}
    for d in docs:
        if not d["icao"]:
            continue
        counts[d["icao"]] = counts.get(d["icao"], 0) + 1
        names.setdefault(d["icao"], d["airport"])

    airports = [
        {"icao": icao, "name": names[icao], "chartCount": counts[icao]}
        for icao in sorted(counts)
    ]

    index = {
        "schemaVersion": 2,
        "generated": now,
        "root": os.path.abspath(root).replace(os.sep, "/"),
        "airports": airports,
        "documents": docs,
    }
    return index, review


def write_db(index, path):
    if os.path.exists(path):
        os.remove(path)
    con = sqlite3.connect(path)
    con.executescript(DB_SCHEMA)
    con.executemany(
        "INSERT INTO charts(id, icao, airport, chartType, title, file, rwys) VALUES (?,?,?,?,?,?,?)",
        [
            (d["id"], d["icao"] or "", d["airport"] or "", d["chartType"] or "",
             d["title"], d["file"], ",".join(d["rwys"]))
            for d in index["documents"]
        ],
    )
    con.executemany(
        "INSERT INTO airports(icao, name, chartCount) VALUES (?,?,?)",
        [(a["icao"], a["name"] or "", a["chartCount"]) for a in index["airports"]],
    )
    con.commit()
    con.execute("VACUUM")
    con.close()


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True, help="航图库根目录（只读）")
    ap.add_argument("--out", default="index.json")
    ap.add_argument("--db", default="charts.db")
    ap.add_argument("--review", default="review.csv")
    args = ap.parse_args(argv)

    if not os.path.isdir(args.root):
        print("目录不存在: %s" % args.root, file=sys.stderr)
        return 2

    index, review = build(args.root)

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)

    write_db(index, args.db)

    # utf-8-sig：Excel 直接双击不乱码
    with open(args.review, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["file", "reason", "hint"])
        w.writeheader()
        w.writerows(review)

    by_type = {}
    for d in index["documents"]:
        by_type[d["chartType"]] = by_type.get(d["chartType"], 0) + 1

    print("入库 %d 条 / 机场 %d 个 / 待复核 %d 条"
          % (len(index["documents"]), len(index["airports"]), len(review)))
    print("图型分布: %s" % json.dumps(by_type, ensure_ascii=False, sort_keys=True))
    print("输出: %s (%.1f KB) / %s (%.1f KB)"
          % (args.out, os.path.getsize(args.out) / 1024,
             args.db, os.path.getsize(args.db) / 1024))
    return 0


if __name__ == "__main__":
    sys.exit(main())
