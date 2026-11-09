package com.oguricap.efb.ui.tools

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
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
import com.oguricap.efb.diag.AppLog

private const val TAG = "EFB/JS"

/**
 * 两个网页工具的宿主。
 *
 * **只有一个 AndroidView + FrameLayout，两个 WebView 都挂在里面，切换只切可见性。**
 *
 * 历史上有两版实现，各自踩过一个坑，这里的做法同时避开：
 *
 *  1. **两个 AndroidView 并排放进 Box、靠 VISIBLE/INVISIBLE 切换** ——
 *     Compose 的 AndroidView 外层是一个 ViewGroup 宿主，宿主本身始终可见、始终参与命中测试。
 *     上层那个即使把内部 WebView 设成 INVISIBLE，点击也会先被它的宿主吃掉 ——
 *     表现就是「页面画出来了，但怎么点都没反应」。
 *  2. **换孩子（removeView 旧页 + addView 新页）** —— 触摸是对的，但 WebView 被摘下来
 *     会走 onDetachedFromWindow，重新挂上时 `loadDataWithBaseURL` 的页面会被重新装载：
 *     航路生成器里已经算好的结果回到初始占位，只剩「已导入的导航数据」被缓存自动补回来，
 *     看起来就像「切到油量计算器再回来，航路没了」（1.3.11 修）。
 *
 * 现在：**视图始终挂在窗口上，只改 visibility**。GONE 的子视图不参与布局与命中测试，
 * 所以触摸不会被挡住；也不会触发摘下/挂回，页面里的 JS 状态（结果区、滚动位置、
 * 已输入机场）原样保留 —— 结果一直留到用户自己点页面上的「清空」。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ToolsWebView(
    vm: ToolsViewModel,
    modifier: Modifier = Modifier,
    onEvent: (String) -> Unit = {},
    onPickFiles: (String) -> Unit = {},
    onPickFolder: (String) -> Unit = {},
) {
    val context = LocalContext.current

    // WebView 懒建、只建一次；页面在第一次被挂上去之前就已装载完成
    val runtime = remember(context) { ToolWebRuntime(context, vm, onEvent, onPickFiles, onPickFolder) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx -> FrameLayout(ctx) },
        update = { frame ->
            runtime.mount(frame, vm.page)
            runtime.showOnly(frame, vm.page)
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
    private val onPickFolder: (String) -> Unit,
) {

    private class Entry(val web: WebView, val channel: JsChannel)

    private val entries = HashMap<ToolsPage, Entry?>()

    /** 每个子页被装载的次数。>1 说明页面被重新装载过，页面内状态已经丢了，值得记一笔。 */
    private val loads = HashMap<ToolsPage, Int>()

    /**
     * 确保 [page] 的 WebView 存在并挂进 [frame]。只挂一次，之后切页复用同一个实例。
     * 挂进去时先设成 GONE，由 [showOnly] 决定谁可见。
     */
    fun mount(frame: ViewGroup, page: ToolsPage) {
        val web = ensureView(page) ?: return
        if (web.parent === frame) return
        (web.parent as? ViewGroup)?.removeView(web)
        web.visibility = View.GONE
        frame.addView(
            web,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        AppLog.i(TAG, "挂载 ${page.label}")
    }

    /**
     * 只让 [active] 可见，其余 GONE。
     *
     * 用 GONE 而不是 INVISIBLE：GONE 不参与布局也不参与命中测试，
     * 两层 MATCH_PARENT 的 WebView 叠在一起也不会互相抢触摸。
     * 关键点是不把视图摘出窗口 —— 摘下来再挂回去会让页面重新装载。
     */
    fun showOnly(frame: ViewGroup, active: ToolsPage) {
        for (i in 0 until frame.childCount) {
            val child = frame.getChildAt(i)
            val page = entries.entries.firstOrNull { it.value?.web === child }?.key
            child.visibility = if (page == active) View.VISIBLE else View.GONE
        }
    }

    private fun ensureView(page: ToolsPage): WebView? {
        if (entries.containsKey(page)) return entries[page]?.web
        val entry = runCatching { create(page) }
            .onFailure {
                AppLog.e(TAG, "创建 ${page.label} 的 WebView 失败", it)
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
        // 读 assets → 内存锚点替换 → 静态断言；失败会抛，由 ensureView 的 runCatching 兜住
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
                AppLog.i(TAG, text)
                onEvent(text)
            },
            onPickFiles = onPickFiles,
            onPickFolder = onPickFolder,
            onPlansChanged = vm::onPlansChanged,
            onPackSerialized = vm::onPackSerialized,
        )

        web.addJavascriptInterface(bridge, ToolContract.JS_INTERFACE)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val n = (loads[page] ?: 0) + 1
                loads[page] = n
                // 同一个实例装载第二次 = 页面被打回初始状态，结果区、已输入的机场都没了。
                // 正常情况下不该发生（切页只切可见性）；真发生了至少日志里能看出来。
                if (n > 1) AppLog.w(TAG, "${page.label} 被重新装载（第 $n 次），页面内状态已丢失")
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

            // 页面级加载失败（离线应用里本就该发生 0 次）。不接这个回调的话，
            // 页面白着、什么都不提示，用户只会以为「工具页坏了」。
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                val isMain = request?.isForMainFrame == true
                AppLog.e(
                    TAG,
                    "${page.label} 加载失败（main=$isMain, code=${error?.errorCode}）：${error?.description}",
                )
                if (isMain) vm.onPageLoadFailed(error?.description?.toString() ?: "页面加载失败")
            }

            /**
             * 渲染进程被系统杀掉（内存不足最常见）。
             *
             * **必须返回 true**：默认行为是把整个应用一起杀掉 ——
             * 用户看到的是「用着用着闪退」，其实只是那一个页面的渲染进程没了。
             * 这里把这个已成废品的 WebView 销毁、重建一个，页面回到初始状态，
             * 已导入的导航数据由缓存自动恢复；生成好的航路确实没了，所以明确告诉用户。
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                AppLog.e(TAG, "${page.label} 渲染进程终止（crashed=${detail?.didCrash()}）")
                val frame = view?.parent as? ViewGroup
                runCatching { vm.detachChannel(page, channel) }
                runCatching { frame?.removeView(view) }
                runCatching { view?.destroy() }
                entries.remove(page)
                loads.remove(page)
                runCatching { vm.onRendererGone(page) }
                if (frame != null) {
                    runCatching {
                        mount(frame, page)
                        showOnly(frame, page)
                    }.onFailure { AppLog.e(TAG, "${page.label} 重建失败", it) }
                }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // 按真实级别落盘：页面 console.error 原来一律记成 info，
            // 导出日志后根本区分不出普通输出和真的报错
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val text = "${msg.message()} @${msg.lineNumber()}"
                when (msg.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> AppLog.e(TAG, text)
                    ConsoleMessage.MessageLevel.WARNING -> AppLog.w(TAG, text)
                    ConsoleMessage.MessageLevel.DEBUG -> AppLog.d(TAG, text)
                    else -> AppLog.i(TAG, text)
                }
                return true
            }
        }

        // baseUrl 在前、data 在后；页面里的相对引用只做同源占位，不联网
        web.loadDataWithBaseURL(spec.baseUrl, html, "text/html", "UTF-8", null)
        AppLog.i(TAG, "${page.label} 已装载：${html.length} 字符")
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
