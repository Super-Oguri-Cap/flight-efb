package com.oguricap.efb.diag

import android.content.Context
import android.os.Build
import android.util.Log
import com.oguricap.efb.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 落盘日志。原来全工程只有 `android.util.Log`，日志只活在 logcat 环形缓冲里 ——
 * 用户手机上出问题时既看不到也拿不出来，反馈只能靠口述。这里把同一份日志同时
 * 写进应用私有目录，设置页可以整份导出到用户自选的位置。
 *
 * **为什么放后台单线程写**：日志调用点大量落在主线程（Compose 的 update、桥回调），
 * 同步写盘会被 StrictMode 记一条主线程磁盘 IO，也会给滑动添抖动。调用线程只负责
 * 格式化（含堆栈），追加与轮转都在 `efb-log` 线程上做。
 *
 * **为什么每条都 flush**：进程被杀时缓冲区里的内容就没了，而日志最有价值的时刻
 * 恰好是崩溃前那几行。崩溃路径另外走 [flush] 屏障，确保排队中的条目全部落地。
 *
 * **体积封顶**：单文件 [MAX_FILE_BYTES]，最多 [MAX_ARCHIVES] 个归档，总量约 1.5 MB。
 * 轮转靠改名，导出时按「归档序号从大到小 → 当前文件」拼起来就是时间顺序。
 *
 * 未 [init] 时（单元测试、Application.onCreate 之前的极早期调用）自动降级成只进 logcat，
 * 不抛异常 —— 日志设施本身永远不能成为新的故障源。
 */
object AppLog {

    const val DIR_NAME = "logs"

    private const val TAG = "EFB/LOG"
    private const val ACTIVE_NAME = "efb.log"

    /** 单文件上限 512 KB：够记几千条，又不至于让导出文件大到打不开。 */
    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val MAX_ARCHIVES = 2

    /** 堆栈截断：一个递归炸掉的堆栈能有几十 KB，留前 8 KB 足够定位。 */
    private const val MAX_STACK_CHARS = 8_000

    private const val FLUSH_TIMEOUT_MS = 3_000L

    private val lock = Any()
    private var dir: File? = null
    private var writer: BufferedWriter? = null

    @Volatile
    var enabled: Boolean = true

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "efb-log").apply { isDaemon = true }
    }

    private val stamp = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /** 由 Application.onCreate 调用。重复调用只换开关与目录，不会把旧句柄留在半路。 */
    fun init(context: Context, enabled: Boolean = true) {
        this.enabled = enabled
        val next = File(context.filesDir, DIR_NAME)
        synchronized(lock) {
            if (dir != next) {
                closeWriter()
                dir = next
            }
        }
        i(TAG, "会话开始 · " + describeEnvironment())
    }

    /** 版本 + 机型 + 系统：日志头、崩溃记录、导出文件三处共用同一份口径。 */
    fun describeEnvironment(): String =
        "飞行EFB ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun d(tag: String, message: String, error: Throwable? = null) = log(Log.DEBUG, "D", tag, message, error)
    fun i(tag: String, message: String, error: Throwable? = null) = log(Log.INFO, "I", tag, message, error)
    fun w(tag: String, message: String, error: Throwable? = null) = log(Log.WARN, "W", tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(Log.ERROR, "E", tag, message, error)

    private fun log(priority: Int, level: String, tag: String, message: String, error: Throwable?) {
        // logcat 那一份始终照写：开关只决定要不要落盘，不影响 adb 排查
        if (error != null) Log.println(priority, tag, message + "\n" + Log.getStackTraceString(error))
        else Log.println(priority, tag, message)

        if (!enabled) return
        val target = synchronized(lock) { dir } ?: return
        val line = format(level, tag, message, error)
        // 格式化在调用线程做完（堆栈可能很长），排队之后就不再占用调用方时间
        runCatching { executor.execute { append(target, line) } }
    }

    private fun format(level: String, tag: String, message: String, error: Throwable?): String {
        val time = stamp.get()!!.format(Date())
        val thread = Thread.currentThread().name
        val head = "$time $level/$tag [$thread]: $message"
        if (error == null) return head
        val trace = Log.getStackTraceString(error).let {
            if (it.length > MAX_STACK_CHARS) it.take(MAX_STACK_CHARS) + "\n…（堆栈已截断）" else it
        }
        return head + "\n" + trace.prependIndent("    ")
    }

    private fun append(target: File, line: String) {
        synchronized(lock) {
            try {
                if (!target.isDirectory && !target.mkdirs()) return
                val active = File(target, ACTIVE_NAME)
                if (writer == null || active.length() >= MAX_FILE_BYTES) {
                    closeWriter()
                    if (active.length() >= MAX_FILE_BYTES) rotate(target, active)
                    writer = BufferedWriter(
                        OutputStreamWriter(FileOutputStream(active, true), Charsets.UTF_8),
                    )
                }
                writer?.apply {
                    write(line)
                    newLine()
                    flush()
                }
            } catch (t: Throwable) {
                // 写不进去（磁盘满、目录被清）就关掉句柄下次重试，绝不让日志把应用带崩
                Log.w(TAG, "写日志失败", t)
                closeWriter()
            }
        }
    }

    private fun rotate(target: File, active: File) {
        File(target, archiveName(MAX_ARCHIVES)).delete()
        for (i in MAX_ARCHIVES - 1 downTo 1) {
            File(target, archiveName(i)).renameTo(File(target, archiveName(i + 1)))
        }
        active.renameTo(File(target, archiveName(1)))
    }

    private fun closeWriter() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
    }

    /**
     * 阻塞到排队中的条目全部写盘。导出前与崩溃时必须调用，
     * 否则拿到的是缺了最后几行的日志 —— 而最后几行正是出事的地方。
     */
    fun flush() {
        await { runCatching { writer?.flush() } }
    }

    /** 清空全部运行日志（不含崩溃记录，那些由 [CrashGuard.clear] 管）。 */
    fun clear() {
        await {
            synchronized(lock) {
                closeWriter()
                dir?.let { d -> logFiles(d).forEach { runCatching { it.delete() } } }
            }
        }
    }

    private fun await(block: () -> Unit) {
        if (synchronized(lock) { dir } == null) return
        val done = CountDownLatch(1)
        val submitted = runCatching {
            executor.execute {
                try {
                    block()
                } finally {
                    done.countDown()
                }
            }
        }.isSuccess
        // 提交失败（executor 已关闭）就别等了，否则会白挂三秒
        if (submitted) runCatching { done.await(FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
    }

    /** 导出顺序：最旧的归档在前，当前文件在后。 */
    fun logFiles(context: Context): List<File> = logFiles(File(context.filesDir, DIR_NAME))

    private fun logFiles(target: File): List<File> {
        val out = ArrayList<File>(MAX_ARCHIVES + 1)
        for (i in MAX_ARCHIVES downTo 1) {
            val f = File(target, archiveName(i))
            if (f.isFile) out += f
        }
        val active = File(target, ACTIVE_NAME)
        if (active.isFile) out += active
        return out
    }

    fun sizeBytes(context: Context): Long = logFiles(context).sumOf { it.length() }

    private fun archiveName(index: Int) = "efb.$index.log"
}