package com.oguricap.efb.ui.tools

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.JavascriptInterface
import java.io.IOException

/**
 * 暴露给网页的 Java 桥（addJavascriptInterface 的名字见 [ToolContract.JS_INTERFACE]）。
 *
 * **线程模型**：所有 @JavascriptInterface 方法都在 WebView 的 JavaBridge 线程被调用，
 * 不是主线程。Compose 状态、Snackbar 文案、写盘这些都必须回主线程做，
 * 否则轻则状态更新丢帧/异常，重则直接崩。所以这里每个入口第一件事就是 post 到主线程。
 *
 * **R8 坑**：release 包开了 minify，R8 会把
 * `@JavascriptInterface` 标注的方法当成「没人调用」的私有实现去改名（a、b、c…），
 * 于是网页里的 `Android.exportJson(...)` 找不到方法 —— 表现为 debug 完全正常、
 * release 桥静默失效（不报错，回调不来）。因此 proguard-rules.pro 里必须 keep，见该文件。
 *
 * @param onDataApplied 网页解析完成后的回执（JSON 字符串），始终在主线程回调
 * @param onEvent       面向用户的提示（导出成功/失败等），始终在主线程回调
 */
class ToolsBridge(
    context: Context,
    onDataApplied: (String) -> Unit,
    onEvent: (String) -> Unit,
    onPickFiles: (String) -> Unit = {},
    onPlansChanged: () -> Unit = {},
    onPackSerialized: (String) -> Unit = {},
) {

    // 只留 applicationContext：WebView 生命周期比 Activity 长，持有 Activity 会泄漏
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // 参数不能与下面的同名方法撞名，一律另起字段名。
    // 这条不是洁癖：Kotlin 里 `foo()` 会优先解析成成员函数而不是 `foo` 属性的 invoke，
    // 一旦同名，`main.post { foo() }` 就变成「投递一个再次调用自己的 Runnable」——
    // 无限空转、回调永远不执行，而编译器一声不吭。
    private val applyData = onDataApplied
    private val emitEvent = onEvent
    private val onPickRequest = onPickFiles
    private val notifyPlansChanged = onPlansChanged
    private val onPack = onPackSerialized

    // 数据集回传缓冲。整份 pack 有几 MB，只能分块收，收齐再一次性交给 ViewModel。
    private var packBuffer: StringBuilder? = null

    // ---------------------------------------------------------------- 网页回调

    /**
     * 网页里三个隐藏的 `<input type=file>` 在 WebView 里点不开（没有 FileChooser 回调），
     * shim 会把它们的 `.click()` 改成调这里，按 kind 区分：
     * `nav`（导航数据）/ `aip`（CAAC 航路表）/ `pack`（本工具导出的数据包）。
     * 选完仍然走同一条分块注入管线，解析在页面里做。
     */
    @JavascriptInterface
    fun pickFiles(kind: String) {
        Log.i(TOOL_TAG, "网页请求选择文件 kind=$kind")
        main.post { onPickRequest(kind) }
    }

    // ---------------------------------------------------------------- 数据集缓存

    @JavascriptInterface
    fun packBegin(total: Int) {
        main.post { packBuffer = StringBuilder(if (total in 1..64_000_000) total else 1 shl 16) }
    }

    @JavascriptInterface
    fun packChunk(chunk: String) {
        main.post { packBuffer?.append(chunk) }
    }

    @JavascriptInterface
    fun packDone() {
        main.post {
            val text = packBuffer?.toString()
            packBuffer = null
            if (!text.isNullOrBlank()) onPack(text)
        }
    }

    /**
     * 页面结果区有变化（生成完方案、展开/复制卡片）。
     * shim 用 MutationObserver 盯住 #results，稳定 400ms 后调这里，App 自动读一次方案。
     */
    @JavascriptInterface
    fun onPlansChanged() {
        main.post { notifyPlansChanged() }
    }

    /** 网页 importCommit() 解析完成后的回执，payload 是 JSON 字符串，原样转交给 ViewModel。 */
    @JavascriptInterface
    fun onDataApplied(payload: String) {
        main.post { applyData(payload) }
    }

    /** 网页请求把一份 JSON 存到「下载/EFB」。Android 10+ 走 MediaStore，不需要任何存储权限。 */
    @JavascriptInterface
    fun exportJson(filename: String, json: String) {
        val safe = sanitize(filename)
        main.post { writeToDownloads(safe, json) }
    }

    /**
     * 预留入口：当前页面没有「保存」按钮，保存是 Kotlin 侧的独立动作。
     * 先留着，将来网页加保存按钮时可以零 Kotlin 改动接上。
     */
    @JavascriptInterface
    fun saveRoute(json: String) {
        Log.i(TOOL_TAG, "saveRoute $json")
        main.post { emitEvent("保存请用底部按钮") }
    }

    @JavascriptInterface
    fun log(level: String, message: String) {
        Log.i(JS_TAG, "$level $message")
    }

    // ---------------------------------------------------------------- 写盘

    /**
     * 写到系统「下载」集合的 EFB 子目录。
     *
     * 用 MediaStore 而不是直接拼 /sdcard 路径：
     *  - 分区存储下 App 写不到公共目录的裸路径；
     *  - Downloads 集合在 Android 10+ 由系统代管，不需要 WRITE_EXTERNAL_STORAGE；
     *  - IS_PENDING 先占位再写完，避免用户看到半份文件。
     */
    private fun writeToDownloads(name: String, json: String) {
        val resolver = app.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, MIME_JSON)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EFB")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        var target: Uri? = null
        try {
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)
                ?: throw IOException("系统没有给出下载条目")
            target = uri

            val out = resolver.openOutputStream(uri)
                ?: throw IOException("打不开下载条目")
            out.use { it.write(json.toByteArray(Charsets.UTF_8)) }

            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)

            Log.i(TOOL_TAG, "已导出 $name（${json.length} 字符）")
            emitEvent("已导出 $name")
        } catch (t: Throwable) {
            Log.e(TOOL_TAG, "导出 $name 失败", t)
            // 占位条目留着会变成 0 字节的幽灵文件，删掉
            runCatching { target?.let { resolver.delete(it, null, null) } }
            emitEvent("导出失败：" + (t.message ?: t.javaClass.simpleName))
        }
    }

    /**
     * 文件名基本清洗：去掉路径分隔符（否则 RELATIVE_PATH/DISPLAY_NAME 语义会错乱），
     * 空白兜底成 export.json。不做更激进的字符替换 —— 中文名是合法且好认的。
     */
    private fun sanitize(filename: String): String {
        val trimmed = filename.trim()
            .replace('/', '_')
            .replace('\\', '_')
            .replace('\u0000', '_')
        return trimmed.ifBlank { DEFAULT_NAME }
    }

    companion object {
        private const val TOOL_TAG = "EFB/TOOL"
        private const val JS_TAG = "EFB/JS"

        private const val MIME_JSON = "application/json"
        private const val DEFAULT_NAME = "export.json"
    }
}
