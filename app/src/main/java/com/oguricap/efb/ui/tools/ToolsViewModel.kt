package com.oguricap.efb.ui.tools

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oguricap.efb.diag.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 工具页的状态中枢：两个子页的 WebView 通道、导入进度、方案快照、已存航路。
 *
 * 设计要点：
 *  - **两个 WebView 常驻**，切页只切可见性，不重新 loadDataWithBaseURL，已导入数据不丢；
 *  - 导入走「分块 → JS 拼接 → 页面内解析」，App 侧不做任何数据变换；
 *  - 保存航路只有用户点「保存」才写盘，生成/展开/复制一律不落盘；
 *  - 油量页只在切过去时注入一次距离，之后用户手改不受影响。
 */
class ToolsViewModel(app: Application) : AndroidViewModel(app) {

    private val tag = "EFB/TOOL"
    private val importer = ImportRepository(app)
    private val routeStore = RouteStore(app)
    private val cache = DatasetCache(app)

    // ---- 子页与通道 ----

    var page by mutableStateOf(ToolsPage.Route)
        private set

    private var routeChannel: JsChannel? = null
    private var fuelChannel: JsChannel? = null

    var routeReady by mutableStateOf(false)
        private set
    var fuelReady by mutableStateOf(false)
        private set

    /** 网页资源缺失或锚点漂移时的故障态提示；为空表示正常。 */
    var loadError by mutableStateOf<String?>(null)
        private set

    // ---- 导入 ----

    var importBusy by mutableStateOf(false)
        private set
    var importStatus by mutableStateOf("")
        private set
    var lastImport by mutableStateOf<ImportOutcome?>(null)
        private set

    /** 上次导入过的 SAF Uri（只是路径，不含任何数据内容）。 */
    var rememberedCount by mutableStateOf(
        if (importer.treeUri() != null) 1 else importer.persistedUris().size
    )
        private set

    /** 本机缓存的数据集体积（字节）。0 表示还没缓存过。 */
    var cacheSize by mutableStateOf(cache.sizeBytes())
        private set

    /**
     * 网页里当前是否已经载着数据。
     *
     * 网页数据不落盘（只记 Uri），WebView 销毁后数据就没了 ——
     * 所以这个标记一变 false，下次页面加载完成就按记住的 Uri 自动重导一次。
     */
    private var routeHasData = false

    /** 自动恢复失败过一次就不再自动重试，避免每次进页面都白等一轮解析。 */
    private var autoRestoreFailed = false

    /**
     * 「等页面确认数据可用再记」的两份待提交记录。
     *
     * 不能在开始导入时就写进偏好：导一份坏数据会把上次能用的 Uri 一起顶掉，
     * 下次启动按坏 Uri 重导又是一次失败，用户就再也回不到能用状态了。
     */
    private var pendingRemember: List<Uri>? = null
    private var pendingRememberTree: Uri? = null

    /**
     * 当前这次恢复是不是「导入失败后的补救」。
     * 只用来换提示语：补救成功要说「已恢复上一次导入的数据」，而不是「已导入」。
     */
    private var recovering = false

    /** 压缩包因为体积/条目上限被截断时的包名，导入真失败时用它解释原因。 */
    private var lastZipTruncated: String? = null

    /** 上一次成功生效的数据集规模，用来判断「导入失败后页面里的数据到底换没换」。 */
    private var lastGoodDataset: ImportOutcome? = null

    /**
     * 「选文件夹」导入时，导航数据生效后要接着导入的 AIP 表。
     *
     * 为什么必须等到 onDataApplied 再导：AIP 表要拿导航数据的坐标去重建国内走廊，
     * 网页侧的 applyAip 会检查 `window.__DATASET__`；两个 importCommit 连着发，
     * 第二个会读到还没换新的数据集（AipParser 解析是 setTimeout 里的同步活）。
     */
    private var followAip: FolderSelection? = null

    /** 一次文件夹导入里挑出来的东西（可能是原件，也可能是压缩包）。 */
    private data class FolderSelection(
        val files: List<DataFolder.Found>,
        val zips: List<DataFolder.Found>,
    )

    // ---- 方案 ----

    var snapshot by mutableStateOf<PlanSnapshot?>(null)
        private set

    /** 读方案期间页面又变了，读完补读一次。 */
    private var plansDirty = false
    var plansBusy by mutableStateOf(false)
        private set

    // ---- 已存航路 ----

    var saved by mutableStateOf<List<SavedRoute>>(emptyList())
        private set
    var activeId by mutableStateOf<String?>(null)
        private set

    /** 一次性提示（Snackbar），UI 读走以后调 [consumeMessage] 清掉。 */
    var message by mutableStateOf<String?>(null)
        private set

