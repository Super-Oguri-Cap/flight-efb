# 检查单与 QRH

资料用途：**for simulation only**。不得用于真实飞行。

## 1. 内容构成

| 部分 | 机型数 | 条目数 | 来源 | 许可 |
|---|---|---|---|---|
| 检查单 | 21 | 2208 | simchecklists | MIT |
| 随附检查单（文字版） | 1（A319/A320/A321） | 106 | 用户提供的 PDF，`tools/build_a320_cn.py` 转换 | 用户自备 |
| QRH | 4 类分组 | 16 | 本工程自行整理 | 仅自用 |

### 1.1 随附检查单为什么要转文字

A320 正常检查单最初是以 PDF 形式内置的，阅读页用 `PdfRenderer` 整页光栅化到 1.5 倍屏宽，
双指放大到 2.5 倍时等于把位图再拉大 1.67 倍，文字边缘必然发虚；而检查单本来就是
「项目 + 应答」两列文字，用不着当图片看。

于是把它转成结构化数据 `assets/checklists_cn.json`：

- 转换脚本：`python tools/build_a320_cn.py`（读 `assets/docs/a320_normal_checklist.pdf` 的文字层）
- 阶段按原版顺序：开车前 → 开车前·推出开车后 → 开车后 → 起飞前 → 起飞后/爬升 → 下降 → 进近 → 着陆 → 着陆后 → 停机 → 离机安全 → 紧急撤离
- 保留原版标记：`(B)` 双人确认、`#` RVSM 项目、`*` 客舱项目、`【】` RNP 项目
- 条目支持逐条勾选，每份检查单一个「重置」；勾选进度存在 `efb_checklist_progress` 里
- 原件 PDF 仍留在「随附手册」里，用于与文字版对照

检查单覆盖空客 A310 / A320neo / A32x 家族 / A330，波音 727 / 737-800 / 747-800，以及其他 14 个机型（BAe 146、Avro RJ、Beechcraft、Cessna、Piper、Robin、TBM 等）。

QRH 覆盖 A320 的记忆项目与独立应急程序，以及波音机型的共通记忆项目。

## 2. 检查单

### 2.1 数据来源

原始清单来自 [simchecklists](https://github.com/rkaw92/simchecklists)（MIT 许可），共 21 份文本文件，缓存在 `tools/source/`。

原文格式是：

```
--Pre Start--
PARKING BRAKE=SET
CHOCKS=REMOVED
```

构建脚本把它转成 JSON：`--阶段--` 变成 `phases[].name`，每行 `项目=状态` 拆成 `items[].item` 与 `items[].state`。

### 2.2 中文解释

原文阶段名是英文且各机型写法不统一（`Pre Start` / `Before Start` / `Cockpit Preparation` / `Prior To Before Start` 都表示启动前）。

构建脚本里有一张 `PHASE_GLOSS` 对照表，按小写全名精确匹配，不做近义词推断。**任何阶段名匹配不上，脚本直接报错退出**，不会静默产出空解释。当前 21 个机型的阶段名全部有解释。

项目与状态本身保持英文原文，和座舱里看到的字样一致。

### 2.3 重新生成

```powershell
# 首次或换机器时先取原始清单
# 从 https://codeload.github.com/rkaw92/simchecklists/tar.gz/refs/heads/main 下载
# 解压后把 lists/ 下的 *.txt 放进 tools/source/

python tools/build_checklists.py checklists.json
Copy-Item checklists.json app/src/main/assets/checklists.json -Force
```

脚本输出的统计：

```
a310.txt             Airbus A310              阶段 10 条目 121
a320neo.txt          Airbus A320neo           阶段 14 条目 180
...
机型 21 个 / 条目 2208 条 / 输出 checklists.json (199.8 KB)
```

## 3. QRH

### 3.1 为什么是自编的

真实的 A320 QRH 是空客的受控文件，拿不到也不该拿。A320 的处置逻辑主要在 **ECAM 自动显示**里，QRH 只是补充。所以正确做法是自建「ECAM 告警 → 处置」映射，而不是去下载一本 QRH PDF。

本工程的 QRH 按 A320 公开的 ECAM 体系整理，条目结构与真实 ECAM 一致（ATA 章节、告警标题、动作序列、INOP SYS），内容为自行编写。

### 3.2 分组

`qrh.json` 分三个部分：

| 分组 | 机型 | 类型 | 条目数 |
|---|---|---|---|
| A320 记忆项目 | A320 | memory | 8 |
| A320 独立应急程序 | A320 | procedure | 4 |
| 波音记忆项目 | 737 / 747 / 727 | memory | 4 |

每条包含：

| 字段 | 含义 |
|---|---|
| `ata` | ATA 章节号 |
| `title` | 告警标题，与 ECAM 显示一致 |
| `condition` | 触发条件 |
| `memory` | 是否记忆项目（必须不查手册立即执行） |
| `actions` | 动作序列，逐行 |
| `inop` | 失效系统 |

### 3.3 编辑

直接改 `qrh.json`，然后：

```powershell
Copy-Item qrh.json app/src/main/assets/qrh.json -Force
# 重新编译
```

JSON 结构受 `ReferenceLibraryTest` 校验：每条必须有 `ata`、`title`、`condition`、非空 `actions`。

## 4. 界面

底部三个标签：航图、检查单、QRH。每个标签维护自己的页面栈，按返回键回到该标签的根页面。

| 标签 | 层级 |
|---|---|
| 航图 | 搜索 → 机场 → 阅读，设置 |
| 检查单 | 机型列表（按空客/波音/其他分组）→ 阶段折叠列表 |
| QRH | 分组列表 → 条目折叠列表（展开看动作序列） |

检查单与 QRH 都是只读，不带勾选，不记录进度。

## 5. 未覆盖的机型

777 与 757 不在 simchecklists 里，GitHub 与公开资料中也找不到带明确许可的现成文本。需要时从 FlightFactor 自带的 `docs` 目录里取出检查单文件，按第 2 节的格式解析后加入。

DCS 战斗机（Su-25T / Su-27）的清单虽然在原仓库里，本工程按你的要求不收。
