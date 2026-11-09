# 网页工具 HTML 升级规范（v1）

> **适用对象**：AI（以及任何人）为 App 更新两个网页工具时的完整流程与硬性约束。
> **目的**：确保 AI 产出的新 HTML **每次都能干净合并**进 `assets/tools/`，契约测试一次通过，不出包失败、不真机静默出错。
> **机制依据**：`ToolContract.kt`（运行期装载 + 静态断言）、`ToolAssetsContractTest.kt`（出包期唯一门禁）、`efb_patches.json`（补丁配置）。
> 本规范与代码不一致时，**以代码和契约测试为准**，并回改本文件。

---

## 1. 总览：升级是一条流水线

```
AI 产出新版 HTML（草稿）
    ↓  按 §3 硬性契约自查
Walker'sHTML/  存档草稿（命名见 §5）
    ↓  按 §4 合并清单逐项执行
覆盖 app/src/main/assets/tools/route_gen.html / fuel_calc.html
    ↓
跑 ToolAssetsContractTest（出包期门禁，必须全绿）
    ↓
重新出包 → 真机验收（§4 第 6 步）
```

三条不可动摇的约定（引自 `ToolContract.kt`）：

1. 磁盘上 `assets/tools/` 的 HTML **字节级原样**，所有补丁只在内存里打；
2. 每个补丁锚点在对应文件里必须**恰好命中 1 次**，否则构建失败；
3. 正常升级 **0 处 Kotlin 改动**。只有契约本身变了（§6）才允许动 Kotlin。

---

## 2. 两个目标文件与它们的角色

| 文件 | 草稿来源 | 说明 |
|---|---|---|
| `app/src/main/assets/tools/route_gen.html` | `Walker'sHTML/航路生成器_vX.Y_无数据版.html` | 航路生成器，含 8 个顶层 const 模块 |
| `app/src/main/assets/tools/fuel_calc.html` | `Walker'sHTML/油量计算器_vX.html` | 油量计算器，含自检函数 |

App 运行时对这两份 HTML 做**内存锚点替换**（注入 `efb_shim.js` 桥 / 皮肤），磁盘文件永不写回。所以 AI 只负责产出**干净的原始 HTML**，桥、皮肤、导出劫持全部由 App 侧补丁完成——**AI 不要在 HTML 里自己实现任何桥逻辑**。

---

## 3. 硬性契约（AI 生成 HTML 必须逐条满足）

以下每一条都对应 `ToolAssetsContractTest` 里的一条断言，**违反任意一条 = 构建红**。

### 3.1 通用（两个文件都适用）

| # | 规则 | 原因 |
|---|---|---|
| U1 | **单文件自包含**：全文不得出现 `http://`、`https://`、`<script src`、`<link href`、`@import`、`type="module"`、`type='module'` | 无网络权限、禁止外部依赖与模块化。**注意：注释和 title 里也不行**，`<link rel="icon">` 也不行——这是字符串级检查 |
| U2 | **不得包含任何补丁 marker**：`__EFB_BRIDGE__`、`window.__EFB_EXPORT_GUARD__ = 1;`、`__EFB_FUEL_SKIN__` | marker 是幂等标记，预置会让补丁被跳过 |
| U3 | 文件编码 **UTF-8**（无 BOM 最佳）；换行 LF/CRLF 均可（内存里会统一 CRLF→LF） | 中文 Windows 默认 GBK 会让锚点静默失配 |
| U4 | 不内嵌任何导航/AIP 数据（保持"无数据版"），数据一律运行时由用户导入 | Navigraph 授权红线 |
| U5 | 桥调用只允许两种既有形态：`window.__EFB__.*`（shim 提供）或被补丁劫持的 `downloadJson`。不要发明新的 `Android.*` 调用 | release 包 R8 会混淆未 keep 的方法名 |

### 3.2 航路生成器（route_gen.html）专属

| # | 规则 | 要求 |
|---|---|---|
| R1 | **8 个顶层 const 模块一个都不能少、不能改名**，且各自恰好出现 1 次，写法必须是 `const 名字 = (function`：`Codec`、`DatasetLoader`、`Geo`、`PackCodec`、`DataStore`、`NavParser`、`UI`、`AipParser` | IIFE 形式是契约检查的字面依据；改成箭头函数/类/拆成两个文件都会红 |
| R2 | 桥插入锚点 `</script>` 紧跟 `</body>` 的序列**全文恰好 1 次**（`</script>\s*</body>`） | 补丁从这里注入 shim；页面上出现第二个内联 script 顶着 `</body>` 就会命中 2 次 |
| R3 | `function downloadJson(filename, obj) {` **恰好 1 次**，且函数体保留 `const blob = new Blob` 一行不被改 | 导出补丁以此为锚点做头部替换 |
| R4 | 浏览器下载机制不变：`URL.createObjectURL`、`a.download`、`a.click()` 各恰好 1 次 | 这是"导出走 Blob"的证据；换 `showSaveFilePicker` 属契约变更，见 §6 |
| R5 | `id="fileNav"` 恰好 1 次（导航数据文件选择框） | shim 劫持它的 `.click()` |
| R6 | 页面里存在形如 `v8.6` 的版本串（可被正则 `v(\d+\.\d+)` 提取） | 只进 Logcat，但正则提不到会红。**加版本时同步改这里** |

### 3.3 油量计算器（fuel_calc.html）专属

