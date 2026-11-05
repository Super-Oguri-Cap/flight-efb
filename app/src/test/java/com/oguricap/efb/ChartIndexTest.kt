package com.oguricap.efb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.oguricap.efb.data.ChartIndex
import com.oguricap.efb.data.ChartType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric 的 SDK 36 沙箱要求 Java 21，本机是 JDK 17；
// 在 SDK 34（等于 minSdk）上跑索引与路径逻辑没有差异。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChartIndexTest {

    private fun open(): ChartIndex {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ChartIndex.open(context)
    }

    @Test
    fun airports_coversWholeLibrary() {
        open().use { index ->
            val airports = index.airports()
            assertEquals(84, airports.size)
            assertEquals("ENR", airports.first().icao)
            val total = airports.sumOf { it.chartCount }
            assertEquals(3203, total)
        }
    }

    @Test
    fun airport_lookup_returnsChineseName() {
        open().use { index ->
            val beijing = index.airport("ZBAA")
            assertNotNull(beijing)
            assertEquals("北京首都", beijing!!.name)
            assertEquals(82, beijing.chartCount)
        }
    }

    @Test
    fun charts_of_airport_sorted_by_flight_order() {
        open().use { index ->
            val charts = index.charts("ZBAA")
            assertEquals(82, charts.size)
            val ranks = charts.map { ChartType.rank(it.chartType) }
            assertEquals(ranks.sorted(), ranks)
        }
    }

    @Test
    fun chart_type_order_matches_flight_flow() {
        assertEquals(0, ChartType.rank("ADC"))
        assertTrue(ChartType.rank("IAC") > ChartType.rank("SID"))
        assertTrue(ChartType.rank("ENR") > ChartType.rank("CODING"))
        assertEquals(ChartType.order.size, ChartType.rank("未定义图型"))
    }

    @Test
    fun search_by_icao_prefix_lists_airports() {
        open().use { index ->
            val hits = index.searchAirports("ZB")
            assertTrue(hits.size >= 10)
            assertTrue(hits.all { it.icao.startsWith("ZB") })
        }
    }

    @Test
    fun search_by_chinese_name_ranks_name_match_first() {
        open().use { index ->
            val hits = index.searchAirports("成都")
            assertEquals(listOf("ZUTF", "ZUUU"), hits.map { it.icao }.sorted())
            val beijing = index.searchAirports("北京")
            assertEquals(listOf("ZBAA", "ZBAD"), beijing.map { it.icao }.sorted())
        }
    }

    @Test
    fun search_charts_by_title_keyword() {
        open().use { index ->
            val hits = index.searchCharts("ILS")
            assertTrue(hits.isNotEmpty())
            assertTrue(hits.all { it.title.contains("ILS", ignoreCase = true) })
        }
    }

    @Test
    fun search_escapes_like_wildcards() {
        open().use { index ->
            assertTrue(index.searchAirports("%").isEmpty())
            assertTrue(index.searchAirports("_").isEmpty())
        }
    }

    @Test
    fun charts_carry_relative_pdf_path() {
        open().use { index ->
            val charts = index.charts("ZGGG")
            assertEquals(119, charts.size)
            assertTrue(charts.all { it.file.endsWith(".pdf") })
            assertTrue(charts.all { !it.file.startsWith("/") })
            assertTrue(charts.none { it.file.contains("\\") })
        }
    }

    @Test
    fun enr_group_is_reachable() {
        open().use { index ->
            val enr = index.charts("ENR")
            assertEquals(86, enr.size)
            assertTrue(enr.all { it.chartType == "ENR" })
        }
    }
}