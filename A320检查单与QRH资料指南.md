# A320 快速检查单 / QRH：从哪找

> 结论先说：**检查单容易搞到、能合法用；真正的 QRH（FCOM 那份受控手册）搞不到也不该搞。**
> 正确做法是**用 ECAM 告警标题自建映射表，而不是去下载一本 QRH PDF**。
> 全部资料 **for simulation only**，不进 Git、不分发。

## 1. 先分清三样东西

很多人把这三个混为一谈，导致方向跑偏：

| 东西 | 是什么 | 能合法拿到吗 | 本项目要不要 |
|---|---|---|---|
| **快速检查单 / SOP** | 起飞前、进近、着陆等阶段逐项确认的清单 | ✅ **能**，社区有大量 sim 专用版本 | ✅ v2 的核心资料 |
| **QRH**（Quick Reference Handbook） | 航司受控文件，非正常/应急程序 + OEB + 性能 | ❌ **拿不到**，属 Airbus 授权文件 | ⚠️ 只借"结构"，不下载原件 |
| **ECAM 告警 → 处置** | A320 上由 ECAM 自动弹出的动作序列 | ✅ **能**，从模拟器故障注入列表反推 | ✅ 这才是真需求 |

**关键认知**：A320 的处置逻辑主要在 **ECAM 自动显示**里，QRH 是补充（复位表、OEB、
非 ECAM 程序、性能与限制）。你要的「告警码 → 页码直达」，本质是 **ECAM 告警 → 处置条目** 的映射表，
拆成条目正好塞进 `alert → file + page` 索引。**不需要一本 QRH PDF。**

## 2. 快速检查单：三个可用来源（按推荐度）

### ① simchecklists（⭐ 首选，已实测可下载）

- 地址：https://github.com/rkaw92/simchecklists/blob/main/lists/a320neo.txt
- 内容：**196 行、4.04 KB**，我完整读过，覆盖 Pre Start / Startup / Before Taxi / Taxi /
  Before Take Off / Take Off / Climb / Cruise / Descent / Approach / Final /
  Landing / After Landing / Shutdown **共 14 个阶段**
- 格式：纯文本，`--阶段名--` 分节，`项目=状态` 一行一条，**转 Markdown 复选框几乎零成本**
- 许可：仓库为开源，清单注明作者 tibonev + 校对者 Swisskommando
- 免责：README 明确 "for simulation purposes only"
- 还有 `a32x_simple.txt`（简化版，适合快速上手）

> 转成 App 用的 Markdown 只需一行 shell：
> `sed -e 's/^--\(.*\)--$/## \1/' -e 's/^\(.*\)=\(.*\)$/- [ ] \1 → \2/' a320neo.txt`

### ② FlyByWire A32NX 文档（⭐ 权威，社区维护）

- 地址：https://docs.flybywiresim.com/
- 新手引导：https://docs.flybywiresim.com/pilots-corner/a32nx/a32nx-beginner-guide/overview/
- 系统简报（含 ECAM 详解）：https://docs.flybywiresim.com/pilots-corner/a32nx/a32nx-briefing/
- 优点：**由 A320 型别等级飞行员审核**，2026 年仍活跃维护，MkDocs 结构规整
- 缺点：对应 **MSFS 的 A32NX**，ECAM 细节与 ToLiss 有差异
- 用法：**只借程序框架**；记忆项目、数值、限制以你 ToLiss 的 Aircraft Manual 为准

### ③ FlightGear A320-family（GPL-2.0，可改造）

- 地址：https://github.com/noteas/A320-family
- 含 `A32X-Checklists.xml`，覆盖 A320-251N / 271N
- 优点：**GPL-2.0 明确可改造**，XML 结构化好解析
- 缺点：偏老，程序细节不如前两个

### ④ flightsim.to 的 A32NX Checklist v1.1

- 地址：https://cs.flightsim.to/file/24601/airbus-a32nx-a320neo-checklist-procedure
- 2021-12-14，181 KB，**LaTeX 源开源**
- 排版精美，适合直接转 PDF 当"美观版"

## 3. QRH：正确姿势

### 3.1 先翻本机（优先级最高，别急着上网）

**ToLiss A320neo 的 `manuals/` 目录**（注意：FF 777 的在 `docs/`，ToLiss 的在 `manuals/`，别找错）

通常有 3 本：
- Simulation Manual
- Tutorial Flight
- **Aircraft Manual**（SOP + 系统深入）