    /** 油量页本次填进去的距离（NM），null 表示没填过。 */
    var fuelFilledNM by mutableStateOf<Int?>(null)
        private set

    /** 切到油量页时排队的填充：等 refreshPlans() 读完最新 DOM 再执行。 */
    private var pendingFuelFill = false

    /**
     * 切页后允许「补填」的时间窗。
     *
     * 方案卡片比 shim 的 400ms 防抖通知早出现，切页过快会读到空快照；
     * 这批方案稍后到了要能补上。但补填只在这个窗口内做 ——
     * 过了窗口说明用户已经在看油量页，再改他手输的距离就是打扰。
     */
    private var fuelFillWindowUntil = 0L

    /** 注入失败后只自动重试一次，避免和「页面还没初始化好」死循环。 */
    private var fuelRetried = false

    init {
        reloadSaved()
        ToolContract.logVersions(app)
    }

    // ---------------------------------------------------------------- 通道

    fun attachChannel(page: ToolsPage, channel: JsChannel) {
        when (page) {
            ToolsPage.Route -> routeChannel = channel
            ToolsPage.Fuel -> fuelChannel = channel
        }
    }

    fun detachChannel(page: ToolsPage, channel: JsChannel) {
        when (page) {
            ToolsPage.Route -> if (routeChannel === channel) {
                routeChannel = null
                routeReady = false
                // 页面销毁 = 网页里的数据集一起没了。下次回来要按记住的 Uri 重新导入，
                // 否则「切到航图再切回工具」就变成一张空页面。
                routeHasData = false
            }

            ToolsPage.Fuel -> if (fuelChannel === channel) {
                fuelChannel = null
                fuelReady = false
                fuelFilledNM = null
            }
        }
    }

    /** 网页 onPageFinished：桥已经就绪，可以安全地调用 __EFB__。 */
    fun onPageReady(page: ToolsPage) {
        when (page) {
            ToolsPage.Route -> {
                routeReady = true
                // 进页面先看桥在不在（纯诊断，日志里能一眼看出 7 个 const 是否还在）
                routeChannel?.eval(
                    "typeof window.__EFB__ + ',' + (window.__EFB__ ? window.__EFB__.bridgeVersion : '')"
                ) { v ->
                    AppLog.i(tag, "route bridge=$v")
                    if (v.startsWith("\"undefined") || v == "\"undefined,null\"") {
                        loadError = "网页资源不完整，请检查安装包"
                    }
                }
                refreshPlans()
                maybeAutoRestore()
            }

            ToolsPage.Fuel -> {
                fuelReady = true
                // 已经读到过方案的话，进油量页就把距离补上
                if (fuelFilledNM == null) fillFuelDistance()
            }
        }
    }

    fun onPageLoadFailed(reason: String) {
        AppLog.e(tag, "网页资源加载失败：$reason")
        loadError = "网页资源不完整，请检查安装包"
    }

    /**
     * 渲染进程被系统回收后，页面已经重建过一次。
     *
     * 页面里的东西全没了（生成好的航路、已输入的机场），已导入的导航数据靠缓存自动恢复。
     * 用户此刻看到的是一个空页面，必须明确说一句，否则会以为「数据也被清掉了」。
     */
    fun onRendererGone(page: ToolsPage) {
        AppLog.w(tag, "${page.label} 渲染进程被回收，页面已重建")
        if (page == ToolsPage.Route) {
            // 强制走一次 maybeAutoRestore：重建的页面里数据集是空的
            routeHasData = false
            snapshot = null
            message = "航路页被系统回收后重新加载，已导入的数据会自动恢复，请重新生成航路"
        }
    }

    fun selectPage(target: ToolsPage) {
        if (page == target) return
        page = target
        if (target == ToolsPage.Fuel) {
            // 生成完立刻切到油量页时，shim 的 400ms 防抖可能还没到 ——
            // 直接填会读到上一批（甚至是空的）方案。先主动读一次 DOM，读完再填。
            pendingFuelFill = true
            fuelFillWindowUntil = System.currentTimeMillis() + FUEL_HEAL_MS
            refreshPlans()
        }
    }

    fun consumeMessage() {
        message = null
    }

