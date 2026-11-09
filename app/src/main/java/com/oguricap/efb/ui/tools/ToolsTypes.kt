package com.oguricap.efb.ui.tools

import android.net.Uri

/** 工具页的两个子页。切换时两个 WebView 都留在内存里，不重载、不丢已导入数据。 */
enum class ToolsPage(val label: String) {
    Route("航路生成器"),
    Fuel("油量计算器"),
}

/** 从网页 DOM 收敛出来的一条航路方案。key 取 .pcopy 的 data-copy（off0 / eng0）。 */
data class PlannedRoute(
    val key: String,
    val kind: String,
    val distNM: Int,
    val route: String,
)

data class PlanSnapshot(
    val from: String,
    val to: String,
    val plans: List<PlannedRoute>,
)

/** 本机保存的一条航路。只有用户点了「保存」才会产生。 */
data class SavedRoute(
    val id: String,
    val name: String,
    val from: String,
    val to: String,
    val distNM: Int,
    val route: String,
    val kind: String,
    val savedAt: Long,
)

/** 导入结果回执（由网页侧 datasetInfo() 报上来）。 */
data class ImportOutcome(
    val ok: Boolean,
    val message: String,
    val airports: Int,
    val nodes: Int,
    val segments: Int,
    val airways: Int,
)

/**
 * WebView 的 JS 通道。
 *
 * ViewModel 不直接持 WebView：Compose 侧把 AndroidView 包成这个接口传进来，
 * 页面销毁时置空。所有 eval 都必须落在主线程（由实现方保证）。
 */
interface JsChannel {
    fun eval(js: String)
    fun eval(js: String, onResult: (String) -> Unit)
}

/** 文件选择回调。SAF 的多选 launcher 在 Compose 侧注册，结果回传给 ViewModel。 */
typealias UriPickCallback = (List<Uri>) -> Unit
