package com.oguricap.efb

import com.oguricap.efb.data.ChartIndex
import com.oguricap.efb.data.ChartScanner
import com.oguricap.efb.data.IndexBuilder
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// 用真实航图库的文件清单（3203 条，只含相对路径）验证三件事：
// 一、扫描器的解析结果与 PC 上 naip_index.py 生成的索引逐条一致；
// 二、扫描后写出的 SQLite 能被 ChartIndex 正确读回；
// 三、机场数量、图型分布、各机场张数与原索引完全相同。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerParityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun libraryFiles(): List<String> {
        val stream = javaClass.classLoader!!.getResourceAsStream("library_files.json")
            ?: error("测试资源 library_files.json 缺失")
        val text = stream.bufferedReader().use { it.readText() }
        val array = JSONArray(text)
        return (0 until array.length()).map { array.getString(it) }
    }

    // 按真实清单建一棵只有文件名、没有内容的目录树
    private fun buildTree(files: List<String>): File {
        val rootDir = File(temp.root, "library").apply { mkdirs() }
        for (rel in files) {
            File(rootDir, rel).apply { parentFile?.mkdirs() }.writeText("stub")
        }
        return rootDir
    }

    @Test
    fun scanMatchesPythonIndexEntryByEntry() {
        val files = libraryFiles()
        assertEquals(3203, files.size)
        val scanned = ChartScanner.scan(buildTree(files))

        assertEquals("条目数必须与原索引一致", 3203, scanned.charts.size)
        assertEquals("不应当有任何解析失败", 0, scanned.issues.size)

        val scannedFiles = scanned.charts.map { it.file }.sorted()
        assertEquals("文件相对路径必须一一对应", files.sorted(), scannedFiles)
    }

    @Test
    fun scanReproducesAirportCounts() {
        val scanned = ChartScanner.scan(buildTree(libraryFiles()))
        assertEquals(84, scanned.airports.size)
        assertEquals(3203, scanned.airports.sumOf { it.chartCount })

        val byIcao = scanned.airports.associate { it.icao to it.chartCount }
        assertEquals(82, byIcao["ZBAA"])
        assertEquals(119, byIcao["ZGGG"])
        assertEquals(105, byIcao["ZSPD"])
        assertEquals(86, byIcao["ENR"])
        assertEquals("北京首都", scanned.airports.first { it.icao == "ZBAA" }.name)
    }

    @Test
    fun scanReproducesChartTypeDistribution() {
        val scanned = ChartScanner.scan(buildTree(libraryFiles()))
        val byType = scanned.charts.groupingBy { it.chartType }.eachCount()
        assertEquals(110, byType["ADC"])
        assertEquals(70, byType["GMC"])
        assertEquals(138, byType["APDC"])
        assertEquals(705, byType["SID"])
        assertEquals(662, byType["IAC"])
        assertEquals(501, byType["STAR"])
        assertEquals(149, byType["AOC"])
        assertEquals(35, byType["ATCSMAC"])
        assertEquals(735, byType["CODING"])
        assertEquals(86, byType["ENR"])
        assertEquals(12, byType["OTHER"])
    }

    @Test
    fun writtenDatabaseReadsBackIdentically() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val scanned = ChartScanner.scan(buildTree(libraryFiles()))
        val dbFile = IndexBuilder.writeToPrivate(context, scanned)

        ChartIndex.open(dbFile).use { index ->
            assertEquals(3203, index.chartCount())
            val airports = index.airports()
            assertEquals(84, airports.size)
            assertEquals(3203, airports.sumOf { it.chartCount })
            assertEquals("北京首都", index.airport("ZBAA")?.name)

            val zbaa = index.charts("ZBAA")
            assertEquals(82, zbaa.size)
            assertTrue(zbaa.all { it.file.endsWith(".pdf") })
            assertTrue(zbaa.all { !it.file.startsWith("/") })
            // 图型按飞行流程排序
            val ranks = zbaa.map { com.oguricap.efb.data.ChartType.rank(it.chartType) }
            assertEquals(ranks.sorted(), ranks)
        }
    }

    @Test
    fun scannedIndexSearchBehavesLikeAssetIndex() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val dbFile = IndexBuilder.writeToPrivate(context, ChartScanner.scan(buildTree(libraryFiles())))

        ChartIndex.open(dbFile).use { index ->
            assertEquals(listOf("ZBAA"), index.searchAirports("ZBAA").map { it.icao })
            assertEquals(setOf("ZUTF", "ZUUU"), index.searchAirports("成都").map { it.icao }.toSet())
            assertTrue(index.searchCharts("ILS").isNotEmpty())
        }
    }
}
