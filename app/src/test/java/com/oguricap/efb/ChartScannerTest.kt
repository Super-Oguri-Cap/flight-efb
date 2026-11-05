package com.oguricap.efb

import com.oguricap.efb.data.ChartScanner
import com.oguricap.efb.data.ChartType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// 扫描器与 naip_index.py 必须给出同一套结果，否则手机上重建的索引会和 PC 上预置的对不上。
class ChartScannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun touch(relative: String) {
        val file = File(temp.root, relative)
        file.parentFile!!.mkdirs()
        file.writeText("stub")
    }

    @Test
    fun parsesAirportDirectoryWithCityAndName() {
        val (icao, name) = ChartScanner.parseAirportDir("ZBAA_北京_首都")
        assertEquals("ZBAA", icao)
        assertEquals("北京首都", name)
    }

    @Test
    fun parsesSingleWordAirportDirectory() {
        val (icao, name) = ChartScanner.parseAirportDir("ZSYW_义乌")
        assertEquals("ZSYW", icao)
        assertEquals("义乌", name)
    }

    @Test
    fun mapsEnrDirectoryToGeneralMaterial() {
        val (icao, name) = ChartScanner.parseAirportDir("00_ENR通用资料")
        assertEquals("ENR", icao)
        assertEquals("通用资料", name)
    }

    @Test
    fun rejectsDirectoryThatIsNeitherAirportNorEnr() {
        val (icao, name) = ChartScanner.parseAirportDir("随手放的资料")
        assertNull(icao)
        assertNull(name)
    }

    @Test
    fun mapsEveryChartTypePrefix() {
        assertEquals("ADC", ChartScanner.chartTypeOf("01_机场图ADC"))
        assertEquals("GMC", ChartScanner.chartTypeOf("02_滑行道图GMC"))
        assertEquals("APDC", ChartScanner.chartTypeOf("03_机位停靠图APDC"))
        assertEquals("SID", ChartScanner.chartTypeOf("04_离场图SID"))
        assertEquals("IAC", ChartScanner.chartTypeOf("05_进近图IAC"))
        assertEquals("STAR", ChartScanner.chartTypeOf("06_进场图STAR"))
        assertEquals("AOC", ChartScanner.chartTypeOf("07_障碍物与地形图AOC-PATC"))
        assertEquals("ATCSMAC", ChartScanner.chartTypeOf("08_监视最低高度图ATCSMAC"))
        assertEquals("CODING", ChartScanner.chartTypeOf("09_编码表CODING"))
        assertEquals("OTHER", ChartScanner.chartTypeOf("10_其它OTHER"))
        assertNull(ChartScanner.chartTypeOf("机场图ADC"))
    }

    @Test
    fun scansNestedLibrary() {
        touch("ZBAA_北京_首都/01_机场图ADC/ADC ZBAA.pdf")
        touch("ZBAA_北京_首都/05_进近图IAC/IAC RWY36L.pdf")
        touch("ZBAA_北京_首都/05_进近图IAC/IAC RWY36R.pdf")
        touch("ZGGG_广州_白云/05_进近图IAC/IAC RWY02L.pdf")
        touch("00_ENR通用资料/ENR 1.1 总则.pdf")

        val result = ChartScanner.scan(temp.root)

        assertEquals(5, result.charts.size)
        assertEquals(3, result.airports.size)
        assertEquals(listOf("ENR", "ZBAA", "ZGGG"), result.airports.map { it.icao })
        // ZBAA 有 ADC 1 张 + IAC 2 张
        assertEquals(3, result.airports.first { it.icao == "ZBAA" }.chartCount)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun fileFieldIsRelativeWithForwardSlashes() {
        touch("ZBAA_北京_首都/01_机场图ADC/ADC ZBAA.pdf")
        val chart = ChartScanner.scan(temp.root).charts.single()
        assertEquals("ZBAA_北京_首都/01_机场图ADC/ADC ZBAA.pdf", chart.file)
        assertTrue(chart.id == chart.file)
    }

    @Test
    fun extractsRunwayNumbers() {
        touch("ZBAA_北京_首都/05_进近图IAC/A RWY36L.pdf")
        touch("ZBAA_北京_首都/05_进近图IAC/B RWY 18R.pdf")
        touch("ZBAA_北京_首都/05_进近图IAC/C 无跑道.pdf")
        // 扫描结果按 图型顺序 + 文件名 排序，A/B/C 前缀保证顺序确定
        val rwys = ChartScanner.scan(temp.root).charts.map { it.rwys }
        assertEquals(listOf("36L"), rwys[0])
        assertEquals(listOf("18R"), rwys[1])
        assertEquals(emptyList<String>(), rwys[2])
    }

    @Test
    fun enrPdfsSittingDirectlyUnderRootFolderAreAccepted() {
        // 真实库的 00_ENR通用资料 下 PDF 直接放在一级目录里，没有图型子目录
        touch("00_ENR通用资料/ENR 1.1 总则.pdf")
        touch("00_ENR通用资料/GEN 0.1 前言.pdf")
        val result = ChartScanner.scan(temp.root)
        assertEquals(2, result.charts.size)
        assertTrue(result.charts.all { it.chartType == "ENR" })
        assertTrue(result.charts.all { it.icao == "ENR" })
        assertTrue(result.charts.all { it.airport == "通用资料" })
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun pdfDirectlyInsideAirportFolderIsReported() {
        touch("ZBAA_北京_首都/ADC ZBAA.pdf")
        val result = ChartScanner.scan(temp.root)
        assertEquals(0, result.charts.size)
        assertEquals(1, result.issues.size)
        assertTrue(result.issues.first().reason.contains("缺少图型子目录"))
    }

    @Test
    fun recordsIssuesInsteadOfGuessing() {
        touch("随手放的资料/01_机场图ADC/x.pdf")
        touch("ZBAA_北京_首都/随意命名/y.pdf")
        val result = ChartScanner.scan(temp.root)
        assertEquals(0, result.charts.size)
        assertEquals(2, result.issues.size)
    }

    @Test
    fun ignoresNonPdfFiles() {
        touch("ZBAA_北京_首都/01_机场图ADC/ADC ZBAA.pdf")
        touch("ZBAA_北京_首都/01_机场图ADC/说明.txt")
        touch("ZBAA_北京_首都/01_机场图ADC/图片.png")
        assertEquals(1, ChartScanner.scan(temp.root).charts.size)
    }

    @Test
    fun uppercasePdfExtensionIsAccepted() {
        touch("ZBAA_北京_首都/01_机场图ADC/ADC ZBAA.PDF")
        assertEquals(1, ChartScanner.scan(temp.root).charts.size)
    }

    @Test
    fun chartsOfAirportSortByFlightOrderAfterScan() {
        touch("ZBAA_北京_首都/05_进近图IAC/1.pdf")
        touch("ZBAA_北京_首都/01_机场图ADC/2.pdf")
        touch("ZBAA_北京_首都/04_离场图SID/3.pdf")
        val charts = ChartScanner.scan(temp.root).charts
        val types = charts.map { it.chartType }
        assertEquals(types.sortedBy { ChartType.rank(it) }, types)
    }

    // 平铺目录：用户把一堆 PDF 直接放在所选目录下，没有任何子目录。
    // 这类目录必须逐个报出来，不能只给一个空结果让用户猜。
    @Test
    fun flatDirectoryReportsEveryFile() {
        repeat(5) { index ->
            File(temp.root, "chart-" + index + ".pdf").writeText("stub")
        }
        val result = ChartScanner.scan(temp.root)
        assertEquals(0, result.charts.size)
        assertTrue(result.isEmpty)
        assertEquals(5, result.issues.size)
        assertTrue(result.issues.all { it.reason.contains("没有按 机场/图型/ 的目录结构存放") })
    }

    @Test
    fun flatPdfsInsideUnnamedFolderAreReported() {
        val dir = File(temp.root, "随便一个文件夹").apply { mkdirs() }
        repeat(3) { index -> File(dir, "chart-" + index + ".pdf").writeText("stub") }
        val result = ChartScanner.scan(temp.root)
        assertEquals(0, result.charts.size)
        assertEquals(3, result.issues.size)
        assertTrue(result.issues.all { it.reason.contains("一级目录既不是机场目录") })
    }

    @Test
    fun flatPdfsInsideAirportFolderAreReported() {
        val dir = File(temp.root, "ZBAA_北京_首都").apply { mkdirs() }
        repeat(2) { index -> File(dir, "chart-" + index + ".pdf").writeText("stub") }
        val result = ChartScanner.scan(temp.root)
        assertEquals(0, result.charts.size)
        assertEquals(2, result.issues.size)
        assertTrue(result.issues.all { it.reason.contains("缺少图型子目录") })
    }

    @Test
    fun emptyDirectoryYieldsEmptyResult() {
        val result = ChartScanner.scan(temp.root)
        assertTrue(result.isEmpty)
        assertTrue(result.airports.isEmpty())
    }

    @Test
    fun progressIsReportedAndEndsAtTotal() {
        repeat(450) { index ->
            touch("ZBAA_北京_首都/05_进近图IAC/" + index + ".pdf")
        }
        var reported = 0
        val result = ChartScanner.scan(temp.root) { reported = it }
        assertEquals(450, result.charts.size)
        assertEquals(450, reported)
    }
}