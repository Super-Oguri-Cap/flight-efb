# 飞行EFB：工具页 + 自定义检查单 + 网页升级预留 改造计划（最终版）

> **状态：规划完成，暂不实施。** 本文件是 Plan 模式下唯一写入的文件；工程代码、配置、文档一律未动。
> 内容承接此前全部探索与裁决，已收敛、无遗留冲突。
> 切到 Agent 模式后：复制一份到工程根 `工具页改造计划.md`，**先执行阶段 0（紧急合规）**，再按第 6 节开工。

---

## 🚨 0. 立即告警（优先于一切功能开发）

| 事实 | 数值 |
|---|---|
| 暂存区已 `git add` | **3376 个文件，其中 3366 个是 `navData/`(3345) + `co-routes/`(21)**；约 164 MB Navigraph 受限数据已暂存 |
| HEAD 已提交历史 | `git ls-tree -r HEAD -- navData/ co-routes/` = **0，尚未泄露，还有救** |
| 远端 | 公开 GitHub `Super-Oguri-Cap/flight-efb` |
| 授权 | `cycle_info.txt`：Navigraph / Jeppesen「may not be copied, modified, decompiled, **published**, sold or **distributed**, in whole or in part」，仅供个人地面 PC 模拟 |

→ **下一次 `git commit` 就等于公开分发授权数据**。阶段 0：① `.gitignore` 补 `navData/`、`co-routes/`、`*.dat`、`*.fms`、`*.fms.bak`；② `git rm --cached -r navData/ co-routes/`；③ 复核。**实施期间禁止 `git add -A` / `git add .`**。

**三条红线**：① 不预置/不打包任何 Navigraph 派生数据，两个 HTML 保持「无数据版」，用户运行时导入；② 数据变换只在用户设备上、对用户自己合法导入的数据、运行时发生；③ **不改 `android:largeHeap`**（`性能目标.md:99` 明确禁止）。

---

## 1. 目标

| # | 可验收目标 |
|---|---|
| 1 | 底部第 4 个标签「工具」，内含「航路生成器」「油量计算器」两个子页；切换不重载、不丢已导入数据 |
| 2 | 引导用户**运行时导入**三类数据（导航数据 / AIP 表 / 数据包），解析结果与电脑一致；重进工具页数据还在 |
| 3 | **只有**点「保存」的航路才写本机；可命名、删除、设为当前。生成/展开/复制都不写盘 |
| 4 | 切到油量页时当前航路距离（NM）自动填进「航段距离」，可手动改；油量页原有功能全保留 |
| 5 | 检查单页新增「自定义检查单」：**内置只读、可复制一份再改**；图形化编辑，重启后内容与勾选都在 |
| 6 | **网页升级预留**：后期拿到新版 HTML 时，**只需覆盖 `assets/tools/` 两个文件 + 重新出包**，Kotlin 业务代码不改、现有功能完全不变；契约变化由「出包期单测门禁」立刻拦下（不符即出包失败），不是等真机静默出错 |

**明确不做**：不重写 HTML 算法、不加 Gradle 依赖、不申请网络权限（全程离线）、不自动记录未保存航路、不把 `navData/`/`co-routes/` 或派生数据打进 APK/仓库。
**明确排除**（用户已确认）：**不做手机端 HTML 热替换、不做外部文件覆盖/加载、不做多版本兼容或能力降级、不在界面显示网页版本或自检结果**。

> **三处边界（口径核定）**：①「版本只进日志」指界面与 **App 运行期**不显示/不比对；**版本必须参与出包期断言/比对**（`bridgeVersion` 与 `EXPECTED_BRIDGE_VERSION`），不能只打日志，否则升级没有把关点。②「Kotlin 不改」指**后期换 HTML 那一刻**；本期集成该写的 Kotlin 照常写。③ 两个 brief 并不互斥：「只改 `ToolContract.kt` 这一个文件」描述**本期集成范围**，「Kotlin 不改」描述**升级那一刻** → 裁决为 **本期只新增 `ToolContract.kt` 一处 Kotlin，其余 Kotlin 不动；升级时连它都不改**（锚点串与 shim 全放非 Kotlin 资源）。主路径因此定为**内存锚点替换**（升级只换 HTML 文件、不跑脚本）。

---

## 2. 已确认的决定

