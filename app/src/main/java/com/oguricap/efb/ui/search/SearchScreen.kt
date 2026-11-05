package com.oguricap.efb.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oguricap.efb.data.Airport
import com.oguricap.efb.data.ChartType
import com.oguricap.efb.ui.ChartViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    vm: ChartViewModel,
    ready: Boolean,
    storageGranted: Boolean,
    onRequestStorage: () -> Unit,
    onOpenAirport: (String) -> Unit,
    onOpenChart: (com.oguricap.efb.data.Chart) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // 从阅读页/机场页退回搜索页时回到原来那一屏，而不是列表顶部
    val savedSearch = vm.savedSearchScroll()
    val searchState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedSearch?.first ?: 0,
        initialFirstVisibleItemScrollOffset = savedSearch?.second ?: 0,
    )
    LaunchedEffect(searchState) {
        snapshotFlow { searchState.firstVisibleItemIndex to searchState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> vm.rememberSearchScroll(index, offset) }
    }

    // 部分定制系统上点击输入框只给焦点、不弹输入法（系统不派发 IME 显示请求）。
    // 拿到焦点时主动请求一次，避免用户以为输入框坏了。
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val interactionSource = remember { MutableInteractionSource() }
    // 每次按下都请求一次输入法：部分定制系统在点击已聚焦的输入框时
    // 不派发输入法显示请求，只靠焦点变化会表现为「点了没反应」
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) keyboard?.show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("飞行EFB") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            TextField(
                value = vm.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { state -> if (state.isFocused) keyboard?.show() },
                interactionSource = interactionSource,
                placeholder = { Text("四字码、机场名或图名") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                colors = TextFieldDefaults.colors(),
            )

            if (!storageGranted) {
                NoticeCard(
                    title = "需要「所有文件访问权限」",
                    body = "读取 /sdcard/飞行 下的航图需要这个权限。点右侧按钮到系统设置打开。",
                    action = "去授权",
                    onAction = onRequestStorage,
                )
            }

            when {
                vm.loadError != null -> Text(
                    text = "索引加载失败: " + vm.loadError,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                !ready -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { CircularProgressIndicator() }
                else -> LazyColumn(state = searchState, modifier = Modifier.fillMaxSize()) {
                    if (vm.chartHits.isNotEmpty()) {
                        item {
                            SectionHeader("图名匹配 " + vm.chartHits.size + " 条")
                        }
                        items(vm.chartHits, key = { "chart-" + it.id }) { chart ->
                            ChartHitRow(chart) { onOpenChart(chart) }
                        }
                        item { HorizontalDivider() }
                    }
                    item { SectionHeader("机场 " + vm.airports.size + " 个") }
                    items(vm.airports, key = { "airport-" + it.icao }) { airport ->
                        AirportRow(airport) { onOpenAirport(airport.icao) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun AirportRow(airport: Airport, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = airport.icao,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "  " + airport.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = airport.chartCount.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChartHitRow(chart: com.oguricap.efb.data.Chart, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = chart.icao,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "  " + ChartType.label(chart.chartType) + "  " + chart.title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NoticeCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall)
            Text(
                text = action,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp).clickable(onClick = onAction),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}