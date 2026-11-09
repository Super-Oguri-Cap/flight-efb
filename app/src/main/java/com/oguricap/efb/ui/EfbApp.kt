package com.oguricap.efb.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oguricap.efb.data.Chart
import com.oguricap.efb.ui.charts.AirportScreen
import com.oguricap.efb.ui.checklist.ChecklistDetailScreen
import com.oguricap.efb.ui.checklist.ChecklistListScreen
import com.oguricap.efb.ui.checklist.CustomChecklistEditorScreen
import com.oguricap.efb.ui.checklist.ReferenceDocScreen
import com.oguricap.efb.ui.reader.ReaderScreen
import com.oguricap.efb.ui.search.SearchScreen
import com.oguricap.efb.ui.settings.SettingsScreen
import com.oguricap.efb.ui.tools.ToolsScreen

// 顶层三个标签，每个标签维护自己的页面栈。
// 页面栈全部用字符串保存（rememberSaveable），Activity 重建后能回到原来的位置。
sealed interface Tab {
    data object Charts : Tab
    data object Checklists : Tab
    data object Tools : Tab

    companion object {
        fun fromKey(key: String): Tab = when (key) {
            "checklists" -> Checklists
            "tools" -> Tools
            else -> Charts
        }

        fun key(tab: Tab): String = when (tab) {
            is Checklists -> "checklists"
            is Tools -> "tools"
            else -> "charts"
        }
    }
}

