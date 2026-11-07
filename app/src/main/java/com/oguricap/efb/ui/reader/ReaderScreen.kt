package com.oguricap.efb.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.oguricap.efb.data.Chart
import com.oguricap.efb.data.ChartType
import com.oguricap.efb.perf.Perf
import com.oguricap.efb.ui.ChartViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// 航图阅读页：把 Chart 翻译成「文件 + 标题」，正文交给 PdfReaderScreen
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    vm: ChartViewModel,
    chart: Chart,
    onBack: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit = {},
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
        onFullscreenChange = onFullscreenChange,
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
    onFullscreenChange: (Boolean) -> Unit = {},
) {
    var document by remember(documentKey) { mutableStateOf<PdfDocument?>(null) }
    // 全屏：顶栏和底部标签一起隐藏，页面铺满整屏
    var fullscreen by rememberSaveable(documentKey) { mutableStateOf(false) }
    // 右侧页码滑块的状态（拖拽时显示页码气泡）
    var sliderDragging by remember { mutableStateOf(false) }
    var sliderPage by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var error by remember(documentKey) { mutableStateOf<String?>(null) }
    var scale by remember(documentKey) { mutableFloatStateOf(1f) }
    var offset by remember(documentKey) { mutableStateOf(Offset.Zero) }
    // 放大到 2 倍以上时换成高清版；退回 1.6 倍以下才换回来。
    // 两个阈值不同是刻意的：手指在 2.0 抖动时不会反复触发重绘。
    var hiRes by remember(documentKey) { mutableStateOf(false) }
    LaunchedEffect(scale) {
        hiRes = if (hiRes) scale > HI_RES_EXIT else scale > HI_RES_ENTER
    }
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
    // 按 2 倍屏宽出图。PdfRenderer 输出的是位图，放大只是把位图拉大：
    // 1 倍屏宽时放到 2.5 倍相当于插值 2.5 倍，字必然发虚；2 倍时只插值 1.25 倍，
    // 2.5 倍以内基本看不出模糊。代价是单页 RGB_565 约 13 MB，缓存已相应减到 2 页。
    // 横屏时屏宽会翻倍，不封顶的话单页位图会涨到 30 MB 以上（再加上高清档直接 OOM）。
    // 2600 px 足够覆盖 1080p / 1440p 手机横过来之后的显示宽度。
    val widthPx = minOf(
        with(density) { configuration.screenWidthDp.dp.roundToPx() * 2 },
        BASE_MAX_WIDTH_PX,
    )

    // ---- 横屏 ----
    // 进阅读页就放开方向限制（FULL_SENSOR）：手机横过来自动变横屏，
    // 即使系统里把「自动旋转」关了也生效 —— 因为这里直接改了 Activity 的期望方向。
    // 顶栏的按钮再给一个手动开关，方便把手机插在支架上时固定方向。
    val activity = LocalContext.current.findActivity()
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    DisposableEffect(documentKey) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose {
            // 离开阅读页恢复系统默认，不要把锁死的方向带出去
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 横屏自动全屏：横过来就是为了看图，顶栏和标签栏都让位给页面。
    // 只记「这次全屏是转屏自动进的」，转回竖屏时才自动退出；
    // 用户在横屏下手动按了圆叉退出，下一次转屏之前不会再被强行拉回全屏。
    var autoFullscreen by remember(documentKey) { mutableStateOf(false) }
    LaunchedEffect(isLandscape) {
        if (isLandscape) {
            if (!fullscreen) {
                fullscreen = true
                onFullscreenChange(true)
                autoFullscreen = true
            }
        } else if (autoFullscreen) {
            fullscreen = false
            onFullscreenChange(false)
            autoFullscreen = false
        }
    }

    LaunchedEffect(documentKey, file, widthPx) {
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
            // 转屏会改变渲染宽度，这里换新的渲染器；
            // 旧的必须先关，否则每次旋转都泄漏一个 PdfRenderer 和文件句柄
            val previous = document
            document = opened
            previous?.close()
        }
        result.onFailure { error = it.message ?: it.toString() }
    }

    DisposableEffect(documentKey) {
        onDispose {
            document?.close()
            // 离开阅读页时把底部标签栏恢复出来，否则用户会被困在全屏里
            if (fullscreen) onFullscreenChange(false)
        }
    }

    // 预渲染下一页，但加 250ms 防抖：
    // 拖滑块时会连续经过几十页，逐页预渲染等于把渲染线程占满，
    // 真正要看的那页反而排在后面。停下来再算下一页就够了。
    LaunchedEffect(document, invert) {
        val doc = document ?: return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.collectLatest { index ->
            delay(PREFETCH_DELAY_MS)
            withContext(Dispatchers.IO) {
                runCatching { doc.prefetch(index + 1, invert) }
            }
        }
    }

    // 全屏时把系统状态栏/导航栏也一起藏掉，否则页面顶部会压在状态栏底下
    val view = LocalView.current
    DisposableEffect(fullscreen) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen && controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            // 退出全屏或页面被销毁时恢复；没进过全屏时这条是无害的重置
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val pageCount = document?.pageCount ?: 0

    Scaffold(
        topBar = {
            // 全屏时整条顶栏不组合，页面直接顶到状态栏下面
            if (!fullscreen) TopAppBar(
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
                    TextButton(
                        onClick = {
                            activity?.requestedOrientation = if (isLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(if (isLandscape) "竖屏" else "横屏")
                    }
                    TextButton(
                        onClick = {
                            fullscreen = true
                            onFullscreenChange(true)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("全屏")
                    }
                    if (scale > 1.01f) {
                        TextButton(
                            onClick = {
                                scale = 1f
                                offset = Offset.Zero
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text("复位")
                        }
                    }
                    TextButton(
                        onClick = {
                            onToggleInvert()
                            scale = 1f
                            offset = Offset.Zero
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(if (invert) "反色开" else "反色关")
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
                        PdfPageItem(doc, index, invert, hiRes)
                    }
                }
            }

            // 右侧页码滑块：378 页的手册靠甩屏翻页找条目不现实
            if (doc != null && doc.pageCount > 1) {
                PageSlider(
                    pageCount = doc.pageCount,
                    currentPage = listState.firstVisibleItemIndex,
                    dragging = sliderDragging,
                    dragPage = sliderPage,
                    onDraggingChange = { sliderDragging = it },
                    // 拖动过程中只更新气泡上的页码，不发跳转请求
                    onPreview = { sliderPage = it },
                    // 松手（或点轨道）才真正跳一次，只渲染目标页
                    onCommit = { page ->
                        sliderPage = page
                        scope.launch { listState.scrollToItem(page) }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                        .width(SLIDER_WIDTH)
                        .fillMaxHeight()
                        .padding(vertical = 48.dp),
                )
            }

            // 拖滑块时把页码显示出来，不然不知道停在哪一页
            if (sliderDragging && doc != null) {
                Surface(
                    modifier = Modifier.align(Alignment.CenterStart).padding(16.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xCC000000),
                ) {
                    Text(
                        text = "第 " + (sliderPage + 1) + " / " + doc.pageCount + " 页",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            // 全屏时留一个退出口：顶栏已经隐藏，只剩系统返回键不够直观
            if (fullscreen) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    shape = CircleShape,
                    color = Color(0x66000000),
                ) {
                    IconButton(onClick = {
                        fullscreen = false
                        onFullscreenChange(false)
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "退出全屏",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

private val SLIDER_WIDTH = 28.dp
private val SLIDER_THUMB_HEIGHT = 44.dp

// 右侧页码滑块：按住上下拖动直接跳页，轻点轨道也能跳。
// 只服务于「章节多、页数大」的手册；只有一两页的文件不显示。
@Composable
private fun PageSlider(
    pageCount: Int,
    currentPage: Int,
    dragging: Boolean,
    dragPage: Int,
    onDraggingChange: (Boolean) -> Unit,
    onPreview: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = (if (dragging) dragPage else currentPage).coerceIn(0, pageCount - 1)
    val fraction = if (pageCount <= 1) 0f else shown.toFloat() / (pageCount - 1)

    BoxWithConstraints(
        modifier = modifier
            .pointerInput(pageCount) {
                var lastPage = -1
                fun preview(y: Float) {
                    val page = PageGeometry.pageAt(y, size.height.toFloat(), pageCount)
                    if (page != lastPage) {
                        lastPage = page
                        onPreview(page)
                    }
                }
                detectVerticalDragGestures(
                    onDragStart = { position ->
                        onDraggingChange(true)
                        preview(position.y)
                    },
                    onDragEnd = {
                        onDraggingChange(false)
                        if (lastPage >= 0) onCommit(lastPage)
                    },
                    onDragCancel = {
                        onDraggingChange(false)
                        if (lastPage >= 0) onCommit(lastPage)
                    },
                ) { change, _ ->
                    preview(change.position.y)
                    change.consume()
                }
            }
            .pointerInput(pageCount) {
                detectTapGestures { position ->
                    onCommit(PageGeometry.pageAt(position.y, size.height.toFloat(), pageCount))
                }
            },
    ) {
        Box(
            modifier = Modifier.align(Alignment.Center)
                .width(3.dp)
                .fillMaxHeight()
                .background(Color(0x22000000), RoundedCornerShape(2.dp)),
        )
        Box(
            modifier = Modifier.align(Alignment.TopCenter)
                .offset(
                    y = (maxHeight * fraction - SLIDER_THUMB_HEIGHT / 2)
                        .coerceIn(0.dp, maxHeight - SLIDER_THUMB_HEIGHT),
                )
                .width(6.dp)
                .height(SLIDER_THUMB_HEIGHT)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
private fun PdfPageItem(
    document: PdfDocument,
    index: Int,
    invert: Boolean,
    hiRes: Boolean,
) {
    var page by remember(document, index, invert) { mutableStateOf<RenderedPage?>(null) }
    var preview by remember(document, index, invert) { mutableStateOf<RenderedPage?>(null) }
    var renderError by remember(document, index, invert) { mutableStateOf<String?>(null) }

    LaunchedEffect(document, index, invert, hiRes) {
        // 两段式：正式分辨率没有缓存时，先出一张 1/4 宽的预览垫上，
        // 跳页后不至于整屏空白等几百毫秒；正式图算完再替换掉。
        // 已经显示了基准图、只是升级高清时不用再垫预览。
        if (page == null && !document.isCached(index, invert, hiRes)) {
            withContext(Dispatchers.IO) {
                runCatching { document.renderPreview(index, invert) }
            }.onSuccess { preview = it }
        }
        // 渲染必须离开主线程；命中缓存时这里是一次纯查表
        val result = withContext(Dispatchers.IO) {
            runCatching {
                Perf.timed("pdf_render_" + (index + 1)) {
                    document.render(index, invert, hiRes)
                }
            }
        }
        result.onSuccess { page = it }
        result.onFailure {
            renderError = it.message ?: it.toString()
            // 预览已经显示出来时，正式图失败也不用把整页清空
        }
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val rendered = page ?: preview
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

// Compose 里拿不到 Activity，只能顺着 ContextWrapper 往上找
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// 预渲染的防抖时长：滑得快时不断重置，停稳之后才真正渲染下一页
private const val PREFETCH_DELAY_MS = 250L

// 基准渲染宽度的上限：横屏时屏宽翻倍，不封顶会把内存吃穿
private const val BASE_MAX_WIDTH_PX = 2600

// 放大重绘的迟滞阈值：过 2 倍换高清，退回 1.6 倍以下才还原
private const val HI_RES_ENTER = 2.0f
private const val HI_RES_EXIT = 1.6f
