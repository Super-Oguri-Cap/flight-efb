package com.oguricap.efb

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.oguricap.efb.ui.ChartViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// 从 assets 装载真实索引，走一遍「打开 → 搜索 → 进机场 → 展开分组」的完整数据路径。
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChartViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): ChartViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return ChartViewModel(app).apply { io = dispatcher }
    }

    @Test
    fun loadsIndexFromAssets() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.load()
        advanceUntilIdle()
        assertNull(vm.loadError)
        assertNotNull(vm.index)
        assertEquals(84, vm.airports.size)
        assertEquals("ENR", vm.airports.first().icao)
    }

    @Test
    fun typingFourLetterCodeFiltersAirports() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.load()
        advanceUntilIdle()
        vm.onQueryChange("ZBAA")
        advanceUntilIdle()
        assertEquals(1, vm.airports.size)
        assertEquals("ZBAA", vm.airports.first().icao)
        assertEquals("北京首都", vm.airports.first().name)
    }

    @Test
    fun typingChineseNameMatchesAirport() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.load()
        advanceUntilIdle()
        vm.onQueryChange("昆明")
        advanceUntilIdle()
        assertEquals(listOf("ZPPP"), vm.airports.map { it.icao })
    }

    // 搜 ZGGG 要的是「广州白云」这个机场大类，不是 119 张散图
    @Test
    fun typingIcaoShowsOnlyAirportCategory() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.load()
        advanceUntilIdle()
        vm.onQueryChange("ZGGG")
        advanceUntilIdle()
        assertEquals(listOf("ZGGG"), vm.airports.map { it.icao })
        assertEquals("广州白云", vm.airports.first().name)
        assertTrue("搜机场时不应再列具体航图", vm.chartHits.isEmpty())
    }

    @Test
    fun typingChineseAirportNameShowsOnlyAirportCategory() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.load()
        advanceUntilIdle()
        vm.onQueryChange("广州")
        advanceUntilIdle()
        assertTrue(vm.airports.isNotEmpty())
        assertTrue("中文机场名同样不该带出一堆图名匹配", vm.chartHits.isEmpty())
    }

    // 图名搜索仍然可用：查询词没命中机场时才列具体航图
    @Test
    fun typingChartKeywordStillListsCharts() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.load()
        advanceUntilIdle()
        vm.onQueryChange("ILS")
        advanceUntilIdle()
        assertTrue(vm.airports.isEmpty())
        assertTrue(vm.chartHits.isNotEmpty())
    }

    @Test
    fun queryPersistsAcrossReload() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.load()
        advanceUntilIdle()
        vm.onQueryChange("ZGGG")
        advanceUntilIdle()

        val relaunched = newViewModel()
        assertEquals("ZGGG", relaunched.query)
    }

    @Test
    fun openingAirportBuildsGroupsInFlightOrder() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.load()
        advanceUntilIdle()
        vm.openAirport("ZBAA")
        advanceUntilIdle()

        assertEquals("北京首都", vm.currentAirport?.name)
        assertEquals(82, vm.currentCharts.size)
        val types = vm.currentCharts.map { it.chartType }.distinct()
        assertEquals(types.sortedBy { com.oguricap.efb.data.ChartType.rank(it) }, types)
        assertTrue(vm.expandedTypes.isEmpty())
    }

    @Test
    fun togglingTypeExpandsAndCollapses() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.load()
        advanceUntilIdle()
        vm.openAirport("ZBAA")
        advanceUntilIdle()

        vm.toggleType("IAC")
        assertTrue("IAC" in vm.expandedTypes)
        vm.toggleType("IAC")
        assertTrue(vm.expandedTypes.isEmpty())
    }

    @Test
    fun absoluteFileIsNullUntilRootIsSet() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.load()
        advanceUntilIdle()
        vm.openAirport("ZBAA")
        advanceUntilIdle()
        val chart = vm.currentCharts.first()
        assertNull("根目录未设置时不应拼出相对路径", vm.absoluteFile(chart))
    }

    @Test
    fun scanningLibrarySetsRootAndRebuildsIndex() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.load()
        advanceUntilIdle()

        val library = buildLibrary("scan-ok")
        vm.scanInto(library.absolutePath)
        advanceUntilIdle()

        assertNull(vm.rootError)
        assertEquals(library.absolutePath, vm.chartRoot)
        assertEquals(3, vm.lastScan?.charts?.size)
        assertEquals(listOf("ENR", "ZBAA"), vm.lastScan?.airports?.map { it.icao })
        assertEquals(2, vm.airports.size)
        assertEquals(2, vm.index?.airport("ZBAA")?.chartCount)
    }

    @Test
    fun scanningWrongDirectoryReportsInsteadOfGuessing() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.load()
        advanceUntilIdle()

        val empty = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "scan-empty")
            .apply { mkdirs() }
        vm.scanInto(empty.absolutePath)
        advanceUntilIdle()

        assertNotNull(vm.rootError)
        assertTrue(vm.rootError!!.contains("AIP航图库"))
        assertNull(vm.chartRoot)
    }

    @Test
    fun scanningMissingPathReportsImmediately() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.load()
        advanceUntilIdle()
        vm.scanInto("/storage/emulated/0/不存在的目录")
        advanceUntilIdle()
        assertNotNull(vm.rootError)
        assertTrue(vm.rootError!!.contains("目录不存在"))
    }

    @Test
    fun onboardingCompletesOnlyAfterSuccessfulScan() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.load()
        advanceUntilIdle()
        assertTrue("未扫描前不能完成引导", !vm.completeOnboarding())

        vm.scanInto(buildLibrary("onboarding").absolutePath)
        advanceUntilIdle()

        assertTrue(vm.completeOnboarding())
        assertTrue(vm.onboardingDone)
        assertTrue("完成标记应持久化", newViewModel().onboardingDone)
    }

    @Test
    fun resetOnboardingAllowsRerun() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.load()
        advanceUntilIdle()
        vm.scanInto(buildLibrary("rerun").absolutePath)
        advanceUntilIdle()
        vm.completeOnboarding()
        assertTrue(vm.onboardingDone)

        vm.resetOnboarding()
        assertTrue(!vm.onboardingDone)
        assertTrue(!newViewModel().onboardingDone)
    }

    @Test
    fun invertTogglePersists() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.load()
        advanceUntilIdle()
        assertTrue(!vm.invertReader)
        vm.toggleInvert()
        assertTrue(vm.invertReader)

        val relaunched = newViewModel()
        assertTrue("反色开关应持久化", relaunched.invertReader)
        relaunched.applyInvert(false)
        assertTrue(!relaunched.invertReader)
    }

    private fun buildLibrary(name: String): File {
        val base = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, name)
        base.deleteRecursively()
        fun touch(relative: String) {
            File(base, relative).apply { parentFile?.mkdirs() }.writeText("stub")
        }
        touch("ZBAA_北京_首都/01_机场图ADC/ADC ZBAA.pdf")
        touch("ZBAA_北京_首都/05_进近图IAC/IAC RWY36L.pdf")
        touch("00_ENR通用资料/ENR 1.1 总则.pdf")
        return base
    }
}