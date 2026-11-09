package com.oguricap.efb.ui.tools

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

private const val TAG = "EFB/JS"

/**
 * 两个网页工具的宿主。
 *
 * **只用一个 AndroidView + FrameLayout，同一时刻只把当前子页的 WebView 挂进去。**
 *
 * 为什么不把两个 WebView 并排放进 Box、靠 VISIBLE/INVISIBLE 切换：
 * Compose 的 AndroidView 外层是一个 ViewGroup 宿主，宿主本身始终可见、始终参与命中测试。
 * 上层那个即使把内部 WebView 设成 INVISIBLE，点击也会先被它的宿主吃掉 ——
 * 表现就是「页面画出来了，但怎么点都没反应」。这是实测踩到的坑，不是理论风险。
 *
 * 换成「换孩子」之后：
 *  - 屏幕上永远只有一个 WebView，不存在谁盖住谁；
 *  - 未挂载的那个实例仍然活着，JS 上下文、已导入的数据、滚动位置都不丢，
 *    切回来只是重新 addView，不会重新 loadDataWithBaseURL；
 *  - 顺带省掉一个 WebView 的合成开销。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ToolsWebView(
    vm: ToolsViewModel,
    modifier: Modifier = Modifier,
    onEvent: (String) -> Unit = {},
    onPickFiles: (String) -> Unit = {},
) {
    val context = LocalContext.current

    // WebView 懒建、只建一次；页面在第一次被挂上去之前就已装载完成
    val runtime = remember(context) { ToolWebRuntime(context, vm, onEvent, onPickFiles) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx -> FrameLayout(ctx) },
        update = { frame ->
            val target = runtime.viewFor(vm.page)
            if (target != null && (frame.childCount != 1 || frame.getChildAt(0) !== target)) {
                // 先把旧的那个摘下来（实例仍活着），再把新的挂上
                while (frame.childCount > 0) {
                    frame.removeView(frame.getChildAt(0))
                }
                (target.parent as? ViewGroup)?.removeView(target)
                frame.addView(
                    target,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                Log.i(TAG, "挂载 ${vm.page.label}")
            }
        },
    )

    DisposableEffect(runtime) {
        onDispose { runtime.destroyAll() }
    }
}

/**
 * WebView 的创建与销毁都收在这里，Composable 只负责把当前页挂进容器。
 *
 * 页面资源在首次创建时读一次就缓存住：`route_gen.html` 有 200K+ 字符，
 * 每次切子页重新读盘 + 打补丁会明显卡一下。
 */
private class ToolWebRuntime(
    private val context: Context,
    private val vm: ToolsViewModel,
    private val onEvent: (String) -> Unit,
    private val onPickFiles: (String) -> Unit,
) {

    private class Entry(val web: WebView, val channel: JsChannel)

    private val entries = HashMap<ToolsPage, Entry?>()

    fun viewFor(page: ToolsPage): WebView? {
        if (entries.containsKey(page)) return entries[page]?.web
        val entry = runCatching { create(page) }
            .onFailure {
                Log.e(TAG, "创建 ${page.label} 的 WebView 失败", it)
                vm.onPageLoadFailed(it.message ?: it.javaClass.simpleName)
            }
            .getOrNull()
        entries[page] = entry
        return entry?.web
    }

    private fun create(page: ToolsPage): Entry {
        val asset = when (page) {
            ToolsPage.Route -> ToolContract.ROUTE_ASSET
            ToolsPage.Fuel -> ToolContract.FUEL_ASSET
        }
        val config = ToolContract.loadConfig(context)
        val spec = config.tools[asset] ?: error("配置里没有 $asset")
        // 读 assets → 内存锚点替换 → 静态断言；失败会抛，由 viewFor 的 runCatching 兜住
        val html = ToolContract.prepareHtml(context, spec)

        val web = WebView(context)
        web.settings.apply {
            javaScriptEnabled = true
            // 关掉 DOM Storage：页面 boot 时直接落到空状态，
            // 不会和导入流程抢着读上一次会话的旧缓存
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportZoom(true)
            builtInZoomControls = true
            // 缩放按钮会挡住表单右下角，手势缩放留着就够了
            displayZoomControls = false
            useWideViewPort = true
            // 不缩放到整页宽：工具页是表单，1:1 显示更好点
            loadWithOverviewMode = false
            mediaPlaybackRequiresUserGesture = true
        }

        val channel = object : JsChannel {
            override fun eval(js: String) {
                web.post { web.evaluateJavascript(js, null) }
            }

            override fun eval(js: String, onResult: (String) -> Unit) {
                web.post { web.evaluateJavascript(js) { result -> onResult(result) } }
            }
        }

        val bridge = ToolsBridge(
            context = context,
            onDataApplied = vm::onDataApplied,
            onEvent = { text ->
                Log.i(TAG, text)
                onEvent(text)
            },
            onPickFiles = onPickFiles,
            onPlansChanged = vm::onPlansChanged,
            onPackSerialized = vm::onPackSerialized,
        )

        web.addJavascriptInterface(bridge, ToolContract.JS_INTERFACE)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // 先挂通道再通知：onPageReady 里要立刻 eval 探测桥，
                // 顺序反了那一次探测会静默落到 null 通道上
                vm.attachChannel(page, channel)
                vm.onPageReady(page)
            }

            // 离线应用，任何跳转都不放行
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean = true
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.i(TAG, "${msg.message()} @${msg.lineNumber()}")
                return true
            }
        }

        // baseUrl 在前、data 在后；页面里的相对引用只做同源占位，不联网
        web.loadDataWithBaseURL(spec.baseUrl, html, "text/html", "UTF-8", null)
        Log.i(TAG, "${page.label} 已装载：${html.length} 字符")
        return Entry(web, channel)
    }

    fun destroyAll() {
        for ((page, entry) in entries) {
            if (entry == null) continue
            // 用创建时那一份 channel 去解绑：detachChannel 是按引用比对的，
            // 随手 new 一个空实现传进去不会命中，会留下指向已销毁 WebView 的悬挂通道
            runCatching { vm.detachChannel(page, entry.channel) }
            runCatching { entry.web.removeJavascriptInterface(ToolContract.JS_INTERFACE) }
            runCatching { (entry.web.parent as? ViewGroup)?.removeView(entry.web) }
            runCatching { entry.web.destroy() }
        }
        entries.clear()
    }
}
