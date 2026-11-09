package com.oguricap.efb.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oguricap.efb.data.Airport
import com.oguricap.efb.data.Chart
import com.oguricap.efb.data.ChartIndex
import com.oguricap.efb.data.ChartScanner
import com.oguricap.efb.data.IndexBuilder
import com.oguricap.efb.data.PinKind
import com.oguricap.efb.data.PinStore
import com.oguricap.efb.data.SpecExporter
import com.oguricap.efb.diag.AppLog
import com.oguricap.efb.perf.Perf
import com.oguricap.efb.prefs.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ViewModel 默认工厂用反射找 (Application) 单参构造，
// 多一个带默认值的构造参数就不会生成这个签名，
// 启动时 viewModel() 直接抛 NoSuchMethodException，进界面即闪退。
// 测试注入调度器改走内部可变属性。
class ChartViewModel(app: Application) : AndroidViewModel(app) {

    private val tag = "EFB/CHART"
    private val settings = Settings(app)

    // 选中航图的落盘位置：离场区 / 进场区
    private val pinStore = PinStore(app)

    @JvmSynthetic
    internal var io: CoroutineDispatcher = Dispatchers.IO

    var index: ChartIndex? = null
        private set

    var loadError: String? by mutableStateOf(null)
        private set

    var rootError: String? by mutableStateOf(null)
        private set

    var query: String by mutableStateOf(settings.lastQuery)
        private set

    var airports: List<Airport> by mutableStateOf(emptyList())
        private set

    var chartHits: List<Chart> by mutableStateOf(emptyList())
        private set

    var currentAirport: Airport? by mutableStateOf(null)
        private set

    var currentCharts: List<Chart> by mutableStateOf(emptyList())
        private set

    var expandedTypes: Set<String> by mutableStateOf(emptySet())
        private set

    // 选中后置顶的两组图，进程重建后从磁盘恢复
    var departures: List<Chart> by mutableStateOf(pinStore.load(PinKind.DEPARTURE))
        private set

    var arrivals: List<Chart> by mutableStateOf(pinStore.load(PinKind.ARRIVAL))
        private set

    var chartRoot: String? by mutableStateOf(settings.chartRoot)
        private set

    var invertReader: Boolean by mutableStateOf(settings.invertInReader)
        private set

    var onboardingDone: Boolean by mutableStateOf(settings.onboardingDone)
        private set

    var scanning: Boolean by mutableStateOf(false)
        private set

    var scanProgress: Int by mutableStateOf(0)
        private set

    var lastScan: ChartScanner.Result? by mutableStateOf(null)
        private set

    var specExportPath: String? by mutableStateOf(null)
        private set

    var specExportError: String? by mutableStateOf(null)
        private set

    val indexOrigin: String get() = settings.indexOrigin

    fun toggleInvert() {
        invertReader = !invertReader
        settings.invertInReader = invertReader
    }

    fun applyInvert(value: Boolean) {
        invertReader = value
        settings.invertInReader = value
    }

    fun load() {
        viewModelScope.launch {
            val result = withContext(io) {
                runCatching {
                    val app = getApplication<Application>()
                    val scanned = File(File(app.filesDir, "index"), IndexBuilder.SCAN_DB_NAME)
                    // 已经在本机扫描过就用扫描结果，否则用 APK 预置的索引
                    if (settings.indexOrigin == Settings.ORIGIN_SCAN && scanned.isFile) {
                        ChartIndex.open(scanned)
                    } else {
                        ChartIndex.open(IndexBuilder.installAsset(app))
                    }
                }
            }
            result.onSuccess {
                index = it
                loadError = null
                // 搜索框可能带着上次的输入，列表必须与它一致，
                // 否则重启后会出现「框里有 ZBAA、列表却是全部机场」
                if (query.isBlank()) refreshAirports() else applyQuery(query)
            }.onFailure {
                loadError = it.message ?: it.toString()
            }
        }
    }

    private var searchJob: Job? = null

    fun onQueryChange(text: String) {
        query = text
        settings.lastQuery = text
        applyQuery(text)
    }

