package com.oguricap.efb.ui.tools

import android.content.Context
import com.oguricap.efb.diag.AppLog
import java.io.File

/**
 * 解析结果的**本机缓存**。
 *
 * 为什么要有它：网页侧的数据集只在内存里，WebView 一销毁就没了。原来的做法是
 * 「只记 SAF Uri，每次进页面重新解析一遍」——25 MB 文本每次重来几十秒，
 * 用户体感就是「每次都要重新导入」，而且 Uri 授权一旦失效就直接解析失败。
 *
 * 现在的做法：导入成功后让网页把编码好的 pack 分块回传，写进应用私有目录
 * （`filesDir`，不进 APK、不进仓库、别的应用读不到），下次启动直接读回来应用。
 * 重新导入时会整份覆盖，所以「换数据」仍然只是「再导一次」。
 *
 * 文件是纯文本 JSON，含 NODES/SEGMENTS/AIRPORTS 三段编码串 + 上次生效的 CAAC 表，
 * 体积与导航数据规模同量级（数十 MB 上限），所以读写一律放 IO 线程。
 */
class DatasetCache(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    fun exists(): Boolean = file.isFile && file.length() > 0L

    fun sizeBytes(): Long = if (file.isFile) file.length() else 0L

    /** 读回缓存的 pack JSON；不存在或读失败返回 null，调用方回落成「按 Uri 重新导入」。 */
    fun read(): String? {
        if (!exists()) return null
        return runCatching { file.readText(Charsets.UTF_8) }
            .onFailure { AppLog.w(TAG, "数据集缓存读取失败：${file.length()} 字节", it) }
            .getOrNull()
    }

    /**
     * 整份覆盖写入。先写临时文件再 rename，避免写到一半被杀留下半个 JSON。
     *
     * @return 是否落盘成功。失败不抛：缓存是「省一次重新解析」的优化，
     *   写不进去不该打断导入流程，但调用方要能把「下次还得重解析」告诉用户。
     */
    fun write(text: String): Boolean = runCatching {
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            // rename 失败（跨分区/占用）就退回直接写
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
        true
    }.onFailure {
        AppLog.e(TAG, "数据集缓存写入失败", it)
        runCatching { File(file.parentFile, "$FILE_NAME.tmp").delete() }
    }.getOrDefault(false)

    fun clear() {
        runCatching { file.delete() }
        runCatching { File(file.parentFile, "$FILE_NAME.tmp").delete() }
    }

    companion object {
        private const val TAG = "EFB/CACHE"
        private const val FILE_NAME = "efb_dataset_cache.json"
    }
}