@Composable
fun EfbApp(
    vm: ChartViewModel,
    ready: Boolean,
    storageGranted: Boolean,
    onRequestStorage: () -> Unit,
    onPickRoot: (onPath: (String) -> Unit) -> Unit,
    onRestartOnboarding: () -> Unit,
) {
    // 标签
    var tabKey by rememberSaveable { mutableStateOf(Tab.key(Tab.Charts)) }
    val tab = Tab.fromKey(tabKey)

    // 航图栈：search / airport / reader / settings。
    // 机场页的参数（四字码）与阅读页的参数（文件路径）必须分开保存：
    // 共用一个变量的话，从阅读页返回机场页时变量里仍是文件路径，
    // 机场页会拿它当四字码查询，得到「0 张图」的死页面。
    var chartPage by rememberSaveable { mutableStateOf(PAGE_SEARCH) }
    var chartIcao by rememberSaveable { mutableStateOf("") }
    var chartFile by rememberSaveable { mutableStateOf("") }
    // 阅读页是从哪儿打开的：搜索页（图名命中）还是机场页。
    // 返回键必须回到来路，否则用户从搜索结果打开的图，退回机场页是另一个上下文。
    var readerFromSearch by rememberSaveable { mutableStateOf(false) }

    // 检查单栈：列表 / 机型详情 / 随附 PDF 手册
    var checklistPage by rememberSaveable { mutableStateOf(PAGE_LIST) }
    var checklistArg by rememberSaveable { mutableStateOf("") }
    var checklistDoc by rememberSaveable { mutableStateOf("") }

    // 阅读页全屏：顶栏由阅读页自己隐藏，这里只负责把底部标签栏一起收掉
    var immersive by rememberSaveable { mutableStateOf(false) }

    val referenceVm: ReferenceViewModel = viewModel()

    // 重建后当前机场的图还没加载，补一次
    LaunchedEffect(chartPage, chartIcao) {
        if (chartPage == PAGE_AIRPORT && chartIcao.isNotEmpty() && vm.currentAirport?.icao != chartIcao) {
            vm.openAirport(chartIcao)
        }
    }

    // 阅读页只保存文件路径，重建后按路径从当前机场的图里找回 Chart；
    // 从搜索结果直接打开时机场图没在手边，退回索引全局查询
    val readerChart: Chart? = if (chartPage == PAGE_READER && chartFile.isNotEmpty()) {
        vm.currentCharts.firstOrNull { it.file == chartFile }
            ?: vm.chartByPath(chartFile)
    } else {
        null
    }

    fun chartBack() {
        // 返回时一定要退全屏，否则标签栏会一直藏着
        immersive = false
        when (chartPage) {
            PAGE_READER -> {
                chartPage = if (readerFromSearch) PAGE_SEARCH else PAGE_AIRPORT
            }
            else -> chartPage = PAGE_SEARCH
        }
    }

    fun checklistBack() {
        immersive = false
        // 从编辑器返回要回到副本详情，而不是直接跳回列表 —— 用户刚改完还想看勾选效果
        checklistPage = if (checklistPage == PAGE_EDIT) PAGE_DETAIL else PAGE_LIST
    }

    val canGoBack = when (tab) {
        is Tab.Charts -> chartPage != PAGE_SEARCH
        is Tab.Checklists -> checklistPage != PAGE_LIST
        // 工具页没有内部页面栈，返回键交给系统（退出 App）
        is Tab.Tools -> false
    }

    BackHandler(enabled = canGoBack) {
        when (tab) {
            is Tab.Charts -> chartBack()
            is Tab.Checklists -> checklistBack()
            is Tab.Tools -> {}
        }
    }

    Scaffold(
        bottomBar = {
            if (!immersive) NavigationBar {
                NavigationBarItem(
                    selected = tab is Tab.Charts,
                    onClick = { tabKey = Tab.key(Tab.Charts) },
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text("航图") },
                )
                NavigationBarItem(
                    selected = tab is Tab.Checklists,
                    onClick = { tabKey = Tab.key(Tab.Checklists) },
                    icon = { Icon(Icons.Filled.Menu, contentDescription = null) },
                    label = { Text("检查单") },
                )
                NavigationBarItem(
                    selected = tab is Tab.Tools,
                    onClick = { tabKey = Tab.key(Tab.Tools) },
                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                    label = { Text("航路/油量") },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                is Tab.Charts -> when (chartPage) {
                    PAGE_SEARCH -> SearchScreen(
                        vm = vm,
                        ready = ready,
                        storageGranted = storageGranted,
                        onRequestStorage = onRequestStorage,
                        onOpenAirport = { icao ->
                            vm.openAirport(icao)
                            chartIcao = icao
                            chartPage = PAGE_AIRPORT
                        },
                        onOpenChart = { chart ->
                            chartFile = chart.file
                            readerFromSearch = true
                            chartPage = PAGE_READER
                        },
                        onOpenSettings = { chartPage = PAGE_SETTINGS },
                    )

                    PAGE_AIRPORT -> AirportScreen(
                        vm = vm,
                        icao = chartIcao,
                        onBack = { chartBack() },
                        onOpenChart = { chart ->
                            chartFile = chart.file
                            readerFromSearch = false
                            chartPage = PAGE_READER
                        },
                    )

                    PAGE_READER -> {
                        if (readerChart != null) {
                            ReaderScreen(
                                vm = vm,
                                chart = readerChart,
                                onBack = { chartBack() },
                                onFullscreenChange = { immersive = it },
                            )
                        } else {
                            // 重建后当前机场的图还没取回，或索引已变；
                            // 退回机场页而不是显示空白阅读页
                            LaunchedEffect(Unit) { chartPage = PAGE_SEARCH }
                        }
                    }

                    PAGE_SETTINGS -> SettingsScreen(
                        vm = vm,
                        exclusivePhases = referenceVm.exclusivePhases,
                        onToggleExclusive = { referenceVm.applyExclusivePhases(it) },
                        storageGranted = storageGranted,
                        onOpenSystemSettings = onRequestStorage,
                        onRerunOnboarding = onRestartOnboarding,
                        onBack = { chartBack() },
                    )
                }

                is Tab.Checklists -> when (checklistPage) {
                    PAGE_LIST -> ChecklistListScreen(
                        vm = referenceVm,
                        onOpenAircraft = { id ->
                            checklistArg = id
                            checklistPage = PAGE_DETAIL
                        },
                        onOpenDoc = { id ->
                            checklistDoc = id
                            checklistPage = PAGE_DOC
                        },
                        onDeleteCustom = { id -> referenceVm.deleteCustom(id) },
                    )

                    PAGE_DOC -> {
                        val doc = referenceVm.doc(checklistDoc)
                        if (doc != null) {
                            ReferenceDocScreen(
                                doc = doc,
                                // 反色开关与航图阅读页共用一份设置，切过去不用重新设
                                invert = vm.invertReader,
                                onToggleInvert = { vm.toggleInvert() },
                                onBack = { checklistBack() },
                                onFullscreenChange = { immersive = it },
                            )
                        } else {
                            // 手册被删掉或 id 对不上时回列表，不要留空白页
                            LaunchedEffect(Unit) { checklistPage = PAGE_LIST }
                        }
                    }

                    // 编辑器必须排在 else 之前：else 是详情页的兜底分支
                    PAGE_EDIT -> CustomChecklistEditorScreen(
                        vm = referenceVm,
                        checklistId = checklistArg,
                        onBack = { checklistBack() },
                    )

                    else -> ChecklistDetailScreen(
                        vm = referenceVm,
                        aircraftId = checklistArg,
                        onBack = { checklistBack() },
                        // 复制内置检查单后直接进副本详情，用户不用回列表再找一遍
                        onOpenAircraft = { id ->
                            checklistArg = id
                            checklistPage = PAGE_DETAIL
                        },
                        onEditCustom = { id ->
                            checklistArg = id
                            checklistPage = PAGE_EDIT
                        },
                    )
                }

                // 工具页自带 ViewModel（viewModel() 默认参数），这里不传
                is Tab.Tools -> ToolsScreen()
            }
        }
    }
}

private const val PAGE_SEARCH = "search"
private const val PAGE_AIRPORT = "airport"
private const val PAGE_READER = "reader"
private const val PAGE_SETTINGS = "settings"
private const val PAGE_LIST = "list"
private const val PAGE_DETAIL = "detail"
private const val PAGE_DOC = "doc"
private const val PAGE_EDIT = "edit"
private const val PAGE_SECTION = "section"