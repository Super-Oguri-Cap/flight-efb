package com.oguricap.efb

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.oguricap.efb.diag.AppLog
import com.oguricap.efb.diag.CrashGuard
import com.oguricap.efb.diag.LogExporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * 崩溃记录与日志导出。
 *
 * 导出这条链路最容易「看起来做了、实际写不进去」—— 选择器返回的 Uri、写入模式、
 * 拼接顺序，任何一处错了都要等用户真的出问题才发现。所以逐条钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogExportTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AppLog.enabled = true
        AppLog.init(app)
        AppLog.clear()
        CrashGuard.clear(app)
    }

    @After
    fun tearDown() {
        AppLog.clear()
        CrashGuard.clear(app)
        AppLog.enabled = true
    }

    // ---------------------------------------------------------------- 崩溃记录

    @Test
    fun crashFileCarriesTimeThreadAndStack() {
        val file = CrashGuard.recordCrash(app, "main", IllegalStateException("测试崩溃"))

        assertNotNull("应当写出崩溃文件", file)
        val text = file!!.readText()
        assertTrue("应当有标题：$text", text.contains("飞行EFB 崩溃记录"))
        assertTrue("应当有异常首行：$text", text.contains("IllegalStateException: 测试崩溃"))
        assertTrue("应当记下线程：$text", text.contains("线程：main"))
        assertTrue("应当记下环境：$text", text.contains("飞行EFB " + BuildConfig.VERSION_NAME))
        assertTrue("应当有调用栈：$text", text.contains("LogExportTest"))
    }

    @Test
    fun lastCrashSummarisesNewestRecord() {
        CrashGuard.recordCrash(app, "main", IllegalStateException("第一次"))
        CrashGuard.recordCrash(app, "main", IllegalArgumentException("第二次"))

        val summary = CrashGuard.lastCrash(app)
        assertNotNull(summary)
        assertTrue("摘要应当取最近一次：$summary", summary!!.contains("第二次"))
    }

    @Test
    fun onlyTheMostRecentCrashesAreKept() {
        repeat(8) { index ->
            CrashGuard.recordCrash(app, "main", RuntimeException("第 $index 次"))
        }
        assertEquals("崩溃记录应当有上限", 5, CrashGuard.crashFiles(app).size)
        assertTrue(
            "留下的应当是最新的那几份",
            CrashGuard.crashFiles(app).last().readText().contains("第 7 次"),
        )
    }

    @Test
    fun crashIsNotRecordedWhenLoggingIsOff() {
        AppLog.enabled = false
        assertNull(CrashGuard.recordCrash(app, "main", IllegalStateException("不该记")))
        assertEquals(0, CrashGuard.crashFiles(app).size)
    }

    // ---------------------------------------------------------------- 导出

    @Test
    fun suggestedNameIsTimestampedAndTxt() {
        val name = LogExporter.suggestedName(0L)
        assertTrue("应当带中文前缀与时间戳：$name", Regex("""^EFB日志-\d{8}-\d{6}\.txt$""").matches(name))
    }

    @Test
    fun reportPutsCrashesBeforeLogsAndIncludesBoth() {
        AppLog.i("EFB/TEST", "运行日志标记")
        AppLog.flush()
        CrashGuard.recordCrash(app, "main", IllegalStateException("崩溃标记"))

        val report = LogExporter.buildReport(app)
        assertTrue("应当有标题", report.contains("飞行EFB 运行日志"))
        assertTrue("应当有环境信息", report.contains("飞行EFB " + BuildConfig.VERSION_NAME))
        assertTrue("应当含崩溃内容", report.contains("崩溃标记"))
        assertTrue("应当含运行日志", report.contains("运行日志标记"))
        assertTrue(
            "崩溃段应当排在运行日志段之前",
            report.indexOf("崩溃记录（") < report.indexOf("运行日志（"),
        )
    }

    @Test
    fun reportExplainsWhenThereIsNothingToShow() {
        val report = LogExporter.buildReport(app)
        assertTrue("空崩溃要有说明", report.contains("没有崩溃记录"))
        assertTrue("空日志要有说明", report.contains("没有运行日志"))
    }

    @Test
    fun exportWritesIntoTheUriUserPicked() {
        val uri = Uri.parse("content://efb-test/log.txt")
        val sink = ByteArrayOutputStream()
        // registerOutputStream 是 shadow 的实例方法（4.16 起不再是静态的）
        Shadows.shadowOf(app.contentResolver).registerOutputStream(uri, sink)

        AppLog.i("EFB/TEST", "导出标记")
        AppLog.flush()

        val shownName = LogExporter.export(app, uri)

        val written = sink.toString("UTF-8")
        assertTrue("应当真的写进目标 Uri：$written", written.contains("导出标记"))
        assertEquals("返回给界面的应当是文件名", "log.txt", shownName)
    }
}