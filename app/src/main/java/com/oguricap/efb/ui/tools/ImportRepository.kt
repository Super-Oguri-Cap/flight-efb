package com.oguricap.efb.ui.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.oguricap.efb.diag.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.io.InputStreamReader

/**
 * 导航数据的导入仓库：记 Uri + 把文件流按块吐出来。
 *
 * 这一层**只做搬运**：不做解析、不做坐标编码、不做 base64。
 * 解析全在网页里（PackCodec.encode），App 侧保持「读文本 → 送 JS」一条直线，
 * 这样换新版本的 HTML 解析器时不需要动 Kotlin。
 *
 * @param context 任意 Context，内部只留 applicationContext，避免持有 Activity。
 */
class ImportRepository(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- Uri 记忆

    /**
     * 上次记住的 SAF Uri。这里存的**只是路径字符串**，不含任何文件内容；
     * 用户文件删除或授权失效时，调用方拿到的 Uri 会在 stream 时抛异常，按失败处理即可。
     */
    fun persistedUris(): List<Uri> {
        val raw = prefs.getString(KEY_URIS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<Uri>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotEmpty()) out += Uri.parse(s)
            }
            out
        }.getOrElse {
            // 存量数据被外部改坏就当作没记过，不阻塞导入
            AppLog.w(TAG, "导入记录解析失败，按空处理", it)
            emptyList()
        }
    }

    /**
     * 记住这批 Uri：先要持久读权限，再**整批覆盖**落盘。
     *
     * 为什么是覆盖而不是合并：用户换一份导航数据 = 换一个周期，旧周期的四个文件
     * 留在列表里没有任何意义，反而会在缓存失效时和新周期一起被自动重导 ——
     * 同一个航路点出现两套坐标、同一段航路出现两条记录，就是「第二份数据打架」。
     *
     * 为什么要 takePersistableUriPermission：
     *  SAF 给的读权限默认只活在本次进程/本次返回的数据里，重启后就没了。
     *  拿到「持久」授权后，用户下次进来不用重新选文件。
     * 为什么必须容错：
     *  部分 provider（第三方网盘、某些文件管理器）根本不支持持久授权，
     *  调用会抛 SecurityException；这时只是「记不住下次要重选」，
     *  本次导入仍然应该成功 —— 绝不能让它把整个导入流程炸掉。
     */
    fun remember(uris: List<Uri>) {
        if (uris.isEmpty()) return
        for (uri in uris) {
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure { AppLog.w(TAG, "持久授权失败（provider 可能不支持）：$uri", it) }
        }

        val arr = JSONArray()
        for (uri in uris) arr.put(uri.toString())
        // 记了文件夹就清掉散文件：两者是两种导入方式，同时留着会重复解析
        prefs.edit().putString(KEY_URIS, arr.toString()).remove(KEY_TREE).apply()
    }

    /** 记住用户选中的数据目录（SAF 树）。与 [remember] 互斥。 */
    fun rememberTree(treeUri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { AppLog.w(TAG, "目录持久授权失败：$treeUri", it) }
        prefs.edit().putString(KEY_TREE, treeUri.toString()).remove(KEY_URIS).apply()
    }

    fun treeUri(): Uri? = prefs.getString(KEY_TREE, null)
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** 只清 App 自己记的列表，不动用户的文件，也不主动 release 授权。 */
    fun clear() {
        prefs.edit().remove(KEY_URIS).remove(KEY_TREE).apply()
    }

    // ---------------------------------------------------------------- 展示名

    /**
     * 展示名：OpenableColumns.DISPLAY_NAME 最靠谱（SAF 文档 provider 都会填）。
     * 取不到就退到 lastPathSegment，再从路径里取最后一段，最后兜底 "file.dat"。
     */
    fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            app.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                }
        }.getOrNull()

        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: FALLBACK_NAME
    }

    // ---------------------------------------------------------------- 流式读取

    /**
     * 按块读文本，每块交给 [onChunk]；返回读到的总字符数。
     *
     * 三条硬约束：
     *  1. **绝不整份读**：earth_fix.dat 有 15 MB，readBytes/readText 会把整份内容
     *     同时压进 Java 字符串 + UTF-8 字节数组，峰值内存翻倍，低端机直接 OOM。
     *  2. **分块 192K 字符**：这块文本最终要经 evaluateJavascript 走 Binder 送给网页，
     *     单次约 1 MB 上限；块按字符算，ASCII 时 192K 字符 ≈ 192 KB 文本，
     *     再算上 JSON 转义与前后 JS 片段仍在安全区内。网页侧只管 append，不关心块边界。
     *  3. **不用 base64**：base64 会把体积放大约 1/3，还多一次编解码，
     *     而我们只需要「原样文本」—— JS 侧的 importAppend 直接收字符串。
     *
     * 回调是 suspend：调用方自己切主线程去 eval JS，IO 线程只负责读。
     */
    suspend fun stream(uri: Uri, onChunk: suspend (name: String, chunk: String) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val name = displayName(uri)
            val input = app.contentResolver.openInputStream(uri)
                ?: throw IOException("打不开这个文件：$name")
            input.use { raw ->
                InputStreamReader(raw, Charsets.UTF_8).use { reader ->
                    pump(name, reader, onChunk)
                }
            }
        }

    /**
     * 压缩包的扫描结果。
     *
     * @param names     实际读到的条目名（原始路径），供进度提示
     * @param chars     读出来的字符总量，用于「解析中 · N K 字符」这类进度文案
     * @param truncated 是否因为条目数/体积上限提前停止 —— 停止意味着后面的条目没送进去，
     *   网页侧很可能因此解析失败。调用方要能把这条真实原因告诉用户，
     *   否则用户只会看到一句笼统的「数据不可用」。
     */
    data class ZipScan(val names: List<String>, val chars: Long, val truncated: Boolean)

    /**
     * 直接读压缩包：只把 [accept] 认可的条目吐出来，其余跳过。
     *
     * 为什么在 App 侧解压而不是丢给网页：网页那条管线是**纯文本分块**的
     * （evaluateJavascript 只传 String），zip 是二进制，塞进去只会变成乱码。
     * 这里用 ZipInputStream 顺序扫一遍，边解边按 192K 字符分块送，内存里始终只有一块。
     */
    suspend fun streamZip(
        uri: Uri,
        accept: (entryName: String) -> Boolean,
        onChunk: suspend (name: String, chunk: String) -> Unit,
    ): ZipScan = withContext(Dispatchers.IO) {
        val raw = app.contentResolver.openInputStream(uri)
            ?: throw IOException("打不开压缩包：${displayName(uri)}")
        val names = ArrayList<String>()
        var total = 0L
        var truncated = false
        java.util.zip.ZipInputStream(java.io.BufferedInputStream(raw)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) { zis.closeEntry(); continue }
                if (!accept(entry.name)) { zis.closeEntry(); continue }
                if (names.size >= MAX_ZIP_ENTRIES || total >= MAX_ZIP_CHARS) {
                    AppLog.w(
                        TAG,
                        "压缩包条目/体积超限，提前停止：${entry.name}（已收 ${names.size} 条 / $total 字符）",
                    )
                    zis.closeEntry()
                    truncated = true
                    break
                }
                names += entry.name
                // 不能 close：关掉 Reader 会把整个 ZipInputStream 一起关掉，
                // 后面的条目就读不到了。读到 -1 正好是当前条目的结尾。
                // 名字用条目全路径：同名的 earth_fix.dat 出现在两个子目录时不会被拼成一份，
                // 网页侧的 classify() 会自己取最后一段做判断。
                val reader = InputStreamReader(zis, Charsets.UTF_8)
                total += pump(entry.name, reader, onChunk)
                zis.closeEntry()
            }
        }
        ZipScan(names, total, truncated)
    }

    /**
     * 把 Reader 里的字符按块吐出去。分块规则见 [stream] 的注释：
     * 192K 字符一块，处理 BOM 与跨块代理对。
     */
    private suspend fun pump(
        name: String,
        reader: java.io.Reader,
        onChunk: suspend (name: String, chunk: String) -> Unit,
    ): Long {
        var total = 0L
        var first = true
        // 上一块末尾那个被留下的高位代理，拼到下一块开头
        var carry: Char? = null

        val buf = CharArray(CHUNK_CHARS)
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            if (n == 0) continue

            val head = carry
            carry = null
            var text = String(buf, 0, n)
            if (head != null) text = head.toString() + text

            // BOM 只在最开头有意义；留在串里会让网页按行解析时第一行多一个不可见字符
            if (first && text.isNotEmpty() && text[0] == '\uFEFF') {
                text = text.substring(1)
            }
            first = false

            // 代理对（emoji / 生僻汉字）跨块会被劈成两个孤立 char，拼回去就不成字了。
            // 导航数据基本是 ASCII，但这里只留 1 个 char 的成本，不值得省。
            if (text.isNotEmpty() && Character.isHighSurrogate(text[text.length - 1])) {
                carry = text[text.length - 1]
                text = text.substring(0, text.length - 1)
            }

            if (text.isNotEmpty()) {
                total += text.length
                onChunk(name, text)
            }
        }

        // 文件末尾是畸形半个代理对时也不能丢字节，原样送出去让网页自己处理
        carry?.let {
            total += 1
            onChunk(name, it.toString())
        }
        return total
    }

    companion object {
        private const val TAG = "EFB/TOOL"

        private const val PREFS = "efb_tool_imports"
        private const val KEY_URIS = "uris"
        private const val KEY_TREE = "tree"
        private const val FALLBACK_NAME = "file.dat"

        /** 每块 192K 字符：见 [stream] 注释，受 Binder 单次 1 MB 传输上限约束。 */
        private const val CHUNK_CHARS = 192_000

        /** 压缩包防御：条目数与解压后字符总量的上限，避免误选大包把手机拖死。 */
        private const val MAX_ZIP_ENTRIES = 200
        private const val MAX_ZIP_CHARS = 120_000_000L
    }
}
