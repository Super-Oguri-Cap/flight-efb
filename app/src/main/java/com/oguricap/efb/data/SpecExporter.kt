package com.oguricap.efb.data

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.IOException

// 把随 APK 分发的规范文档写成手机公共下载目录里的一个 Markdown 文件。
// 航图库目录保持零写入：文档永远不写进航图库。
object SpecExporter {

    const val ASSET_NAME = "library_spec.md"
    const val FILE_NAME = "航图库结构与命名规范.md"

    fun export(context: Context): File {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        // 这个 API 声明为不返回 null，但旧实现有 @Nullable 的历史包袱，保留兜底
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("取不到公共下载目录")
        // mkdirs() 在「目录已存在」时也返回 false，所以判定要看 isDirectory 而不是返回值；
        // 建不出来时明确报错，否则接下来的 writeText 只会抛一句看不出原因的 ENOENT
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("建不了下载目录：" + dir.absolutePath)
        }
        val target = File(dir, FILE_NAME)
        target.writeText(text)
        return target
    }
}
