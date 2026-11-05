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
import java.io.File

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
    }

    private var closed = false

    // 缓存两种反色状态，上限按「两种状态各自能放当前页与预取页」计算，
    // 否则切换反色时会把另一状态整批挤出，表现为每次切都重新渲染
    private val cache = object : LruCache<Int, RenderedPage>(CACHE_PAGES * 2) {}

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

    fun render(index: Int, invert: Boolean): RenderedPage {
        require(index in 0 until pageCount) {
            "页码越界: " + index + "，本文件共 " + pageCount + " 页"
        }
        val key = cacheKey(index, invert)
        synchronized(this) {
            cache.get(key)?.let { return it }
            val page = renderUncached(index, invert)
            cache.put(key, page)
            return page
        }
    }

    // 当前页显示完之后在后台把下一页算好，翻页时直接命中缓存
    fun prefetch(index: Int, invert: Boolean) {
        if (index < 0 || index >= pageCount) return
        val key = cacheKey(index, invert)
        synchronized(this) {
            if (closed || cache.get(key) != null) return
            cache.put(key, renderUncached(index, invert))
        }
    }

    // 调用方必须已经持有锁。
    // PdfRenderer.Page.render() 只接受 ARGB_8888，给它 RGB_565 会抛
    // IllegalArgumentException("Unsupported pixel format")（真机实测）。
    // 缓存仍以 RGB_565 存放（省一半内存）：8888 渲染完成后一次性转换，
    // 反色在这个转换步骤里做，临时 8888 位图立刻回收。
    private fun renderUncached(index: Int, invert: Boolean): RenderedPage {
        renderer.openPage(index).use { page ->
            val size = PageGeometry.fitWidth(
                targetWidthPx,
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

    fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            cache.evictAll()
            renderer.close()
            pfd.close()
        }
    }

    private fun cacheKey(index: Int, invert: Boolean): Int = index * 2 + if (invert) 1 else 0

    companion object {
        const val CACHE_PAGES = 3
    }
}