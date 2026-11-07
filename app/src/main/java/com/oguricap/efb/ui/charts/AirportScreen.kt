package com.oguricap.efb.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oguricap.efb.data.Chart
import com.oguricap.efb.data.ChartType
import com.oguricap.efb.data.PinKind
import com.oguricap.efb.ui.ChartViewModel
import com.oguricap.efb.ui.TwoLineTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirportScreen(
    vm: ChartViewModel,
    icao: String,
    onBack: () -> Unit,
    onOpenChart: (Chart) -> Unit,
) {
    LaunchedEffect(icao) {
        if (vm.currentAirport?.icao != icao) vm.openAirport(icao)
    }

    // 从阅读页退回时回到原来那一屏，而不是列表顶部
    val saved = vm.savedScroll(icao)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = saved?.first ?: 0,
        initialFirstVisibleItemScrollOffset = saved?.second ?: 0,
    )
    LaunchedEffect(listState, icao) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> vm.rememberScroll(icao, index, offset) }
    }

    // 选择模式：勾几张图，一次性丢进离场区或进场区。
    // 选中集合按机场隔离，切换机场不会把上一个机场的勾带过来。
    var selecting by rememberSaveable(icao) { mutableStateOf(false) }
    val selected = remember(icao) { mutableStateMapOf<String, Chart>() }
    // 置顶区的折叠状态，默认展开（选中就是为了马上看到）
    var showDeparturePin by rememberSaveable(icao) { mutableStateOf(true) }
    var showArrivalPin by rememberSaveable(icao) { mutableStateOf(true) }

    fun quitSelecting() {
        selecting = false
        selected.clear()
    }

    val airport = vm.currentAirport
    val pinnedDeparture = vm.departures.filter { it.icao == icao }
    val pinnedArrival = vm.arrivals.filter { it.icao == icao }
    // 已选中的图只在置顶区出现一次：按类型分组的列表里把它们剔掉，
    // 否则同一张 SID 在顶部和「离场图」里各有一条，看着像重复数据
    val pinnedFiles = (pinnedDeparture + pinnedArrival).map { it.file }.toSet()
    val grouped = vm.currentCharts
        .filterNot { it.file in pinnedFiles }
        .groupBy { it.chartType }
        .toSortedMap(compareBy { ChartType.rank(it) })
    val selectedCharts = selected.values.toList()

    Scaffold(
        topBar = {
            TwoLineTopBar(
                title = icao + "  " + (airport?.name ?: ""),
                subtitle = buildString {
                    append(vm.currentCharts.size).append(" 张图")
                    val pinned = pinnedDeparture.size + pinnedArrival.size
                    if (pinned > 0) append(" · 已选 ").append(pinned)
                },
                onBack = { if (selecting) quitSelecting() else onBack() },
                backDescription = if (selecting) "退出选择" else "返回",
                navIcon = if (selecting) Icons.Filled.Clear else Icons.AutoMirrored.Filled.ArrowBack,
                actions = {
                    if (selecting) {
                        TextButton(onClick = { quitSelecting() }) { Text("完成") }
                    } else {
                        IconButton(onClick = { selecting = true }) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = "选择航图")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selecting) {
                SelectionBar(
                    count = selected.size,
                    onPinDeparture = {
                        vm.pinCharts(selectedCharts, PinKind.DEPARTURE)
                        showDeparturePin = true
                        quitSelecting()
                    },
                    onPinArrival = {
                        vm.pinCharts(selectedCharts, PinKind.ARRIVAL)
                        showArrivalPin = true
                        quitSelecting()
                    },
                    onRemove = {
                        vm.unpinCharts(selectedCharts)
                        quitSelecting()
                    },
                )
            }
        },
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
            if (pinnedDeparture.isNotEmpty()) {
                item(key = "pin-header-" + PinKind.DEPARTURE.key) {
                    PinnedHeader(
                        kind = PinKind.DEPARTURE,
                        count = pinnedDeparture.size,
                        expanded = showDeparturePin,
                        onToggle = { showDeparturePin = !showDeparturePin },
                    )
                }
                if (showDeparturePin) {
                    items(pinnedDeparture, key = { "pin-dep-" + it.id }) { chart ->
                        PinnedRow(
                            chart = chart,
                            kind = PinKind.DEPARTURE,
                            selecting = selecting,
                            checked = chart.file in selected,
                            onClick = {
                                if (selecting) selected.toggle(chart) else onOpenChart(chart)
                            },
                            onRemove = { vm.unpinCharts(listOf(chart)) },
                        )
                    }
                }
            }
            if (pinnedArrival.isNotEmpty()) {
                item(key = "pin-header-" + PinKind.ARRIVAL.key) {
                    PinnedHeader(
                        kind = PinKind.ARRIVAL,
                        count = pinnedArrival.size,
                        expanded = showArrivalPin,
                        onToggle = { showArrivalPin = !showArrivalPin },
                    )
                }
                if (showArrivalPin) {
                    items(pinnedArrival, key = { "pin-arr-" + it.id }) { chart ->
                        PinnedRow(
                            chart = chart,
                            kind = PinKind.ARRIVAL,
                            selecting = selecting,
                            checked = chart.file in selected,
                            onClick = {
                                if (selecting) selected.toggle(chart) else onOpenChart(chart)
                            },
                            onRemove = { vm.unpinCharts(listOf(chart)) },
                        )
                    }
                }
            }

            grouped.forEach { (type, charts) ->
                val expanded = type in vm.expandedTypes
                item(key = "header-" + type) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { vm.toggleType(type) }
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = ChartType.label(type),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = charts.size.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                        )
                    }
                }
                if (expanded) {
                    items(charts, key = { it.id }) { chart ->
                        ChartRow(
                            chart = chart,
                            selecting = selecting,
                            checked = chart.file in selected,
                            onClick = {
                                if (selecting) selected.toggle(chart) else onOpenChart(chart)
                            },
                        )
                    }
                }
            }
        }
    }
}

// 勾选 / 取消勾选：选中集合以文件路径为键，同一张图不会因为重复点击长出两条
private fun MutableMap<String, Chart>.toggle(chart: Chart) {
    if (containsKey(chart.file)) remove(chart.file) else put(chart.file, chart)
}

@Composable
private fun SelectionBar(
    count: Int,
    onPinDeparture: () -> Unit,
    onPinArrival: () -> Unit,
    onRemove: () -> Unit,
) {
    val enabled = count > 0
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Text(
                text = if (enabled) "已选 " + count + " 张" else "点选要加入的航图",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp),
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPinDeparture, enabled = enabled) { Text("加入离场") }
                TextButton(onClick = onPinArrival, enabled = enabled) { Text("加入进场") }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onRemove, enabled = enabled) { Text("移出分组") }
            }
        }
    }
}

@Composable
private fun PinnedHeader(
    kind: PinKind,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = kind.label + "（已选）",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun PinnedRow(
    chart: Chart,
    kind: PinKind,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = checked, onCheckedChange = { onClick() })
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(text = chart.title, style = MaterialTheme.typography.bodyMedium)
            val rwy = if (chart.rwys.isEmpty()) "" else " · " + chart.rwys.joinToString(" / ")
            Text(
                text = ChartType.label(chart.chartType) + rwy,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!selecting) {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Clear,
                    contentDescription = "从" + kind.label + "区移出",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun ChartRow(
    chart: Chart,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = checked, onCheckedChange = { onClick() })
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = chart.title, style = MaterialTheme.typography.bodyMedium)
            if (chart.rwys.isNotEmpty()) {
                Text(
                    text = chart.rwys.joinToString(" / "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = ChartType.label(chart.chartType),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
