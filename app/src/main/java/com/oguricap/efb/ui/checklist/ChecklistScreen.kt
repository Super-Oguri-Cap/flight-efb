package com.oguricap.efb.ui.checklist

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oguricap.efb.data.AircraftCategory
import com.oguricap.efb.data.AircraftChecklist
import com.oguricap.efb.ui.ReferenceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistListScreen(
    vm: ReferenceViewModel,
    onOpenAircraft: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("检查单", fontWeight = FontWeight.Bold)
                        Text(
                            text = "共 " + vm.checklists.size + " 个机型",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
        },
    ) { padding ->
        val grouped = vm.checklists.groupBy { it.category }
            .toSortedMap(compareBy { AircraftCategory.rank(it) })
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
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
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
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
