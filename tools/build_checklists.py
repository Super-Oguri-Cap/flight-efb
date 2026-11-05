# -*- coding: utf-8 -*-
import json
import os
import sys

# 原始清单缓存目录，由 tools/fetch_sources.py 或手工解压仓库归档得到
SOURCE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "source")
REPO = "rkaw92/simchecklists"
REF = "main"

# 机型 -> (分类, 显示名, 备注)。分类只有 airbus / boeing / other，
# 原仓库里的 DCS 战斗机不收。
CATALOG = [
    ("a310.txt", "airbus", "Airbus A310", "iniBuilds"),
    ("a320neo.txt", "airbus", "Airbus A320neo", ""),
    ("a32x_simple.txt", "airbus", "Airbus A32x 家族（简化）", ""),
    ("a330.txt", "airbus", "Airbus A330 / Beluga", ""),
    ("b727.txt", "boeing", "Boeing 727", "FlyJSim"),
    ("b738x.txt", "boeing", "Boeing 737-800", ""),
    ("b748.txt", "boeing", "Boeing 747-800", ""),
    ("b461.txt", "other", "BAe 146（简化）", ""),
    ("rj85.txt", "other", "Avro RJ70/85/100", ""),
    ("b58_xp.txt", "other", "Beechcraft Baron 58", ""),
    ("bonanza_g36.txt", "other", "Beechcraft Bonanza G36", ""),
    ("c152.txt", "other", "Cessna 152", ""),
    ("c172_530.txt", "other", "Cessna 172 Skyhawk (G530)", ""),
    ("c337.txt", "other", "Cessna 337 Skymaster", "Thranda/X-Plane"),
    ("c750.txt", "other", "Cessna Citation X", ""),
    ("cessna_cj4.txt", "other", "Cessna Citation CJ4", ""),
    ("f260.txt", "other", "SIAI-Marchetti SF-260D", "Jrollon/X-Plane"),
    ("leg2.txt", "other", "Lancair Legacy", "Aerobask/X-Plane"),
    ("p28a_161.txt", "other", "Piper Warrior 2", ""),
    ("robin_dr400.txt", "other", "Robin DR400/100 Cadet", ""),
    ("tbm900.txt", "other", "TBM900", ""),
]

# 阶段名 -> 中文解释。原清单各机型的阶段命名不统一，
# 这里按小写全名匹配，不做近义词推断，避免把不同阶段混为一谈。
PHASE_GLOSS = {
    "pre start": "启动前",
    "prestart": "启动前",
    "before start": "启动前",
    "prior to before start": "启动前准备",
    "preflight": "飞行前",
    "pre flight": "飞行前",
    "pre-flight": "飞行前",
    "cockpit initial setup": "座舱初始设置",
    "cockpit preparation": "座舱准备",
    "cockpit safety inspection": "座舱安全检查",
    "cockpit setup": "座舱设置",
    "preliminary cockpit preparation initial": "座舱初步准备（初始）",
    "preliminary cockpit preparation pilots": "座舱初步准备（飞行员）",
    "preliminary cockpit preparation flight engineer": "座舱初步准备（随机工程师）",
    "preliminary cockpit preparation flight engineer continued": "座舱初步准备（随机工程师，续）",
    "flight deck safety": "驾驶舱安全",
    "exterior safety inspection": "外部安全检查",
    "crew at station": "机组就位",
    "startup": "启动",
    "start": "启动",
    "starting": "启动",
    "startup engine": "发动机启动",
    "engine start": "发动机启动",
    "engines start": "发动机启动",
    "starting engine": "发动机启动",
    "before engine start": "发动机启动前",
    "cleared for engine start": "获得启动许可",
    "external power start": "外电源启动",
    "apu start": "APU 启动",
    "after start": "启动后",
    "after engine start": "发动机启动后",
    "after starting engine": "发动机启动后",
    "before taxi": "滑行前",
    "pre-taxi": "滑行前",
    "taxi": "滑行",
    "taxiing": "滑行",
    "taxi check": "滑行检查",
    "taxi out": "滑出",
    "taxi out (if loose gravel/fod present, consider rear-engine taxi)": "滑出（有碎石或外来物时考虑后置发动机滑行）",
    "taxi in": "滑入",
    "taxi to parking": "滑至停机位",
    "run up": "试车",
    "ground check": "地面检查",
    "on ramp checks": "停机坪检查",
    "system check": "系统检查",
    "systems test": "系统测试",
    "before take off": "起飞前",
    "before takeoff": "起飞前",
    "before take-off": "起飞前",
    "line up": "进跑道",
    "cleared for takeoff": "获得起飞许可",
    "normal take-off": "正常起飞",
    "maximum performance take-off": "最大性能起飞",
    "gravel/cinder field take-off": "碎石场地起飞",
    "take off": "起飞",
    "takeoff": "起飞",
    "after take off": "起飞后",
    "after takeoff": "起飞后",
    "after take-off": "起飞后",
    "after take off / climb": "起飞后爬升",
    "initial climb": "起始爬升",
    "normal climb": "正常爬升",
    "maximum performance climb": "最大性能爬升",
    "climb": "爬升",
    "climb check": "爬升检查",
    "climb / cruise": "爬升与巡航",
    "cruise": "巡航",
    "cruising": "巡航",
    "through flight": "过站",
    "flight": "飞行",
    "in flight": "飞行中",
    "descent": "下降",
    "descent (for cold weather, observe temps, keep adequate power)": "下降（低温时注意温度并保持足够功率）",
    "descent / approach": "下降与进近",
    "preparation for approach": "进近准备",
    "approach": "进近",
    "approach & landing": "进近与着陆",
    "approach / landing": "进近与着陆",
    "approach/landing": "进近与着陆",
    "final approach": "最后进近",
    "downwind": "三边",
    "pre landing": "着陆前",
    "before landing": "着陆前",
    "landing": "着陆",
    "landing checklist": "着陆检查单",
    "final glideslope descent": "最后进近下降",
    "after landing": "着陆后",
    "after landing / taxi": "着陆后滑行",
    "flight termination": "飞行终止",
    "shutdown": "关车",
    "shutdown checklist": "关车检查单",
    "engine shutdown": "发动机关车",
    "parking": "停放",
    "securing aircraft": "飞机安保",
    "secure": "安保",
    "leaving aircraft": "离机",
}


