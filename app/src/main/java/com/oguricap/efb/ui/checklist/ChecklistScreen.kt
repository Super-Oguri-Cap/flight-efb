package com.oguricap.efb.ui.checklist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oguricap.efb.data.AircraftCategory
import com.oguricap.efb.data.AircraftChecklist
import com.oguricap.efb.data.ReferenceDoc
import com.oguricap.efb.ui.ReferenceViewModel

// 列表滚动位置的键：列表页与每架飞机的详情页各存一份
private const val SCROLL_LIST = "checklist:list"
private fun scrollKey(aircraftId: String) = "checklist:$aircraftId"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistListScreen(
    vm: ReferenceViewModel,
    onOpenAircraft: (String) -> Unit,
    onOpenDoc: (String) -> Unit,
) {
    // 从详情页或别的标签页回来时回到原来那一屏，而不是列表顶部
    val saved = vm.savedScroll(SCROLL_LIST)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = saved?.first ?: 0,
        initialFirstVisibleItemScrollOffset = saved?.second ?: 0,
    )
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> vm.rememberScroll(SCROLL_LIST, index, offset) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("检查单", fontWeight = FontWeight.Bold)
                        Text(
                            text = "共 " + vm.checklists.size + " 个机型 · 随附 " + vm.docs.size + " 份手册",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
        },
    ) { padding ->
        val grouped = vm.checklists.groupBy { it.category }
            .toSortedMap(compareBy { AircraftCategory.rank(it) })
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
            // 随附手册放最前：这是随机带下来的原件，用到的概率高于其它机型
            if (vm.docs.isNotEmpty()) {
                item(key = "docs-header") {
                    Text(
                        text = "随附手册",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                items(vm.docs, key = { "doc-" + it.id }) { doc ->
                    DocRow(doc) { onOpenDoc(doc.id) }
                }
                item(key = "docs-divider") { HorizontalDivider() }
            }

            grouped.forEach { (category, aircraft) ->
                item(key = "cat-" + category) {
                    Text(
                        text = AircraftCategory.label(category),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                items(aircraft, key = { it.id }) { ac ->
                    AircraftRow(ac) { onOpenAircraft(ac.id) }
                }
            }
        }
    }
}

@Composable
private fun DocRow(doc: ReferenceDoc, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = doc.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = doc.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "PDF",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AircraftRow(aircraft: AircraftChecklist, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = aircraft.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = aircraft.phases.size.toString() + " 阶段",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (aircraft.note.isNotBlank()) {
            Text(
                text = aircraft.note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistDetailScreen(
    vm: ReferenceViewModel,
    aircraftId: String,
    onBack: () -> Unit,
) {
    val aircraft = vm.aircraft(aircraftId)
    val key = scrollKey(aircraftId)
    val saved = vm.savedScroll(key)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = saved?.first ?: 0,
        initialFirstVisibleItemScrollOffset = saved?.second ?: 0,
    )
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> vm.rememberScroll(key, index, offset) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = aircraft?.name ?: aircraftId,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = aircraft?.let { it.itemCount.toString() + " 项" } ?: "",
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
        if (aircraft == null) {
            Text("没有这个机型的检查单", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
            items(aircraft.phases, key = { it.name }) { phase ->
                val expanded = phase.name in vm.expandedPhases
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { vm.togglePhase(phase.name) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(phase.name, fontWeight = FontWeight.Bold)
                        if (phase.gloss.isNotBlank()) {
                            Text(
                                text = phase.gloss,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = phase.items.size.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                    )
                }
                if (expanded) {
                    phase.items.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                        ) {
                            Text(
                                text = item.item,
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = item.state,
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