    // 连续输入时取消上一次查询，避免先发的慢查询后返回并覆盖新结果
    private fun applyQuery(text: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val idx = index ?: return@launch
            // runCatching 只包住真正的查询：如果包住整个协程体，
            // 上面 cancel() 抛出的 CancellationException 会被当成查询失败写进 loadError
            val result = runCatching {
                withContext(io) {
                    Perf.timed("search_query") {
                        idx.searchAirports(text) to idx.searchCharts(text)
                    }
                }
            }
            val error = result.exceptionOrNull()
            if (error is CancellationException) return@launch
            result.onSuccess { (airportList, chartList) ->
                airports = airportList
                // 搜的是机场（四字码 / 中文机场名）就只给「机场大类」这一个入口，
                // 不再把具体航图平铺出来：点进机场页按图型挑才是正确的路径。
                // 只有查询词完全没命中机场时（如 ILS、RWY36L），图名匹配才有意义。
                chartHits = if (airportList.isNotEmpty()) emptyList() else chartList
            }.onFailure {
                // 索引文件被换掉/删掉后查询会抛 IllegalStateException。
                // 不接住的话这就是一次主线程未捕获异常 —— 输一个字就闪退。
                AppLog.e(tag, "搜索失败：$text", it)
                loadError = "索引读取失败：" + (it.message ?: it.javaClass.simpleName)
            }
        }
    }

    fun refreshAirports() {
        viewModelScope.launch {
            val idx = index ?: return@launch
            val result = runCatching {
                withContext(io) { Perf.timed("query_airports") { idx.airports() } }
            }
            val error = result.exceptionOrNull()
            if (error is CancellationException) return@launch
            result.onSuccess { airports = it }
                .onFailure {
                    AppLog.e(tag, "读取机场列表失败", it)
                    loadError = "索引读取失败：" + (it.message ?: it.javaClass.simpleName)
                }
        }
    }

    // 每个机场的列表滚动位置：从阅读页退回机场页时要回到原来那一屏
    private val airportScroll = mutableMapOf<String, Pair<Int, Int>>()

    fun savedScroll(icao: String): Pair<Int, Int>? = airportScroll[icao]

    // 搜索页的滚动位置，逻辑同上
    private var searchScroll: Pair<Int, Int>? = null

    fun savedSearchScroll(): Pair<Int, Int>? = searchScroll

    fun rememberSearchScroll(index: Int, offset: Int) {
        searchScroll = index to offset
    }

    fun rememberScroll(icao: String, index: Int, offset: Int) {
        airportScroll[icao] = index to offset
    }

    // 按文件路径找回单张图：阅读页重建、搜索命中直接打开时用
    fun chartByPath(path: String): Chart? {
        val idx = index ?: return null
        return chartByPathCache.get(path) ?: runCatching { idx.chartByFile(path) }
            .getOrNull()
            ?.also { chartByPathCache.put(path, it) }
    }

    private val chartByPathCache = object : androidx.collection.LruCache<String, Chart>(64) {}

    fun openAirport(icao: String) {
        currentAirport = null
        currentCharts = emptyList()
        expandedTypes = emptySet()
        viewModelScope.launch {
            val idx = index ?: return@launch
            val result = runCatching {
                withContext(io) {
                    idx.airport(icao) to Perf.timed("query_icao") { idx.charts(icao) }
                }
            }
            val error = result.exceptionOrNull()
            if (error is CancellationException) return@launch
            result.onSuccess { (airport, charts) ->
                currentAirport = airport
                currentCharts = charts
            }.onFailure {
                AppLog.e(tag, "打开机场失败：$icao", it)
                loadError = "读取 $icao 的航图失败：" + (it.message ?: it.javaClass.simpleName)
            }
        }
    }

    fun toggleType(type: String) {
        expandedTypes = if (type in expandedTypes) expandedTypes - type else expandedTypes + type
    }

    // ---- 选中航图（离场区 / 进场区） ----

    fun pinned(kind: PinKind): List<Chart> =
        if (kind == PinKind.DEPARTURE) departures else arrivals

    fun pinKindOf(chart: Chart): PinKind? = when {
        departures.any { it.file == chart.file } -> PinKind.DEPARTURE
        arrivals.any { it.file == chart.file } -> PinKind.ARRIVAL
        else -> null
    }

    // 把选中的图放进某一组。同一张图只能待在一个组里：
    // 先整体从另一组剔掉，再加入本组，避免两边各出现一次。
    fun pinCharts(charts: List<Chart>, kind: PinKind) {
        if (charts.isEmpty()) return
        val files = charts.map { it.file }.toSet()
        val keepDeparture = departures.filterNot { it.file in files }
        val keepArrival = arrivals.filterNot { it.file in files }
        val target = (if (kind == PinKind.DEPARTURE) keepDeparture else keepArrival).toMutableList()
        charts.forEach { chart ->
            if (target.none { it.file == chart.file }) target.add(chart)
        }
        val nextDeparture = if (kind == PinKind.DEPARTURE) target else keepDeparture
        val nextArrival = if (kind == PinKind.DEPARTURE) keepArrival else target
        departures = nextDeparture
        arrivals = nextArrival
        pinStore.save(PinKind.DEPARTURE, nextDeparture)
        pinStore.save(PinKind.ARRIVAL, nextArrival)
    }

    // 从分组里移出（两个组一起扫，调用方不用关心这张图在哪儿）
    fun unpinCharts(charts: List<Chart>) {
        if (charts.isEmpty()) return
        val files = charts.map { it.file }.toSet()
        val nextDeparture = departures.filterNot { it.file in files }
        val nextArrival = arrivals.filterNot { it.file in files }
        if (nextDeparture == departures && nextArrival == arrivals) return
        departures = nextDeparture
        arrivals = nextArrival
        pinStore.save(PinKind.DEPARTURE, nextDeparture)
        pinStore.save(PinKind.ARRIVAL, nextArrival)
    }

    fun absoluteFile(chart: Chart): File? =
        chartRoot?.let { File(it, chart.file) }

    fun clearRootError() {
        rootError = null
    }

    // 扫描目录生成索引，成功后替换内存中的索引并记录根目录
    fun scanInto(targetPath: String) {
        val root = File(targetPath.trimEnd('/'))
        if (!root.isDirectory) {
            rootError = "目录不存在或不可读：" + root.absolutePath
            return
        }
        scanning = true
        scanProgress = 0
        rootError = null
        viewModelScope.launch {
            val result = withContext(io) {
                runCatching {
                    Perf.timed("scan_directory") {
                        val scanned = ChartScanner.scan(root) { count -> scanProgress = count }
                        val dbFile = IndexBuilder.writeToPrivate(getApplication(), scanned)
                        scanned to dbFile
                    }
                }
            }
            scanning = false
            result.onSuccess { pair ->
                val scanned = pair.first
                lastScan = scanned
                if (scanned.isEmpty) {
                    rootError = "这个目录下没有找到符合命名规则的航图。" +
                        "请确认选中的是 AIP航图库 那一层（里面应当有 ZBAA_北京_首都 这样的目录）。"
                    return@onSuccess
                }
                // 先把新库打开拿到引用，成功之后再关旧的并落盘设置。
                // 反过来做的话，新库打不开时 index 会指向已关闭的数据库，
                // 之后每次查询都抛 IllegalStateException。
                val opened = runCatching { ChartIndex.open(pair.second) }
                opened.onFailure {
                    rootError = "索引已生成但无法打开：" + (it.message ?: it.toString())
                    return@onSuccess
                }
                val previous = index
                index = opened.getOrThrow()
                previous?.close()
                settings.chartRoot = root.absolutePath
                settings.indexOrigin = Settings.ORIGIN_SCAN
                chartRoot = root.absolutePath
                refreshAirports()
            }.onFailure {
                rootError = it.message ?: it.toString()
            }
        }
    }

    fun exportSpec() {
        viewModelScope.launch {
            val result = withContext(io) {
                runCatching { SpecExporter.export(getApplication()) }
            }
            result.onSuccess {
                specExportPath = it.absolutePath
                specExportError = null
            }.onFailure {
                specExportPath = null
                specExportError = it.message ?: it.toString()
            }
        }
    }

    fun completeOnboarding(): Boolean {
        val result = lastScan
        if (result == null || result.isEmpty) return false
        // 引导完成前，扫描产生的索引文件名与预置索引不同，这里把来源记下
        settings.indexOrigin = Settings.ORIGIN_SCAN
        onboardingDone = true
        settings.onboardingDone = true
        return true
    }

    // reset 之前的完成状态，用来区分「首次安装」与「主动重新运行」
    var hasCompletedOnboardingBefore: Boolean by mutableStateOf(settings.onboardingDone)
        private set

    fun resetOnboarding() {
        hasCompletedOnboardingBefore = onboardingDone
        onboardingDone = false
        settings.onboardingDone = false
        lastScan = null
        scanProgress = 0
    }
}