| # | 决定 | 来源 |
|---|---|---|
| 1 | **一个**「工具」标签 + 内部两个子页 | 用户确认 |
| 2 | 航路**只有点保存才持久化** | 用户确认 |
| 3 | 内置检查单**不可改**，可「复制一份」生成可编辑副本 | 用户确认 |
| 4 | `co-routes/*.fms` **仅作验收对照**，不进 App | 用户确认 |
| 5 | `navData\` 为运行时导入验收数据；计划先写系统计划文件，切 Agent 后复制到工程根 | 用户确认 |
| 6 | 油量计算器以**当前 1016 行版本**为准（规划期间被更新过） | 主代理复核 |
| 7 | **升级方式 = 重新构建 APK**：覆盖两个 HTML 文件后重新出包 | 用户确认 |
| 8 | **兼容性 = App 与网页一起更新，功能完全不变**：不为旧版兜底、不做能力探测降级、不做版本匹配拦截 | 用户确认 |
| 9 | **版本信息只写 Logcat / 验收清单，界面不增加元素** | 用户确认 |
| 10 | **主路径 = App 运行时「内存唯一锚点替换」**（磁盘 HTML 字节级原样、不写回、不跑脚本）；**唯一把关点 = 出包期 `ToolAssetsContractTest` 门禁** | 探索收尾 + 主代理裁决 |

---

## 3. 技术基线

- 两个 HTML 单文件、自包含、无外链/无第三方库；窄屏自带响应式，**无需改 CSS**；硬编码深色；**都已有 charset meta**（A:4 / B:5），**不补冗余 meta、不改 HTML 头部**。源文件：`Walker'sHTML\航路生成器_v8.4_无数据版.html`（5030 行）、`Walker'sHTML\油量计算器.html`（1016 行，有「计」字）。
- 装载：`AndroidView` + 系统 `android.webkit.WebView` + `loadDataWithBaseURL(htmlText, "text/html", "UTF-8", "https://efb.local/route.html"|"fuel.html")`；**零新增 Gradle 依赖**、**不加 INTERNET 权限**、**不开 largeHeap**。**baseURL 固定不变**；UTF-8 三者一致。
- **初始集成期一次性配置：`settings.domStorageEnabled = false`** → `DataStore.get()` 落 `mem=null`(A:2445)、IDB/LS 不可用 → `boot()` 快速落到 `renderEmptyState()`(A:3235)，基本消除旧缓存竞态；App 的 `UI.applyData()` 注入固化在 **`onPageFinished`（DOMContentLoaded 之后）**。
- 导入主路径（零 HTML 改动、零算法改动）：SAF 多选 → `contentResolver.openInputStream` 流式读 → 按 Kotlin `String` 字符分块（≤256 KB、`JSONObject.quote`、不用 base64，规避 Binder ≈1 MB）累加 `window.__NAV_FILES__` → 页面内 `NavParser.parse()` + `PackCodec.encode({airports})` 补 `AIRPORTS_GLOBAL` + `UI.applyData({source:'app'}).then(ok => Android.onDataApplied(ok))` → 置 null 释放。
  - 硬约束：`rec.source` 不能是 `'builtin'`；不能只改 `window.__DATASET__`（必须走 `UI.applyData`）；`applyData` 返回 Promise 必须 `.then()`；坐标转换必须用 `PackCodec.encode`（严禁手工拼 `AIRPORTS_GLOBAL`，空字段会静默写入 (-90,-180) 错坐标），且在转换后、encode 前做全量范围校验。
  - 缺 `apt.dat` 是「机场坐标」缺口，不是导航数据缺口；坐标数据全部由用户运行时导入。
- 持久化：**只存 SAF Uri 列表**（SharedPreferences + `takePersistableUriPermission`），每次进工具页重新导入，**不落派生 pack、不存在 restore 回灌**、不依赖 WebView 存储。工程目前只有 `OpenDocumentTree`，`OpenMultipleDocuments`/持久 Uri 权限是**新增能力**。
- 保存航路：调 `__EFB__.getPlans()`（HTML 内脚本读 DOM 返回结构化 JSON），key 取 `.pcopy` 的 `data-copy`（不用 `data-plan`），禁用 `getPlan(key)`（索引错位）；距离注入改为 `#dist` 设值后派发 `input` 事件（油量页 969-971 本就绑 `calc`，不依赖全局函数名、不触发 `history.replaceState` 污染返回栈），`window.calc()` 兜底。
- 自定义检查单：`Models.kt` 加带默认值 `uid`；SharedPreferences 存「我的检查单」；`ReferenceViewModel` 合并 + 7 个进度方法按 `custom_` 分流；`ChecklistScreen.kt` 改 257/220/239 三处。
- `app/proguard-rules.pro`（实测仅一条 `-keepattributes SourceFile,LineNumberTable`）补两条 keep（release 开 R8，不补则 debug 正常、**release 桥静默失效**）；注入脚本是字符串常量、不参与混淆，**无需因升级预留再加 keep**。`@JavascriptInterface` 回调切主线程。

---

## 4. 升级预留设计（本轮核心 · 最终裁决）

### 4.1 决定方案形态的硬事实

1. **顶层 `const` ≠ window 属性**：航路页 7 个对象全是 `const X = (function(){...})()`（629 `Codec`/664 `DatasetLoader`/860 `Geo`/2285 `PackCodec`/2348 `DataStore`/2519 `NavParser`/3148 `UI`），`window.UI === undefined`，只有裸名可解析；App 侧不能写 `window.UI.applyData(...)`。
2. **🔴 桥插入点只能是「主 `</script>` 之后、`</body>` 之前的独立新 `<script>`」**：顶层 `const` 的全局词法绑定只在主 `<script>`(A:624-5029) 求值时创建。放 `<head>` 会先执行、直接引用抛 **`ReferenceError: UI is not defined`**（`typeof` 防御也只得到 7 个 `undefined`）；**插进主脚本内部（如 5019 空行）同样不行**——一旦漏过门禁，ReferenceError 会**中止整段主脚本**，导致 `DOMContentLoaded` 注册不上、`UI.init()` 不跑、**整页废掉**。独立新 script 里报错只炸 shim、主页面无恙；经典脚本共享全局词法环境，裸名 `UI` 照样取到，且 DOMContentLoaded 在全部脚本跑完才触发 → 执行顺序仍落在 `UI.init()/boot()` 之前。
3. **`UI` 的 return(5016-5017) 不含 `downloadJson`** → 它是 UI IIFE 内局部函数，**运行时从外部根本覆盖不到**，只能改源字符串（内存替换）。「包装 `URL.createObjectURL` + `click`」有 `blob.text()` 异步、`click` 原型遮蔽、错误不被 `downloadJson` 的 try 捕获、`setDownloadListener` 拿不到 blob 等坑，**全部否决**。
4. **HTML 里读不到可靠版本号**：A 无 `schemaVersion`/`CYCLE`/单一版本常量，v8.4 只散在可见文本，内部注释混着 v8.1/v8/v4 自相矛盾；B 的 `APP_VERSION='V6'`(358) 是 `const`，只能文本正则读。→ 版本串由 `ToolContract` 正则提取：**界面 / App 运行期只写 Logcat（不 gating），但出包期必须参与 `bridgeVersion` 比对断言**。
5. **`runSelfTests()` 不是布尔信号**：B:979-1009 无返回值、不抛异常，只有 B:1008 一条 `console.info('[自检] N/N 通过')` → 只作 Logcat 文本判读。
6. **`window.__DATASET__`(A:3274) 是现成、零补丁的 window 锚点**；`acceptNavFiles`/`acceptAipFiles`/`acceptPack` 是 `bindDataPanel()`(3501) 局部函数、未暴露，App 不调。
7. **自包含校验别把 `fetch(` 一律判外链**：航路 843 行 `fetch(src.url)` 在 `DatasetLoader.load()` 的 `kind==='fetch'` 死分支、无 URL 字面量、App 不会走到。两文件实测无任何 `http(s)://` 字面量、无 `<script src>`、无 `<link>` 外链、无 `@import`、无 `type="module"`。