    /**
     * 页面刚加载完：如果网页里还没有数据、而用户上次导入过，就按记住的 Uri 自动重导一次。
     *
     * 这是「切到别的标签再切回工具页，数据还在」的实现方式。整套设计只持久化 Uri、
     * 不落任何派生数据包（避免把 Navigable 数据写到本机），所以重进必然要重解析一遍。
     * 失败过一次就不再自动重试，免得每次进页面都白等几十秒。
     */
    private fun maybeAutoRestore() {
        if (routeHasData || importBusy || autoRestoreFailed) return
        // 有缓存就直接用缓存：不用再读 25 MB 原文件、也不用重跑解析
        if (cache.exists()) {
            AppLog.i(tag, "发现本机缓存 ${cache.sizeBytes() / 1024} KB，直接恢复")
            restoreFromCache()
            return
        }
        // 缓存没写成（比如上次被系统杀掉）时，退回「按记住的位置重新解析」。
        // 优先用记住的文件夹（一条记录就能重扫出整套数据），其次才是散文件。
        importer.treeUri()?.let { tree ->
            AppLog.i(tag, "没有缓存，按记住的文件夹重新扫描：$tree")
            importFolder(tree)
            return
        }
        val uris = importer.persistedUris()
        if (uris.isEmpty()) return
        AppLog.i(tag, "没有缓存，按记住的 ${uris.size} 个 Uri 重新导入")
        importNav(uris)
    }

    /**
     * 用本机缓存恢复数据集。
     *
     * 走的是和数据包导入完全相同的分块通道（kind = restore），只是来源换成私有目录里的
     * JSON —— 网页侧拿到的东西和解码逻辑都没变，所以不需要额外维护一套恢复代码。
     *
     * @param recovery true = 刚有一次导入失败，用缓存把上一次能用的数据放回去。
     *   此时不覆盖用户的失败提示（失败原因比「缓存读不出来」更重要），也不把它记成
     *   「自动恢复失败」，否则下次进页面就不再尝试了。
     */
    private fun restoreFromCache(recovery: Boolean = false) {
        val ch = routeChannel ?: return
        viewModelScope.launch {
            importBusy = true
            importStatus = if (recovery) "恢复上一次导入的数据…" else "恢复上次导入的导航数据…"
            try {
                val text = withContext(Dispatchers.IO) { cache.read() }
                if (text.isNullOrBlank()) {
                    importBusy = false
                    importStatus = ""
                    autoRestoreFailed = true
                    if (!recovery) message = "本机缓存读不出来，请重新导入一次"
                    return@launch
                }
                recovering = recovery
                ch.eval("__EFB__.importBegin(\"restore\")")
                var at = 0
                while (at < text.length) {
                    val end = minOf(at + CACHE_CHUNK, text.length)
                    val chunk = text.substring(at, end)
                    withContext(Dispatchers.Main.immediate) {
                        ch.eval("__EFB__.importAppend(\"本机缓存\", ${JSONObject.quote(chunk)})")
                    }
                    at = end
                }
                ch.eval("__EFB__.importFileEnd()")
                withContext(Dispatchers.Main.immediate) { ch.eval("__EFB__.importCommit()") }
            } catch (t: Throwable) {
                recovering = false
                AppLog.e(tag, "缓存恢复失败", t)
                importBusy = false
                importStatus = ""
                autoRestoreFailed = true
                if (!recovery) message = "上次的数据恢复失败，请重新导入一次"
            }
        }
    }

