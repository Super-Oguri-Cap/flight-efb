package com.oguricap.efb.ui.tools

import android.content.Context
import com.oguricap.efb.diag.AppLog
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 一条锚点补丁。所有字段都来自 `assets/tools/efb_patches.json`，
 * Kotlin 侧不出现任何锚点字面量 —— 换新版 HTML 时只改配置，不改这里。
 *
 * @param marker     幂等标记：产物里已存在就跳过，重复调用不叠加
 * @param anchor     锚点文本
 * @param anchorType `literal` 或 `regex`
 * @param mode       `insertBefore` / `insertAfter` / `insertBetween` / `replace`
 * @param afterLiteral 仅 `insertBetween` 用：从锚点匹配起点偏移该字面量长度处插入
 * @param payload    已解析好的插入文本（shim 已在 loadConfig 时读入并包好 script 标签）
 */
data class PatchSpec(
    val id: String,
    val marker: String,
    val anchor: String,
    val anchorType: String,
    val mode: String,
    val afterLiteral: String,
    val payload: String,
    val wrapScript: Boolean,
)

/** 一个网页工具的全部静态契约。 */
data class ToolSpec(
    val asset: String,
    val baseUrl: String,
    val versionRegex: String,
    val requiredConsts: List<String>,
    val requiredLiterals: List<String>,
    val patches: List<PatchSpec>,
    val distanceJs: String,
)

data class ToolConfig(
    val bridgeVersion: String,
    val tools: Map<String, ToolSpec>,
)

/**
 * 网页工具的通用装载器：读 assets → 内存锚点替换 → 交给 WebView。
 *
 * 三条不可动摇的约定：
 *  1. 磁盘上的 `assets/tools/` 两个 HTML 字节级原样，补丁只在内存里；
 *  2. 每个锚点在对应文件里必须**恰好命中 1 次**，否则直接失败、停止加载；
 *  3. 这里只做「读 + 替换 + 静态断言」，不做版本协商、不做能力探测、不做降级。
 *
 * 升级流程：覆盖两个 HTML → 跑 `ToolAssetsContractTest` → 出包。
 * 正常升级 0 处 Kotlin 改动。
 */
object ToolContract {

    private const val TAG = "EFB/TOOL"

    /** 与 efb_patches.json 的 `bridgeVersion`、efb_shim.js 里的 BRIDGE_VERSION 三者必须一致。 */
    const val EXPECTED_BRIDGE_VERSION = "1"

    const val PATCHES_ASSET = "tools/efb_patches.json"
    const val ROUTE_ASSET = "tools/route_gen.html"
    const val FUEL_ASSET = "tools/fuel_calc.html"

    /** WebView 暴露给 JS 的桥对象名（addJavascriptInterface 的名字）。 */
    const val JS_INTERFACE = "Android"

    /** shim 挂到 window 上的对象名，App 侧只认这个，不用 window.UI。 */
    const val BRIDGE_OBJECT = "__EFB__"

    // ---------------------------------------------------------------- 读取

    /**
     * 显式 UTF-8：中文 Windows 的 JVM 默认编码可能是 GBK，
     * 省略字符集会让锚点匹配静默失败（HTML 里有大量中文）。
     */
    fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun loadConfig(context: Context): ToolConfig {
        val root = JSONObject(readAsset(context, PATCHES_ASSET))
        val bridgeVersion = root.optString("bridgeVersion", "")
        val toolsJson = root.getJSONObject("tools")
        val tools = LinkedHashMap<String, ToolSpec>()

        for (asset in toolsJson.keys()) {
            val o = toolsJson.getJSONObject(asset)
            val patchesJson = o.optJSONArray("patches")
            val patches = ArrayList<PatchSpec>()
            if (patchesJson != null) {
                for (i in 0 until patchesJson.length()) {
                    patches += parsePatch(context, patchesJson.getJSONObject(i), asset)
                }
            }
            val runtime = o.optJSONObject("runtime")
            tools[asset] = ToolSpec(
                asset = asset,
                baseUrl = o.optString("baseUrl", ""),
                versionRegex = o.optString("versionRegex", ""),
                requiredConsts = o.optJSONArray("requiredConsts").toStringList(),
                requiredLiterals = o.optJSONArray("requiredLiterals").toStringList(),
                patches = patches,
                distanceJs = runtime?.optString("distanceJs", "").orEmpty(),
            )
        }
        return ToolConfig(bridgeVersion = bridgeVersion, tools = tools)
    }