### 4.2 最终主路径：内存唯一锚点替换 + 出包期单测门禁

**核心**：`assets/tools/*.html` 在磁盘上**字节级原样**（就是新版 HTML，不含任何补丁）。`ToolWebView` 读成 String 后，由 `ToolContract.applyPatches()` 在**内存里**按唯一锚点插入两处胶水，再把结果字符串交给 `loadDataWithBaseURL`；**不写回磁盘、不改文件、不用跑任何脚本**。升级时不会出现「忘了给新 HTML 重打补丁」。

**方案 3（最终采纳）**：全部 `{anchor, replacement}` 对、shim 文本、`requiredConsts` 名单放进**一个非 Kotlin 配置 asset** `app/src/main/assets/tools/efb_patches.json`（shim 拆成同目录 `efb_shim.js`）；`ToolContract.kt` 只留 assets 路径 / baseURL / 桥名 / `EXPECTED_BRIDGE_VERSION` + **一次写好永不改**的通用逻辑（`loadPatches(context)`、`applyPatches(html, patches)`、`validate()`）。**正常升级 0 Kotlin 改动；锚点漂移只改 `efb_patches.json`（非 Kotlin）**。config 用现有 `JSONObject`（参照 `ReferenceLibrary.kt:44`），零新增依赖。

| 补丁 | 唯一锚点 | 插入内容 |
|---|---|---|
| ① 桥接 | **`</script>\s*</body>` 唯一序列**（A:5029→A:5030；B 在 1015→1016，但 B 不打补丁） | 在该处插一个**新 `<script>` 块**，内容是独立资源 `assets/tools/efb_shim.js` 的文本（由 `efb_patches.json` 的 `shimAsset` 引用）：把 `Codec/DatasetLoader/Geo/PackCodec/DataStore/NavParser/UI`（**7 个对象引用必须都挂**）与 `bridgeVersion/toolVersion/capabilities/domContract/datasetInfo()/getPlans()/exportJson()/stepLevel()/degraded/exportHooked` 赋到 `window.__EFB__`。新块在主脚本求值之后、`DOMContentLoaded` 触发之前执行 |
| ② 导出 | `function downloadJson(filename, obj) {`(3821，**全文唯一**，带 2 空格缩进，子串匹配) 之后、`try {`(3822) **之前** | 一行 early-return guard：`if (window.Android && Android.exportJson) { Android.exportJson(filename, JSON.stringify(obj)); return; }`；替换成功后置 `exportHooked=true`（在 ① 的脚本里） |

- **🔴 锚点唯一性硬约束**：`<script` 全文 **7 处**（611-624，前 6 个是 `text/plain`/`application/json` 数据块）、`</script>` 也不唯一、`})();` **十余处**（645/855/880/1204/1428/1550/1915/2274/2334/2496/2951/3143/5018）——**都不能单独当锚点**。① 用 `</script>\s*</body>` 唯一序列；② 用 3821 完整函数签名。**每个锚点命中数必须恰好 1**，否则 `applyPatches()` 直接失败（`Log.e` + 停止加载），单测也会把出包拦下。
- **shim 放独立资源 `assets/tools/efb_shim.js`**：`efb_patches.json` 以 `shimAsset: "efb_shim.js"` 引用；`ToolContract` 只做「读 config → 读 shim → 拼进 HTML 内存字符串」，**不把 JS 或锚点硬编码进 Kotlin**。万一新版迫使 shim/锚点改动，改的是非 Kotlin 资源。
- **否决「外置导出」但保留知识**：包装 `URL.createObjectURL`(A:3824，全文件唯一) + 窄包装 `HTMLAnchorElement.prototype.click`（`a.download` 3826 / `a.click()` 3828 各唯一；`new Blob` 另有 3 处走 `.stream()` 不经过 createObjectURL；静态 HTML 无 `<a>` 标签）技术上成立，**但无法在出包期静态验证、未来换导出机制会静默失效**，故**已否决、不作备选**。记录陷阱防翻回：绝不能用 `HTMLElement.prototype.click` 宽包装（A:3518/3610/3680 的 `$('fileNav'|'fileAip'|'filePack').click()` 目标是 `<input type="file">`，宽包装会把三个 SAF 文件选择器一起拦死）。
- **函数级替换，不在调用点钩**：`downloadJson` 被 3751、3758 两处调用、载荷不同（`current.pack+stats` 与 `current.caac`），函数头插 guard 天然覆盖两个调用点与两个文件名；导出字节就是 `JSON.stringify(obj)` 原文，**算法一行不改**。
- **DOM 读取收敛进 ① 的脚本**：`__EFB__.getPlans()` 内部读 DOM 返回结构化 JSON，Kotlin 不刮 `.pdist`/`#route-<key>`。不给卡片加 `data-efb-*` 属性（要动 `render()` 模板，多一处补丁）。
- **距离注入**：`el.value=x; el.dispatchEvent(new Event('input',{bubbles:true}))`，`window.calc()` 兜底，**油量 HTML 字节级零改动**。
- **🔴 桥的脆弱性（最易踩）**：`window.__EFB__ = { UI, NavParser, ... }` 是**单个对象字面量**——7 个顶层 `const` 里任一改名/移除，整个赋值就抛 `ReferenceError`，**整座桥都建不起来**。失效边界写宽：任何**把 7 个声明移出经典脚本顶层**的变化（`type="module"` / 外层 IIFE / `defer`+DOMContentLoaded 包裹 / bundler 产物 / 压缩器重命名 / 脚本挪进 `<head>` 加包装）都会触发。
- **修法只用构建期 fail-fast，不做运行期降级**：`ToolAssetsContractTest` **逐个**断言 `const UI = (function` … `const Geo = (function` 共 **7 条各命中恰好 1 次**，任一改名 → 构建红 → 只改配置资源一处。**因此 `efb_shim.js` 里不做逐名 `typeof` 探测、不设 `degraded` 标志、不做任何能力探测/降级**（用户明确禁止）。`window.__DATASET__` 共存作主锚点。
- **契约里禁止出现 `window.UI`**：629 等是 `const`，`window.UI === undefined` 恒成立；shim 用裸名 `UI` + `window.__EFB__`，App 侧也只调 `__EFB__`。
- 方案是「内存拼桥」，**不存在「`evaluateJavascript` 能否见裸名」的问题**；阶段 1 做一次**开发期一次性机制选定**冒烟 `evaluateJavascript("typeof UI+','+typeof NavParser+','+typeof PackCodec")` 期望 `"object,object,object"`，失败即改用构建期拼桥。**不是运行期 gating、不是能力探测**。
- **冻结契约**（任一变化 = 改 `efb_patches.json` 这个**非 Kotlin** 资源；仅 §4.3「能力边界」例外才动 Kotlin）：文件名 `route_gen.html`/`fuel_calc.html`；baseURL；`window.__EFB__` 与 `Android` 注入对象名；`NavParser.parse`/`PackCodec.encode`/`UI.applyData`/`window.__DATASET__`；三个 file input id；DOM 契约（`.plan`/`.pdist`/`.pcopy[data-copy]`/`#route-off*|eng*`/`.overview .rt`，集中在 `render()` A:4733-4816；`.plan.open` 是运行时瞬时态、不作锚点）；B 的 `#dist`/`calc`/`runSelfTests`；7 个 `const <name> = (function` 声明。
- **契约 meta？不需要**：HTML 磁盘字节级原样，版本握手在 App 侧 + `efb_patches.json` 完成；**不给 HTML 加任何 meta、不改 charset/viewport 头部**。读 assets 统一 UTF-8、锚点纯 ASCII、CRLF 在内存里归一。
- **兜底（默认不跑，写进 SOP）**：若新版改成 `<script type="module">` 或整体包进 IIFE，导致 ① 插入点失效 → 单测失败 → **只改 `efb_patches.json` 的锚点/shim 资源**，必要时把挂载语句改成能进主 script 作用域的形式，**不改任何 Kotlin**。

