[en.README](https://gitee.com/Super_Oguri_Cap/flight-efb/blob/master/README.en.md)

# 飞行EFB

Android 手机上的离线 EFB：查阅 AIP 航图、随机检查单与 QRH。全程无网络，原始资料只读。

> **仅供飞行模拟使用（for simulation only）**。本应用不含任何官方航图，也不得用于真实飞行。

## 功能

| 标签 | 内容 |
|---|---|
| 航图 | 输入四字码或机场名 → 选图型 → 打开 PDF；双指缩放、双击缩放、放大后单指推图、反色、预渲染下一页 |
| 检查单 | 21 个机型 / 2208 条，按空客、波音、其他分类 |
| QRH | 16 条处置，A320 ECAM 与波音记忆项目 |

首次启动走四步引导：欢迎 → 授予文件权限 → 选航图库目录并扫描 → 查看结果。之后启动直接进主界面。

## 环境要求

| 项 | 要求 |
|---|---|
| 系统 | Android 14 及以上（minSdk 34） |
| 存储 | 航图库约 1.2 GB（3203 个 PDF） |
| 权限 | 所有文件访问权限（MANAGE_EXTERNAL_STORAGE） |
| 网络 | 不需要 |

## 安装

从 Releases 下载 `app-release.apk`，用文件管理器打开安装。首次启动按引导授权并选择航图库目录。

## 航图库结构

应用靠目录名识别机场与图型。目录必须整理成：

```
AIP航图库/
├─ ZBAA_北京_首都/
│   ├─ 01_机场图ADC/
│   └─ 05_进近图IAC/
└─ 00_ENR通用资料/
```

完整规则见 [航图库结构与命名规范.md](航图库结构与命名规范.md)。

应用内「设置 → 生成规范文档」会把它写到手机的 `/sdcard/Download/航图库结构与命名规范.md`，方便在电脑上对照整理或发给别人。

## 从源码构建

需要 JDK 17、Android SDK 36。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

签名的证书文件不随仓库分发，构建前需要在 `app/build.gradle.kts` 里配置自己的 keystore。

## 测试

```bash
./gradlew testDebugUnitTest
```

75 个 JVM 单元测试，覆盖索引查询、中文名匹配、目录扫描、图型分组、SAF 路径还原、页面尺寸换算、检查单与 QRH 数据结构。

`ScannerParityTest` 用真实 3203 条文件清单验证手机端扫描器与 PC 上 `naip_index.py` 的结果逐条一致。

## 索引与数据

| 文件 | 用途 |
|---|---|
| `assets/charts.db` | 预置航图索引（620 KB） |
| `assets/checklists.json` | 检查单数据 |
| `assets/qrh.json` | QRH 数据 |
| `assets/library_spec.md` | 规范文档，可在设置里导出 |

换航图库时在应用内重新扫描即可，不必重新编译。

### 重新生成检查单

```bash
python tools/fetch_sources.py          # 取 simchecklists 原始清单
python tools/build_checklists.py checklists.json
cp checklists.json app/src/main/assets/
```

### 重新生成航图索引（PC 端）

```bash
python naip_index.py --root /path/to/AIP航图库 --out index.json --db charts.db
cp charts.db app/src/main/assets/
```

## 项目结构

```
app/src/main/java/com/oguricap/efb/
├── MainActivity.kt          入口，权限与目录选择
├── TreePaths.kt             SAF 选择结果 → 文件系统路径
├── EfbApplication.kt        StrictMode（仅 debug）
├── data/
│   ├── Models.kt            数据模型
│   ├── ChartIndex.kt        预置索引只读查询
│   ├── ChartScanner.kt      目录扫描（naip_index.py 的 Kotlin 移植）
│   ├── IndexBuilder.kt      扫描结果落库
│   ├── ReferenceLibrary.kt  检查单与 QRH 加载
│   └── SpecExporter.kt      规范文档导出
├── prefs/Settings.kt        偏好设置
├── perf/Perf.kt             耗时埋点
└── ui/                      界面层
```

## 文档

| 文件 | 内容 |
|---|---|
| [发行版说明.md](发行版说明.md) | **下载/安装/校验/本版更新**，可直接贴到 GitHub Release |
| [航图库结构与命名规范.md](航图库结构与命名规范.md) | 目录与文件名规则 |
| [目标与范围.md](目标与范围.md) | 目标、约束、不做的事 |
| [首次引导.md](首次引导.md) | 引导流程与错误提示 |
| [检查单与QRH.md](检查单与QRH.md) | 数据来源与构建 |
| [性能目标.md](性能目标.md) | 性能基线要求 |
| [真机验收清单.md](真机验收清单.md) | 真机测试步骤 |
| [如何去电脑上构建.md](如何去电脑上构建.md) | 环境配置与踩坑记录 |
| [代码审查记录.md](代码审查记录.md) | 独立审查报出的 13 条问题与修法 |
| [NOTICE.md](NOTICE.md) | 第三方内容与依赖的许可声明 |

## 数据来源与许可

### 代码

全部 Kotlin 代码为本项目原创，未包含任何第三方源代码。依赖库均为官方库：

| 库 | 许可 |
|---|---|
| Kotlin 标准库、kotlinx-coroutines | Apache-2.0 |
| AndroidX（core / lifecycle / activity / compose） | Apache-2.0 |

### 检查单

来自 [simchecklists](https://github.com/rkaw92/simchecklists)，**MIT 许可**。仓库内的清单文本按 MIT 条款分发，本项目对其进行了格式转换（阶段分组、JSON 化）并补充了阶段名的中文解释。

### QRH

16 条处置条目为**本项目自行整理**，依据的是公开的 A320 ECAM 体系与波音记忆项目结构，不含任何空客或波音的受控文件内容。

### 航图

本仓库不包含任何航图 PDF。航图由使用者自行准备，其版权归原发布机构所有。

### 本项目许可

见 [LICENSE](LICENSE)。

## 免责声明

本项目仅供飞行模拟使用。作者不对内容的准确性作任何保证，不承担因使用本软件产生的任何责任。**禁止用于真实飞行**。
