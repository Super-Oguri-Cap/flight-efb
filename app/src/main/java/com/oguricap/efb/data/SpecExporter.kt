package com.oguricap.efb.data

import android.content.Context
import android.os.Environment
import java.io.File

// 把随 APK 分发的规范文档写成手机公共下载目录里的一个 Markdown 文件。
// 航图库目录保持零写入：文档永远不写进航图库。
object SpecExporter {

    const val ASSET_NAME = "library_spec.md"
    const val FILE_NAME = "航图库结构与命名规范.md"

    fun export(context: Context): File {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        check(dir != null) { "取不到公共下载目录" }
        dir.mkdirs()
        val target = File(dir, FILE_NAME)
        target.writeText(text)
        return target
    }
}