### 4.3 出包期门禁（唯一把关点，不符即出包失败）

`app/src/test/.../ToolAssetsContractTest.kt`（Robolectric `@Config(sdk=[34])`；工程已开 `isIncludeAndroidResources=true`、已依赖 robolectric → **零新增 Gradle 依赖**；仿 `SpecExporterTest.exportedContentMatchesAsset` 读 assets 文本）：

1. 航路 HTML **逐个**含 7 条 `const UI = (function` … `const Geo = (function` 声明（各恰好 1 次，`requiredConsts` 来自 `efb_patches.json`），且 `</script>\s*</body>` 唯一序列命中恰好 1 次、`function downloadJson(filename, obj) {`(3821) 命中恰好 1 次、`URL.createObjectURL`(3824)/`a.download`(3826)/`a.click()`(3828) 各恰好 1 次（发现导出机制变化）；
2. 油量 HTML 含 `#dist`、`calc`、`runSelfTests`；
3. **`efb_patches.json` 里每个锚点在对应文件命中数恰好 1**；
4. 自包含：无 `http(s)://` 字面量、无 `<script src`、无 `<link href`、无 `@import`、无 `type="module"`（**不按 `fetch(` 判**，843 是死分支）；
5. `applyPatches(assetText)` 产出串里 `window.__EFB__` 与 guard 各出现恰好 1 次，`downloadJson` 函数体结构完整，重复调用**不叠加**（幂等）；
6. 版本正则能从文本提取 v8.4 / V6 供 `logVersions()` 使用，且 **`bridgeVersion` 与 `EXPECTED_BRIDGE_VERSION` 比对通过** —— 版本不是只进日志：界面/运行期只进日志，但**必须参与出包期断言**。

**`efb_patches.json` 与产物断言的 5 条硬约束**（任一不满足即 fail-fast 抛错，不做降级）：

1. 每个锚点须标明 `regex` / `literal`。`</script>\s*</body>` 是**跨行正则**，JSON 里必须写 `"</script>\\s*</body>"`（单反斜杠 `\s` 是**非法 JSON 转义**，解析器直接报错）；Kotlin 读出后按类型 `Regex()` 编译或字面量匹配，逐条先断言命中数 ==1。3821 完整函数签名用 literal。**中文 Windows 下 HTML 可能是 CRLF**：纯 `\n` 字面量锚点会漏匹配 → 用 `\s*` 正则，或 loader 先把 `\r\n` 归一成 `\n`（**只改内存 String，磁盘文件不受影响**）。
2. shim 文本断言**不含 `</script>` 字面**（否则提前截断内联 script），且**含 `window.__EFB__`**；shim 放独立 `.js` 资源而非 Kotlin 字符串 —— 既避开 Kotlin `$` 模板 / `}` 转义冲突，也让 JS 里的 `${...}` 原样保留。
3. 测试**不能只断言锚点命中数**，还须断言替换后的产物（见第 5 点），否则「config 锚点写偏但恰好命中 1 次」会漏过。
4. `fuel_calc.html` 在 config 里 **patches 保持为空**：B 顶层 function 本就是 window 属性（`window.calc` 可用），不需要 shim；B 维持字节级原样，运行期只做 `#dist` 距离注入。
5. 读 assets **显式 `bufferedReader(Charsets.UTF_8)`**（不要学 `ReferenceLibrary.kt:44` 省略字符集——中文 Windows 的 JVM 默认编码可能是 GBK，会让锚点匹配静默失败）；锚点保持**纯 ASCII**。

