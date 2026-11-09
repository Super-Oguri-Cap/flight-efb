package com.oguricap.efb

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.oguricap.efb.ui.tools.DataFolder
import com.oguricap.efb.ui.tools.DataRole
import com.oguricap.efb.ui.tools.ImportRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「导入整个 zip」这条路。
 *
 * 1.3.11 之前的文件选择器把 .zip 当 UTF-8 文本读，二进制进解析器只会得到
 * 「数据未生效：数据不可用」。这里钉住两件事：压缩包能被认出来，
 * 以及解压时只吐出需要的那几个条目。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZipImportTest {

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun zipIsRecognisedByFileName() {
        assertEquals(DataRole.ZIP, DataFolder.roleOf("navdata.zip"))
        assertEquals(DataRole.ZIP, DataFolder.roleOf("Custom Data/NAV.ZIP"))
        // 目录里的普通文件不能被误判成压缩包
        assertEquals(DataRole.NAV_FIX, DataFolder.roleOf("nav/earth_fix.dat"))
    }

    @Test
    fun streamZipEmitsOnlyAcceptedEntries() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val bytes = zipOf(
            "nav/earth_fix.dat" to "I\n1201 A 40.0 116.0\n",
            "nav/earth_hold.dat" to "用不到的文件",
            "readme.txt" to "也不是导航数据",
        )
        val uri = Uri.parse("content://efb-test/navdata.zip")
        Shadows.shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))

        val seen = StringBuilder()
        val scan = ImportRepository(app).streamZip(uri, { it.endsWith("earth_fix.dat") }) { name, chunk ->
            seen.append(name).append('|').append(chunk)
        }

        assertEquals(listOf("nav/earth_fix.dat"), scan.names)
        assertFalse("没到上限，不该标记为截断", scan.truncated)
        assertTrue("应当统计到读出的字符数", scan.chars > 0)
        assertTrue("条目内容必须完整读出来：$seen", seen.toString().contains("1201 A 40.0 116.0"))
    }

    @Test
    fun streamZipWithNoMatchingEntryReturnsEmpty() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val uri = Uri.parse("content://efb-test/other.zip")
        Shadows.shadowOf(app.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(zipOf("readme.txt" to "hi")))

        val scan = ImportRepository(app).streamZip(uri, { it.endsWith(".dat") }) { _, _ -> }

        assertTrue("一个都不匹配时要老实返回空，让调用方给出具体原因", scan.names.isEmpty())
    }
}