| # | 规则 | 要求 |
|---|---|---|
| F1 | `id="dist"`、`id="aircraft"`、`id="regime"` 各恰好 1 次 | 距离自动注入依赖 `#dist` + `input` 事件 |
| F2 | `function calc()`、`function runSelfTests()` 各恰好 1 次 | 距离注入会触发 `calc()`；自检函数是契约的一部分 |
| F3 | 顶部定义 `APP_VERSION = 'X.Y'`（**单引号**，正则 `APP_VERSION\s*=\s*'([^']+)'` 可提取） | 双引号提取不到会红 |
| F4 | 页面末尾同样要有唯一的 `</script>\s*</body>` 序列（皮肤补丁锚点） | 同 R2 |

### 3.4 AI 绝对不碰的文件

- `app/src/main/java/**`（全部 Kotlin，尤其 `ToolContract.kt`、`ToolsBridge.kt`、`ToolsWebView.kt`）
- `app/src/main/assets/tools/efb_patches.json`
- `app/src/main/assets/tools/efb_shim.js`、`efb_fuel_skin.js`
- `app/src/test/java/com/oguricap/efb/ToolAssetsContractTest.kt` 及其他测试

例外：锚点漂移（§6 情形 B）时，只允许**人工确认后**改 `efb_patches.json` 的锚点串或 shim，Kotlin 仍不动。

---

## 4. 合并清单（每次升级照着做）

1. **自查**：AI 对照 §3 逐条输出自查结论（哪条过了、版本串是什么），再交付草稿。
2. **存档**：草稿放入 `Walker'sHTML/`，命名见 §5，不覆盖旧版本草稿。
3. **覆盖**：把草稿字节级复制为对应 assets 文件（`route_gen.html` / `fuel_calc.html`）。一次升级可以只换其中一个。
4. **跑门禁**：
   ```
   gradlew :app:testDebugUnitTest --tests "com.oguricap.efb.ToolAssetsContractTest"
   ```
   全绿才继续；红 → 查 §7 诊断表，改的是**草稿**，不是测试。
5. **出包**：正常构建 APK。
6. **真机验收**：两个工具页能打开、无白屏；航路页导入导航数据后能生成方案并能导出 JSON；油量页切换时航段距离自动填入；Logcat 里 `EFB/TOOL` 打出版本号与 sha256。
7. **记录**：在 `docs/更新文案.md` / `docs/开发日志.md` 追加一条（版本号 + 变更点 + 验收结果）。

---

## 5. 草稿命名与版本规范

- 命名：`工具名_v版本_备注.html`，例：`航路生成器_v8.7_无数据版.html`、`油量计算器_v7.html`。备注可省略。
- 版本只增不减；小改 +1 小数位（8.6→8.7），结构/契约级改动进位（8.7→9.0）。
- HTML 内部版本串（R6/F3）与文件名版本**保持一致**。
- `Walker'sHTML/` 只追加不删除，作为可回滚的历史。

---

## 6. 什么时候才允许"改契约"

| 情形 | 判定 | 动作 |
|---|---|---|
| A. 正常功能迭代 | §3 全部满足 | 覆盖 HTML → 门禁 → 出包，0 Kotlin 改动 |
| B. 锚点漂移（新版 HTML 结构变了，但能力没变） | R2/R3/F4 等锚点命中数 ≠ 1，且新结构功能等价 | 人工确认后只改 `efb_patches.json` 的锚点串或 shim，Kotlin 仍不动；门禁须全绿 |
| C. 能力变更（如导出换成 `showSaveFilePicker`、新增桥方法） | R4 等机制证据缺失，或需要新 `Android.*` 接口 | 属于**契约变更**：同步改 Kotlin（`ToolContract`/`ToolsBridge`）、`efb_patches.json`、`efb_shim.js`、`bridgeVersion`（三处一起 +1）、本规范和测试。单独走一次代码评审 |

**判定原则**：先问"新 HTML 在浏览器里（无桥）是否与旧版能力等价"。等价 → 情形 B；不等价 → 情形 C。

---

## 7. 常见失败速查

| 门禁报错（关键词） | 根因 | 修法 |
|---|---|---|
| `const X = (function 命中 0/2 次` | 模块改名 / 拆文件 / 写法变了 | 恢复原 const 名与 IIFE 写法 |
| `</script>\s*</body> 命中 2 次` | 页面尾部多了顶着 `</body>` 的内联 script | 合并脚本或挪进前一个 script 块，留唯一一处 |
| `downloadJson 的函数签名必须唯一` | 函数改名或签名变了 | 恢复 `function downloadJson(filename, obj) {` |
| `不应出现 http://（自包含被破坏）` | 注释/字体/图标里带了 URL，或 `<link rel=icon>` | 全文搜索删掉，离线资源一律内联 |
| `versionRegex 提取不到版本串` | 版本串格式变了 / `APP_VERSION` 用了双引号 | 对齐 R6/F3 格式 |
| `必须声明幂等 marker` / 补丁被跳过 | HTML 预置了 marker 串 | 删掉预置串（U2） |
| `#dist 输入框应恰好 1 个` 等油量断言红 | 改了 id 或 `calc()`/`runSelfTests()` 签名 | 恢复 F1/F2 命名 |
| debug 正常、release 桥失效 | R8 混淆了桥方法 | 确认 `proguard-rules.pro` keep 未被删；AI 别新增未 keep 的 `Android.*` 方法 |

---

## 8. 本规范的维护

- 代码契约变了（情形 C）→ 同一提交里更新本文件 §3 的对应行。
- 门禁新增断言 → 同步补进 §3 与 §7。
- 修订记录：v1（2026-11-09）初版，依据 `ToolContract.kt` / `ToolAssetsContractTest.kt` / `efb_patches.json` 现状编写。
