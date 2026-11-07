package com.oguricap.efb.ui.reader

import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt

// 把 PDF 页面按屏幕宽度等比换算成位图尺寸。
// 抽成纯函数是为了能在 JVM 单元测试里验证换算规则，不依赖 PdfRenderer。
object PageGeometry {

    data class Size(val width: Int, val height: Int)

    // 滑块位置 → 目标页：y 在轨道内的比例换算成页码。
    // 抽成纯函数，JVM 上就能验证边界（顶部必为第 1 页、底部必为最后一页）。
    fun pageAt(y: Float, trackHeight: Float, pageCount: Int): Int {
        if (pageCount <= 1 || trackHeight <= 0f) return 0
        val ratio = (y / trackHeight).coerceIn(0f, 1f)
        return (ratio * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)
    }

    fun fitWidth(targetWidthPx: Int, pageWidth: Float, pageHeight: Float): Size {
        require(targetWidthPx > 0) { "目标宽度必须为正数，实际为 " + targetWidthPx }
        require(pageWidth > 0f && pageHeight > 0f) { "PDF 页面尺寸必须为正数" }
        val height = (targetWidthPx * pageHeight / pageWidth).toInt().coerceAtLeast(1)
        return Size(targetWidthPx, height)
    }

    // 缩放后允许平移的距离：内容比视口大出的部分的一半。
    // 不钳制的话可以把整页拖出屏幕，再也拉不回来。
    fun clampOffset(offset: Offset, scale: Float, viewportWidth: Float, viewportHeight: Float): Offset {
        if (scale <= 1.01f) return Offset.Zero
        val maxX = (viewportWidth * (scale - 1f)) / 2f
        val maxY = (viewportHeight * (scale - 1f)) / 2f
        return Offset(
            x = offset.x.coerceIn(-maxX, maxX),
            y = offset.y.coerceIn(-maxY, maxY),
        )
    }
}