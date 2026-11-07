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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
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
import java.io.File

// 航图阅读页：把 Chart 翻译成「文件 + 标题」，正文交给 PdfReaderScreen
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    vm: ChartViewModel,
    chart: Chart,
    onBack: () -> Unit,
) {
    PdfReaderScreen(
        file = vm.absoluteFile(chart),
        documentKey = chart.id,
        title = chart.title,
        subtitlePrefix = chart.icao + " · " + ChartType.label(chart.chartType),
        filePendingMessage = "还没有设置航图库目录，请回到设置页选择",
        invert = vm.invertReader,
        onToggleInvert = { vm.toggleInvert() },
        onBack = onBack,
    )
}

// 通用 PDF 阅读页。航图与随附手册共用：
// 只有「文件从哪来」不同，缩放 / 反色 / 翻页的手势与状态管理完全一致，
// 分成两份实现迟早会走偏，所以这里只留一个入口。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    file: File?,
    documentKey: String,
    title: String,
    subtitlePrefix: String,
    filePendingMessage: String,
    invert: Boolean,
    onToggleInvert: () -> Unit,
    onBack: () -> Unit,
) {
    var document by remember(documentKey) { mutableStateOf<PdfDocument?>(null) }
    var error by remember(documentKey) { mutableStateOf<String?>(null) }
    var scale by remember(documentKey) { mutableFloatStateOf(1f) }
    var offset by remember(documentKey) { mutableStateOf(Offset.Zero) }
    var savedPage by rememberSaveable(documentKey) { mutableIntStateOf(0) }
    var savedOffset by rememberSaveable(documentKey) { mutableIntStateOf(0) }
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

    LaunchedEffect(documentKey, file) {
        if (file == null) {
            // 文件还没准备好（例如手册正在从 assets 落盘），
            // 由调用方负责给出进度或错误；这里保持加载态即可
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

    DisposableEffect(documentKey) {
        onDispose { document?.close() }
    }

    // 当前页渲染完后立刻预渲染下一页
    LaunchedEffect(document, invert) {
        val doc = document ?: return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            withContext(Dispatchers.IO) {
                runCatching { doc.prefetch(index + 1, invert) }
            }
        }
    }

    val pageCount = document?.pageCount ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append(subtitlePrefix)
                                if (pageCount > 1) {
                                    append(" · 第 ").append(listState.firstVisibleItemIndex + 1)
                                        .append('/').append(pageCount).append(" 页")
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
                        onToggleInvert()
                        scale = 1f
                        offset = Offset.Zero
                    }) {
                        Text(if (invert) "反色 开" else "反色 关")
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
                file == null -> Text(
                    text = filePendingMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    // 手势层必须装在 graphicsLayer 外面。
                    // 反过来（先缩放再收手势）时，指针坐标落在图层内部，
                    // 已经被 scale 除过一次：2.5 倍下手指推 100 px 只算 40 px，
                    // 表现就是「放大以后怎么推都推不动」。装在外面拿到的就是屏幕像素，
                    // 位移与手指 1:1，offset 与 zoomAround 的换算也全部留在屏幕空间里。
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(documentKey) {
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
                                // 全程走 Initial 阶段：这个阶段由外向内派发，
                                // 比 LazyColumn 自己的滚动先一步拿到事件。
                                // 用 Main 的话列表会先把位移吃掉，放大后表现为
                                // 「图推得慢，页还在滚」——两个动作同时生效。
                                val firstDown = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                var moved = false
                                if (scale > 1.01f) firstDown.consume()
                                var anyPressed = true
                                while (anyPressed) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val downCount = event.changes.count { it.pressed }
                                    if (downCount >= 2) {
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
                                    } else if (downCount == 1) {
                                        // 先分清「拖动」与「点按」：位移超过阈值才算拖动。
                                        // 用 pan != 0 判定的话，点按时的几像素抖动会被当成拖动，
                                        // 双击缩放就再也触发不了。
                                        val pan = event.calculatePan()
                                        if (pan.getDistance() > TOUCH_SLOP) moved = true
                                        if (scale > 1.01f) {
                                            val maxX = size.width * (scale - 1f) / 2f
                                            val maxY = size.height * (scale - 1f) / 2f
                                            val rawX = offset.x + pan.x
                                            val rawY = offset.y + pan.y
                                            val boundX = rawX.coerceIn(-maxX, maxX)
                                            val boundY = rawY.coerceIn(-maxY, maxY)
                                            offset = Offset(boundX, boundY)
                                            val leftoverY = rawY - boundY
                                            if (leftoverY != 0f) {
                                                // 列表内容整体被 scale 放大过，
                                                // 换算回内容坐标才能和手指位移对上
                                                listState.dispatchRawDelta(-leftoverY / scale)
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                        // 未放大时故意不消费事件，让列表照常滚动
                                    }
                                    anyPressed = event.changes.any { it.pressed }
                                }

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
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                ) {
                    items((0 until doc.pageCount).toList(), key = { it }) { index ->
                        PdfPageItem(doc, index, invert)
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

// 单指位移超过这个值就按「拖动」处理，不再参与双击判定
private const val TOUCH_SLOP = 8f
