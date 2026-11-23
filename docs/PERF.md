# 性能基线（PERF）

> 本文件按 `docs/性能目标.md` 第 6.2 节与 6.3 节的要求建立：
> **改任何性能相关代码前先来这里记一遍数字，改完再记一遍。**
> 没有基线的性能改动只能靠感觉，通常会把慢的地方改得更慢。

## 关于「未测」

下面标 **未测** 的项，都是需要真机（USB 调试 + `adb`）而本次会话拿不到的。
不为它们填估算值或模拟值 —— 模拟出来的数字会让下一次改动失去对照意义。
拿到真机后按「采集方法」一列的命令跑一遍，把结果补进「本次」列并写上日期。

## 1. 设备与口径

| 项 | 值 |
|---|---|
| 目标设备 | Redmi 25053RT47C / Android 16 / SDK 36 |
| 应用内实测日期 | 2026-11-05（见 `docs/性能目标.md` 第 6.4 节） |
| 应用包名 | `com.oguricap.efb` |
| 调试包 | `debug`，StrictMode `detectAll().penaltyLog()` 全开 |

`docs/性能目标.md` 第 6.2 节的命令里写的是 `com.example.checklist_interactive`，
那是早期包名，**现在会直接报「找不到进程」**。下面表格里用的都是当前的 `com.oguricap.efb`。

## 2. 五项指标

### 2.1 冷启动耗时

```bash
adb shell am start -W -n com.oguricap.efb/.MainActivity
```

| 版本 | 冷启动 TotalTime | 来源 | 备注 |
|---|---|---|---|
| 1.3.13 基线 | 215 / 181 / 214 ms（三次） | `docs/性能目标.md` 6.4 节，2026-11-05 真机 | 系统侧口径，含进程创建 |
| 本次（1.3.13 + 本轮改动） | **未测** | — | 需要真机与 USB 调试 |

目标：飞行模式冷启动到搜索框可输入 ≤ 1000 ms；第二次（热缓存）≤ 600 ms。

**本轮相关的应用内近似值**：新增埋点 `cold_start_to_first_frame`。
它从 `Application.onCreate` 第一行算起，到首帧 `doOnPreDraw` 为止，
**不含进程创建与 `LoadedApk` 装配**，因此一定小于上表的 `TotalTime`。
两者不能直接对比，只能各自纵向比版本。
查看方式：`adb logcat -s PERF | grep cold_start_to_first_frame`。

### 2.2 掉帧

```bash
adb shell dumpsys gfxinfo com.oguricap.efb framestats
```

| 场景 | 掉帧率 | 来源 | 备注 |
|---|---|---|---|
| 1.3.13 基线 | **未测** | — | 目标：快速连打 4 个字符时掉帧 < 5% |
| 本次 | **未测** | — | 需要真机 |

### 2.3 内存

```bash
adb shell dumpsys meminfo com.oguricap.efb | grep -E "TOTAL|Native Heap|Dalvik Heap"
```

| 场景 | PSS | 来源 | 备注 |
|---|---|---|---|
| 1.3.13 静置 | 76 MB | `docs/性能目标.md` 6.4 节，2026-11-05 真机 | |
| 1.3.13 开图后 | 101–104 MB | 同上 | |
| 本次 静置 | **未测** | — | 需要真机 |
| 本次 连续开 20 张图后 | **未测** | — | 目标：峰值 ≤ 200 MB，硬上限 400 MB |

本轮相关：新增 `onTrimMemory` 分层清缓存（`EfbApplication` → `PdfDocument.trimAllCaches`）。
它只在系统压内存时才动作，**静置与正常开图的数字不因它变化**，
真正要看的是「后台 30 分钟后回来」与「连续翻 20 页」的峰值。
`PdfDocument.liveDocumentCount()` 可以确认文档有没有被正确注销。

### 2.4 fd 泄漏

```bash
adb shell ls /proc/$(adb shell pidof com.oguricap.efb)/fd | wc -l
```