    private fun parsePatch(context: Context, o: JSONObject, asset: String): PatchSpec {
        val wrapScript = o.optBoolean("wrapScript", false)
        val shimAsset = o.optString("shimAsset", "")
        val payload = if (shimAsset.isNotEmpty()) {
            val shim = readAsset(context, "tools/$shimAsset")
            // shim 里出现 </script> 会提前截断内联脚本块，这类错误必须在出包期就炸
            require(!shim.contains("</script>", ignoreCase = true)) {
                "$shimAsset 里不能出现 </script> 字面量，否则会把内联 script 提前截断"
            }
            if (wrapScript) "<script>\n$shim\n</script>" else shim
        } else {
            o.optString("payloadText", "")
        }
        return PatchSpec(
            id = o.optString("id", "$asset#patch"),
            marker = o.optString("marker", ""),
            anchor = o.getString("anchor"),
            anchorType = o.optString("anchorType", "literal"),
            mode = o.optString("mode", "insertBefore"),
            afterLiteral = o.optString("afterLiteral", ""),
            payload = payload,
            wrapScript = wrapScript,
        )
    }

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) out += optString(i, "")
        return out
    }

    // ---------------------------------------------------------------- 替换

    /** CRLF → LF 只在内存里做，磁盘文件不受影响。 */
    private fun normalize(html: String): String =
        if (html.indexOf('\r') >= 0) html.replace("\r\n", "\n") else html

    /**
     * 按配置在内存里打补丁。纯函数：不会写磁盘，也不会修改传入的字符串。
     * 幂等：payload 的 marker 已在文本里就跳过，重复调用不叠加。
     */
    fun applyPatches(html: String, spec: ToolSpec): String {
        var out = normalize(html)
        for (p in spec.patches) {
            if (p.marker.isNotEmpty() && out.contains(p.marker)) continue
            val range = locate(out, spec, p)
            out = when (p.mode) {
                "insertBefore" ->
                    out.substring(0, range.first) + p.payload + out.substring(range.first)

                "insertAfter" ->
                    out.substring(0, range.last + 1) + p.payload + out.substring(range.last + 1)

                "insertBetween" -> {
                    val at = out.indexOf(p.afterLiteral, range.first)
                    require(at >= range.first && at + p.afterLiteral.length <= range.last + 1) {
                        "patch=${p.id} 的 afterLiteral 不在锚点范围内"
                    }
                    val cut = at + p.afterLiteral.length
                    out.substring(0, cut) + "\n" + p.payload + out.substring(cut)
                }

                "replace" ->
                    out.substring(0, range.first) + p.payload + out.substring(range.last + 1)

                else -> throw IllegalStateException("patch=${p.id} 的 mode 未知：${p.mode}")
            }
        }
        return out
    }

    private fun locate(html: String, spec: ToolSpec, p: PatchSpec): IntRange {
        val hits: List<IntRange> = if (p.anchorType == "regex") {
            Regex(p.anchor).findAll(html).map { it.range }.toList()
        } else {
            val list = ArrayList<IntRange>()
            var from = 0
            while (true) {
                val at = html.indexOf(p.anchor, from)
                if (at < 0) break
                list += at until (at + p.anchor.length)
                from = at + 1
            }
            list
        }
        // 锚点漂移必须当场失败，不能"命中了就凑合" —— 唯一把关点在出包期，
        // 运行期这一层只是为了不把明显坏掉的页面加载出来
        if (hits.size != 1) {
            throw IllegalStateException(
                "patch=${p.id} 的锚点在 ${spec.asset} 里命中 ${hits.size} 次（要求恰好 1 次）"
            )
        }
        return hits[0]
    }

    // ---------------------------------------------------------------- 静态断言

    private val FORBIDDEN = listOf(
        "http://", "https://", "<script src", "<link href", "@import",
        "type=\"module\"", "type='module'",
    )

    /**
     * 对**原始** HTML（未打补丁）做静态断言：
     * 7 个顶层 const 各 1 次、必需字面量各 1 次、单文件自包含、版本正则可提取。
     *
     * 出包期 `ToolAssetsContractTest` 与运行期 `prepareHtml` 共用这份实现。
     */
    fun verifySource(html: String, spec: ToolSpec) {
        val text = normalize(html)
        for (name in spec.requiredConsts) {
            val needle = "const $name = (function"
            val n = countOf(text, needle)
            check(n == 1) { "${spec.asset} 里 `$needle` 命中 $n 次（要求 1 次）" }
        }
        for (needle in spec.requiredLiterals) {
            val n = countOf(text, needle)
            check(n == 1) { "${spec.asset} 里 `$needle` 命中 $n 次（要求 1 次）" }
        }
        for (bad in FORBIDDEN) {
            check(!text.contains(bad)) { "${spec.asset} 出现外链/模块化标志 `$bad`，不再是单文件自包含" }
        }
        if (spec.versionRegex.isNotEmpty()) {
            val m = Regex(spec.versionRegex).find(text)
            check(m != null) { "${spec.asset} 用版本正则 `${spec.versionRegex}` 提取不到版本串" }
        }
    }

    private fun countOf(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var n = 0
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return n
            n++
            from = at + 1
        }
    }

    /** 提取版本串（给 Logcat 用，不参与运行期判断）。 */
    fun extractVersion(html: String, spec: ToolSpec): String {
        if (spec.versionRegex.isEmpty()) return ""
        val m = Regex(spec.versionRegex).find(normalize(html)) ?: return ""
        return if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.value
    }

    /** 读 assets → 打补丁 → 静态断言。失败抛异常，由调用方转成界面提示 + 停止加载。 */
    fun prepareHtml(context: Context, spec: ToolSpec): String {
        val raw = readAsset(context, spec.asset)
        verifySource(raw, spec)
        return applyPatches(raw, spec)
    }

    /** 版本串 + 文件 hash 只进 Logcat：界面不显示、App 运行期不比对。 */
    fun logVersions(context: Context) {
        runCatching {
            val config = loadConfig(context)
            AppLog.i(TAG, "bridgeVersion=${config.bridgeVersion} expected=$EXPECTED_BRIDGE_VERSION")
            for (spec in config.tools.values) {
                val raw = readAsset(context, spec.asset)
                val ver = extractVersion(raw, spec)
                AppLog.i(TAG, "${spec.asset} version=${ver.ifEmpty { "?" }} sha256=${sha256(raw)}")
            }
        }.onFailure { AppLog.e(TAG, "读取工具资源失败", it) }
    }

    fun sha256(text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }
}
