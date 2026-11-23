package com.oguricap.efb.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.oguricap.efb.diag.AppLog
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class RenderedPage(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
)

// 单份 PDF 的渲染器。
//
// PdfRenderer 不是线程安全的：同一时刻只允许一个 Page 处于打开状态，
// 打开第二个时会抛 IllegalStateException("Current page not closed")。
// 页面渲染在 Dispatchers.IO 上并发执行，列表首屏会同时组合多页，
// 预取协程也会同时进入，因此所有对 PdfRenderer 的调用都必须串行化。
class PdfDocument(
    private val file: File,
    private val targetWidthPx: Int,
) {
    private val pfd: ParcelFileDescriptor
    private val renderer: PdfRenderer

    val pageCount: Int

    init {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                renderer = PdfRenderer(pfd)
            } catch (error: Throwable) {
                // 文件损坏、截断或不是 PDF 时 PdfRenderer 构造失败，
                // 此时 pfd 已经打开，必须先关掉再抛，否则句柄泄漏
                pfd.close()
                throw error
            }
            pageCount = renderer.pageCount
            // 登记到全局存活表：系统内存紧张时要能找到所有还开着的文档去清缓存。
            // 用强引用集合、靠 close() 注销，理由见 [liveDocuments] 的说明。
            synchronized(liveDocuments) { liveDocuments.add(this@PdfDocument) }
        }

    /**
     * 已置位的关闭标记。
     *
     * 用 [AtomicBoolean] 而不是普通布尔量：`close()` 可能同时从退出阅读页的主线程
     * 与 `onRenderProcessGone` 之类的位置进入，`if (closed) return; closed = true`
     * 这种两段式在并发下会两边都通过，释放任务被派发两次，第二次 `renderer.close()`
     * 抛 `IllegalStateException`。`compareAndSet` 保证只有一个调用者派发释放。
     *
     * 渲染协程在锁内读它，读到 true 就立刻抛取消，不再往正在关闭的渲染器上开新页。
     */
    private val closed = AtomicBoolean(false)

    /**
     * 释放渲染器用的作用域。
     *
     * 刻意不挂在 Composable 或 ViewModel 的生命周期上：关闭动作必须在页面被销毁、
     * 协程被取消之后仍然能执行完。`SupervisorJob` 让某一次释放失败也不影响其它文档。
     */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 缓存两种反色状态，上限按「两种状态各自能放当前页与预取页」计算，
    // 否则切换反色时会把另一状态整批挤出，表现为每次切都重新渲染
    private val cache = object : LruCache<Int, RenderedPage>(CACHE_PAGES * 2) {}

    // 放大到 2 倍以上时用的高清版：1.5 倍基准宽度，一张约 30 MB。
    // 只留 1 张 —— 放大时用户只会盯着一页看，翻页后旧的直接淘汰，
    // 这样内存上限是「基准 2 页 + 高清 1 页」，不会随页数增长。
    private val hiResCache = object : LruCache<Int, RenderedPage>(HIRES_CACHE_PAGES) {}

    // 低分辨率预览：1/4 屏宽，一张不到 1 MB，几十毫秒就能出。
    // 跳页时先垫上它，界面不会长时间空着等正式分辨率。
    private val previewCache = object : LruCache<Int, RenderedPage>(PREVIEW_CACHE_PAGES * 2) {}

    private val invertPaint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }

    // render 改成挂起函数，是为了能在「排队等渲染锁」的过程中被打断。
    // 拖页码滑块时列表会连续跳到很多页，每页都排着队等这把锁；
    // 如果等锁的协程不能取消，它们拿到锁后仍会把已经翻过去的那页画完，
    // 真正要看的那页只能排在后面 —— 表现就是「停下来以后还要转好几秒」。
    fun isCached(index: Int, invert: Boolean, hiRes: Boolean = false): Boolean {
        val store = if (hiRes) hiResCache else cache
        return store.get(cacheKey(index, invert)) != null
    }

    suspend fun renderPreview(index: Int, invert: Boolean): RenderedPage {
        require(index in 0 until pageCount) {
            "页码越界: " + index + "，本文件共 " + pageCount + " 页"
        }
        val key = cacheKey(index, invert)
        previewCache.get(key)?.let { return it }
        synchronized(this) {
            previewCache.get(key)?.let { return it }
            coroutineContext.ensureActive()
            if (closed.get()) throw kotlinx.coroutines.CancellationException("文档已关闭")
            val page = renderUncached(index, invert, previewWidthPx())
            previewCache.put(key, page)
            return page
        }
    }

    suspend fun render(index: Int, invert: Boolean, hiRes: Boolean = false): RenderedPage {
        require(index in 0 until pageCount) {
            "页码越界: " + index + "，本文件共 " + pageCount + " 页"
        }
        val store = if (hiRes) hiResCache else cache
        val widthPx = if (hiRes) hiResWidthPx() else targetWidthPx
        val key = cacheKey(index, invert)
        store.get(key)?.let { return it }
        synchronized(this) {
            store.get(key)?.let { return it }
            // 拿到锁先确认这次请求还有没有人要：滑走了就直接抛取消
            coroutineContext.ensureActive()
            if (closed.get()) throw kotlinx.coroutines.CancellationException("文档已关闭")
            val page = renderUncached(index, invert, widthPx)
            store.put(key, page)
            return page
        }
    }

    // 当前页显示完之后在后台把下一页算好，翻页时直接命中缓存。
    // 快速滑动时这次预渲染多半会被取消，同样是上面那把锁上的排队问题。
    suspend fun prefetch(index: Int, invert: Boolean) {
        if (index < 0 || index >= pageCount) return
        val key = cacheKey(index, invert)
        synchronized(this) {
            if (closed.get() || cache.get(key) != null) return
            coroutineContext.ensureActive()
            cache.put(key, renderUncached(index, invert))
        }
    }

    // 调用方必须已经持有锁。
    // PdfRenderer.Page.render() 只接受 ARGB_8888，给它 RGB_565 会抛
    // IllegalArgumentException("Unsupported pixel format")（真机实测）。
    // 缓存仍以 RGB_565 存放（省一半内存）：8888 渲染完成后一次性转换，
    // 反色在这个转换步骤里做，临时 8888 位图立刻回收。
    private fun renderUncached(
        index: Int,
        invert: Boolean,
        widthPx: Int = targetWidthPx,
    ): RenderedPage {
        renderer.openPage(index).use { page ->
            val size = PageGeometry.fitWidth(
                widthPx,
                page.width.toFloat(),
                page.height.toFloat(),
            )
            val width = size.width
            val height = size.height
            val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // PdfRenderer 只画内容不铺底色，先涂白，否则透明区域会变成黑块
                raw.eraseColor(Color.WHITE)
                page.render(raw, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val target = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                Canvas(target).drawBitmap(raw, 0f, 0f, if (invert) invertPaint else null)
                return RenderedPage(target, width, height)
            } finally {
                raw.recycle()
            }
        }
    }

    /**
     * 关闭文档。**调用方不会被阻塞**：这里只把 [closed] 置位并派发释放任务，
     * 真正的 `renderer.close()` / `pfd.close()` 在 IO 线程上等渲染锁收尾。
     *
     * 为什么要这样拆：`renderer.close()` 必须排在正在跑的渲染之后，而一次渲染
     * 是几十毫秒级的 PDF 光栅化。原来整段在 `synchronized` 里，从阅读页退出时
     * 主线程（`DisposableEffect.onDispose`）就得一直等到那一页画完 —— 真机上表现
     * 为返回键顿一下。
     *
     * 置位与释放分开还有一层作用：`closed` 一置位，所有还在排队的渲染请求拿到锁后
     * 立刻抛 `CancellationException`，不会再往一个正在关闭的渲染器上开新页。
     *
     * 释放任务跑在 [closeScope] 上，它不随 Composable 生命周期结束，所以
     * 「`LaunchedEffect` 被取消时 `PdfDocument` 仍然会被关掉」这条保证依然成立：
     * 取消路径里那次 `close()` 已经把释放任务交出去了，任务本身不会被一起取消。
     */
    /**
     * 系统内存紧张时主动腾位图。
     *
     * 只清「不会被立刻用到」的那两层：预览（1/4 分辨率，跳页时的垫图）
     * 与高清（3/2 基准宽，单张约 30 MB）。这两层清掉之后界面最多退回基准分辨率，
     * 不会白屏，重算也只是一次几十毫秒的渲染。
     *
     * @param aggressive true 时连基准缓存一起清（`TRIM_MEMORY_RUNNING_CRITICAL`），
     *   此时界面会退到「重新渲染当前页」，是拿流畅度换不 OOM 的取舍。
     *
     * 线程：本方法由 [EfbApplication] 收到 `onTrimMemory` 时在**主线程**调用。
     * LruCache 本身是线程安全的，但这里仍要避免与渲染协程同时改同一批位图，
     * 所以不在这里直接操作缓存，而是派发到 IO 上**取同一把渲染锁**再做 ——
     * 与 `render`/`prefetch`/`close` 用同一个 `synchronized(this)`，不会交错。
     * 已关闭的文档直接跳过，不去碰一个已经释放的渲染器。
     */
    fun trimCache(aggressive: Boolean) {
        if (closed.get()) return
        closeScope.launch {
            synchronized(this@PdfDocument) {
                if (closed.get()) return@synchronized
                previewCache.evictAll()
                hiResCache.evictAll()
                if (aggressive) cache.evictAll()
            }
        }
    }

    fun close() {
        // 先置位：不取锁，所以主线程不会被正在进行的渲染挡住。
        // compareAndSet 保证并发进来时只有一个调用者派发释放任务。
        if (!closed.compareAndSet(false, true)) return
        // 先注销再派发释放：注销是纯内存操作，主线程立刻完成，
        // 之后到来的 onTrimMemory 不会再碰这份正在关闭的文档
        synchronized(liveDocuments) { liveDocuments.remove(this) }
        closeScope.launch {
            synchronized(this@PdfDocument) {
                cache.evictAll()
                hiResCache.evictAll()
                previewCache.evictAll()
                runCatching { renderer.close() }
                    .onFailure { AppLog.w(TAG, "关闭 PdfRenderer 失败", it) }
                runCatching { pfd.close() }
                    .onFailure { AppLog.w(TAG, "关闭文件句柄失败", it) }
            }
        }
    }

    private fun cacheKey(index: Int, invert: Boolean): Int = index * 2 + if (invert) 1 else 0

    private fun previewWidthPx(): Int =
        (targetWidthPx / PREVIEW_DIVISOR).coerceAtLeast(MIN_PREVIEW_WIDTH)

    // 高清版按基准宽度的 1.5 倍出图：基准已是 2 倍屏宽，
    // 合起来能撑到 3 倍缩放仍然清晰（再往上就受内存限制了）
    private fun hiResWidthPx(): Int = minOf(
        targetWidthPx * HI_RES_NUMERATOR / HI_RES_DENOMINATOR,
        HI_RES_MAX_WIDTH_PX,
    )

    companion object {
        private const val TAG = "EFB/PDF"

        /**
         * 当前存活的所有文档。
         *
         * 用强引用集合，靠 `close()` 注销；构造函数里登记时 `this` 还没有任何外部强引用，
         * 弱引用的键在那一刻就可能被回收，登记等于没做（实测过：登记后计数不变）。
         * 漏调 `close()` 的风险由两件事兜住：阅读页的 `DisposableEffect` 一定会在退出时调，
         * 以及 `trimCache` 遇到已关闭文档会直接跳过，不会留下能用的悬空引用。
         *
         * 并发：登记/注销发生在构造与 `close()`（主线程或 IO 都可能），
         * 遍历发生在 `onTrimMemory`（主线程）。集合自身同步，遍历时先取快照。
         */
        private val liveDocuments: MutableSet<PdfDocument> =
            java.util.Collections.synchronizedSet(
                java.util.Collections.newSetFromMap(
                    java.util.IdentityHashMap<PdfDocument, Boolean>(),
                ),
            )

        /**
         * 系统内存紧张时的统一入口，由 `EfbApplication` 的 `onTrimMemory` 调用。
         *
         * @param aggressive 内存严重不足时连基准缓存一起清
         */
        fun trimAllCaches(aggressive: Boolean) {
            val snapshot = synchronized(liveDocuments) { liveDocuments.toList() }
            for (doc in snapshot) {
                runCatching { doc.trimCache(aggressive) }
                    .onFailure { AppLog.w(TAG, "清理位图缓存失败", it) }
            }
        }

        /** 当前登记在册的文档数，给诊断与测试用。 */
        fun liveDocumentCount(): Int = synchronized(liveDocuments) { liveDocuments.size }

        // 分辨率按 1.5 倍屏宽出图后单页内存翻了一倍多，缓存相应减到 2 页
        const val CACHE_PAGES = 2

        // 预览图是正式分辨率的 1/4，占内存很小，可以多留几张
        private const val PREVIEW_CACHE_PAGES = 6

        // 高清版只留一张：约 30 MB，翻页即淘汰
        private const val HIRES_CACHE_PAGES = 1
        private const val HI_RES_NUMERATOR = 3
        private const val HI_RES_DENOMINATOR = 2

        // 高清档的宽度上限：3200 px 下单页约 30 MB，再大就超出应用堆预算
        private const val HI_RES_MAX_WIDTH_PX = 3200
        private const val PREVIEW_DIVISOR = 4
        private const val MIN_PREVIEW_WIDTH = 200
    }
}