**能力边界（SOP 例外分支）**：方案 3 的表达力限于「锚点替换 + 拼接 shim」。升级时**先改 config**；只有 config 表达不了时才动 Kotlin，且这是**升级时改 Kotlin 的唯一合法入口**：例如新版删掉 `downloadJson`、改用 `showSaveFilePicker`、或需要第三种 patch 类型 → 单测失败 → 扩展 config schema 或改 `ToolContract` 的通用 loader（**仍不改任何业务 Kotlin**）。

任一失败 → 测试红 → **出包失败**。App 运行期**不做**能力探测/版本比对/降级。

### 4.4 App 侧其余职责

- `ToolContract.logVersions()`：从 assets 文本正则提取版本串 + 文件内容 hash，写 `Log.i(tag="EFB/TOOL")`；`WebChromeClient.onConsoleMessage` 统一转 `Log.i(tag="EFB/JS")`（A 的 `[DATA]`/`[数据] 应用失败`、B 的 `[自检]`）。**界面不显示版本号/自检结果**。
- **失败提示（可裁决项，默认保留）**：仅加载异常时工具页显示一句通用「网页资源不完整，请检查安装包」并停止加载（故障态提示 ≠ 版本展示；出包期门禁已兜底，几乎不会露面）。若要求界面一个字不变，改为只 `Log.e` + 停止加载（不推荐）。
- **时序**：`domStorageEnabled=false` + App 的 `UI.applyData()` 落在 `onPageFinished`；可选 `DataStore.get = async () => null`（A:2495 可赋值）须写进契约声明、不默认启用。旧 APK 升级不假设缓存为空。

### 4.5 升级 SOP（换新版 HTML → 出包）

| 步 | 动作 | 检查 |
|---|---|---|
| 1 | 用新版覆盖 `app/src/main/assets/tools/{route_gen.html, fuel_calc.html}`（**文件名不变**）；`Walker'sHTML/` 同步留档 | 两者 `diff` 一致（都是字节级原样）；单文件自包含、UTF-8、charset meta 仍在 |
| 2 | **不改任何 Kotlin**，跑门禁 `gradlew :app:testDebugUnitTest --tests "*ToolAssetsContractTest*"`（中文路径加 `-Pefb.buildDir=E:/Android/efb-build/app`） | 断言全过（正常升级 0 Kotlin 改动）；锚点漂移 → **只改 `efb_patches.json` / `efb_shim.js`（非 Kotlin）** 后重跑；导出机制整体换掉 → 走 §4.3 例外分支 |
| 3 | 全量 `gradlew :app:testDebugUnitTest`；Logcat 看 `EFB/TOOL` 版本串/hash、`EFB/JS` 的 `[自检]` | 既有 11 个用例无回归；自检字符串人工判读（非布尔） |
| 4 | 真机按 `真机验收清单.md` 第八节回归：导入 → 生成 → 保存 → 油量联动 → 导出 → 自定义检查单 | **功能完全不变**；导出 `routegen-pack.json` 与旧版字节一致；飞行模式 0 请求 |
| 5 | 递增 `versionCode`/`versionName`（当前 6 / 1.2.2），`assembleRelease`，SHA-256/签名校验 | **沿用同一 `keystore/efb-release.jks`** → 覆盖安装即可；换证书才触发 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| 6 | `工程改造清单.md` 加「网页工具升级」小节；`真机验收清单.md` 第八节打勾；`发布说明.md`/`发行版说明.md` 记 HTML 版本与 asset hash | 不新建独立文档；文档不进 APK |

**升级演练**（阶段 5 验收项）：① 假新版（只改版本号）走 1–5，验证「只换文件即可、Kotlin 一行没改」；② **故意改坏锚点**的假新版，验证门禁确实**让出包失败**。

### 4.6 明确排除（复核）

❌ 手机端选 HTML 热替换　❌ 外部文件加载 HTML　❌ 构建期生成「补丁副本」/跑补丁脚本　❌ 把 shim 放进 `<head>`（TDZ → ReferenceError）　❌ `window.UI.*` 式调用　❌ `evaluateJavascript` 注入桥/覆盖 `downloadJson`　❌ 运行时原型包装 `URL.createObjectURL`/`click`　❌ `setDownloadListener`　❌ Kotlin 侧契约校验/能力协商/多版本降级/版本匹配拦截　❌ 调 `acceptNavFiles/acceptAipFiles/acceptPack`　❌ 依赖 WebView 存储持久化　❌ 界面显示网页版本或自检结果　❌ 给 HTML 加 meta/改 charset 头部　❌ 把 shim 插进主脚本内部（5019 空行：ReferenceError 会中止整段主脚本，`UI.init()` 不跑、整页废掉）　❌ 运行期逐名 `typeof` 能力探测 / `degraded` 降级　❌ `HTMLElement.prototype.click` 宽包装　❌ 因升级预留改动导入/保存/检查单三块的实施内容。

---

## 5. 子代理任务分配

### 5.1 规划阶段（已完成）

