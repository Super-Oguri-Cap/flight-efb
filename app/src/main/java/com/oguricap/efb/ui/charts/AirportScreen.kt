package com.oguricap.efb.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oguricap.efb.data.Chart
import com.oguricap.efb.data.ChartType
import com.oguricap.efb.ui.ChartViewModel

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
    // 从阅读页返回时 currentCharts 可能已被后续操作覆盖，这里以 icao 为准补齐

    val airport = vm.currentAirport
    val grouped = vm.currentCharts.groupBy { it.chartType }
        .toSortedMap(compareBy { ChartType.rank(it) })

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = icao + "  " + (airport?.name ?: ""),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = vm.currentCharts.size.toString() + " 张图",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                        ChartRow(chart) { onOpenChart(chart) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartRow(chart: Chart, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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