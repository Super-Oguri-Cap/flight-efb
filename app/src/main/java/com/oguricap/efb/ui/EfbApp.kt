package com.oguricap.efb.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import com.oguricap.efb.ui.qrh.QrhScreen
import com.oguricap.efb.ui.qrh.QrhSectionScreen
import com.oguricap.efb.ui.reader.ReaderScreen
import com.oguricap.efb.ui.search.SearchScreen
import com.oguricap.efb.ui.settings.SettingsScreen

// 顶层三个标签，每个标签维护自己的页面栈。
// 页面栈全部用字符串保存（rememberSaveable），Activity 重建后能回到原来的位置。
sealed interface Tab {
    data object Charts : Tab
    data object Checklists : Tab
    data object Qrh : Tab

    companion object {
        fun fromKey(key: String): Tab = when (key) {
            "checklists" -> Checklists
            "qrh" -> Qrh
            else -> Charts
        }

        fun key(tab: Tab): String = when (tab) {
            is Checklists -> "checklists"
            is Qrh -> "qrh"
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

    // 航图栈：search / airport / reader / settings
    var chartPage by rememberSaveable { mutableStateOf(PAGE_SEARCH) }
    var chartArg by rememberSaveable { mutableStateOf("") }

    // 检查单栈
    var checklistPage by rememberSaveable { mutableStateOf(PAGE_LIST) }
    var checklistArg by rememberSaveable { mutableStateOf("") }

    // QRH 栈
    var qrhPage by rememberSaveable { mutableStateOf(PAGE_INDEX) }
    var qrhArg by rememberSaveable { mutableStateOf("") }

    val referenceVm: ReferenceViewModel = viewModel()

    // 重建后当前机场的图还没加载，补一次
    LaunchedEffect(chartPage, chartArg) {
        if (chartPage == PAGE_AIRPORT && chartArg.isNotEmpty() && vm.currentAirport?.icao != chartArg) {
            vm.openAirport(chartArg)
        }
    }

    // 阅读页只保存文件路径，重建后按路径从当前机场的图里找回 Chart
    val readerChart: Chart? = if (chartPage == PAGE_READER && chartArg.isNotEmpty()) {
        vm.currentCharts.firstOrNull { it.file == chartArg }
    } else {
        null
    }

    fun chartBack() {
        chartPage = when (chartPage) {
            PAGE_READER -> if (chartArg.isNotEmpty()) PAGE_AIRPORT else PAGE_SEARCH
            else -> PAGE_SEARCH
        }
    }

    fun checklistBack() {
        checklistPage = PAGE_LIST
    }

    fun qrhBack() {
        qrhPage = PAGE_INDEX
    }

    val canGoBack = when (tab) {
        is Tab.Charts -> chartPage != PAGE_SEARCH
        is Tab.Checklists -> checklistPage != PAGE_LIST
        is Tab.Qrh -> qrhPage != PAGE_INDEX
    }

    BackHandler(enabled = canGoBack) {
        when (tab) {
            is Tab.Charts -> chartBack()
            is Tab.Checklists -> checklistBack()
            is Tab.Qrh -> qrhBack()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
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
                    selected = tab is Tab.Qrh,
                    onClick = { tabKey = Tab.key(Tab.Qrh) },
                    icon = { Icon(Icons.Filled.Menu, contentDescription = null) },
                    label = { Text("QRH") },
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
                            chartArg = icao
                            chartPage = PAGE_AIRPORT
                        },
                        onOpenChart = { chart ->
                            chartArg = chart.file
                            chartPage = PAGE_READER
                        },
                        onOpenSettings = { chartPage = PAGE_SETTINGS },
                    )

                    PAGE_AIRPORT -> AirportScreen(
                        vm = vm,
                        icao = chartArg,
                        onBack = { chartBack() },
                        onOpenChart = { chart ->
                            chartArg = chart.file
                            chartPage = PAGE_READER
                        },
                    )

                    PAGE_READER -> {
                        if (readerChart != null) {
                            ReaderScreen(
                                vm = vm,
                                chart = readerChart,
                                onBack = { chartBack() },
                            )
                        } else {
                            // 重建后当前机场的图还没取回，或索引已变；
                            // 退回机场页而不是显示空白阅读页
                            LaunchedEffect(Unit) { chartPage = PAGE_SEARCH }
                        }
                    }

                    PAGE_SETTINGS -> SettingsScreen(
                        vm = vm,
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
                    )

                    else -> ChecklistDetailScreen(
                        vm = referenceVm,
                        aircraftId = checklistArg,
                        onBack = { checklistBack() },
                    )
                }

                is Tab.Qrh -> when (qrhPage) {
                    PAGE_INDEX -> QrhScreen(
                        vm = referenceVm,
                        onOpenSection = { id ->
                            qrhArg = id
                            qrhPage = PAGE_SECTION
                        },
                    )

                    else -> QrhSectionScreen(
                        vm = referenceVm,
                        sectionId = qrhArg,
                        onBack = { qrhBack() },
                    )
                }
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
private const val PAGE_INDEX = "index"
private const val PAGE_SECTION = "section"
