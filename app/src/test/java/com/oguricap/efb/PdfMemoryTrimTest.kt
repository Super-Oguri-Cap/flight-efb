package com.oguricap.efb

import androidx.test.core.app.ApplicationProvider
import com.oguricap.efb.ui.reader.PdfDocument
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 位图缓存的存活登记与内存压力清理。
 *
 * 这条链路在真机上的失败方式是「用着用着被系统杀掉」：三档缓存叠起来几十兆，
 * 收到 `onTrimMemory` 却什么都不清，系统只能结束进程。
 *
 * 这里只验证**登记与注销**这一层（能关掉就必须能关掉、关掉之后不能再被清）：
 * 位图本身在 Robolectric 上是假的，真正清缓存的时机由真机验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfMemoryTrimTest {

    private val opened = ArrayList<PdfDocument>()

    @After
    fun tearDown() {
        // 收尾时把测试打开的文档都关掉，避免影响后续用例里的存活计数
        for (doc in opened) runCatching { doc.close() }
        opened.clear()
    }

    private fun samplePdf(): File {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        return File(ctx.cacheDir, "trim-sample.pdf").apply {
            if (!exists()) {
                ctx.assets.open("docs/a320_normal_checklist.pdf").use { input ->
                    outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun open(): PdfDocument =
        PdfDocument(samplePdf(), 1280).also { opened += it }

    @Test
    fun openedDocumentIsRegistered() {
        val before = PdfDocument.liveDocumentCount()
        open()
        // 只验证登记：Robolectric 的 PdfRenderer 是空实现，pageCount 恒为 0，
        // 页数必须在真机上才有意义，这里不做断言。
        assertEquals("打开后应登记一份", before + 1, PdfDocument.liveDocumentCount())
    }

    @Test
    fun closedDocumentIsUnregisteredImmediately() {
        val doc = open()
        val withDoc = PdfDocument.liveDocumentCount()
        doc.close()
        // 注销是同步做的（只有真正的 renderer/pfd 释放是异步），
        // 所以紧接着查计数就应该少一份 —— 否则 onTrimMemory 还会去碰一个正在关闭的文档
        assertEquals("关闭后应立即注销", withDoc - 1, PdfDocument.liveDocumentCount())
    }

    @Test
    fun closingTwiceIsSafeAndUnregistersOnce() {
        val doc = open()
        val withDoc = PdfDocument.liveDocumentCount()
        doc.close()
        doc.close()
        assertEquals("重复关闭不能重复扣减", withDoc - 1, PdfDocument.liveDocumentCount())
    }

    @Test
    fun trimAllCachesOnClosedDocumentDoesNotThrow() {
        val doc = open()
        doc.close()
        val afterClose = PdfDocument.liveDocumentCount()
        // 已关闭的文档在清理时应当被跳过，而不是去碰已释放的渲染器。
        // 这里同时验证「清理不会把已注销的文档重新登记回来」。
        PdfDocument.trimAllCaches(aggressive = false)
        PdfDocument.trimAllCaches(aggressive = true)
        assertEquals("清理不应改变存活登记", afterClose, PdfDocument.liveDocumentCount())
    }

    @Test
    fun trimCoversEveryLiveDocument() {
        val before = PdfDocument.liveDocumentCount()
        val a = open()
        val b = open()
        assertEquals("两份都应在册", before + 2, PdfDocument.liveDocumentCount())
        // 清理入口不抛异常即算通过：真正清了多少由真机的内存数字验收
        PdfDocument.trimAllCaches(aggressive = true)
        a.close()
        b.close()
        assertEquals("关完应当回到原值", before, PdfDocument.liveDocumentCount())
    }
}
