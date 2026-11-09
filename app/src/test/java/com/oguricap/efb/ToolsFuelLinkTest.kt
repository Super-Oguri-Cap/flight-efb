package com.oguricap.efb

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.oguricap.efb.ui.tools.JsChannel
import com.oguricap.efb.ui.tools.PlannedRoute
import com.oguricap.efb.ui.tools.ToolsPage
import com.oguricap.efb.ui.tools.ToolsViewModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 航路 → 油量的联动。
 *
 * 这条链路在真机上出过「没反应」：生成完点油量页，距离没被填进去。
 * 根因是时序 —— 方案卡片先出现在 DOM，shim 的 MutationObserver 还要防抖 400ms
 * 才通知 Kotlin 去读；用户在这个空窗里切页，读到的就是上一批（甚至空的）快照。
 *
 * 这里用假通道把 WebView 摘掉，只验证 ViewModel 的编排：
 *   1. 解析方案 JSON 取最小距离
 *   2. 切到油量页时先重读 DOM、再注入
 *   3. 航路库里「设为当前」的记录优先于页面方案
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolsFuelLinkTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val plansJson = """
        {"from":"ZBAA","to":"ZSPD","plans":[
          {"key":"eng0","kind":"engine","distNM":673,"route":"ZBAA HUR B339 ZSPD"},
          {"key":"eng1","kind":"engine","distNM":738,"route":"ZBAA HUR W64 ZSPD"}
        ]}
    """.trimIndent()

    /** 记录所有 eval；`reply` 决定同步回调什么（等价于 evaluateJavascript 的返回值）。 */
    private class FakeChannel(
        private val reply: (js: String) -> String? = { null },
    ) : JsChannel {
        val evals = ArrayList<String>()
        override fun eval(js: String) {
            evals += js
        }

        override fun eval(js: String, onResult: (String) -> Unit) {
            evals += js
            reply(js)?.let(onResult)
        }
    }

    private fun vm(): ToolsViewModel = ToolsViewModel(app)

    @Test
    fun parsesPlansAndUsesShortest() {
        val vm = vm()
        val route = FakeChannel { js ->
            if (js.contains("getPlansJson")) JSONObject.quote(plansJson) else null
        }
        vm.attachChannel(ToolsPage.Route, route)
        vm.refreshPlans()

        assertEquals(673, vm.currentDistNM())
        assertEquals("当前方案", vm.currentDistSource())
    }

    @Test
    fun emptyPageHasNoDistance() {
        val vm = vm()
        val route = FakeChannel { js ->
            if (js.contains("getPlansJson")) JSONObject.quote("""{"from":"","to":"","plans":[]}""") else null
        }
        vm.attachChannel(ToolsPage.Route, route)
        vm.refreshPlans()

        assertNull(vm.currentDistNM())
        assertNull(vm.currentDistSource())
    }

    /**
     * 生成完立刻切页：切页那一下 DOM 里已经有方案，但 ViewModel 里还是空的快照。
     * 正确行为是「先重读一次，再填」，而不是拿空快照放弃。
     */
    @Test
    fun switchingToFuelRefreshesPlansBeforeFilling() {
        val vm = vm()
        var reads = 0
        val route = FakeChannel { js ->
            if (js.contains("getPlansJson")) {
                reads++
                // 第一次（页面刚加载）还没有方案，第二次（切页时）已经有了
                JSONObject.quote(if (reads == 1) """{"from":"","to":"","plans":[]}""" else plansJson)
            } else {
                null
            }
        }
        vm.attachChannel(ToolsPage.Route, route)
        vm.refreshPlans()
        assertNull(vm.currentDistNM())

        val fuel = FakeChannel { js -> if (js.contains("getElementById('dist')")) "true" else null }
        vm.attachChannel(ToolsPage.Fuel, fuel)
        vm.onPageReady(ToolsPage.Fuel)
        vm.selectPage(ToolsPage.Fuel)

        assertTrue("切页后应当重新读过方案", reads >= 2)
        assertTrue(
            "油量页应当收到 673 NM 的注入，实际：${fuel.evals}",
            fuel.evals.any { it.contains("673") },
        )
        assertEquals(673, vm.fuelFilledNM)
    }

    /**
     * 切页时方案还没渲染完（读到空快照），400ms 后真正的那批方案才到。
     * 人在油量页、又从来没填成功过，这时必须补填 —— 否则用户永远停在空白。
     */
    @Test
    fun fillsWhenLateSnapshotArrives() {
        val vm = vm()
        var reads = 0
        val route = FakeChannel { js ->
            if (js.contains("getPlansJson")) {
                reads++
                JSONObject.quote(if (reads <= 2) """{"from":"","to":"","plans":[]}""" else plansJson)
            } else {
                null
            }
        }
        vm.attachChannel(ToolsPage.Route, route)
        vm.refreshPlans()

        val fuel = FakeChannel { js -> if (js.contains("getElementById('dist')")) "true" else null }
        vm.attachChannel(ToolsPage.Fuel, fuel)
        vm.onPageReady(ToolsPage.Fuel)
        vm.selectPage(ToolsPage.Fuel)
        assertEquals("空快照时不该填", 0, fuel.evals.count { it.contains("getElementById('dist')") })

        vm.onPlansChanged()          // 迟到的方案

        assertTrue(
            "迟到的方案到了要补填，实际：${fuel.evals}",
            fuel.evals.any { it.contains("673") },
        )
        assertEquals(673, vm.fuelFilledNM)
    }

    /** 「设为当前」的航路库记录优先于页面方案 —— 这是用户最容易误解的一处，必须锁住。 */
    @Test
    fun activeSavedRouteWinsOverPagePlans() {
        val vm = vm()
        val route = FakeChannel { js ->
            if (js.contains("getPlansJson")) JSONObject.quote(plansJson) else null
        }
        vm.attachChannel(ToolsPage.Route, route)
        vm.refreshPlans()

        val plan = PlannedRoute(key = "eng0", kind = "engine", distNM = 673, route = "ZBAA ZSPD")
        vm.savePlan(plan, "北京-上海")
        val saved = vm.saved.first()
        vm.setActive(saved.id)

        assertEquals(673, vm.currentDistNM())
        assertTrue("来源应当标明航路库", vm.currentDistSource()!!.startsWith("航路库"))
    }
}
