package com.oguricap.efb.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import com.oguricap.efb.data.Chart
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
    onOpenChart: (Chart) -> Unit,
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
        // 系统栏留白由外层 EfbApp 的 Scaffold 统一负责；这里再算一次就是双倍，
        // 表现是标题离刘海远一整条状态栏、列表底边离底栏也高出一截
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
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
                // 把「机场优先」写进提示：用户输入四字码时期待的是机场，不是一堆散图
                placeholder = { Text("搜机场：ZGGG / 广州；或搜图名：ILS") },
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
                else -> {
                    val searching = vm.query.isNotBlank()
                    LazyColumn(state = searchState, modifier = Modifier.fillMaxSize()) {
                        // 首页（未搜索）：已选的离场 / 进场图是飞行中最快的入口，置顶
                        if (!searching) pinnedSections(vm, onOpenChart)

                        // 搜索时「机场大类」永远在最上面。
                        // 输入 ZGGG 第一屏就是广州白云这个机场，点进去按图型挑图，
                        // 不再先铺开上百张具体航图。
                        if (vm.airports.isNotEmpty()) {
                            item(key = "airport-header") {
                                SectionHeader(
                                    if (searching) "机场 " + vm.airports.size + " 个 · 点进去按图型查看"
                                    else "机场 " + vm.airports.size + " 个",
                                )
                            }
                            items(vm.airports, key = { "airport-" + it.icao }) { airport ->
                                AirportRow(
                                    airport = airport,
                                    // 四字码精确命中时用主色整卡高亮，一眼看到要找的机场
                                    highlighted = searching &&
                                        airport.icao.equals(vm.query.trim(), ignoreCase = true),
                                    onClick = { onOpenAirport(airport.icao) },
                                )
                            }
                        }

                        if (searching && vm.airports.isEmpty() && vm.chartHits.isEmpty()) {
                            item(key = "no-result") { EmptyHint(vm.query.trim()) }
                        }

                        // 只有查询词没命中任何机场时才会走到这里（如 ILS、RWY36L）
                        if (vm.chartHits.isNotEmpty()) {
                            item(key = "chart-header") {
                                SectionHeader("图名匹配 " + vm.chartHits.size + " 条")
                            }
                            items(vm.chartHits, key = { "chart-" + it.id }) { chart ->
                                ChartHitRow(chart) { onOpenChart(chart) }
                            }
                            item(key = "chart-divider") { HorizontalDivider() }
                        }

                        // 搜索时已选区不抢首屏，移到结果后面
                        if (searching) pinnedSections(vm, onOpenChart)
                    }
                }
            }
        }
    }
}

// 已选航图跨机场汇总：一次飞行只用得上几张图，
// 从 A 机场的离场图跳到 B 机场的进场图不必先搜机场。
// 首页置顶、搜索时沉底，由调用方决定顺序。
private fun LazyListScope.pinnedSections(
    vm: ChartViewModel,
    onOpenChart: (Chart) -> Unit,
) {
    if (vm.departures.isNotEmpty()) {
        item(key = "pin-dep-header") {
            SectionHeader("离场（已选 " + vm.departures.size + "）")
        }
        items(vm.departures, key = { "pin-dep-" + it.file }) { chart ->
            PinnedHitRow(chart) { onOpenChart(chart) }
        }
    }
    if (vm.arrivals.isNotEmpty()) {
        item(key = "pin-arr-header") {
            SectionHeader("进场（已选 " + vm.arrivals.size + "）")
        }
        items(vm.arrivals, key = { "pin-arr-" + it.file }) { chart ->
            PinnedHitRow(chart) { onOpenChart(chart) }
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

// 机场大类卡片：四字码 + 中文名 + 图数 + 进入箭头，整张卡都是点击区
@Composable
private fun AirportRow(airport: Airport, highlighted: Boolean, onClick: () -> Unit) {
    val foreground = if (highlighted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Card(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = airport.icao,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (highlighted) foreground else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = airport.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = foreground,
                )
            }
            Text(
                text = airport.chartCount.toString() + " 张航图",
                style = MaterialTheme.typography.labelLarge,
                color = if (highlighted) foreground else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "查看该机场航图",
                tint = if (highlighted) foreground else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyHint(query: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "没有找到「" + query + "」", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "试试机场四字码（ZGGG）、中文机场名（广州），或图名关键词（ILS）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// 已选航图的紧凑行：机场码 + 图名，点击直接开图
@Composable
private fun PinnedHitRow(chart: Chart, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = chart.icao,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "  " + chart.title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = ChartType.label(chart.chartType),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChartHitRow(chart: Chart, onClick: () -> Unit) {
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
