package com.oguricap.efb

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.oguricap.efb.data.Chart
import com.oguricap.efb.data.PinKind
import com.oguricap.efb.data.PinStore
import com.oguricap.efb.ui.ChartViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// 选中航图的落盘与分组搬家：机场页的「离场 / 进场」置顶区依赖这两条行为
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChartPinTest {

    private fun sampleChart(file: String = "ZBAA_北京_首都/04_离场图SID/SID01.pdf") = Chart(
        id = "chart-" + file,
        icao = "ZBAA",
        airport = "北京/首都",
        chartType = "SID",
        title = "ZBAA SID 01",
        file = file,
        rwys = listOf("36L", "18R"),
    )

    @Test
    fun pinSurvivesStoreRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = PinStore(context)
        val chart = sampleChart()

        store.save(PinKind.DEPARTURE, listOf(chart))
        assertEquals(listOf(chart), store.load(PinKind.DEPARTURE))
        assertTrue(store.load(PinKind.ARRIVAL).isEmpty())

        store.save(PinKind.DEPARTURE, emptyList())
        assertTrue(store.load(PinKind.DEPARTURE).isEmpty())
    }

    @Test
    fun pinningMovesChartBetweenGroupsInsteadOfDuplicating() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ChartViewModel(app)
        val chart = sampleChart()

        vm.pinCharts(listOf(chart), PinKind.DEPARTURE)
        assertEquals(1, vm.departures.size)
        assertTrue(vm.arrivals.isEmpty())
        assertEquals(PinKind.DEPARTURE, vm.pinKindOf(chart))

        // 同一张图改投进场区：离场区里必须消失，否则两处各有一条
        vm.pinCharts(listOf(chart), PinKind.ARRIVAL)
        assertTrue(vm.departures.isEmpty())
        assertEquals(1, vm.arrivals.size)
        assertEquals(PinKind.ARRIVAL, vm.pinKindOf(chart))

        vm.unpinCharts(listOf(chart))
        assertTrue(vm.arrivals.isEmpty())
    }
}
