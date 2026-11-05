package com.oguricap.efb

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TreePathsTest {

    private fun uri(authority: String, docId: String): Uri =
        Uri.parse("content://" + authority + "/tree/" + Uri.encode(docId))

    private fun resolve(authority: String, docId: String): TreePaths.Result =
        TreePaths.resolve(uri(authority, docId))

    private val external = "com.android.externalstorage.documents"

    @Test
    fun primaryVolumeRoot() {
        val result = resolve(external, "primary:")
        assertEquals(TreePaths.Result.Ok("/storage/emulated/0"), result)
    }

    @Test
    fun primaryVolumeSubdirectory() {
        val result = resolve(external, "primary:飞行/AIP航图库")
        assertEquals(TreePaths.Result.Ok("/storage/emulated/0/飞行/AIP航图库"), result)
    }

    @Test
    fun removableVolume() {
        val result = resolve(external, "1A2B-3C4D:飞行")
        assertEquals(TreePaths.Result.Ok("/storage/1A2B-3C4D/飞行"), result)
    }

    @Test
    fun otherProvidersAreRejectedInsteadOfInventingAPath() {
        // 下载、第三方文件管理器、网盘返回的 docId 不是「卷:路径」形式，
        // 直接拆分会编出 /storage/msf/1234 这种不存在的路径
        assertEquals(TreePaths.Result.UnsupportedProvider, resolve("com.android.providers.downloads.documents", "msf:1234"))
        assertEquals(TreePaths.Result.UnsupportedProvider, resolve("com.google.android.apps.docs.storage", "abc"))
    }

    @Test
    fun emptyVolumeIsUnparseable() {
        val result = resolve(external, ":")
        assertTrue(result is TreePaths.Result.Unparseable)
    }
}
