package com.oguricap.efb

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.oguricap.efb.diag.AppLog
import com.oguricap.efb.diag.CrashGuard
import com.oguricap.efb.prefs.Settings
import com.oguricap.efb.ui.settings.DiagnosticsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页「诊断与日志」的状态中枢：开关要落到偏好里、统计要跟着刷新、清空要清干净。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        AppLog.enabled = true
        AppLog.init(ApplicationProvider.getApplicationContext())
        AppLog.clear()
        CrashGuard.clear(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        AppLog.enabled = true
    }

    private fun newViewModel(): DiagnosticsViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return DiagnosticsViewModel(app).apply { io = dispatcher }
    }

    @Test
    fun loggingIsOnByDefault() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        assertTrue(vm.logEnabled)
        assertTrue(AppLog.enabled)
    }

    @Test
    fun togglingLoggingPersistsAndReachesTheLogger() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.applyLogEnabled(false)
        advanceUntilIdle()
        assertFalse("界面状态要跟着变", vm.logEnabled)
        assertFalse("日志器要真的停写", AppLog.enabled)
        assertFalse("偏好要落盘，重启后仍然是关", Settings(ApplicationProvider.getApplicationContext()).logEnabled)

        vm.applyLogEnabled(true)
        advanceUntilIdle()
        assertTrue(AppLog.enabled)
        assertTrue(Settings(ApplicationProvider.getApplicationContext()).logEnabled)
    }

    @Test
    fun statsReflectLogsAndCrashes() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        AppLog.i("EFB/TEST", "写一点内容")
        AppLog.flush()
        CrashGuard.recordCrash(ApplicationProvider.getApplicationContext(), "main", RuntimeException("测试"))
        vm.refresh()
        advanceUntilIdle()

        assertTrue("应当统计到日志文件", vm.logFileCount > 0)
        assertTrue("应当统计到体积", vm.logBytes > 0)
        assertEquals(1, vm.crashCount)
        assertNotNull("应当给出最近一次崩溃摘要", vm.lastCrash)
        assertTrue(vm.lastCrash!!.contains("测试"))
    }

    @Test
    fun clearingWipesLogsAndCrashes() = runTest(dispatcher) {
        AppLog.i("EFB/TEST", "清空前")
        AppLog.flush()
        CrashGuard.recordCrash(ApplicationProvider.getApplicationContext(), "main", RuntimeException("清空前"))

        val vm = newViewModel()
        advanceUntilIdle()
        assertEquals(1, vm.crashCount)

        vm.clearLogs()
        advanceUntilIdle()

        assertEquals("崩溃记录应当清空", 0, vm.crashCount)
        assertEquals("日志应当只剩清空动作自己那一条", 1, vm.logFileCount)
        assertNotNull("清空本身要能提示", vm.message)
    }
}