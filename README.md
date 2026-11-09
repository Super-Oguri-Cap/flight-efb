[en.README](https://github.com/Super-Oguri-Cap/flight-efb/blob/main/README.en.md)

# 飞行EFB

Android 手机上的离线 EFB：查 AIP 航图、跑检查单、算航路与油量。**全程无网络**，原始资料只读，导航数据由使用者自行导入。

> **仅供飞行模拟使用（for simulation only）**。本应用不含任何官方航图与导航数据，不得用于真实飞行。

当前版本 **1.3.6**（versionCode 13）。下载与校验信息见 [发行版说明.md](docs/发行版说明.md)。

## 功能

底部三个标签：

| 标签 | 内容 |
|---|---|
| 航图 | 四字码 / 中文名 / 图名搜索 → 按图型分组 → 阅读 PDF；双指缩放、双击缩放、放大后单指推图、反色、预渲染下一页 |
| 检查单 | 内置 22 个机型 / 2314 条，逐条勾选、两级重置；**自定义检查单**（复制一份后可改名 / 增删 / 排序，改动即时保存） |
| 航路/油量 | 内嵌两个单文件网页工具：**航路生成器**（自备导航数据 → 官方 / 引擎方案）与**油量计算器**（CAAC / FAA / EASA，支持日间 / 夜间模式） |

首次启动走四步引导：欢迎 → 授予文件权限 → 选航图库目录并扫描 → 查看结果。

## 工具页（航路 / 油量）

### 数据从哪来

APK 里**不含任何第三方导航数据**。首次进工具页需要自己导入 X-Plane 的导航文件：

| 文件 | 是否必需 | 作用 |
|---|---|---|
| `earth_fix.dat` | 必需 | 航路点 |
| `earth_awy.dat` | 必需 | 航段 |
| `earth_nav.dat` | 建议 | VOR / NDB，缺了少数以导航台为端点的航段连不上 |
| `earth_aptmeta.dat` | 必需 | 机场坐标；数据包里通常没有 `apt.dat`，缺它就没有机场可选 |

这四份文件在 X-Plane 的 `Custom Data`（没装订阅数据则在 `Resources/default data`）目录里。

### 导入一次，长期有效

- 导入成功后，解析结果会编码成一份 pack，写进**应用私有目录**（`filesDir`，不进 APK、不进仓库、其它应用读不到）
- 下次启动直接读回缓存应用，**不再重读几十兆原文件、也不重新解析**
- 要换数据就再导一次，整份覆盖
- 卸载应用会一并清掉缓存

### 三个导入入口

网页「数据管理」里三个入口都接到了系统文件选择器：

| 入口 | 类型 | 说明 |
|---|---|---|
| 导航数据 | `nav` | 上面那四个 `.dat` |
| AIP 航路表 | `aip` | CAAC 四张表的 CSV，或 `caac-official.json`；需先有导航数据 |
| 数据包 | `pack` | 本工具导出的 `routegen-pack.json`，用于换机 / 备份 |

### 界面

- 生成方案后**自动读取**，不需要点任何按钮
- 右下角一颗可展开的胶囊：展开后是「保存当前方案」与「航路库」，点空白处收起
- 只有点保存才写盘；生成、展开、复制都不落盘
- 切到油量页时，当前航路距离自动填进「航段距离」，手改后不再覆盖

## 环境要求

| 项 | 要求 |
|---|---|
| 系统 | Android 14 及以上（minSdk 34） |
| 存储 | 应用约 18 MB + 航图库约 1.2 GB + 导航数据缓存（与导入规模同量级） |
| 权限 | 所有文件访问权限（MANAGE_EXTERNAL_STORAGE），仅用于读航图库与导入文件 |
| 网络 | **不需要**，任何功能都不联网 |

## 安装

从 Releases 下载 `flight-efb-1.3.6.apk`，用文件管理器打开安装。签名证书与 1.3.x 各版一致，可直接覆盖。

## 航图库结构

应用靠目录名识别机场与图型：

```
AIP航图库/
├─ ZBAA_北京_首都/
│   ├─ 01_机场图ADC/
│   └─ 05_进近图IAC/
└─ 00_ENR通用资料/
```

完整规则见 [航图库结构与命名规范.md](docs/航图库结构与命名规范.md)。应用内「设置 → 生成规范文档」也会把它写到手机 Download 目录。

## 从源码构建

需要 JDK 17、Android SDK 36。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
```

工程路径含中文时，构建产物目录要指到纯 ASCII 路径，否则测试进程读不到 classpath：

```bash
./gradlew testDebugUnitTest -Pefb.buildDir=E:/Android/efb-build/app
```

签名的证书文件不随仓库分发，构建前需要在 `app/build.gradle.kts` 里配置自己的 keystore。

## 测试

```bash
./gradlew testDebugUnitTest
```

**100 个 JVM 单元测试**，覆盖索引查询、中文名匹配、目录扫描、图型分组、SAF 路径还原、页面尺寸换算、检查单与自定义检查单数据结构，以及**网页工具的出包期契约门禁**。

`ScannerParityTest` 用真实 3203 条文件清单验证手机端扫描器与 PC 上 `naip_index.py` 的结果逐条一致。

## 网页工具的升级方式（重要）

两个网页工具在 `app/src/main/assets/tools/` 里保持**字节级原样**，桥接层、导出挂钩、界面适配全部由 App 在**内存里**按锚点注入，磁盘文件一行不改。

换新版网页的流程：

1. 用新版覆盖 `assets/tools/route_gen.html` 或 `fuel_calc.html`（文件名不变），`Walker'sHTML/` 同步留档
2. **不改任何 Kotlin**，跑门禁：
   ```bash
   ./gradlew testDebugUnitTest --tests "*ToolAssetsContractTest*" -Pefb.buildDir=E:/Android/efb-build/app
   ```
3. 门禁全绿就出包；锚点漂移只改 `assets/tools/efb_patches.json` 或 `efb_shim.js`（都是非 Kotlin 资源）

`ToolAssetsContractTest` 断言：8 个顶层对象各命中 1 次、锚点唯一、导出机制未变、单文件自包含、补丁幂等、桥版本三方一致。**任一不符即出包失败** —— 这是唯一的把关点，运行期不做任何版本协商或降级。

已验证两次演练：只改版本号的假新版 → 门禁绿；故意改坏锚点 → 门禁红、出包被拦。1.3.4 的油量页 V6→V7 升级就是按这条流程走的，Kotlin 与配置零改动。

## 项目结构

```
flight-efb/
├─ app/                   Android 工程（源码与 assets）
├─ docs/                  全部文档（README 之外的 .md 都在这里）
├─ gradle/                Gradle 版本目录
├─ keystore/              签名证书（不入库）
├─ tools/                 PC 端脚本（取数据、生成索引）
├─ Walker'sHTML/          两个网页工具的原始版本（升级对照基准）
├─ APKs/                  本地留存的各版本安装包（不入库）
├─ charts.db / checklists.json / qrh.json   预置数据源
├─ navData/ co-routes/    验收用导航数据（不入库）
└─ *.gradle.kts / gradlew 构建入口
```

```
app/src/main/java/com/oguricap/efb/
├── MainActivity.kt          入口，权限与目录选择
├── TreePaths.kt             SAF 选择结果 → 文件系统路径
├── EfbApplication.kt        StrictMode（仅 debug）
├── data/
│   ├── Models.kt            数据模型（含自定义检查单 uid）
│   ├── ChartIndex.kt        预置索引只读查询
│   ├── ChartScanner.kt      目录扫描（naip_index.py 的 Kotlin 移植）
│   ├── IndexBuilder.kt      扫描结果落库
│   ├── ReferenceLibrary.kt  检查单与 QRH 加载
│   ├── CustomChecklistStore.kt  用户自定义检查单
│   └── SpecExporter.kt      规范文档导出
├── prefs/Settings.kt        偏好设置
├── perf/Perf.kt             耗时埋点
└── ui/
    ├── theme/Theme.kt       深色配色（取自航路生成器页面）
    ├── tools/               工具页：WebView 宿主、桥、导入、缓存、航路库
    └── checklist/           检查单与自定义编辑器
app/src/main/assets/tools/   两个网页工具 + 补丁配置 + 注入脚本
```

## 索引与数据

| 文件 | 用途 |
|---|---|
| `assets/charts.db` | 预置航图索引（620 KB） |
| `assets/checklists.json` | 检查单数据 |
| `assets/qrh.json` | QRH 数据（1.3.6 起界面入口已移除，数据保留） |
| `assets/docs/*.pdf` | 随附的两份检查单手册原件，随仓库分发 |
| `assets/library_spec.md` | 规范文档，可在设置里导出 |
| `assets/tools/*` | 两个网页工具 + `efb_patches.json` + `efb_shim.js` + `efb_fuel_skin.js` |

换航图库时在应用内重新扫描即可，不必重新编译。

### 重新生成检查单

```bash
python tools/fetch_sources.py
python tools/build_checklists.py checklists.json
cp checklists.json app/src/main/assets/
```

### 重新生成航图索引（PC 端）

```bash
python naip_index.py --root /path/to/AIP航图库 --out index.json --db charts.db
cp charts.db app/src/main/assets/
```

## 文档

| 文件 | 内容 |
|---|---|
| [发行版说明.md](docs/发行版说明.md) | **下载 / 安装 / 校验 / 本版更新**，可直接贴到 GitHub Release |
| [开发日志.md](docs/开发日志.md) | 逐日开发记录：改动、踩坑、验证数据 |
| [工程改造清单.md](docs/工程改造清单.md) | 工程实现说明与工具页改造的完整技术细节 |
| [工具页改造计划.md](docs/工具页改造计划.md) | 工具页最初的设计与裁决（已实施） |
| [航图库结构与命名规范.md](docs/航图库结构与命名规范.md) | 目录与文件名规则 |
| [目标与范围.md](docs/目标与范围.md) | 目标、约束、不做的事 |
| [首次引导.md](docs/首次引导.md) | 引导流程与错误提示 |
| [检查单与QRH.md](docs/检查单与QRH.md) | 数据来源与构建 |
| [性能目标.md](docs/性能目标.md) | 性能基线要求 |
| [真机验收清单.md](docs/真机验收清单.md) | 真机测试步骤 |
| [如何去电脑上构建.md](docs/如何去电脑上构建.md) | 环境配置与踩坑记录 |
| [代码审查记录.md](docs/代码审查记录.md) | 独立审查报出的问题与修法 |
| [NOTICE.md](NOTICE.md) | 第三方内容与依赖的许可声明 |

## 数据来源与许可

### 代码

全部 Kotlin 代码为本项目原创。依赖库均为官方库：Kotlin 标准库与 kotlinx-coroutines、AndroidX（core / lifecycle / activity / compose），均为 Apache-2.0。

### 检查单

来自 [simchecklists](https://github.com/rkaw92/simchecklists)，**MIT 许可**。本项目做了格式转换（阶段分组、JSON 化）并补充阶段名的中文解释。

### QRH

16 条处置条目为**本项目自行整理**，依据公开的 A320 ECAM 体系与波音记忆项目结构，不含任何空客或波音的受控文件内容。

### 航图与导航数据

本仓库不包含任何航图 PDF，也不包含任何 Navigraph / Jeppesen 导航数据。两者均由使用者自行准备，版权归原权利人所有。

`.gitignore` 已排除 `navData/`、`co-routes/`、`*.dat`、`*.fms`、`*.fms.bak`，避免误提交。

### 本项目许可

见 [LICENSE](LICENSE)。

## 免责声明

本项目仅供飞行模拟使用。作者不对内容的准确性作任何保证，不承担因使用本软件产生的任何责任。**禁止用于真实飞行**。
