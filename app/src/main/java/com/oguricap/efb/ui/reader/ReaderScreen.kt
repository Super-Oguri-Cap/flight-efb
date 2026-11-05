package com.oguricap.efb.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oguricap.efb.data.Chart
import com.oguricap.efb.data.ChartType
import com.oguricap.efb.perf.Perf
import com.oguricap.efb.ui.ChartViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    vm: ChartViewModel,
    chart: Chart,
    onBack: () -> Unit,
) {
    val file = vm.absoluteFile(chart)
    var document by remember(chart.id) { mutableStateOf<PdfDocument?>(null) }
    var error by remember(chart.id) { mutableStateOf<String?>(null) }
    var scale by remember(chart.id) { mutableFloatStateOf(1f) }
    var offset by remember(chart.id) { mutableStateOf(Offset.Zero) }
    var savedPage by rememberSaveable(chart.id) { mutableIntStateOf(0) }
    var savedOffset by rememberSaveable(chart.id) { mutableIntStateOf(0) }
    // 滚动位置跨重建保留，否则切字体大小回来会跳回第一页
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedPage,
        initialFirstVisibleItemScrollOffset = savedOffset,
    )

    // 记录当前页，供重建后恢复
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, scrollOffset) ->
                savedPage = index
                savedOffset = scrollOffset
            }
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val widthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }

    LaunchedEffect(chart.id) {
        if (file == null) {
            error = "还没有设置航图库目录，请回到设置页选择"
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            runCatching { Perf.timed("pdf_open") { PdfDocument(file, widthPx) } }
        }
        result.onSuccess { opened ->
            // 打开期间用户可能已经按了返回，协程被取消。
            // 这时页面不会被赋值，onDispose 关不到它，必须在这里主动关闭，
            // 否则 ParcelFileDescriptor 与 PdfRenderer 都会泄漏。
            if (!currentCoroutineContext().isActive) {
                opened.close()
                return@LaunchedEffect
            }
            document = opened
        }
        result.onFailure { error = it.message ?: it.toString() }
    }

    DisposableEffect(chart.id) {
        onDispose { document?.close() }
    }

    // 当前页渲染完后立刻预渲染下一页
    LaunchedEffect(document, vm.invertReader) {
        val doc = document ?: return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            withContext(Dispatchers.IO) {
                runCatching { doc.prefetch(index + 1, vm.invertReader) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = chart.title,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = chart.icao + " · " + ChartType.label(chart.chartType),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (scale > 1.01f) {
                        TextButton(onClick = {
                            scale = 1f
                            offset = Offset.Zero
                        }) {
                            Text("复位")
                        }
                    }
                    TextButton(onClick = {
                        vm.toggleInvert()
                        scale = 1f
                        offset = Offset.Zero
                    }) {
                        Text(if (vm.invertReader) "反色 开" else "反色 关")
                    }
                },
            )
        },
        containerColor = Color.White,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(Color.White)) {
            val doc = document
            when {
                error != null -> Text(
                    text = "打开失败: " + error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                doc == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                doc.pageCount == 0 -> Text(
                    text = "这份 PDF 没有可显示的页面",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .pointerInput(chart.id) {
                            // 只拦截双指，单指留给列表滚动
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.count { it.pressed } >= 2) {
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        val centroid = event.calculateCentroid(useCurrent = true)
                                        if (zoom != 1f) {
                                            val next = (scale * zoom).coerceIn(1f, 6f)
                                            offset = if (next <= 1.01f) {
                                                Offset.Zero
                                            } else {
                                                offset + centroid * (1f - next / scale) + pan
                                            }
                                            scale = next
                                            offset = PageGeometry.clampOffset(offset, scale, size.width.toFloat(), size.height.toFloat())
                                        } else if (scale > 1.01f) {
                                            offset = PageGeometry.clampOffset(offset + pan, scale, size.width.toFloat(), size.height.toFloat())
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                ) {
                    items((0 until doc.pageCount).toList(), key = { it }) { index ->
                        PdfPageItem(doc, index, vm.invertReader)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageItem(document: PdfDocument, index: Int, invert: Boolean) {
    var page by remember(document, index, invert) { mutableStateOf<RenderedPage?>(null) }
    var renderError by remember(document, index, invert) { mutableStateOf<String?>(null) }

    LaunchedEffect(document, index, invert) {
        // 渲染必须离开主线程；命中缓存时这里是一次纯查表
        val result = withContext(Dispatchers.IO) {
            runCatching { document.render(index, invert) }
        }
        result.onSuccess { page = it }
        result.onFailure { renderError = it.message ?: it.toString() }
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val rendered = page
        if (rendered == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                if (renderError != null) {
                    Text(
                        text = "第 " + (index + 1) + " 页渲染失败：" + renderError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    CircularProgressIndicator()
                }
            }
        } else {
            Image(
                bitmap = rendered.bitmap.asImageBitmap(),
                contentDescription = "第 " + (index + 1) + " 页",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}