| 子代理 | 结论要点 | 采纳 |
|---|---|---|
| `explore-upgrade-html` | 顶层 `const` 非 window；head 前置会崩；版本来源不存在；`window.__DATASET__` 主锚点；DOM 刮取高风险；`input` 事件注入更稳；`runSelfTests` 非布尔；出包期门禁是唯一把关点；charset 已存在 | ✅ 全部 |
| `explore-upgrade-app` | 零依赖/keystore/`isIncludeAndroidResources` 核实；冻结契约；DOM storage 残留；`SpecExporterTest` 模板与 `@Config(sdk=[34])`；「Kotlin 不改」边界澄清；`domStorageEnabled=false` + `onPageFinished` | ✅ 采纳不冲突部分；外部覆盖/能力降级/设置页显示 **否决** |
| `plan-upgrade` | **内存锚点替换**、`UI` return 不含 `downloadJson` 故必须改源字符串、**锚点唯一性清单**、guard 插在函数头覆盖两个调用点、自包含校验别按 `fetch(` 判 | ✅ **采纳为最终主路径**；其早期「原型包装导出」「运行时注入覆盖」已自行撤回，一并否决 |
| 主代理 | 澄清用户、复核 `proguard-rules.pro`、裁决分歧、写本计划 | — |

**主代理最终裁决**：① 升级 = 覆盖两个 HTML 文件 + 重新出包；② 不做多版本兼容/能力降级；③ 版本只进 Logcat（界面/运行期），出包期门禁属构建步骤、为唯一把关点且不符即失败；④ **补丁在 App 内存里按唯一锚点替换、磁盘字节级原样、不跑脚本**；⑤ 落点为 `</script>\s*</body>` 之间的独立新 `<script>`；⑥ `domStorageEnabled=false` + `onPageFinished` 固化时序；⑦ `ToolContract.kt` 只是通用 loader，不含锚点字面量、不含运行时校验/协商。

### 5.2 实施阶段（切 Agent 后主代理指挥，文件独占）

| 编号 | 子代理 | 独占文件 | 任务 | 依赖 / 并行 | 验收 |
|---|---|---|---|---|---|
| **0** 合规前置 🔴 | `agent-compliance` | `.gitignore`；git 索引 | 5 条忽略规则 + `git rm --cached -r` | **先于一切** | `git status` 无两目录；`git ls-tree HEAD` 仍为 0 |
| **A** 工具页骨架 + 契约 | `agent-tools-shell` | `ui/EfbApp.kt`、`ui/tools/{ToolsScreen,ToolsWebView,ToolsViewModel,ToolContract}.kt`(新)、`assets/tools/{route_gen.html,fuel_calc.html}`(原样复制)、`assets/tools/{efb_patches.json,efb_shim.js}`(新) | 第 4 标签 + 两子页；WebView 封装（`domStorageEnabled=false`、`onConsoleMessage`）；Compose SAF launcher；`ToolContract.kt` 通用 loader；`onPageFinished` 时序 | **先行**；HTML 原样复制，B/C/D/E 都**不改其内容** | 两页能开、切页不重载、Logcat 有版本串；`diff` 证明 assets 与原版字节级一致；改坏锚点的假新版能让门禁失败 |
| **B** 网页桥接与导入 | `agent-web-bridge` | `ui/tools/{ToolsBridge,ImportRepository}.kt`(新)、`app/proguard-rules.pro` | 分块注入 + `NavParser.parse` + `PackCodec.encode` + `UI.applyData`；`ToolsBridge`（`exportJson`/`saveRoute`/`onDataApplied`/`log`、切主线程）；两条 keep 规则 | 依赖 A 常量与回调签名 | 真数据导入→airports>0→能生成航路；release 桥有效；导出与旧版字节一致 |
| **C** 航路保存与联动 | `agent-route-link` | `ui/tools/{RouteStore,RoutesPanel}.kt`(新) | 点保存才写；命名/删除/设为当前；距离注入 | 依赖契约②③；与 B/D 并行 | 保存一条→切油量页 `dist` 自动变；不点保存不写盘 |
| **D** 自定义检查单 | `agent-checklist` | `data/Models.kt`、`data/CustomChecklistStore.kt`(新)、`ui/ReferenceViewModel.kt`、`ui/checklist/{ChecklistScreen,CustomChecklistEditorScreen}.kt` | 内置只读 + 复制；图形化编辑；7 个进度方法分流 | 与 A/B/C **无文件重叠，全程并行** | 复制→改顺序/改名→勾选→重启都在；内置不可改 |
| **E** 验收 + 升级 SOP | `agent-verify` | `app/src/test/...`（含 `ToolAssetsContractTest.kt`）、`工程改造清单.md`/`真机验收清单.md`(第八节)/`发布说明.md`/`发行版说明.md` | 单测（6 用例见 §4.3）；`.fms` 基线对照；两次升级演练；release 出包；`diff`；计划文档复制到工程根 | A–D 完成后 | 测试全过、两次演练符合预期、APK 校验通过 |

**冲突点已消除**：两个 HTML 由 A 原样复制，**所有子代理都不修改其内容**（补丁在内存里）；`EfbApp.kt` 只 A 改；`ReferenceViewModel.kt`/`Models.kt` 只 D 改；`MainActivity.kt`/`Settings.kt`/`SettingsScreen.kt` 无人改；`ToolContract.kt` A 独占、其余只读引用。

**时序**：`0 → A 定契约 → (B ∥ C ∥ D) → E`（共 6 阶段）。

---

## 6. 阶段与验收（6 阶段）

