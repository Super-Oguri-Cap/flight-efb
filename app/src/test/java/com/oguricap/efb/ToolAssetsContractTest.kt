package com.oguricap.efb

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.oguricap.efb.ui.tools.ToolContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 网页工具资源的出包期门禁 —— 整个改造**唯一的把关点**。
 *
 * 为什么只有这里能把关：正常升级是「覆盖两个 HTML + 重新出包」，Kotlin 一行不改，
 * 运行期也没有任何补救通道。所以锚点漂移、顶层 const 改名、导出机制被换掉、
 * 单文件不再自包含，这些都必须在这里让**构建失败**，而不是等真机上静默出错。
 *
 * 断言分三层：
 *   1. 原始 HTML（磁盘上的字节级原样）：结构仍在；
 *   2. 配置里的每个锚点：命中数恰好 1；
 *   3. 替换后的产物：桥与 guard 各只有一份，且重复调用不叠加。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolAssetsContractTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    /** 显式 UTF-8：中文 Windows 的 JVM 默认编码可能是 GBK，省略字符集会让锚点静默匹配不上。 */
    private fun read(path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun count(text: String, needle: String): Int {
        var n = 0
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return n
            n++
            from = at + 1
        }
    }

    private fun config() = ToolContract.loadConfig(context)

    private fun spec(asset: String) = config().tools[asset]
        ?: error("efb_patches.json 里没有 $asset")

    // ------------------------------------------------------------------ 1

    @Test
    fun routeHtmlKeepsStructuralContract() {
        val html = read(ToolContract.ROUTE_ASSET)

        // 7 个顶层 const：桥是单个对象字面量，任一改名整座桥都建不起来
        for (name in spec(ToolContract.ROUTE_ASSET).requiredConsts) {
            val needle = "const $name = (function"
            assertEquals("$needle 必须恰好出现 1 次", 1, count(html, needle))
        }

        // 桥的插入点：只有 </script>\s*</body> 这个序列是唯一的
        assertEquals(
            "桥插入点 </script>\\s*</body> 必须唯一",
            1,
            Regex("</script>\\s*</body>").findAll(html).count()
        )

        // 导出补丁的锚点
        assertEquals(
            "downloadJson 的函数签名必须唯一",
            1,
            count(html, "function downloadJson(filename, obj) {")
        )
    }

    // ------------------------------------------------------------------ 2

    @Test
    fun routeHtmlKeepsExportMechanism() {
        val html = read(ToolContract.ROUTE_ASSET)

        // 这三个是「导出还是 Blob + a.click」的证据。将来换成 showSaveFilePicker
        // 之类，这里会红 —— 那时才允许动 Kotlin（§4.3 能力边界例外分支）。
        assertEquals("URL.createObjectURL 应恰好 1 处", 1, count(html, "URL.createObjectURL"))
        assertEquals("a.download 应恰好 1 处", 1, count(html, "a.download"))
        assertEquals("a.click() 应恰好 1 处", 1, count(html, "a.click()"))
    }

    // ------------------------------------------------------------------ 3

    @Test
    fun fuelHtmlKeepsDistanceAndSelfTestContract() {
        val html = read(ToolContract.FUEL_ASSET)
        val s = spec(ToolContract.FUEL_ASSET)

        // 距离注入依赖 #dist + input 事件 + calc()；自检只有文本输出，不是布尔信号
        assertEquals("#dist 输入框应恰好 1 个", 1, count(html, "id=\"dist\""))
        assertEquals("calc() 应恰好 1 处", 1, count(html, "function calc()"))
        assertEquals("runSelfTests() 应恰好 1 处", 1, count(html, "function runSelfTests()"))

        // 油量页只打一个「皮肤」补丁（机型/规则弹层美化），不含桥：
        // 顶层 function 本来就是 window 属性，不需要 shim
        assertEquals("油量页应当只有一个皮肤补丁", 1, s.patches.size)
        assertEquals("油量页补丁的 id 应为 skin", "skin", s.patches[0].id)
        assertTrue("油量页必须配置距离注入脚本", s.distanceJs.isNotBlank())
        assertTrue("距离注入脚本必须带 __NM__ 占位符", s.distanceJs.contains("__NM__"))
    }

    // ------------------------------------------------------------------ 4

    @Test
    fun everyConfiguredAnchorHitsExactlyOnce() {
        for (s in config().tools.values) {
            val html = read(s.asset)
            for (p in s.patches) {
                val hits = if (p.anchorType == "regex") {
                    Regex(p.anchor).findAll(html).count()
                } else {
                    count(html, p.anchor)
                }
                assertEquals(
                    "${s.asset} 的 patch=${p.id} 锚点 `${p.anchor}` 必须恰好命中 1 次",
                    1,
                    hits
                )
                assertFalse(
                    "${s.asset} 的 patch=${p.id} 必须声明幂等 marker",
                    p.marker.isBlank()
                )
            }
        }
    }

    // ------------------------------------------------------------------ 5

    @Test
    fun htmlStaysSelfContained() {
        // 不按 fetch( 判：航路页 843 行的 fetch(src.url) 在 DatasetLoader 的
        // kind==='fetch' 死分支里，没有 URL 字面量，App 也走不到。
        val forbidden = listOf(
            "http://", "https://", "<script src", "<link href",
            "@import", "type=\"module\"", "type='module'",
        )
        for (s in config().tools.values) {
            val html = read(s.asset)
            for (bad in forbidden) {
                assertFalse("${s.asset} 不应出现 `$bad`（单文件自包含被破坏）", html.contains(bad))
            }
        }
    }

    // ------------------------------------------------------------------ 6

    @Test
    fun appliedPatchesAreSingleAndIdempotent() {
        val s = spec(ToolContract.ROUTE_ASSET)
        val raw = read(ToolContract.ROUTE_ASSET)
        val once = ToolContract.applyPatches(raw, s)

        assertEquals("产物里 window.__EFB__ 只能有 1 处", 1, count(once, "window.__EFB__"))
        assertEquals(
            "产物里导出 guard 只能有 1 处",
            1,
            count(once, "if (window.Android && Android.exportJson)")
        )
        // 函数体结构仍在：guard 只是插在函数头，try/Blob 一行没动
        assertEquals("downloadJson 签名仍应恰好 1 处", 1, count(once, "function downloadJson(filename, obj) {"))
        assertEquals("导出机制未被破坏", 1, count(once, "URL.createObjectURL"))
        assertTrue("guard 必须排在 try 之前", once.indexOf("if (window.Android && Android.exportJson)") < once.indexOf("const blob = new Blob"))

        // 幂等：重复调用不叠加
        val twice = ToolContract.applyPatches(once, s)
        assertEquals("applyPatches 必须幂等", once, twice)

        // 桥落在主脚本之后、</body> 之前
        val bridgeAt = once.indexOf("window.__EFB__")
        assertTrue("桥必须在主 </script> 之后", bridgeAt > once.lastIndexOf("const UI = (function"))
        assertTrue("桥必须在 </body> 之前", bridgeAt < once.indexOf("</body>"))

        // 原始磁盘文本不受影响（纯函数，不写盘）
        assertEquals("applyPatches 不能改原始字符串", read(ToolContract.ROUTE_ASSET), raw)
    }

    // ------------------------------------------------------------------ 7

    @Test
    fun fuelSkinIsAppliedInMemoryOnly() {
        val s = spec(ToolContract.FUEL_ASSET)
        val raw = read(ToolContract.FUEL_ASSET)

        // 磁盘上的页面必须还是原版：皮肤只在内存里拼进去
        assertFalse("assets 里的油量页不应含皮肤样式", raw.contains("efb-fuel-skin-style"))

        val once = ToolContract.applyPatches(raw, s)
        assertEquals("皮肤样式只能注入一次", 1, count(once, "efb-fuel-skin-style"))
        assertTrue(
            "皮肤必须作为独立 script 块插在页面末尾",
            once.contains("<script>\n/* __EFB_FUEL_SKIN__"),
        )
        assertEquals("重复调用不叠加", once, ToolContract.applyPatches(once, s))
    }

    // ------------------------------------------------------------------ 8

    @Test
    fun versionsAreExtractableAndBridgeVersionMatches() {
        val c = config()

        val route = c.tools[ToolContract.ROUTE_ASSET] ?: error("缺 route_gen.html 配置")
        val fuel = c.tools[ToolContract.FUEL_ASSET] ?: error("缺 fuel_calc.html 配置")

        // 版本号本身**不做钉死**：换新版 HTML 本来就会改版本，钉死等于每次升级都红。
        // 这里只保证「正则还能从这一版里提出东西来」，供 logVersions() 打日志用。
        val routeVer = ToolContract.extractVersion(read(ToolContract.ROUTE_ASSET), route)
        assertTrue("航路页版本正则应当能提取到版本串，实际得到 `$routeVer`", routeVer.isNotBlank())
        assertTrue(
            "航路页版本串形状应形如 8.6，实际 `$routeVer`",
            Regex("^\\d+\\.\\d+").containsMatchIn(routeVer)
        )

        val fuelVer = ToolContract.extractVersion(read(ToolContract.FUEL_ASSET), fuel)
        assertTrue("油量页版本正则应当能提取到版本串，实际得到 `$fuelVer`", fuelVer.isNotBlank())

        // 这三处 bridgeVersion 必须一致：Kotlin 常量 / 配置 / shim。
        // 改了桥的接口就三处一起改，漏一处这里就红。
        assertEquals(
            "efb_patches.json 的 bridgeVersion 必须等于 EXPECTED_BRIDGE_VERSION",
            ToolContract.EXPECTED_BRIDGE_VERSION,
            c.bridgeVersion
        )
        val shim = read("tools/efb_shim.js")
        assertTrue(
            "efb_shim.js 的 BRIDGE_VERSION 必须等于 EXPECTED_BRIDGE_VERSION",
            shim.contains("BRIDGE_VERSION = '${ToolContract.EXPECTED_BRIDGE_VERSION}'")
        )
        assertTrue("shim 必须挂 window.__EFB__", shim.contains("window.__EFB__"))
        assertFalse("shim 不能含 </script> 字面量，否则会提前截断内联脚本", shim.contains("</script>"))
        assertTrue("shim 必须含幂等 marker", shim.contains("__EFB_BRIDGE__"))
    }
}
