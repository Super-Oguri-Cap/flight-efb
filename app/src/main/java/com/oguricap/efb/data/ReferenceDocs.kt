package com.oguricap.efb.data

import android.content.Context
import java.io.File

// 随 APK 分发的 PDF 原件（用户自己的检查单），不进航图索引，
// 只做「原样打开」：缩放、反色、翻页都复用航图阅读页那套手势。
data class ReferenceDoc(
    val id: String,
    val title: String,
    val subtitle: String,
    val note: String,
    // assets 下的相对路径，文件名保持 ASCII，避免打包后路径编码问题
    val asset: String,
    // 原件换了内容就把 revision 加一，下次打开会重新从 assets 复制
    val revision: String,
)

object ReferenceDocs {

    val all: List<ReferenceDoc> = listOf(
        ReferenceDoc(
            id = "a320-normal",
            title = "A320 正常检查单（原件）",
            subtitle = "PDF 原件 · 文字版在机型列表里，可逐条勾选",
            note = "按下发版本原样收录，用于与文字版对照。",
            asset = "docs/a320_normal_checklist.pdf",
            revision = "1",
        ),
        ReferenceDoc(
            id = "juneyao-qrh",
            title = "吉祥航空快速检查单",
            subtitle = "全机队版 · 快速检查单手册",
            note = "整机队 QRH 手册，条目多，建议先看目录再跳页。",
            asset = "docs/juneyao_qrh.pdf",
            revision = "1",
        ),
    )

    fun get(id: String): ReferenceDoc? = all.firstOrNull { it.id == id }

    // PdfRenderer 只能读真实文件，assets 里的条目得先落到私有目录。
    // 复制走 .part 临时文件再改名：中途被杀不会留下半个 PDF，
    // 否则下次打开会一直报「文件损坏」。
    fun materialize(context: Context, doc: ReferenceDoc): File {
        // 这两个 PDF 按 .gitignore 的规则不入库（体积大且是第三方手册），
        // 换机器 clone 后如果忘了放回来，这里给出能直接照做的提示，
        // 而不是抛一个只有文件名的 FileNotFoundException
        if (!assetExists(context, doc.asset)) {
            throw IllegalStateException(
                "APK 里没有收录「" + doc.title + "」。" +
                    "请把 PDF 放到 app/src/main/assets/docs/ 后重新打包" +
                    "（可用 tools/import_docs.py 从原路径导入）。",
            )
        }
        val dir = File(context.filesDir, "docs")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("无法创建缓存目录：" + dir.absolutePath)
        }
        val target = File(dir, doc.asset.substringAfterLast('/'))
        val expected = assetSize(context, doc)

        if (target.isFile) {
            val usable = if (expected > 0) {
                target.length() == expected
            } else {
                stampOf(dir, target.name) == doc.revision
            }
            if (usable) return target
            target.delete()
        }

        val temp = File(dir, target.name + ".part")
        temp.delete()
        context.assets.open(doc.asset).use { input ->
            temp.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        writeStamp(dir, target.name, doc.revision)
        return target
    }

    private fun assetExists(context: Context, asset: String): Boolean =
        runCatching { context.assets.open(asset).close() }.isSuccess

    // 压缩存放的 asset 拿不到 openFd（会抛 FileNotFoundException），
    // 这时退回 revision 戳记判断，不影响正确性
    private fun assetSize(context: Context, doc: ReferenceDoc): Long =
        runCatching { context.assets.openFd(doc.asset).use { it.length } }.getOrDefault(-1L)

    private fun stampFile(dir: File, name: String) = File(dir, name + ".stamp")

    private fun stampOf(dir: File, name: String): String =
        runCatching { stampFile(dir, name).readText() }.getOrDefault("")

    private fun writeStamp(dir: File, name: String, revision: String) {
        runCatching { stampFile(dir, name).writeText(revision) }
    }

    private const val COPY_BUFFER = 64 * 1024
}