| 阶段 | 产出 | 验收动作 |
|---|---|---|
| **0 紧急合规** 🔴 | 5 条忽略规则 + `git rm --cached -r` | `git status --porcelain` 无 `A navData/`/`A co-routes/`；`git ls-tree HEAD` 仍为 0；**禁止 `git add -A`** |
| **1 骨架 + 契约** | A：第 4 标签、两子页、WebView、`ToolContract.kt`、两个 HTML 原样进 assets、`efb_patches.json`+`efb_shim.js` | Logcat 无 JS 报错；两页显示/滚动/缩放；4 标签与返回键；`unzip -l app.apk` 有 `assets/tools/` 四个文件且无 `.dat`/`.fms`；**`diff Walker'sHTML/ assets/tools/` 字节级一致**；`applyPatches()` 后 `window.__EFB__` 挂了 7 个对象引用、`getPlans()`/`exportJson()`/`exportHooked` 可用；Logcat 打出两个版本串与 hash |
| **2 导入（最关键）** | B：SAF 多选、分块注入、`ToolsBridge`、proguard keep | 真机多选散装 `.dat` → `datasetInfo()` 断言 airports>0、segments>0；坐标转换后 encode 前全量范围校验无 (-90,-180)；选 ZGSZ→ZSPD 生成航路成功；`rec.source='app'`；`onDataApplied(true)` 到主线程、**注入落在 `onPageFinished`**；重进工具页数据仍在；两个导出按钮由 App 写文件且能被 `filePack` 重新导入；CAAC/AIP 无数据属预期；**release 包桥未被 R8 剪掉** |
| **3 保存与联动** | C | 生成→保存→列表出现一条；生成/复制/展开不写盘；切油量页 `#dist` 等于 `distNM`；手改仍重算且不污染返回栈；20 条 `.fms` 总 NM 对照 **40643.2 NM**（只比公式/量级） |
| **4 自定义检查单** | D（与 1–3 并行） | 内置无编辑入口、复制后可编；▲▼ 排序/改名即时保存、勾选不串位；内置老进度不丢；`testDebugUnitTest` 全绿 |
| **5 验收 + 升级演练** | E：`ToolAssetsContractTest`、升级 SOP、APK、diff | `git status` 两目录未跟踪；APK 不含 `navData/`/`co-routes/`/`*.fms`/`*.dat`/派生 pack；Manifest 无 INTERNET、largeHeap=false；依赖未增；**`ToolAssetsContractTest` 6 用例全过**；**演练 A：假新版（只改版本号）→ 只覆盖两个 HTML、不改 Kotlin → 门禁 → 出包 → 功能回归全过**；**演练 B：故意改坏锚点 → 门禁红、出包失败**；`assembleRelease` + SHA-256/签名校验；计划文档复制到工程根 |

---

## 7. 风险清单

| 级别 | 风险 | 处置 |
|---|---|---|
| 🔴 | **3366 个 Navigraph 文件已暂存，下一次 commit 即公开分发** | 阶段 0 前置止损 + 阶段 5 复核 |
| 🔴 | 预置/打包派生 pack = published + modified + distributed | 三条红线：不预置、运行时变换、结果不落 APK |
| 🔴 | 缺 `apt.dat` → 机场为空 → `#btnGen` 恒禁用 | 运行时 `PackCodec.encode` 注入 `AIRPORTS_GLOBAL`；坐标由用户运行时导入 |
| 🔴 | Binder ≈ 1 MB：一次注入几 MB 会静默丢数据 | 分块 ≤256 KB、`JSONObject.quote`、按 Kotlin String 字符切、不用 base64、累加完置 null |
| 🔴 | 只改 `window.__DATASET__` 会让机场搜索/建图失效；`rec.source='builtin'` 触发 strict 失败 | 一律走 `UI.applyData`，`rec.source='app'`，`.then()` 取结果 |
| 🟡 | **锚点漂移会静默不生效**：`<script` 7 处、`</script>` 不唯一、`})();` 十余处都不唯一 | **命中数恰好 1**，`applyPatches()` 与单测双重断言；用 `</script>\s*</body>` 正则序列 / 3821 完整函数签名（config 标 `regex`/`literal`）；产物断言 + 幂等断言；演练 B 验证 |
| 🟡 | **Kotlin 不改 ⇒ 运行期无补救通道；新版悄悄改 DOM/const 名会静默出错** | **唯一把关点 = 出包期 `ToolAssetsContractTest`，不符即出包失败**；两次升级演练验证 |
| 🟡 | **桥插入点错**：放 `<head>` 抛 `ReferenceError`；插进主脚本内部(5019) 会中止整段主脚本 → `UI.init()` 不跑、整页废掉 | 落点固定为 `</script>\s*</body>` 之间的独立新 `<script>` |
| 🟡 | **单对象字面量「一颗子弹」**：7 个 const 任一改名/移出经典脚本顶层 → 整条赋值抛错、桥全不建 | **只用构建期 fail-fast**：单测逐条断言 `requiredConsts` 7 条各命中 1 次；**运行期不探测、不降级** |
| 🟡 | **顶层 `const` 不是 window 属性**；`window.UI.*` 式调用全失败 | 桥接挂 7 个对象引用到 `window.__EFB__`；App 只调 `__EFB__` |
| 🟡 | **`UI` return 不含 `downloadJson`** → 运行时覆盖不到 | 只在函数头(3821)插 guard；否决原型包装与 `setDownloadListener` |
| 🟡 | **版本来源不存在 / `APP_VERSION` 是 const** | 版本串由 `ToolContract` 正则提取写 Logcat；出包期参与 `bridgeVersion` 比对 |
| 🟡 | **自启动竞态 / 旧缓存残留**（baseURL 固定 → origin 不变） | `domStorageEnabled=false` → boot 快速落空状态；App 注入固化在 `onPageFinished`；`DataStore.clear()` 兜底 |
| 🟡 | **`runSelfTests()` 无返回值、不抛异常** | 只作 Logcat 文本判读，不作布尔自检信号 |
| 🟡 | **自包含校验误报**：按 `fetch(` 判会把 843 死分支当外链 | 校验改为「无 URL 字面量 + 无 script src + 无 link + 无 @import + 无 module」 |
| 🟡 | **出包忘跑门禁 / 中文路径未加 `-Pefb.buildDir`** | SOP 第 2 步列为硬门禁；命令模板写全参数 |
| 🟡 | 静默错坐标：手工拼 `AIRPORTS_GLOBAL` 会写入 (-90,-180) 或丢机场 | **必须 `PackCodec.encode`**；转换后 encode 前全量范围校验 |
| 🟡 | release 混淆破坏 JS 桥（debug 正常、release 静默失效） | 两条 keep 规则 + release 包实测 |
| 🟡 | `largeHeap=false` + 5 MB 文本 + 建图峰值内存 | 不得开 largeHeap；`Dispatchers.IO` 流式、禁 `readBytes()`、注入后释放；峰值 ≤200 MB |
| 🟡 | zip 解压 `deflate-raw` 版本依赖；SAF 4 坑；拖拽不可用 | 散装 `.dat`；`type="*/*"` + `DISPLAY_NAME` + 持久读权限（新增能力）；点击是唯一主路径 |
| 🟡 | `.plan.open` 默认不存在 / `data-plan` 与 `data-copy` 不一致 / `off${i}` 过滤后索引 | 由 `__EFB__.getPlans()` 收敛；禁止按 `last.*` 索引反查 |
| 🟡 | `fileAip`/`filePack` 无真实数据 | 导出→再导入闭环验格式链路，阶段 2 写明「预期行为」 |
| 🟢 | 高度层滚轮无 touch 滑动 | 页面 ▲▼ 按钮 + `UI.LevelWheel.up/down()`(4658) 已导出 |
| 🟢 | 版本串 / 自检结果变化；charset meta 已存在 | 只影响 Logcat；不加冗余 meta、不改 HTML 头部 |
| 🟢 | 沿用同一 keystore；故障态一句通用提示是否越界 | 覆盖安装不需卸载；提示默认保留（不含版本号/自检数字） |