外加 EFB 内**可自定义的交互式检查单**。
装 ceo 扩展合并后可能有 5 个 PDF。

> ⚠️ ToLiss **不一定有独立成册的 QRH PDF**，更可能要从 Aircraft Manual 拆章建页码索引。

### 3.2 用 ToLiss 反推告警清单（最靠谱的做法）

ToLiss A320neo 提供 **210+ 种可注入故障**（ISCS 面板）。
操作步骤：
1. 打开 ISCS 故障注入列表
2. **抄出所有 ECAM 标题**（如 `ENG 1 FAIL`、`HYD G SYS LO PR`、`ELEC EMER CONFIG`）
3. 对照上面 ② FlyByWire 的 ECAM 页，补全每条的动作序列
4. 生成 `alert → 章节 + 页码` 映射表

这样索引**天然贴合你实际会遇到的故障**，比抄一本真 QRH 有用得多。

### 3.3 SmartCockpit（🟡 可用但要注意）

- 地址：https://www.smartcockpit.com/my-aircraft/airbus-a320/
- 有一份份独立 PDF：Engine Failure (damage/no damage)、Engine Out After Takeoff、
  Dual HYD Fault、Dual ADR Fault、ELEC EMER CONFIG、Emergency Descent、ECAM Philosophy…
- **这些正是 QRH 条目**
- ⚠️ 注意：
  - 免责声明：仅培训用途、非商业、不得删版权声明、非最新修订
  - **A320 family 通用，非 neo 专属**
  - 站点 DNS 偶尔不稳（我实测时解析失败），需要时用 VPN 或换时间再试

### 3.4 ⛔ 别碰

- **Scribd / PDFCoffee 上的"完整 A320 FCOM / QRH"扫描件** —— 未经空客授权转载
- **真实航司的 QRH** —— 受控文件，泄露有法律风险
- **chinaflier 的《A320 系列快速飞行手册》**（2014）—— 版权不明、ceo 时代、
  不含 PW1100G / LEAP-1A 程序。只作入门读物，**不进主索引**

## 4. 合规分级（照这个处理就不会出事）

| 级别 | 情形 | 处理 |
|---|---|---|
| 🔴 高 | 真实航司 QRH/FCOM 扫描件、Jeppesen 图、NAIP 批量抓取件 | 只本地存放，**不进 Git、不上云、不分发**；索引只存"告警码 + 页码"元数据，即使泄露也不构成内容分发 |
| 🟡 中 | SmartCockpit 资料（family 通用、版本偏旧、非 neo 专属） | 每条写 `note`，标 `usage: training-only`，保留版权声明，**不得转发** |
| 🟡 中 | FlyByWire 文档（对应 MSFS A32NX，ECAM 逻辑与 ToLiss 不一致） | 只借程序框架；数值以 ToLiss 为准 |
| 🟢 低 | 已购插件 `manuals/`（ToLiss）、完全自编内容 | 无限制，但**仍不进公开仓库** |
| ⛔ 禁止 | 公开 GitHub Pages / 公开仓库分发任何资料 | 与"绝不公开"约束直接冲突，不做 |

## 5. 落地清单（v2 开工时照着做）

- [ ] 从 simchecklists 下载 `a320neo.txt`，转成 14 个阶段的 Markdown 复选框
- [ ] 在 ToLiss `manuals/` 里确认 Aircraft Manual 的章节结构（页码从哪开始）
- [ ] 打开 ToLiss ISCS 故障注入，抄出全部 ECAM 标题到 `alerts.csv`
- [ ] 用 FlyByWire ECAM 页补全每条的动作序列
- [ ] 挑 20–30 条高频告警（ENG FAIL / HYD / ELEC / EMER DESCENT…）先做 MVP
- [ ] 每条记 `SOURCES.md`：文件名 / 来源 / URL / 下载日期 / 许可 / usage 级别
- [ ] 全部标注 **for simulation only**

## 6. 一句话回答你的两个问题

- **A320 快速检查单**：https://github.com/rkaw92/simchecklists/blob/main/lists/a320neo.txt
  （196 行纯文本，14 阶段，转 Markdown 一行命令）
- **QRH**：**别下载**。真 QRH 拿不到也不该拿。正确做法是用 ToLiss 的 210+ 故障注入列表
  反推 ECAM 告警清单，配上 FlyByWire 的公开 ECAM 文档，自建「告警 → 页码」映射表。
