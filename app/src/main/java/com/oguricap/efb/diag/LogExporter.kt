package com.oguricap.efb.diag

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把运行日志与崩溃记录拼成一份纯文本，写到**用户自己选的位置**。
 *
 * 位置由系统的「创建文档」选择器给出（SAF `ACTION_CREATE_DOCUMENT`）：
 * 内部存储、SD 卡、网盘都行，文件名也能当场改。不固定写 Download ——
 * 固定目录在分区存储下要么写不进去，要么用户根本找不到。
 *
 * 用纯文本而不是 zip：日志总量本来就封顶在 1.5 MB 左右，
 * 手机上直接点开就能看，发给别人也不用先解压。
 */
object LogExporter {

    const val MIME_TYPE = "text/plain"

    /** 选择器里预填的文件名，带时间戳，多次导出不会互相覆盖。 */
    fun suggestedName(at: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
        return "EFB日志-$stamp.txt"
    }

    /**
     * 写入目标 Uri，返回无权限/打不开时抛出的异常由调用方转成界面提示。
     *
     * `"wt"` 而不是 `"w"`：用户可能选中一个已存在的同名文件，
     * 只写不截断的话旧内容会留在尾部，看起来像日志错乱。
     */
    fun export(context: Context, uri: Uri): String {
        AppLog.flush()
        val report = buildReport(context)
        val resolver = context.contentResolver
        val out = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("打不开所选位置，请换一个目录再试")
        out.use { it.write(report.toByteArray(Charsets.UTF_8)) }
        val name = displayName(context, uri)
        AppLog.i(TAG, "日志已导出：$name（${report.length} 字符）")
        return name
    }

    /** 组装整份报告。读盘量大，必须在 IO 线程调用。 */
    fun buildReport(context: Context): String {
        val crashes = CrashGuard.crashFiles(context)
        val logs = AppLog.logFiles(context)
        val shown = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("飞行EFB 运行日志")
            appendLine("导出时间：$shown")
            appendLine("环境：" + AppLog.describeEnvironment())
            appendLine("内容：运行日志与崩溃堆栈，可能含文件路径与航路点串；不含航图、手册与导航数据文件。")
            appendLine()

            appendLine("==================== 崩溃记录（${crashes.size} 份） ====================")
            if (crashes.isEmpty()) {
                appendLine("（没有崩溃记录）")
            } else {
                crashes.forEach { f -> appendSection(f) }
            }
            appendLine()

            val bytes = logs.sumOf { it.length() }
            appendLine("==================== 运行日志（${logs.size} 个文件 · ${bytes / 1024} KB） ====================")
            if (logs.isEmpty()) {
                appendLine("（没有运行日志，可能已在设置里关闭记录或刚被清空）")
            } else {
                logs.forEach { f -> appendSection(f) }
            }
        }
    }

    private fun StringBuilder.appendSection(file: File) {
        appendLine()
        appendLine("---------- ${file.name} ----------")
        // 单个文件读失败（被外部清掉、权限变了）只标注一行，不能让整份导出失败
        val text = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { "（读取失败：${it.message ?: it.javaClass.simpleName}）" }
        appendLine(text.trimEnd())
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "日志.txt"
    }

    private const val TAG = "EFB/LOG"
}