def fetch(name):
    path = os.path.join(SOURCE_DIR, name)
    if not os.path.isfile(path):
        raise SystemExit("缺少原始清单 %s，先运行 tools/fetch_sources.py" % path)
    with open(path, encoding="utf-8") as f:
        return f.read()


def gloss(phase):
    return PHASE_GLOSS.get(phase.strip().lower(), "")


def parse(text):
    phases = []
    current = None
    seen = set()
    for raw_line in text.split("\n"):
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("--") and line.endswith("--") and len(line) > 4:
            title = line.strip("-").strip()
            if title.lower().startswith("checklist by"):
                continue
            if title in seen:
                current = None
                continue
            seen.add(title)
            current = {"name": title, "gloss": gloss(title), "items": []}
            phases.append(current)
            continue
        if "=" in line and current is not None:
            item, _, state = line.partition("=")
            current["items"].append({"item": item.strip(), "state": state.strip()})
    return [p for p in phases if p["items"]]


def build():
    aircraft = []
    total = 0
    for filename, category, display, note in CATALOG:
        phases = parse(fetch(filename))
        count = sum(len(p["items"]) for p in phases)
        total += count
        missing = [p["name"] for p in phases if not p["gloss"]]
        if missing:
            raise SystemExit("阶段名缺少中文解释: %s -> %s" % (filename, missing))
        aircraft.append({
            "id": filename.replace(".txt", ""),
            "category": category,
            "name": display,
            "note": note,
            "source": "https://github.com/%s/blob/%s/lists/%s" % (REPO, REF, filename),
            "license": "MIT",
            "phases": phases,
        })
        print("%-20s %-24s 阶段 %2d 条目 %3d" % (filename, display, len(phases), count))
    return {
        "schemaVersion": 1,
        "source": "https://github.com/%s" % REPO,
        "license": "MIT",
        "notice": "清单正文来自 simchecklists（MIT），for simulation only；"
                  "阶段名的中文解释由本工程补充。",
        "aircraft": aircraft,
        "totalItems": total,
    }


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "checklists.json"
    data = build()
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print("机型 %d 个 / 条目 %d 条 / 输出 %s (%.1f KB)"
          % (len(data["aircraft"]), data["totalItems"], out,
             os.path.getsize(out) / 1024))
    return 0


if __name__ == "__main__":
    sys.exit(main())