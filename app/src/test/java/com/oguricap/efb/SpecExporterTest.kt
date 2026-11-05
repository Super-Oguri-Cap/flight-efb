package com.oguricap.efb

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.oguricap.efb.data.SpecExporter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpecExporterTest {

    @Test
    fun exportedFileContainsTheWholeSpec() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = SpecExporter.export(context)

        assertTrue("导出文件必须存在", file.isFile)
        assertTrue("文件名应为中文规范名", file.name == SpecExporter.FILE_NAME)

        val text = file.readText()
        assertTrue("应当包含结构说明", text.contains("整体结构"))
        assertTrue("应当包含机场目录规则", text.contains("一级目录：机场"))
        assertTrue("应当包含图型目录规则", text.contains("二级目录：图型"))
        assertTrue("应当包含十种图型", text.contains("ATCSMAC"))
        assertTrue("应当包含常见问题", text.contains("常见问题"))
        assertTrue("应当说明平铺目录无法使用", text.contains("平铺目录"))
    }

    @Test
    fun exportedContentMatchesAsset() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val asset = context.assets.open(SpecExporter.ASSET_NAME)
            .bufferedReader().use { it.readText() }
        val file = SpecExporter.export(context)
        assertTrue("导出内容必须与 assets 一致", asset == file.readText())
    }
}
