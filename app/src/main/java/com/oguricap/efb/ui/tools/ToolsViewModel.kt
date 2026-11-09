package com.oguricap.efb.ui.tools

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
    var rememberedCount by mutableStateOf(importer.persistedUris().size)
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
                    Log.i(tag, "route bridge=$v")
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
        Log.e(tag, "网页资源加载失败：$reason")
        loadError = "网页资源不完整，请检查安装包"
    }

    fun selectPage(target: ToolsPage) {
        if (page == target) return
        page = target
        if (target == ToolsPage.Fuel) fillFuelDistance()
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
            Log.i(tag, "发现本机缓存 ${cache.sizeBytes() / 1024} KB，直接恢复")
            restoreFromCache()
            return
        }
        // 缓存没写成（比如上次被系统杀掉）时，退回「按记住的 Uri 重新解析」
        val uris = importer.persistedUris()
        if (uris.isEmpty()) return
        Log.i(tag, "没有缓存，按记住的 ${uris.size} 个 Uri 重新导入")
        importNav(uris)
    }

    /**
     * 用本机缓存恢复数据集。
     *
     * 走的是和数据包导入完全相同的分块通道（kind = restore），只是来源换成私有目录里的
     * JSON —— 网页侧拿到的东西和解码逻辑都没变，所以不需要额外维护一套恢复代码。
     */
    private fun restoreFromCache() {
        val ch = routeChannel ?: return
        viewModelScope.launch {
            importBusy = true
            importStatus = "恢复上次导入的导航数据…"
            try {
                val text = withContext(Dispatchers.IO) { cache.read() }
                if (text.isNullOrBlank()) {
                    importBusy = false
                    importStatus = ""
                    autoRestoreFailed = true
                    message = "本机缓存读不出来，请重新导入一次"
                    return@launch
                }
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
                Log.e(tag, "缓存恢复失败", t)
                importBusy = false
                importStatus = ""
                autoRestoreFailed = true
                message = "上次的数据恢复失败，请重新导入一次"
            }
        }
    }

    /** 网页把编码好的数据集分块传回来了（导入成功后自动发生）。 */
    fun onPackSerialized(json: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { cache.write(json) }
            cacheSize = cache.sizeBytes()
            Log.i(tag, "数据集已缓存：${json.length} 字符 / ${cacheSize / 1024} KB")
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
            var total = 0L
            try {
                // 只把导航数据的 Uri 记成「上次选择」；AIP 表和数据包是一次性动作，
                // 自动恢复时重放它们没有意义
                if (kind == KIND_NAV) {
                    importer.remember(uris)
                    rememberedCount = importer.persistedUris().size
                }
                ch.eval("__EFB__.importBegin(${JSONObject.quote(kind)})")
                for ((i, uri) in uris.withIndex()) {
                    val name = importer.displayName(uri)
                    importStatus = "读取 ${i + 1}/${uris.size} · $name"
                    total += importer.stream(uri) { chunkName, chunk ->
                        withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                            ch.eval(
                                "__EFB__.importAppend(${JSONObject.quote(chunkName)}, ${JSONObject.quote(chunk)})"
                            )
                        }
                    }
                    ch.eval("__EFB__.importFileEnd()")
                }
                importStatus = "解析中 · ${total / 1024} K 字符…"
                withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    ch.eval("__EFB__.importCommit()")
                }
                // 剩余流程由网页回调 onDataApplied 推进，这里不 await
            } catch (t: Throwable) {
                Log.e(tag, "导入失败", t)
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

    /** 由 ToolsBridge.onDataApplied 转回来，已经在主线程。 */
    fun onDataApplied(payload: String) {
        importBusy = false
        importStatus = ""
        val outcome = parseOutcome(payload)
        lastImport = outcome
        if (!outcome.ok) {
            autoRestoreFailed = true
            message = "数据未生效：" + outcome.message.ifBlank { "内容为空或格式不符" }
            return
        }
        routeHasData = true
        autoRestoreFailed = false
        val detail = "机场 ${outcome.airports} · 航点 ${outcome.nodes} · 航段 ${outcome.segments}"
        message = if (outcome.airports == 0) {
            "已导入，但没有机场坐标（$detail）。再选一次 apt.dat 或 earth_aptmeta.dat 补坐标。"
        } else {
            "已导入：$detail"
        }
        refreshPlans()
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

        /** 与 shim 里 PACK_CHUNK 保持一致：180K 字符，单次桥调用远低于 1 MB。 */
        const val CACHE_CHUNK = 180_000
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
        val ch = routeChannel ?: return
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

    // ---------------------------------------------------------------- 油量联动

    /**
     * 把当前航路距离注入油量页的 #dist。
     * 注入脚本来自 efb_patches.json 的 runtime.distanceJs，Kotlin 里不写 JS 字面量。
     */
    fun fillFuelDistance() {
        val ch = fuelChannel
        val nm = currentDistNM()
        if (ch == null || !fuelReady) return
        if (nm == null) {
            fuelFilledNM = null
            return
        }
        val config = runCatching { ToolContract.loadConfig(getApplication()) }.getOrNull() ?: return
        val spec = config.tools[ToolContract.FUEL_ASSET] ?: return
        if (spec.distanceJs.isBlank()) return
        val js = spec.distanceJs.replace("__NM__", nm.toString())
        ch.eval(js) { result ->
            if (result == "true") {
                fuelFilledNM = nm
            } else {
                Log.w(tag, "距离注入未生效 result=$result js=$js")
                fuelFilledNM = null
            }
        }
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
