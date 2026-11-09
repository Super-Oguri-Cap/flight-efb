package com.oguricap.efb.diag

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 未捕获异常的最后一道记录。
 *
 * 没有它的时候，应用闪退后用户手上什么都没有：logcat 缓冲早被冲掉，
 * 反馈只能是「它突然没了」。这里在进程结束前把堆栈同步写进私有目录，
 * 下次启动设置页就能看到「上次异常退出」，并能连同运行日志一起导出。
 *
 * **不做自动重启**：重启循环比一次干净的闪退更难排查，也可能在飞行中反复打断操作。
 * 记录完就把异常交回原来的 handler，让系统按正常流程结束进程。
 *
 * **同步写盘**：此刻 AppLog 的后台线程随时会跟着进程一起消失，
 * 所以崩溃文件不走队列，直接在当前线程写完；运行日志那一份再调 [AppLog.flush] 补齐。
 */
object CrashGuard {

    private const val TAG = "EFB/CRASH"
    private const val DIR_NAME = "crash"
    private const val MAX_FILES = 5

    fun install(context: Context) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        // 幂等：Robolectric 会在同一个 JVM 里反复创建 Application，
        // 不挡住的话 handler 会一层层套娃，栈越来越深
        if (current is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(context.applicationContext, current))
    }

    fun crashDir(context: Context): File =
        File(File(context.filesDir, AppLog.DIR_NAME), DIR_NAME)

    /** 导出顺序：旧的在前。 */
    fun crashFiles(context: Context): List<File> =
        crashDir(context).listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * 最近一次崩溃的摘要（时间 + 异常首行），没有记录时返回 null。
     * 文件名带时间戳，按名字排序就是按时间排序，不必再读一遍内容。
     */
    fun lastCrash(context: Context): String? {
        val newest = crashFiles(context).lastOrNull() ?: return null
        return runCatching {
            val lines = newest.readText(Charsets.UTF_8).lineSequence()
            val time = lines.firstOrNull { it.startsWith("时间：") }?.removePrefix("时间：")
            val cause = lines.firstOrNull { it.startsWith("异常：") }?.removePrefix("异常：")
            listOfNotNull(time, cause).joinToString(" · ").ifBlank { newest.name }
        }.getOrNull()
    }

    fun clear(context: Context) {
        crashFiles(context).forEach { runCatching { it.delete() } }
    }

    /**
     * 记一次崩溃：先补运行日志，再同步写一份独立的崩溃文件。返回写出的文件；
     * 日志被关掉或写盘失败时返回 null。
     *
     * 单独暴露出来是为了能在单元测试里验证文件格式与裁剪逻辑 ——
     * 走真的 uncaughtException 会把测试进程自己也结束掉。
     */
    fun recordCrash(context: Context, threadName: String, error: Throwable): File? {
        if (!AppLog.enabled) return null
        val now = Date()
        // 先补运行日志：崩溃前那几行上下文和堆栈在一起才有用
        AppLog.e(TAG, "未捕获异常（线程 $threadName）", error)
        AppLog.flush()

        // 目录都建不出来就没什么可写的了；不要把它放进 runCatching 里靠非局部 return 跳出去，
        // 那种写法在 inline lambda 里能过编译，但读代码的人很难一眼看明白返回点在哪
        val dir = crashDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) return null

        return runCatching {
            // 带毫秒：两个崩溃落在同一秒时会互相覆盖，把真正的第一现场盖掉；
            // 名字按字典序排列依然等于时间顺序，裁剪与导出都依赖这一点
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(now)
            val target = File(dir, "crash-$stamp.txt")
            val shown = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
            target.writeText(
                buildString {
                    appendLine("飞行EFB 崩溃记录")
                    appendLine("时间：$shown")
                    appendLine("环境：" + AppLog.describeEnvironment())
                    appendLine("线程：$threadName")
                    appendLine("异常：${error.javaClass.name}: ${error.message ?: ""}")
                    appendLine()
                    appendLine(Log.getStackTraceString(error))
                },
                Charsets.UTF_8,
            )
            trim(dir)
            target
        }.getOrNull()
    }

    /** 只留最近 [MAX_FILES] 份：崩溃记录单份可能几 KB，无上限会慢慢吃掉私有目录。 */
    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedBy { it.name }
            .orEmpty()
        files.take((files.size - MAX_FILES).coerceAtLeast(0)).forEach { runCatching { it.delete() } }
    }

    private class Handler(
        private val app: Context,
        private val next: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, error: Throwable) {
            // 记录失败也不能挡住后面的进程结束流程，否则应用会挂在一个死掉的主线程上
            runCatching { recordCrash(app, thread.name, error) }
            if (next != null) {
                next.uncaughtException(thread, error)
            } else {
                // Android 上默认 handler 一定存在；纯 JVM（测试）下可能为 null，
                // 这里必须自己结束进程，不然只死一条线程、进程僵在那儿
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}