    /** 网页把编码好的数据集分块传回来了（导入成功后自动发生）。 */
    fun onPackSerialized(json: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { cache.write(json) }
            cacheSize = cache.sizeBytes()
            if (ok) {
                AppLog.i(tag, "数据集已缓存：${json.length} 字符 / ${cacheSize / 1024} KB")
            } else {
                // 缓存没写成功不致命：下次进来会退回「按记住的 Uri 重新解析」，
                // 但用户得知道为什么下次还要等几十秒，而不是以为数据丢了
                AppLog.w(tag, "数据集缓存写入失败：${json.length} 字符")
                message = "本机缓存写入失败，下次进入工具页需要重新解析一次"
            }
        }
    }

    // ---------------------------------------------------------------- 导入

    /**
     * 导入导航数据。只读取用户自己选中的 SAF Uri，内容全部通过 JS 分块送进网页，
     * 解析与坐标编码都在网页侧完成（PackCodec.encode），App 不碰数据变换。
     */
    fun importNav(uris: List<Uri>) = importFiles(KIND_NAV, uris)

    /**
     * 三个入口共用一条管线，靠 [kind] 分流：`nav` 导航数据 / `aip` CAAC 航路表 / `pack` 数据包。
     * 页面侧按 kind 走不同的解析器，App 只负责把文本分块送进去。
     */
    fun importFiles(kind: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ch = routeChannel ?: run { message = "航路页还没准备好，请稍后再试"; return }
        if (importBusy) return

        viewModelScope.launch {
            importBusy = true
            importStatus = "准备导入 ${uris.size} 个文件…"
            lastZipTruncated = null
            recovering = false
            var total = 0L
            var pushed = 0
            try {
                // 只把导航数据的 Uri 记成「上次选择」；AIP 表和数据包是一次性动作，
                // 自动恢复时重放它们没有意义。
                // **但要等页面确认数据可用再记**：先记后导的话，导一份坏数据会把上次
                // 能用的那批 Uri 一起顶掉，下次启动按坏 Uri 重导又是一次失败。
                if (kind == KIND_NAV) pendingRemember = uris
                ch.eval("__EFB__.importBegin(${JSONObject.quote(kind)})")
                for ((i, uri) in uris.withIndex()) {
                    val name = importer.displayName(uri)
                    if (DataFolder.roleOf(name) == DataRole.ZIP) {
                        // 压缩包必须在这里解开再送：当成 UTF-8 文本读出来只是二进制乱码，
                        // 网页侧解析不出任何东西，只会回一句「数据不可用」。
                        // 1.3.11 之前「导入整个 zip」就是栽在这一步。
                        importStatus = "解压 ${i + 1}/${uris.size} · $name"
                        val scan = importer.streamZip(uri, acceptFor(kind)) { entryName, chunk ->
                            withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                ch.eval(
                                    "__EFB__.importAppend(${JSONObject.quote(entryName)}, ${JSONObject.quote(chunk)})"
                                )
                            }
                        }
                        if (scan.truncated) lastZipTruncated = name
                        if (scan.names.isEmpty()) {
                            AppLog.w(tag, "压缩包 $name 里没有 kind=$kind 需要的条目")
                        } else {
                            AppLog.i(tag, "压缩包 $name 命中 ${scan.names.size} 个条目：${scan.names.joinToString()}")
                            pushed += scan.names.size
                            total += scan.chars
                        }
                    } else {
                        importStatus = "读取 ${i + 1}/${uris.size} · $name"
                        total += importer.stream(uri) { chunkName, chunk ->
                            withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                ch.eval(
                                    "__EFB__.importAppend(${JSONObject.quote(chunkName)}, ${JSONObject.quote(chunk)})"
                                )
                            }
                        }
                        pushed++
                    }
                    ch.eval("__EFB__.importFileEnd()")
                }

                if (pushed == 0) {
                    ch.eval("__EFB__.importReset()")
                    importBusy = false
                    importStatus = ""
                    autoRestoreFailed = true
                    message = emptyEntryMessage(kind)
                    return@launch
                }

                importStatus = "解析中 · ${total / 1024} K 字符…"
                withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    ch.eval("__EFB__.importCommit()")
                }
                // 剩余流程由网页回调 onDataApplied 推进，这里不 await
            } catch (t: Throwable) {
                AppLog.e(tag, "导入失败", t)
                importBusy = false
                importStatus = ""
                // 读盘/编码阶段就炸了：网页不会回调 onDataApplied，状态在这里补齐，
                // 否则 importBusy 会一直挂在 true
                lastImport = ImportOutcome(false, t.message ?: t.javaClass.simpleName, 0, 0, 0, 0)
                autoRestoreFailed = true
                message = "导入失败：" + (t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * 压缩包里认哪些条目。与「选文件夹」那条路径共用同一套 [DataFolder.roleOf] 规则，
     * 免得同一个文件在两条入口下得到不同待遇。
     */
    private fun acceptFor(kind: String): (String) -> Boolean = when (kind) {
        KIND_AIP -> { name -> DataFolder.roleOf(name).isAip }
        KIND_PACK -> { name -> name.endsWith(".json", ignoreCase = true) }
        else -> { name -> DataFolder.roleOf(name).isNav }
    }

    /** 一个可用条目都没送进去时的解释。空手而归的原因必须具体，不能只说「失败了」。 */
    private fun emptyEntryMessage(kind: String): String = when (kind) {
        KIND_AIP -> "这个压缩包里没有 AIP 的 CSV / JSON。CAAC 的表名形如 " +
            "「International Flights from Mainland of China Nr2611.csv」。"
        KIND_PACK -> "这个压缩包里没有本工具导出的 .json 数据包。"
        else -> "这个压缩包里没有 earth_fix / earth_awy / earth_nav / earth_aptmeta。" +
            "请确认选的是导航数据包，而不是航图或其它 zip。"
    }

    /**
     * 导入一个**文件夹**：自动挑出需要的导航数据（以及顺带的 AIP 表）。
     *
     * 为什么要有这条入口：X-Plane 12 的导航包解压后，earth_fix / earth_awy / earth_nav /
     * earth_aptmeta 散在一个目录里，旁边还有 CIFP、airspaces、atc data 一堆用不上的东西，
     * 而 apt.dat 又根本不在这个包里（在 Global Scenery 下）。让用户逐个记「要哪四个」
     * 是这一版最容易失败的一步 —— 少选 aptmeta 就没有机场坐标，输入 ZBAA 直接
     * 报「数据库里没有」，看起来就像「导入完不能算」。
     *
     * 这里只做「列目录 + 按文件名定角色」，内容仍然走分块管线交给网页解析。
     */
    fun importFolder(treeUri: Uri, kind: String = KIND_NAV) {
        val ch = routeChannel ?: run { message = "航路页还没准备好，请稍后再试"; return }
        if (importBusy) return
        // 从「② AIP 航路表」进来时只认航路表，避免误把同一目录的导航数据也重导一遍
        val wantNav = kind != KIND_AIP

        viewModelScope.launch {
            importBusy = true
            importStatus = "扫描文件夹…"
            followAip = null
            lastZipTruncated = null
            recovering = false
            try {
                // 同样等页面确认可用再记（见 importFiles 里的说明）
                pendingRememberTree = treeUri
                val found = withContext(Dispatchers.IO) { DataFolder.scan(getApplication(), treeUri) }

                val navFiles = if (wantNav) DataFolder.pickNav(found) else emptyList()
                val aipFiles = DataFolder.pickAip(found)
                // 目录里没有原件时才去翻压缩包，避免同一份数据读两遍
                val zips = found.filter { it.role == DataRole.ZIP }
                    .sortedWith(compareBy({ if (DataFolder.zipLooksRelevant(it.name)) 0 else 1 }, { it.path.length }))

                val navSel = FolderSelection(
                    navFiles,
                    if (wantNav && navFiles.isEmpty()) zips else emptyList(),
                )
                val aipSel = FolderSelection(
                    aipFiles,
                    if (aipFiles.isEmpty()) zips else emptyList(),
                )

                if (navSel.files.isEmpty() && navSel.zips.isEmpty() &&
                    aipSel.files.isEmpty() && aipSel.zips.isEmpty()
                ) {
                    importBusy = false
                    importStatus = ""
                    message = if (wantNav) {
                        "这个文件夹里没找到 earth_fix.dat / earth_awy.dat，也没有 AIP 的 CSV。" +
                            "请选导航数据包解压后的那一层（含 Custom Data 或 earth_*.dat 的目录）。"
                    } else {
                        "这个文件夹里没找到 AIP 的 CSV / JSON。CAAC 的表名形如 " +
                            "「International Flights from Mainland of China Nr2611.csv」。"
                    }
                    return@launch
                }

                if (navSel.files.isNotEmpty() || navSel.zips.isNotEmpty()) {
                    // 同一批文件里带了 AIP 表就顺带装上；先记下来，等导航数据回执再送
                    followAip = if (aipSel.files.isNotEmpty() || aipSel.zips.isNotEmpty()) aipSel else null
                    val names = navSel.files.joinToString("、") { it.name }
                    importStatus = if (names.isNotEmpty()) "识别到：$names" else "压缩包里查找导航数据…"
                    val pushed = push(ch, "nav", navSel, accept = { DataFolder.roleOf(it).isNav })
                    if (!pushed) {
                        followAip = null
                        // 这个目录可能只放了 AIP（比如单独一个 2611V2.zip），改走 AIP 再试一次
                        val aipPushed = push(ch, "aip", aipSel, accept = { DataFolder.roleOf(it).isAip })
                        if (!aipPushed) {
                            importBusy = false
                            importStatus = ""
                            message = "这个压缩包里既没有 earth_fix / earth_awy，也没有 AIP 的 CSV。"
                        }
                    }
                } else {
                    importStatus = "识别到 AIP 航路表，正在读取…"
                    val pushed = push(ch, "aip", aipSel, accept = { DataFolder.roleOf(it).isAip })
                    if (!pushed) {
                        importBusy = false
                        importStatus = ""
                        message = "没找到可用的 AIP 航路表（CSV 需含 Fly along 列）。"
                    }
                }
            } catch (t: Throwable) {
                AppLog.e(tag, "文件夹导入失败", t)
                importBusy = false
                importStatus = ""
                followAip = null
                autoRestoreFailed = true
                message = "读取文件夹失败：" + (t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** 导航数据生效后继续送 AIP 表（由 [onDataApplied] 触发）。 */
    private fun pushAip(sel: FolderSelection) {
        val ch = routeChannel ?: return
        viewModelScope.launch {
            importBusy = true
            importStatus = "应用 AIP 航路表…"
            try {
                val pushed = push(ch, "aip", sel, accept = { DataFolder.roleOf(it).isAip })
                if (!pushed) {
                    importBusy = false
                    importStatus = ""
                    message = "这个目录里没有 AIP 的 CSV / JSON。"
                }
            } catch (t: Throwable) {
                AppLog.e(tag, "AIP 跟随导入失败", t)
                importBusy = false
                importStatus = ""
                message = "AIP 航路表导入失败：" + (t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * 把一组文件（或压缩包里的条目）推进网页的导入管线。
     *
     * @return 是否至少送进去一个文件；false 时调用方负责给用户解释
     */
    private suspend fun push(
        ch: JsChannel,
        kind: String,
        sel: FolderSelection,
        accept: (String) -> Boolean,
    ): Boolean {
        ch.eval("__EFB__.importBegin(${JSONObject.quote(kind)})")
        var emitted = 0

        for (f in sel.files) {
            importStatus = "读取 ${f.name}"
            importer.stream(f.uri) { name, chunk ->
                withContext(Dispatchers.Main.immediate) {
                    ch.eval(
                        "__EFB__.importAppend(${JSONObject.quote(name)}, ${JSONObject.quote(chunk)})"
                    )
                }
            }
            ch.eval("__EFB__.importFileEnd()")
            emitted++
        }

        if (emitted == 0) {
            for (z in sel.zips) {
                importStatus = "解压 ${z.name}…"
                val scan = importer.streamZip(z.uri, accept) { name, chunk ->
                    withContext(Dispatchers.Main.immediate) {
                        ch.eval(
                            "__EFB__.importAppend(${JSONObject.quote(name)}, ${JSONObject.quote(chunk)})"
                        )
                    }
                }
                if (scan.names.isNotEmpty()) {
                    AppLog.i(tag, "压缩包 ${z.name} 命中：${scan.names.joinToString()}")
                    emitted += scan.names.size
                    if (scan.truncated) lastZipTruncated = z.name
                    break
                }
            }
        }

        if (emitted == 0) {
            ch.eval("__EFB__.importReset()")
            return false
        }
        importStatus = "解析中…"
        withContext(Dispatchers.Main.immediate) { ch.eval("__EFB__.importCommit()") }
        return true
    }

    /** 由 ToolsBridge.onDataApplied 转回来，已经在主线程。 */
    fun onDataApplied(payload: String) {
        importBusy = false
        importStatus = ""
        val outcome = parseOutcome(payload)
        lastImport = outcome
        if (!outcome.ok) {
            followAip = null
            // 待提交的 Uri 一并丢掉：这份数据没通过页面校验，不能让它顶掉上次能用的记录
            pendingRemember = null
            pendingRememberTree = null
            onImportRejected(outcome)
            return
        }
        routeHasData = true
        autoRestoreFailed = false
        lastGoodDataset = outcome

        // 页面确认可用了，这时候才把「上次选择」记下来
        pendingRemember?.let {
            importer.remember(it)
            rememberedCount = importer.persistedUris().size
            pendingRemember = null
        }
        pendingRememberTree?.let {
            importer.rememberTree(it)
            rememberedCount = 1
            pendingRememberTree = null
        }

        val detail = "机场 ${outcome.airports} · 航点 ${outcome.nodes} · 航段 ${outcome.segments}"
        val wasRecovery = recovering
        recovering = false
        message = when {
            wasRecovery -> "上一次导入的数据已恢复（$detail）"
            outcome.airports == 0 ->
                "已导入但没有机场坐标（$detail）。换一份含 apt.dat / earth_aptmeta.dat 的数据，" +
                    "否则输入 ZBAA 会提示「数据库里没有」。"
            outcome.segments == 0 ->
                "已导入但没有航段（$detail）。检查是否漏了 earth_awy.dat。"
            else -> "已导入：$detail"
        }

        // 「选文件夹」时导航数据先落地，AIP 表必须等它生效之后再送
        val follow = followAip
        if (follow != null) {
            followAip = null
            pushAip(follow)
            return
        }
        refreshPlans()
    }

    /**
     * 页面判定数据不可用。
     *
     * 关键是不能「导一份坏数据，把正在用的好数据也丢了」。网页侧 `UI.applyData` 是
     * **先校验再提交**（`DATASET = ds` 在全部校验之后），所以正常情况下失败不会动旧数据；
     * 但页面也可能自己退回内置数据、或换成了半成品，这时候旧数据就真的没了。
     *
     * 怎么区分？回执里带的是 `datasetInfo()`，也就是**当前**数据集规模。拿它和上一次
     * 成功生效的规模比：一致说明页面没被改动，不要去碰它；不一致才用本机缓存把
     * 上一次成功导入的那份放回去。
     *
     * 为什么不无条件重放缓存：重放会走 `applyData` 里的 `resetSelection()`，
     * 把用户已经生成好的航路和选好的机场一起清掉 —— 数据没丢的情况下这是纯粹的破坏。
     */
    private fun onImportRejected(outcome: ImportOutcome) {
        val failure = describeFailure(outcome)
        val prev = lastGoodDataset
        val pageIntact = prev != null &&
            prev.nodes == outcome.nodes &&
            prev.airports == outcome.airports &&
            prev.segments == outcome.segments
        val canRecover = !pageIntact && cache.exists()

        autoRestoreFailed = !canRecover
        message = when {
            pageIntact -> "数据未生效：$failure。当前页面里的数据未受影响"
            canRecover -> "数据未生效：$failure。正在恢复上一次导入的数据…"
            else -> "数据未生效：$failure"
        }
        AppLog.w(
            tag,
            "页面拒绝这份数据：$failure；页面数据未变=$pageIntact，可恢复=$canRecover",
        )
        if (canRecover) restoreFromCache(recovery = true)
    }

    /** 失败原因拼上能定位到的线索：压缩包截断是最容易被误判成「数据坏了」的一种。 */
    private fun describeFailure(outcome: ImportOutcome): String {
        val base = outcome.message.ifBlank { "内容为空或格式不符" }
        val zip = lastZipTruncated
        return if (zip == null) {
            base
        } else {
            "$base（压缩包 $zip 超过体积上限被截断，请解压后改用「选择数据文件夹」）"
        }
    }

    private fun parseOutcome(payload: String): ImportOutcome {
        return runCatching {
            val o = JSONObject(payload)
            val info = o.optJSONObject("info") ?: JSONObject()
            ImportOutcome(
                ok = o.optBoolean("ok", false),
                message = o.optString("message", ""),
                airports = info.optInt("airports", 0),
                nodes = info.optInt("nodes", 0),
                segments = info.optInt("segments", 0),
                airways = info.optInt("airways", 0),
            )
        }.getOrElse {
            ImportOutcome(false, payload.take(200), 0, 0, 0, 0)
        }
    }

    private companion object {
        const val KIND_NAV = "nav"
        const val KIND_AIP = "aip"
        const val KIND_PACK = "pack"

        /** 与 shim 里 PACK_CHUNK 保持一致：180K 字符，单次桥调用远低于 1 MB。 */
        const val CACHE_CHUNK = 180_000

        /** 切到油量页后允许补填的时间窗：覆盖「方案渲染 → 400ms 防抖 → 读 DOM」这一小段。 */
        const val FUEL_HEAL_MS = 5_000L
    }

    /** 只清 App 记下的 Uri 列表，不动用户文件。 */
    fun forgetImported() {
        importer.clear()
        cache.clear()
        rememberedCount = 0
        cacheSize = 0
        message = "已清空导入记录与本机缓存"
    }

    // ---------------------------------------------------------------- 方案

    /**
     * 页面结果区变了（生成完方案、展开卡片）。由 shim 的 MutationObserver 触发，
     * 已经是防抖过的，这里直接读一次 DOM。
     */
    fun onPlansChanged() {
        refreshPlans()
    }

    /** 读一次 DOM 里的方案卡片。生成/展开/复制都不会触发写盘。 */
    fun refreshPlans() {
        val ch = routeChannel ?: run { pendingFuelFill = false; return }
        if (plansBusy) {
            // 正在读的时候页面又变了：记一笔，读完再补一次，
            // 否则会漏掉「读完的瞬间正好生成完」的那一批方案
            plansDirty = true
            return
        }
        plansBusy = true
        ch.eval("__EFB__.getPlansJson()") { raw ->
            plansBusy = false
            val json = decodeJsString(raw)
            if (json.isBlank()) {
                pendingFuelFill = false
                if (plansDirty) { plansDirty = false; refreshPlans() }
                return@eval
            }
            val next = runCatching {
                val o = JSONObject(json)
                val arr = o.optJSONArray("plans")
                val plans = ArrayList<PlannedRoute>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val p = arr.getJSONObject(i)
                        plans += PlannedRoute(
                            key = p.optString("key", ""),
                            kind = p.optString("kind", "engine"),
                            distNM = p.optInt("distNM", 0),
                            route = p.optString("route", ""),
                        )
                    }
                }
                PlanSnapshot(
                    from = o.optString("from", ""),
                    to = o.optString("to", ""),
                    plans = plans,
                )
            }.getOrNull()
            // 内容一样就别写 state：每 400ms 一次的 DOM 变动会把整页重组一遍
            if (next != null && next != snapshot) snapshot = next
            // 该填油量页的两个时机：
            //  1) 刚切过去（pendingFuelFill）—— 读完最新 DOM 再填；
            //  2) 人已经在油量页、还没填成功过，且仍在切页后的窗口内 ——
            //     说明切页时方案还没渲染完，这一批到了补填。填过就不再动，免得覆盖手输值。
            val heal = page == ToolsPage.Fuel &&
                fuelFilledNM == null &&
                System.currentTimeMillis() < fuelFillWindowUntil
            if (pendingFuelFill || heal) {
                pendingFuelFill = false
                fillFuelDistance()
            }
            if (plansDirty) {
                plansDirty = false
                refreshPlans()
            }
        }
    }

    // ---------------------------------------------------------------- 已存航路

    private fun reloadSaved() {
        saved = routeStore.list()
        activeId = routeStore.activeId()
    }

    fun savePlan(plan: PlannedRoute, name: String) {
        val snap = snapshot ?: run { message = "先点「读取方案」拿到当前结果"; return }
        if (snap.from.isBlank() || snap.to.isBlank()) {
            message = "当前页面没有有效的起降机场"
            return
        }
        val label = name.trim().ifBlank { "${snap.from}-${snap.to}" }
        val record = routeStore.add(label, snap.from, snap.to, plan)
        reloadSaved()
        message = "已保存「${record.name}」"
    }

    fun renameSaved(id: String, name: String) {
        routeStore.rename(id, name)
        reloadSaved()
    }

    fun deleteSaved(id: String) {
        routeStore.delete(id)
        reloadSaved()
        if (fuelFilledNM != null) fillFuelDistance()
    }

    fun setActive(id: String?) {
        routeStore.setActive(id)
        activeId = id
        if (fuelFilledNM != null) fillFuelDistance()
    }

    fun activeRoute(): SavedRoute? = activeId?.let { id -> saved.firstOrNull { it.id == id } }

    /** 当前航路距离：优先「设为当前」的那条，其次当前页面最优方案。 */
    fun currentDistNM(): Int? {
        activeRoute()?.let { return it.distNM }
        val plans = snapshot?.plans.orEmpty().filter { it.distNM > 0 }
        return plans.minByOrNull { it.distNM }?.distNM
    }

    /**
     * 当前航程的来源。
     *
     * 为什么要在界面上写出来：`currentDistNM()` 优先取「设为当前」的航路库记录，
     * 其次才是刚生成的方案。用户生成完新航路、却发现油量页还是旧数字时，
     * 十有八九是航路库里有一条「当前」记录压着 —— 不标来源就永远看不出这件事。
     */
    fun currentDistSource(): String? = when {
        activeRoute() != null -> "航路库 · " + (activeRoute()?.name ?: "")
        snapshot?.plans?.any { it.distNM > 0 } == true -> "当前方案"
        else -> null
    }

    // ---------------------------------------------------------------- 油量联动

    /**
     * 把当前航路距离注入油量页的 #dist。
     * 注入脚本来自 efb_patches.json 的 runtime.distanceJs，Kotlin 里不写 JS 字面量。
     */
    fun fillFuelDistance() {
        val ch = fuelChannel
        if (ch == null || !fuelReady) return
        val nm = currentDistNM()
        if (nm == null) {
            fuelFilledNM = null
            return
        }
        val config = runCatching { ToolContract.loadConfig(getApplication()) }.getOrNull() ?: return
        val spec = config.tools[ToolContract.FUEL_ASSET] ?: return
        if (spec.distanceJs.isBlank()) return
        val js = spec.distanceJs.replace("__NM__", nm.toString())
        ch.eval(js) { result ->
            // distanceJs 现在会把「#dist 里是不是真的写进去了」一起返回，
            // 只报 true 不代表页面收下了（比如输入框还没建好）
            if (result == "true") {
                fuelFilledNM = nm
                fuelRetried = false
                return@eval
            }
            AppLog.w(tag, "距离注入未生效 result=$result js=$js")
            if (!fuelRetried) {
                // 刚切到油量页时 WebView 可能还在初始化，稍等再试一次
                fuelRetried = true
                viewModelScope.launch {
                    delay(300)
                    fillFuelDistance()
                }
            } else {
                fuelRetried = false
                fuelFilledNM = null
                message = "油量页没接收航程（$nm NM），点右下角「重新填入」再试一次"
            }
        }
    }

    /**
     * 底部「重新填入」按钮：有航程就重填一次，没有就说清楚为什么。
     *
     * 按钮不再按「有没有航程」置灰 —— 灰按钮点不动、也不解释，
     * 用户只会以为联动坏了。
     */
    fun refillFuelDistance() {
        if (currentDistNM() == null) {
            message = "还没有可用的航程：先在航路页生成一次，或在航路库里「设为当前」"
            return
        }
        fuelRetried = false
        fillFuelDistance()
    }

    // ---------------------------------------------------------------- 导出

    /** 供界面上的「导出」按钮使用：把 JSON 交给系统写盘（由 ToolsBridge 落文件）。 */
    fun exportJson(filename: String, json: String) {
        routeChannel?.eval(
            "__EFB__.exportJson(${JSONObject.quote(filename)}, ${JSONObject.quote(json)})"
        )
    }

    // ---------------------------------------------------------------- 工具

    /** evaluateJavascript 的返回是「JSON 编码过的 JS 值」，字符串要再解一层。 */
    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val v = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        return when (v) {
            is String -> v
            null -> ""
            else -> v.toString()
        }
    }

    override fun onCleared() {
        super.onCleared()
        routeChannel = null
        fuelChannel = null
    }
}
