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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.oguricap.efb.diag.AppLog
import java.util.concurrent.atomic.AtomicBoolean

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

    // 网页资源由 ViewModel 在 IO 线程准备：读 241 KB HTML、打补丁、静态断言、算 SHA256。
    // 这里只负责发起，准备好之后再交给 WebView 装载。
    LaunchedEffect(vm.page) { vm.ensurePrepared(vm.page) }

    // runtime 只按 context 记住：换子页不能重建它，否则两个常驻 WebView 会被一起销毁，
    // 「切到油量页再切回航路页，结果还在」这条就没了。
    val runtime = remember(context) {
        ToolWebRuntime(context, vm, onEvent, onPickFiles, onPickFolder)
    }

    // 读这几个状态是为了在资源就绪/失败时触发重组，让下面的 update 再跑一次
    val currentPrepared = vm.preparedPage(vm.page)
    val preparing = vm.isPreparing(vm.page)
    val prepareFailure = vm.prepareError(vm.page)

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> FrameLayout(ctx) },
            update = { frame ->
                // 资源还没就绪时 mount 会直接返回，等状态变化后这次 update 会重跑
                runtime.mount(frame, vm.page)
                runtime.showOnly(frame, vm.page)
            },
        )

        // 当前子页的网页还没就绪：盖一层加载态。
        // 只在**当前页**没有可用页面时出现，已装载好的另一页不受影响。
        if (currentPrepared == null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (prepareFailure != null) {
                        Text(
                            text = "网页资源不完整，请检查安装包\n" + prepareFailure,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp),
                        )
                    } else if (preparing) {
                        CircularProgressIndicator()
                    } else {
                        // 既没备好也不在准备：正常情况下不会停在这里，
                        // 真停住说明 ensurePrepared 没被调到，至少给一句可见的说明
                        Text(
                            text = "正在准备网页资源…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(runtime) {
        onDispose { runtime.destroyAll() }
    }
}

/**
 * WebView 的创建与销毁都收在这里，Composable 只负责把当前页挂进容器。
 *
 * 页面资源的读取与补丁**不在这里做**：那是 IO 重活，由 [ToolsViewModel.ensurePrepared]
 * 在后台完成，这里只取现成结果去装载。这样主线程只剩「创建 WebView + loadDataWithBaseURL」。
 */
private class ToolWebRuntime(
    private val context: Context,
    private val vm: ToolsViewModel,
    private val onEvent: (String) -> Unit,
    private val onPickFiles: (String) -> Unit,
    private val onPickFolder: (String) -> Unit,
) {

    private class Entry(
        val web: WebView,
        val channel: JsChannel,
        /** 通道是否还能执行脚本。销毁 WebView 之前必须先置 false，见 [destroyAll]。 */
        val alive: AtomicBoolean,
    )

    private val entries = HashMap<ToolsPage, Entry?>()

    /** 每个子页被装载的次数。>1 说明页面被重新装载过，页面内状态已经丢了，值得记一笔。 */
    private val loads = HashMap<ToolsPage, Int>()

    /**
     * 确保 [page] 的 WebView 存在并挂进 [frame]。只挂一次，之后切页复用同一个实例。
     * 挂进去时先设成 GONE，由 [showOnly] 决定谁可见。
     *
     * 网页资源还没准备好时直接返回：等 ViewModel 那边状态变化，Compose 会再调一次。
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
        // 资源还没就绪就不建：这里返回 null，等 ViewModel 那边准备好后再来一次。
        // 不把「还没好」记成 null 条目，否则准备好之后就再也不会建了。
        val prepared = vm.preparedPage(page) ?: return null
        val entry = runCatching { create(page, prepared) }
            .onFailure {
                AppLog.e(TAG, "创建 ${page.label} 的 WebView 失败", it)
                vm.onPageLoadFailed(it.message ?: it.javaClass.simpleName)
            }
            .getOrNull()
        entries[page] = entry
        return entry?.web
    }

    private fun create(page: ToolsPage, prepared: PreparedToolPage): Entry {
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

        // 通道存活标志。WebView.destroy() 之后 post 一律被拒，回调永远不会来，
        // 依赖回调推进状态的地方会永久悬挂。这里显式告诉调用方「别再发起请求了」。
        val alive = AtomicBoolean(true)
        val channel = object : JsChannel {
            override val isAlive: Boolean get() = alive.get()

            override fun eval(js: String) {
                if (!alive.get()) return
                web.post { if (alive.get()) web.evaluateJavascript(js, null) }
            }

            override fun eval(js: String, onResult: (String) -> Unit) {
                if (!alive.get()) return
                web.post {
                    if (!alive.get()) return@post
                    web.evaluateJavascript(js) { result -> if (alive.get()) onResult(result) }
                }
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
                // 先掐断通道再销毁：漏了这一步，排队中的 evaluateJavascript 回调永远不会来，
                // ViewModel 里依赖回调复位的那几个标志就会永久悬挂
                alive.set(false)
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
        web.loadDataWithBaseURL(prepared.baseUrl, prepared.html, "text/html", "UTF-8", null)
        AppLog.i(TAG, "${page.label} 已装载：${prepared.html.length} 字符")
        return Entry(web, channel, alive)
    }

    fun destroyAll() {
        for ((page, entry) in entries) {
            if (entry == null) continue
            // 先掐断通道：这里之后 post 一律被拒，所有在飞的 eval 回调都不会再来，
            // ViewModel 侧的 plansBusy 等标志靠 detachChannel 复位
            entry.alive.set(false)
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