---

## 8. 关键文件路径

**新增**：
- `app/src/main/assets/tools/route_gen.html`、`fuel_calc.html`（**字节级原样复制，磁盘上不含任何补丁**）
- `app/src/main/assets/tools/efb_patches.json`（两组补丁锚点 / `requiredConsts` / `shimAsset` / `bridgeVersion`，**非 Kotlin**）
- `app/src/main/assets/tools/efb_shim.js`（桥接脚本，**非 Kotlin**）
- `java/com/oguricap/efb/ui/tools/{ToolsScreen,ToolsWebView,ToolsViewModel,ToolContract,ToolsBridge,ImportRepository,RouteStore,RoutesPanel}.kt`
- `java/com/oguricap/efb/ui/checklist/CustomChecklistEditorScreen.kt`
- `java/com/oguricap/efb/data/CustomChecklistStore.kt`
- `app/src/test/java/com/oguricap/efb/.../ToolAssetsContractTest.kt`

| 文件 | 归属 | 职责要点 |
|---|---|---|
| **`ToolContract.kt`** | A 独占 | **通用 loader，一次写好永不改**：assets 路径、baseURL、桥对象名、`EXPECTED_BRIDGE_VERSION`、`loadPatches(context)`（现有 `JSONObject` 解析 `efb_patches.json`）、`applyPatches(html, patches)`（regex/literal 锚点、命中数==1、CRLF 归一、内存替换、不写磁盘）、`validate()`、`logVersions()`。**不含任何锚点/JS 字面量、不含运行时校验/协商/降级** |
| `ToolsWebView.kt` | A | 读 assets → `applyPatches()` → `loadDataWithBaseURL`（固定 baseURL/UTF-8）；`domStorageEnabled=false`；`onConsoleMessage` 转 Logcat；`onPageFinished` 时序 |
| `ToolAssetsContractTest.kt` | E | Robolectric `@Config(sdk=[34])`（SDK 36 沙箱需 JDK 21，本机 17），仿 `SpecExporterTest.exportedContentMatchesAsset`；**6 用例见 §4.3，唯一把关点** |
| `ToolsBridge.kt` | B | `@JavascriptInterface`（`exportJson`/`saveRoute`/`onDataApplied`/`log`）+ 切主线程 |
| `ImportRepository.kt` | B | SAF 流式读、按 String 字符分块、Uri 列表持久化、坐标→airports 组装、全量坐标校验 |

**修改**：`ui/EfbApp.kt`(A)、`data/Models.kt`(D，加带默认值 uid)、`ui/ReferenceViewModel.kt`(D)、`ui/checklist/ChecklistScreen.kt`(D，257/220/239)、`app/proguard-rules.pro`(B，两条规则)、`.gitignore`(0)、`app/build.gradle.kts`(E，仅递增版本号)、`工程改造清单.md`(E)、`真机验收清单.md`(E，第八节)、`发布说明.md`/`发行版说明.md`(E)。

**不动**：`Walker'sHTML/` 两个原版（diff 对照）、`MainActivity.kt`、`Settings.kt`、`SettingsScreen.kt`、`AndroidManifest.xml`（含 `largeHeap`）、`data/ReferenceLibrary.kt`、`data/ChecklistProgress.kt`、`gradle/libs.versions.toml`、HTML 的 charset/viewport 头部、导入/保存/检查单三块既有实现与算法。

**验收数据（不入库、不打包）**：`navData\...\*.dat`、`co-routes\*.fms`、用户自备的合法/公开坐标数据文件。

---

## 9. 收尾说明

- 本计划承接此前全部探索与裁决，**无遗留冲突**。
- 最终裁决基准：**只覆盖两个 HTML 文件 + 重新出包；正常升级 0 Kotlin 改动，锚点漂移也只改 `efb_patches.json`/`efb_shim.js`（非 Kotlin）；补丁在 App 内存里按 config 锚点替换、磁盘字节级原样、不跑构建脚本；`fuel_calc.html` 的 patches 为空；唯一把关点是出包期 `ToolAssetsContractTest`（版本参与比对 + 产物断言），不符即出包失败；App 运行期零协商零降级；功能完全不变；版本只进日志（界面/运行期）**。
- **可裁决项（不阻塞）**：不兼容时是否在工具页保留一句通用错误提示（默认保留，不含版本号/自检数字）。若要求界面零变化，改为只 `Log.e` + 停止加载。
- 切 Agent 后默认**先执行阶段 0**，再按 1→5 顺序；若希望跳过合规处置或能补一份真实 X-Plane `apt.dat`，实施前告知即可。
- 在用户明确说「开始改」之前，不改任何工程文件。
