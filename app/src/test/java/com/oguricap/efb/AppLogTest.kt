package com.oguricap.efb

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.oguricap.efb.diag.AppLog
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 落盘日志：能不能写、写进去长什么样、关掉以后是不是真的不写、轮转会不会把磁盘吃满。
 *
 * 这些行为的共同点是「只在出事时才被用到」，平时没人验证 —— 所以必须有测试兜着。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLogTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AppLog.enabled = true
        AppLog.init(app)
        // init 自己会写一行「会话开始」，清掉它，让每个用例从空日志开始
        AppLog.clear()
    }

    @After
    fun tearDown() {
        AppLog.clear()
        AppLog.enabled = true
    }

    private fun allText(): String = AppLog.logFiles(app).joinToString("") { it.readText() }

    @Test
    fun entryIsWrittenToDiskWithLevelAndTag() {
        AppLog.i("EFB/TEST", "一条测试日志")
        AppLog.flush()

        val text = allText()
        assertTrue("应当落盘：$text", text.contains("一条测试日志"))
        assertTrue("应当带级别：$text", text.contains("I/EFB/TEST"))
        assertTrue("应当带时间戳：$text", Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""").containsMatchIn(text))
    }

    @Test
    fun throwableStackIsIncluded() {
        val error = IllegalStateException("抽屉打不开")
        AppLog.e("EFB/TEST", "操作失败", error)
        AppLog.flush()

        val text = allText()
        assertTrue("应当有消息：$text", text.contains("操作失败"))
        assertTrue("应当有异常首行：$text", text.contains("IllegalStateException: 抽屉打不开"))
        assertTrue("应当有调用栈：$text", text.contains("AppLogTest"))
    }

    @Test
    fun disabledLogIsNotWrittenToDisk() {
        AppLog.enabled = false
        AppLog.e("EFB/TEST", "关掉以后不该落盘")
        AppLog.flush()

        assertFalse("关闭后不应写入文件", allText().contains("关掉以后不该落盘"))
    }

    @Test
    fun filesAreRotatedAndTotalSizeStaysBounded() {
        // 每条约 1 KB，写 900 条 ≈ 900 KB，足够跨过 512 KB 的轮转阈值两次
        val payload = "y".repeat(1000)
        repeat(900) { AppLog.i("EFB/TEST", payload) }
        AppLog.flush()

        val files = AppLog.logFiles(app)
        assertTrue("文件数应当被限制在当前 + 归档之内，实际 ${files.size}：${files.map { it.name }}", files.size <= 3)
        assertTrue("总量不应当随写入无限增长", AppLog.sizeBytes(app) < 512L * 1024 * 3)
        // 轮转之后仍要能读到内容，别把新日志也一起丢掉了
        assertTrue("轮转后仍有最新内容", allText().contains(payload))
    }

    @Test
    fun clearRemovesEverythingAndKeepsWriting() {
        AppLog.i("EFB/TEST", "清空前")
        AppLog.flush()
        AppLog.clear()

        assertFalse("清空后不应残留旧内容", allText().contains("清空前"))

        AppLog.i("EFB/TEST", "清空后")
        AppLog.flush()
        assertTrue("清空后仍能继续写", allText().contains("清空后"))
    }

    @Test
    fun logFilesAreOrderedOldestFirst() {
        // 撑过一轮轮转，让归档文件出现
        val payload = "z".repeat(1000)
        AppLog.i("EFB/TEST", "最早的一条")
        repeat(700) { AppLog.i("EFB/TEST", payload) }
        AppLog.flush()

        val joined = allText()
        val first = joined.indexOf("最早的一条")
        assertTrue("最早的那条应当还在", first >= 0)
        // 导出是把各文件按「最旧归档 → 当前文件」拼起来，最早的内容必须排在前面
        assertTrue("导出顺序应当是时间顺序", first < joined.lastIndexOf(payload))
    }
}