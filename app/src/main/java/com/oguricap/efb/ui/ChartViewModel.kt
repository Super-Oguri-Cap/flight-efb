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
import com.oguricap.efb.data.SpecExporter
import com.oguricap.efb.perf.Perf
import com.oguricap.efb.prefs.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ChartViewModel(
    app: Application,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(app) {

    private val settings = Settings(app)

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
            val (airportList, chartList) = withContext(io) {
                Perf.timed("search_query") {
                    idx.searchAirports(text) to idx.searchCharts(text)
                }
            }
            airports = airportList
            chartHits = chartList
        }
    }

    fun refreshAirports() {
        viewModelScope.launch {
            val idx = index ?: return@launch
            airports = withContext(io) { Perf.timed("query_airports") { idx.airports() } }
        }
    }

    fun openAirport(icao: String) {
        currentAirport = null
        currentCharts = emptyList()
        expandedTypes = emptySet()
        viewModelScope.launch {
            val idx = index ?: return@launch
            val (airport, charts) = withContext(io) {
                idx.airport(icao) to Perf.timed("query_icao") { idx.charts(icao) }
            }
            currentAirport = airport
            currentCharts = charts
        }
    }

    fun toggleType(type: String) {
        expandedTypes = if (type in expandedTypes) expandedTypes - type else expandedTypes + type
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