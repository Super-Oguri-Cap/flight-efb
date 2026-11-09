package com.oguricap.efb.ui.checklist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.oguricap.efb.data.AircraftCategory
import com.oguricap.efb.data.AircraftChecklist
import com.oguricap.efb.data.ChecklistProgress
import com.oguricap.efb.data.ReferenceDoc
import com.oguricap.efb.ui.ReferenceViewModel
import com.oguricap.efb.ui.TwoLineTopBar

// 列表滚动位置的键：列表页与每架飞机的详情页各存一份
private const val SCROLL_LIST = "checklist:list"
private fun scrollKey(aircraftId: String) = "checklist:$aircraftId"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistListScreen(
    vm: ReferenceViewModel,
    onOpenAircraft: (String) -> Unit,
    onOpenDoc: (String) -> Unit,
    onDeleteCustom: (String) -> Unit = {},
) {
    // 待确认删除的自定义检查单 id：删除不可撤销，点一下按钮先问一次
    var pendingDelete by remember { mutableStateOf<String?>(null) }
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
                    val custom = vm.isCustom(ac.id)
                    AircraftRow(
                        aircraft = ac,
                        custom = custom,
                        onClick = { onOpenAircraft(ac.id) },
                        // 内置检查单只读：不给任何删除/编辑入口
                        onDelete = if (custom) ({ pendingDelete = ac.id }) else null,
                    )
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        val name = vm.aircraft(toDelete)?.name ?: "这份检查单"
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除自定义检查单") },
            text = { Text("「" + name + "」和它的勾选进度都会被删除，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDeleteCustom(toDelete)
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
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
private fun AircraftRow(
    aircraft: AircraftChecklist,
    custom: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = aircraft.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (custom) {
                Text(
                    text = "自定义",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                text = aircraft.phases.size.toString() + " 阶段",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 按钮自己消费点击事件，不会连带打开详情页
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text("删除", fontSize = MaterialTheme.typography.labelMedium.fontSize)
                }
            }
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
    onOpenAircraft: (String) -> Unit = {},
    onEditCustom: (String) -> Unit = {},
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

    // 勾选进度按机型分开存，进页面时读一次
    LaunchedEffect(aircraftId) { vm.ensureProgress(aircraftId) }
    val checkedCount = vm.checkedCount(aircraftId)
    val total = aircraft?.itemCount ?: 0

    Scaffold(
        topBar = {
            // 长机型名（A319/A320/A321…）会折成两行，M3 的 TopAppBar 会把它下面的进度行裁掉，
            // 这里换成按内容撑高的两行顶栏
            TwoLineTopBar(
                title = aircraft?.name ?: aircraftId,
                subtitle = if (total > 0) "已勾 " + checkedCount + " / " + total + " 项" else "",
                onBack = onBack,
                actions = {
                    // 内置检查单只读：不给「编辑」，只给一份可编辑的副本
                    if (aircraft != null) {
                        if (vm.isCustom(aircraft.id)) {
                            TextButton(onClick = { onEditCustom(aircraftId) }) {
                                Text("编辑")
                            }
                        } else {
                            TextButton(
                                onClick = {
                                    vm.duplicateBuiltin(aircraftId)?.let { onOpenAircraft(it.id) }
                                },
                            ) {
                                Text("复制一份")
                            }
                        }
                    }
                    // 每份检查单各自的复位，只有勾过才可点
                    TextButton(
                        onClick = { vm.resetChecklist(aircraftId) },
                        enabled = checkedCount > 0,
                    ) {
                        Text("重置")
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
            // 键优先用 uid：自定义检查单里用户可能建两个同名阶段，用名字当键会撞
            itemsIndexed(aircraft.phases, key = { _, phase -> phase.uid.ifBlank { phase.name } }) { phaseIndex, phase ->
                val phaseKey = phase.uid.ifBlank { phase.name }
                val expanded = phaseKey in vm.expandedPhases
                val doneInPhase = vm.checkedInPhase(aircraftId, phaseIndex)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { vm.togglePhase(phaseKey) }
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
                    // 每个阶段自己的重置：只清这一段的勾，不动别的阶段
                    if (doneInPhase > 0) {
                        TextButton(onClick = { vm.resetPhase(aircraftId, phaseIndex) }) {
                            Text("重置", fontSize = MaterialTheme.typography.labelMedium.fontSize)
                        }
                    }
                    Text(
                        text = if (doneInPhase > 0) doneInPhase.toString() + "/" + phase.items.size
                        else phase.items.size.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (doneInPhase > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                    )
                }
                if (expanded) {
                    phase.items.forEachIndexed { itemIndex, item ->
                        // 自定义条目有 uid，用它当键；内置没有 uid，回落成序号，行为不变。
                        // 自定义检查单能重排条目，序号会平移，只有 uid 不会串位。
                        val itemKey = item.uid.ifBlank { ChecklistProgress.itemKey(phaseIndex, itemIndex) }
                        val done = vm.isChecked(aircraftId, itemKey)
                        ChecklistRow(
                            item = item.item,
                            state = item.state,
                            done = done,
                            onClick = { vm.toggleItem(aircraftId, itemKey) },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// 一行 = 一个勾选框 + 项目 + 应答。
// 整行都能点：只点方框的话，戴手套或颠簸时很难点中。
@Composable
private fun ChecklistRow(
    item: String,
    state: String,
    done: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = done, onCheckedChange = null)
        Text(
            text = item,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            fontFamily = FontFamily.Monospace,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Text(
            text = state,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
        )
    }
}
