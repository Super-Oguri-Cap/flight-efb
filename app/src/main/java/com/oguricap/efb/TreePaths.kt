package com.oguricap.efb

import android.net.Uri
import android.provider.DocumentsContract

// 把 SAF 选择结果还原成 /sdcard 下的真实路径。
// 只用于让用户指认目录，实际读取一律走 File。
object TreePaths {

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    sealed interface Result {
        data class Ok(val path: String) : Result
        // 选择器返回的不是「内部存储」，docId 的形态无法还原成路径
        data object UnsupportedProvider : Result
        data object Unparseable : Result
    }

    fun resolve(uri: Uri): Result {
        if (uri.authority != EXTERNAL_STORAGE) return Result.UnsupportedProvider
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return Result.Unparseable
        val parts = docId.split(":")
        val volume = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return Result.Unparseable
        val relative = parts.getOrNull(1).orEmpty()
        val base = when (volume) {
            "primary" -> "/storage/emulated/0"
            else -> "/storage/" + volume
        }
        val path = if (relative.isBlank()) base else base + "/" + relative
        return Result.Ok(path)
    }
}