| 场景 | fd 数量 | 来源 | 备注 |
|---|---|---|---|
| 1.3.13 基线 | **未测** | — | 目标：连续开图 50 次不增长 |
| 本次 | **未测** | — | 需要真机 |

本轮相关：`PdfDocument.close()` 改成了「同步置位 + 异步释放」。
释放仍然一定会发生（`closeScope` 不随页面销毁而取消），但**时机不再是调用返回那一刻**。
连续开图时观察这条数字，是确认这次改动没有把 fd 释放弄晚到堆积的唯一手段。

### 2.5 主线程 IO

```bash
adb logcat -s StrictMode
```

| 场景 | DiskReadViolation 条数 | 来源 | 备注 |
|---|---|---|---|
| 1.3.13 基线 | **未测**，但已知命中 4 处 | 本轮静态审查，见下 | 目标：0 |
| 本次 | **未测** | — | 需要真机确认 |

1.3.13 已知的 4 处（本轮已全部改造，待真机确认归零）：

| 位置 | 操作 |
|---|---|
| `SettingsScreen.kt` 组合期 → `ChartIndex.chartCount()` → `rawQuery` | 组合期查 SQLite |
| `EfbApp.kt` 组合期 → `ChartViewModel.chartByPath()` → `chartByFile` | 组合期查 SQLite |
| `ToolsWebView.create` → `ToolContract.prepareHtml` | 主线程读 241 KB HTML + 打补丁 + SHA256 |
| `ToolsViewModel.init` → `ToolContract.logVersions` | 构造期读两份 HTML |

## 3. 应用内埋点

`Perf.timed` 写 `PERF` tag，格式 `name=12.3ms`。当前必埋的点：

| 埋点名 | 位置 | 状态 |
|---|---|---|
| `cold_start_to_first_frame` | `MainActivity.onCreate` 的 `doOnPreDraw` | 本轮新增 |
| `db_open` | `ChartViewModel.load()` | 在位 |
| `db_open_scan` | `ChartViewModel.scanInto()` | 在位 |
| `query_icao` | `ChartViewModel.openAirport()` | 在位 |
| `query_airports` | `ChartViewModel.refreshAirports()` | 在位 |
| `search_query` | `ChartViewModel.applyQuery()` | 在位 |
| `pdf_open` | `ReaderScreen` 的 `LaunchedEffect` | 在位 |
| `pdf_render_<页码>` | `PdfPageItem` | 在位，命中缓存时为 0.0 ms |
| `scan_directory` | `ChartViewModel.scanInto()` | 在位 |
| `tool_prepare_route` / `tool_prepare_fuel` | `ToolsViewModel.ensurePrepared()` | 本轮新增 |

`docs/性能目标.md` 第 6.4 节那批应用内实测（2026-11-05 真机）：

| 指标 | 值 |
|---|---|
| `pdf_open` | 13.3–48.8 ms |
| `pdf_render` | 45.0–64.0 ms（单页，1280 px 宽） |

## 4. 版本间比对记录

每完成一次性能相关改动，在下面加一行。**只有同一台设备、同一套命令的数字才可比。**

| 日期 | 版本 | 改动摘要 | 冷启动 | 内存峰值 | fd | 主线程 IO |
|---|---|---|---|---|---|---|
| 2026-11-05 | 1.3.13 | 基线（`docs/性能目标.md` 6.4 节） | 215/181/214 ms | 静置 76 / 开图 101–104 MB | 未测 | 未测 |
| 2026-11-23 | 1.3.13+ | 清除主线程 IO、`onTrimMemory`、工具页状态修复、补两个埋点 | 未测 | 未测 | 未测 | 未测（待真机） |

## 5. 待办

1. 拿到真机后按第 2 节 5 条命令跑一遍，把「本次」列的「未测」替换成实测数字，并写上采集日期。
2. `docs/性能目标.md` 第 5 节的 15 项验收清单目前全部未勾选，按上面的数字逐项判定后勾选。
3. `docs/性能目标.md` 第 6.2 节命令里的旧包名 `com.example.checklist_interactive` 需要改成
   `com.oguricap.efb`，否则照抄必然报「找不到进程」。
