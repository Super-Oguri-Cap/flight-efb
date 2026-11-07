package com.oguricap.efb.ui.qrh

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.oguricap.efb.data.QrhEntry
import com.oguricap.efb.data.QrhSection
import com.oguricap.efb.ui.ReferenceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrhScreen(
    vm: ReferenceViewModel,
    onOpenSection: (String) -> Unit,
) {
    // 切到别的标签页再切回来时，Composable 会重新组合；
    // 位置存在 ViewModel 里，回来还停在原来那一条，不会弹回顶部
    val saved = vm.savedScroll(SCROLL_QRH_INDEX)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = saved?.first ?: 0,
        initialFirstVisibleItemScrollOffset = saved?.second ?: 0,
    )
    LaunchedEffect(listState, vm.qrh.sections.size) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> vm.rememberScroll(SCROLL_QRH_INDEX, index, offset) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("QRH", fontWeight = FontWeight.Bold)
                        Text(
                            text = vm.qrh.sections.sumOf { it.entries.size }.toString() + " 条处置",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
            item(key = "notice") {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = vm.qrh.notice,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            items(vm.qrh.sections, key = { it.id }) { section ->
                Column(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenSection(section.id) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = section.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = section.entries.size.toString() + " 条",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = section.aircraft,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (section.note.isNotBlank()) {
                        Text(
                            text = section.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrhSectionScreen(
    vm: ReferenceViewModel,
    sectionId: String,
    onBack: () -> Unit,
) {
    val section = vm.section(sectionId)
    // 分组页同样记住位置：翻到一半去别处看一眼再回来，不该从头开始
    val key = SCROLL_QRH_SECTION_PREFIX + sectionId
    val saved = vm.savedScroll(key)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = saved?.first ?: 0,
        initialFirstVisibleItemScrollOffset = saved?.second ?: 0,
    )
    LaunchedEffect(listState, sectionId) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> vm.rememberScroll(key, index, offset) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = section?.title ?: sectionId,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = section?.aircraft.orEmpty(),
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
        if (section == null) {
            Text("没有这个分组", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
            items(section.entries, key = { it.ata + it.title }) { entry ->
                EntryBlock(
                    entry = entry,
                    expanded = (section.id + "/" + entry.title) in vm.expandedEntries,
                    onToggle = { vm.toggleEntry(section.id + "/" + entry.title) },
                )
            }
        }
    }
}

@Composable
private fun EntryBlock(entry: QrhEntry, expanded: Boolean, onToggle: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ATA " + entry.ata,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (entry.memory) {
                        Text(
                            text = "  记忆项目",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = entry.condition,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
            )
        }
        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 12.dp)) {
                entry.actions.forEach { action ->
                    Text(
                        text = action,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                if (entry.inop.isNotBlank()) {
                    Text(
                        text = "INOP SYS: " + entry.inop,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

// 滚动位置的键：QRH 索引一份，每个分组各一份
private const val SCROLL_QRH_INDEX = "qrh:index"
private const val SCROLL_QRH_SECTION_PREFIX = "qrh:section:"
