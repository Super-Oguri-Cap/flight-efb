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
                            text = buildString {
                                append(chart.icao).append(" · ").append(ChartType.label(chart.chartType))
                                val count = document?.pageCount ?: 0
                                if (count > 1) {
                                    append(" · 第 ").append(listState.firstVisibleItemIndex + 1)
                                        .append('/').append(count).append(" 页")
                                }
                            },
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
                            // 手势分配：
                            //   双指       捏合缩放与平移
                            //   放大后单指 推图；推到边界后剩余位移交给列表翻页
                            //   未放大单指 不消费事件，列表照常滚动
                            //   双击       在「适应屏宽」与 2.5 倍之间切换（单手可用）
                            var lastTapAt = 0L
                            var lastTapPosition = Offset.Zero

                            // graphicsLayer 的变换顺序是「以中心为原点缩放，再平移」，
                            // 屏幕坐标 = 中心 + (内容 - 中心) * scale + offset。
                            // 要让焦点下方的内容保持不动，平移量必须按这个式子反解，
                            // 否则双击放大会从屏幕中心「跳」一下。
                            fun zoomAround(focus: Offset, nextScale: Float) {
                                val clamped = nextScale.coerceIn(1f, 6f)
                                val ratio = clamped / scale
                                val centerX = size.width / 2f
                                val centerY = size.height / 2f
                                offset = if (clamped <= 1.01f) {
                                    Offset.Zero
                                } else {
                                    offset * ratio + Offset(focus.x - centerX, focus.y - centerY) * (1f - ratio)
                                }
                                scale = clamped
                                offset = PageGeometry.clampOffset(
                                    offset,
                                    scale,
                                    size.width.toFloat(),
                                    size.height.toFloat(),
                                )
                            }

                            awaitEachGesture {
                                val firstDown = awaitFirstDown(requireUnconsumed = false)
                                var moved = false
                                if (scale > 1.01f) firstDown.consume()
                                do {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.count { it.pressed }
                                    if (pressed >= 2) {
                                        moved = true
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        val centroid = event.calculateCentroid(useCurrent = true)
                                        zoomAround(centroid, scale * zoom)
                                        if (pan != Offset.Zero) {
                                            offset = PageGeometry.clampOffset(
                                                offset + pan,
                                                scale,
                                                size.width.toFloat(),
                                                size.height.toFloat(),
                                            )
                                        }
                                        event.changes.forEach { it.consume() }
                                    } else if (pressed == 1 && scale > 1.01f) {
                                        val pan = event.calculatePan()
                                        if (pan != Offset.Zero) moved = true
                                        val maxX = size.width * (scale - 1f) / 2f
                                        val maxY = size.height * (scale - 1f) / 2f
                                        val rawX = offset.x + pan.x
                                        val rawY = offset.y + pan.y
                                        val boundX = rawX.coerceIn(-maxX, maxX)
                                        val boundY = rawY.coerceIn(-maxY, maxY)
                                        offset = Offset(boundX, boundY)
                                        val leftoverY = rawY - boundY
                                        if (leftoverY != 0f) {
                                            listState.dispatchRawDelta(-leftoverY)
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })

                                if (!moved) {
                                    val now = System.currentTimeMillis()
                                    val near = (firstDown.position - lastTapPosition).getDistance() < 120f
                                    if (now - lastTapAt < 320L && near) {
                                        zoomAround(firstDown.position, if (scale > 1.01f) 1f else 2.5f)
                                        lastTapAt = 0L
                                    } else {
                                        lastTapAt = now
                                        lastTapPosition = firstDown.position
                                    }
                                }
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
            runCatching { Perf.timed("pdf_render_" + (index + 1)) { document.render(index, invert) } }
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