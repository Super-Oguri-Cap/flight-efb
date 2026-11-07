package com.oguricap.efb

import androidx.compose.ui.geometry.Offset
import com.oguricap.efb.ui.reader.PageGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

class PageGeometryTest {

    @Test
    fun fitsLandscapeChartToScreenWidth() {
        // 航图多为横向 A4：842 x 595
        val size = PageGeometry.fitWidth(1280, 842f, 595f)
        assertEquals(1280, size.width)
        assertEquals(904, size.height)
    }

    @Test
    fun fitsPortraitChartToScreenWidth() {
        val size = PageGeometry.fitWidth(1280, 595f, 842f)
        assertEquals(1280, size.width)
        assertEquals(1811, size.height) // 1280 * 842 / 595 向下取整
    }

    @Test
    fun keepsAspectRatioOfSquarePage() {
        val size = PageGeometry.fitWidth(600, 500f, 500f)
        assertEquals(600, size.width)
        assertEquals(600, size.height)
    }

    @Test
    fun heightNeverCollapsesBelowOnePixel() {
        val size = PageGeometry.fitWidth(100, 1000f, 0.5f)
        assertEquals(100, size.width)
        assertEquals(1, size.height)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveTargetWidth() {
        PageGeometry.fitWidth(0, 595f, 842f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositivePageSize() {
        PageGeometry.fitWidth(1280, 0f, 842f)
    }

    // 缩放后必须把位移钳制在内容范围内，否则整页可以被拖出屏幕再也拉不回来
    @Test
    fun offsetIsZeroWhenNotZoomed() {
        val result = PageGeometry.clampOffset(Offset(500f, 500f), 1f, 1080f, 1920f)
        assertEquals(Offset.Zero, result)
    }

    @Test
    fun offsetIsClampedToHalfTheOverflow() {
        // 放大 2 倍时，水平方向最多偏移 1080*(2-1)/2 = 540
        val result = PageGeometry.clampOffset(Offset(9999f, -9999f), 2f, 1080f, 1920f)
        assertEquals(540f, result.x, 0.01f)
        assertEquals(-960f, result.y, 0.01f)
    }

    @Test
    fun offsetInsideBoundsIsUnchanged() {
        val result = PageGeometry.clampOffset(Offset(100f, -200f), 3f, 1080f, 1920f)
        assertEquals(100f, result.x, 0.01f)
        assertEquals(-200f, result.y, 0.01f)
    }

    @Test
    fun offsetResetsWhenZoomReturnsToOne() {
        val result = PageGeometry.clampOffset(Offset(300f, 300f), 1.005f, 1080f, 1920f)
        assertEquals(Offset.Zero, result)
    }

    // 页码滑块：位置换算成页码，边界要卡死，不能让浮点误差跳出一页
    @Test
    fun sliderTopHitsFirstPage() {
        assertEquals(0, PageGeometry.pageAt(0f, 1000f, 378))
        assertEquals(0, PageGeometry.pageAt(-50f, 1000f, 378))
    }

    @Test
    fun sliderBottomHitsLastPage() {
        assertEquals(377, PageGeometry.pageAt(1000f, 1000f, 378))
        assertEquals(377, PageGeometry.pageAt(1200f, 1000f, 378))
    }

    @Test
    fun sliderMiddleIsProportional() {
        // 0.5 * 377 = 188.5，四舍五入到 189；0.5 * 9 = 4.5 → 5
        assertEquals(189, PageGeometry.pageAt(500f, 1000f, 378))
        assertEquals(5, PageGeometry.pageAt(500f, 1000f, 10))
    }

    @Test
    fun sliderDegenerateInputsDoNotCrash() {
        assertEquals(0, PageGeometry.pageAt(500f, 0f, 378))
        assertEquals(0, PageGeometry.pageAt(500f, 1000f, 1))
        assertEquals(0, PageGeometry.pageAt(500f, 1000f, 0))
    }
}
